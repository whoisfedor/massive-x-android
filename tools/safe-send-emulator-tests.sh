#!/bin/sh
# FAKE submission only. Dedicated existing task AVD; no owner device/catalog or blockchain writes.
set -eu
adb=${ANDROID_SDK_ROOT:-/Library/Android/sdk}/platform-tools/adb
serial=${1:-emulator-5580}
name=$("$adb" -s "$serial" emu avd name | tr -d '\r' | head -1)
[ "$name" = MassiveX_Dev ] || { echo 'Refusing: dedicated MassiveX_Dev AVD required.' >&2; exit 1; }
./tools/prepare-dev-emulator.sh "$serial"
prior_airplane=$("$adb" -s "$serial" shell settings get global airplane_mode_on | tr -d '\r')
restore_network() { "$adb" -s "$serial" shell am force-stop --user 0 com.massivex.android.dev >/dev/null; if [ "$prior_airplane" = 0 ]; then "$adb" -s "$serial" shell cmd connectivity airplane-mode disable >/dev/null; fi; }
trap restore_network EXIT
"$adb" -s "$serial" shell cmd connectivity airplane-mode enable
"$adb" -s "$serial" install -r app/build/outputs/apk/debug/app-debug.apk
"$adb" -s "$serial" install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
run_case() {
    ./tools/prepare-dev-emulator.sh "$serial"
    result=$("$adb" -s "$serial" shell am instrument -w -e class "$1" com.massivex.android.dev.test/android.test.InstrumentationTestRunner)
    printf '%s\n' "$result"
    case "$result" in *"OK ($2 tests)"*|*"OK ($2 test)"*) ;; *) echo 'Safe Send instrumentation did not pass.' >&2; exit 1 ;; esac
    "$adb" -s "$serial" shell am force-stop --user 0 com.massivex.android.dev
}
run_case com.massivex.android.SafeSendRuntimeTest 3
run_case com.massivex.android.TransferRestartRuntimeTest#testPrepareDurableUnknown 1
run_case com.massivex.android.TransferRestartRuntimeTest#testResumeAfterForceStopWithoutSigningOrResubmission 1
run_case com.massivex.android.TransferRestartRuntimeTest#testTerminalSurvivesSecondForceStopAndOnlyFixtureIsCleaned 1
printf '%s\n' 'PASS: 6 safe-send native/auth/journal/restart cases; all test submissions injected/fake; no production write exercised.'
