#!/bin/sh
# Dedicated task AVD only. Disposable clipboard fixture; never signs or submits.
set -eu
adb=${ANDROID_SDK_ROOT:-/Library/Android/sdk}/platform-tools/adb
serial=${1:-emulator-5580}
./tools/prepare-dev-emulator.sh "$serial"
prior=$("$adb" -s "$serial" shell settings get global airplane_mode_on | tr -d '\r')
restore(){ "$adb" -s "$serial" shell am force-stop com.massivex.android.dev >/dev/null; if [ "$prior" = 0 ]; then "$adb" -s "$serial" shell cmd connectivity airplane-mode disable >/dev/null; fi; }
trap restore EXIT
"$adb" -s "$serial" shell cmd connectivity airplane-mode enable
"$adb" -s "$serial" install -r app/build/outputs/apk/debug/app-debug.apk
"$adb" -s "$serial" install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
result=$("$adb" -s "$serial" shell am instrument -w -e class com.massivex.android.Ui03RuntimeTest com.massivex.android.dev.test/android.test.InstrumentationTestRunner)
printf '%s\n' "$result"
case "$result" in *"OK (6 tests)"*) ;; *) exit 1 ;; esac
