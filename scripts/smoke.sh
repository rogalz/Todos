#!/bin/sh
# Walks the server through its main behaviours with curl and prints each request and response:
# health, create, idempotent retry, stale write, tombstone, deltas, reset on a foreign serverId, 400s.
# It only prints (read the output); the automated checks live in server/src/test.
#
# Usage: scripts/smoke.sh [BASE_URL]      (default http://localhost:8080)
B=${1:-http://localhost:8080}
ID=11111111-1111-4111-8111-111111111111
H='Content-Type: application/json'
req() { printf '\n$ %s\n' "$*"; curl -sS -w '  -> %{http_code}\n' "$@"; }
req "$B/health"
req "$B/todos"
req -X PUT -H "$H" "$B/todos/$ID" -d '{"id":"'$ID'","title":"Buy milk","description":"2 l","isDone":false,"createdAt":1000,"updatedAt":1000,"deletedAt":null}'
req -X PUT -H "$H" "$B/todos/$ID" -d '{"id":"'$ID'","title":"Buy milk","description":"2 l","isDone":false,"createdAt":1000,"updatedAt":1000,"deletedAt":null}'
req "$B/todos?since=0"
req -X PUT -H "$H" "$B/todos/$ID" -d '{"id":"'$ID'","title":"STALE","description":"","isDone":false,"createdAt":1000,"updatedAt":500,"deletedAt":null}'
req -X PUT -H "$H" "$B/todos/$ID" -d '{"id":"'$ID'","title":"Buy milk","description":"2 l","isDone":true,"createdAt":1000,"updatedAt":2000,"deletedAt":2000}'
req "$B/todos?since=1"
req "$B/todos?since=2"
req "$B/todos?since=2&serverId=some-old-server"
req -X PUT -H "$H" "$B/todos/not-a-uuid" -d '{"id":"not-a-uuid","title":"x","description":"","isDone":false,"createdAt":1,"updatedAt":1,"deletedAt":null}'
req "$B/todos?since=-1"
