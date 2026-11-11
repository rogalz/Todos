#!/bin/sh
# Lets the app in the Android emulator reach the local server: inside the emulator
# http://localhost:8080 is tunnelled (`adb reverse`) to port 8080 on this machine. The emulator's
# own `localhost` is the emulator, and the usual host alias 10.0.2.2 is not reachable everywhere,
# so the app talks to localhost (see PlatformModule.android.kt) and this tunnel does the rest.
#
# Run it once after the emulator has booted, with the server running. The tunnel is gone when the
# emulator restarts or the adb server is killed, and it can also vanish silently after a while
# (adb server restart, emulator reconnect), after which the app fails with
# "Failed to connect to localhost:8080". Either run the script again, or leave it running with
# --watch, which puts the rule back by itself.
#
# Usage: scripts/android-reverse.sh            apply the rule once and exit
#        scripts/android-reverse.sh --watch    apply it, then check every 5 s and restore it when it is
#                                              gone (a line is printed each time); stop with Ctrl-C
#
# WATCH_INTERVAL (seconds, default 5) changes the check interval; it exists so the script can be tested
# without waiting.
set -e

RULE='tcp:8080 tcp:8080'
WATCH_INTERVAL=${WATCH_INTERVAL:-5}

watch=false
for arg in "$@"; do
    case "$arg" in
        --watch) watch=true ;;
        -h|--help) sed -n '2,/^set -e$/p' "$0" | sed '$d; s/^# \{0,1\}//'; exit 0 ;;
        *) echo "Unknown option: $arg (try --help)" >&2; exit 2 ;;
    esac
done

if ! command -v adb >/dev/null 2>&1; then
    echo "adb is not on your PATH." >&2
    echo "It ships with the Android SDK platform-tools, on macOS usually in:" >&2
    echo "  \$HOME/Library/Android/sdk/platform-tools" >&2
    echo "Add that directory to PATH (or call adb by its full path), then run this script again." >&2
    exit 1
fi

if ! $watch; then
    adb reverse tcp:8080 tcp:8080
    echo "OK: localhost:8080 inside the emulator now reaches port 8080 on this machine."
    exit 0
fi

# One line of `adb reverse --list` looks like `host-16 tcp:8080 tcp:8080` (the first field is a
# tunnel id). Matched as whole fields, so e.g. `tcp:18080 tcp:8080` does not count. When adb has no
# device to ask, the command fails and so the rule counts as missing, which is what it is.
rule_present() {
    adb reverse --list 2>/dev/null | grep -Eq '(^|[[:space:]])tcp:8080[[:space:]]+tcp:8080([[:space:]]|$)'
}

stamp() { date '+%H:%M:%S'; }

trap 'echo; echo "Stopped watching."; exit 0' INT TERM

echo "Watching the adb reverse rule ($RULE) every ${WATCH_INTERVAL}s. Ctrl-C to stop."

# `unreachable` is true while adb cannot set the rule (no emulator yet, adb server down): the first
# failure is reported and then silence, so a long wait does not fill the terminal every 5 s.
unreachable=false
first=true
while true; do
    if rule_present; then
        $first && echo "[$(stamp)] OK: localhost:8080 inside the emulator reaches port 8080 on this machine."
        unreachable=false
    elif adb reverse tcp:8080 tcp:8080 >/dev/null 2>&1; then
        if $first; then
            echo "[$(stamp)] OK: localhost:8080 inside the emulator now reaches port 8080 on this machine."
        else
            echo "[$(stamp)] The adb reverse rule was gone; restored it."
        fi
        unreachable=false
    elif ! $unreachable; then
        echo "[$(stamp)] The adb reverse rule is missing and adb cannot set it (is the emulator running?); will keep trying."
        unreachable=true
    fi
    first=false
    sleep "$WATCH_INTERVAL"
done
