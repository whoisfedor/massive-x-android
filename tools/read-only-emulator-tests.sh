#!/bin/sh
# Public fixture reads / harmless chooser only. Never run these full tests on a physical phone.
set -eu
adb=${ANDROID_SDK_ROOT:-/Library/Android/sdk}/platform-tools/adb
serial=${1:-emulator-5580}
name=$("$adb" -s "$serial" emu avd name | tr -d '\r' | head -1)
[ "$name" = MassiveX_Dev ] || { echo 'Refusing: dedicated MassiveX_Dev AVD required.' >&2; exit 1; }
./tools/prepare-dev-emulator.sh "$serial"
"$adb" -s "$serial" install -r app/build/outputs/apk/debug/app-debug.apk
"$adb" -s "$serial" install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
"$adb" -s "$serial" shell am force-stop com.massivex.android.dev
result=$("$adb" -s "$serial" shell am instrument -w -e class com.massivex.android.ReadOnlyRuntimeTest com.massivex.android.dev.test/android.test.InstrumentationTestRunner)
printf '%s\n' "$result"
case "$result" in *"OK (3 tests)"*) ;; *) echo 'Read-only instrumentation failed.' >&2; exit 1 ;; esac
"$adb" -s "$serial" shell am force-stop com.massivex.android.dev
