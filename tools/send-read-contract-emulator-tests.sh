#!/bin/sh
# Official READS only. No Activity launch; no selected signing-wallet address leaves the catalog.
set -eu
adb=${ANDROID_SDK_ROOT:-/Library/Android/sdk}/platform-tools/adb
serial=${1:-emulator-5580}
./tools/prepare-dev-emulator.sh "$serial"
"$adb" -s "$serial" install -r app/build/outputs/apk/debug/app-debug.apk
"$adb" -s "$serial" install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
result=$("$adb" -s "$serial" shell am instrument -w -e class com.massivex.android.SendReadContractRuntimeTest com.massivex.android.dev.test/android.test.InstrumentationTestRunner)
printf '%s\n' "$result"
case "$result" in *"OK (1 test)"*|*"OK (1 tests)"*) ;; *) echo 'Read contract runtime check failed.' >&2; exit 1 ;; esac
"$adb" -s "$serial" shell am force-stop com.massivex.android.dev
