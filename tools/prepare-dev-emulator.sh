#!/bin/sh
# ONLY the task-owned disposable AVD. This is never a physical-device unlock helper.
set -eu
adb=${ANDROID_SDK_ROOT:-/Library/Android/sdk}/platform-tools/adb
serial=${1:-emulator-5580}
name=$("$adb" -s "$serial" emu avd name | tr -d '\r' | head -1)
[ "$name" = MassiveX_Dev ] || { echo 'Refusing: dedicated MassiveX_Dev required.' >&2; exit 1; }
[ "$("$adb" -s "$serial" shell getprop ro.kernel.qemu | tr -d '\r')" = 1 ] || { echo 'Refusing a non-emulator.' >&2; exit 1; }
"$adb" -s "$serial" shell service check activity | grep -q ': found'
"$adb" -s "$serial" shell service check connectivity | grep -q ': found'
unlocked() { "$adb" -s "$serial" shell dumpsys user | grep -q 'State: RUNNING_UNLOCKED'; }
if ! unlocked; then
    # Wait for the actual PIN field instead of typing during boot/lockscreen animations.
    python3 "$(dirname -- "$0")/unlock-dev-emulator.py" "$adb" "$serial"
fi
if [ "${2:-}" != keep-process ]; then "$adb" -s "$serial" shell am force-stop --user 0 com.massivex.android.dev; fi
