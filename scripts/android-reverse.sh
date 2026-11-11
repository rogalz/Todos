#!/bin/sh
# Lets the app in the Android emulator reach the local server: inside the emulator
# http://localhost:8080 is tunnelled (`adb reverse`) to port 8080 on this machine. The emulator's
# own `localhost` is the emulator, and the usual host alias 10.0.2.2 is not reachable everywhere,
# so the app talks to localhost (see PlatformModule.android.kt) and this tunnel does the rest.
#
# Run it once after the emulator has booted, with the server running. The tunnel is gone when the
# emulator restarts or the adb server is killed; just run it again.
#
# Usage: scripts/android-reverse.sh
set -e

if ! command -v adb >/dev/null 2>&1; then
    echo "adb is not on your PATH." >&2
    echo "It ships with the Android SDK platform-tools, on macOS usually in:" >&2
    echo "  \$HOME/Library/Android/sdk/platform-tools" >&2
    echo "Add that directory to PATH (or call adb by its full path), then run this script again." >&2
    exit 1
fi

adb reverse tcp:8080 tcp:8080
echo "OK: localhost:8080 inside the emulator now reaches port 8080 on this machine."
