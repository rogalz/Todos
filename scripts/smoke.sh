#!/bin/sh
# Walks the server through its main behaviours with curl: health, create, idempotent retry, stale
# write, tombstone, deltas, reset on a foreign serverId, 400s. Prints each request and response and
# CHECKS each one (expected HTTP status, plus a fragment of the body where the status alone would
# not prove the behaviour). Exits 1 if any check failed; CI relies on that (job `server-docker`).
# Every request is still sent after a failure, so one run lists everything that is wrong.
#
# The delta checks (`since=1`, `since=2`) assume a freshly started server that nobody else writes
# to: the revisions are 1 (create) and 2 (tombstone). Running it again against the same server is
# fine (the retried writes change nothing), but a server that holds other todos can fail them.
# The automated tests live in server/src/test; this script is the black-box check of a real,
# running server (and of the Docker image).
#
# Usage: scripts/smoke.sh [BASE_URL]      (default http://localhost:8080)
B=${1:-http://localhost:8080}
ID=11111111-1111-4111-8111-111111111111
H='Content-Type: application/json'
FAILED=0
BODY=$(mktemp)
trap 'rm -f "$BODY"' EXIT

fail() { printf '  FAIL: %s\n' "$1"; FAILED=$((FAILED + 1)); }

# req WANT_STATUS BODY_FRAGMENT curl-args...
# BODY_FRAGMENT is matched as a fixed string; `-` means "do not check the body".
req() {
  want=$1; needle=$2; shift 2
  printf '\n$ curl %s\n' "$*"
  # No -f: a 4xx/5xx must reach the checks below instead of making curl fail on its own.
  if ! code=$(curl -sS -o "$BODY" -w '%{http_code}' "$@"); then
    fail "curl could not complete the request"
    return
  fi
  cat "$BODY"
  printf '\n  -> %s\n' "$code"
  [ "$code" = "$want" ] || fail "expected HTTP $want, got $code"
  if [ "$needle" != "-" ] && ! grep -qF -- "$needle" "$BODY"; then
    fail "body does not contain: $needle"
  fi
}

put_todo() { # put_todo TITLE IS_DONE UPDATED_AT DELETED_AT  -> JSON body
  printf '{"id":"%s","title":"%s","description":"2 l","isDone":%s,"createdAt":1000,"updatedAt":%s,"deletedAt":%s}' \
    "$ID" "$1" "$2" "$3" "$4"
}

req 200 OK "$B/health"
req 200 '"serverId"' "$B/todos"
req 200 '"title":"Buy milk"' -X PUT -H "$H" "$B/todos/$ID" -d "$(put_todo 'Buy milk' false 1000 null)"
# Same write again: a retry after a lost response. Still 200 with the same version.
req 200 '"title":"Buy milk"' -X PUT -H "$H" "$B/todos/$ID" -d "$(put_todo 'Buy milk' false 1000 null)"
req 200 "\"id\":\"$ID\"" "$B/todos?since=0"
# Older updatedAt loses; the answer is the winning (stored) version, not the stale one.
req 200 '"title":"Buy milk"' -X PUT -H "$H" "$B/todos/$ID" -d "$(put_todo STALE false 500 null)"
req 200 '"deletedAt":2000' -X PUT -H "$H" "$B/todos/$ID" -d "$(put_todo 'Buy milk' true 2000 2000)"
req 200 '"deletedAt":2000' "$B/todos?since=1"
req 200 '"items":[]' "$B/todos?since=2"
req 200 '"reset":true' "$B/todos?since=2&serverId=some-old-server"
req 400 - -X PUT -H "$H" "$B/todos/not-a-uuid" \
  -d '{"id":"not-a-uuid","title":"x","description":"","isDone":false,"createdAt":1,"updatedAt":1,"deletedAt":null}'
req 400 - "$B/todos?since=-1"

if [ "$FAILED" -gt 0 ]; then
  printf '\nSMOKE TEST FAILED: %s check(s) failed\n' "$FAILED"
  exit 1
fi
printf '\nSMOKE TEST PASSED\n'
