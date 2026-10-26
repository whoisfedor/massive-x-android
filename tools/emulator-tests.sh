#!/bin/sh
# Run ONLY on the dedicated throwaway AVD. Never target a physical/owner device implicitly.
set -eu
adb=${ANDROID_SDK_ROOT:-/Library/Android/sdk}/platform-tools/adb
serial=${1:-emulator-5580}
name=$("$adb" -s "$serial" emu avd name | tr -d '\r' | head -1)
[ "$name" = MassiveX_Dev ] || { echo 'Refusing: target must be the dedicated MassiveX_Dev AVD.' >&2; exit 1; }
./tools/prepare-dev-emulator.sh "$serial"
# Wallet-security regressions run offline: generated throwaway addresses must not reach public providers.
prior_airplane=$("$adb" -s "$serial" shell settings get global airplane_mode_on | tr -d '\r')
restore_network() { "$adb" -s "$serial" shell am force-stop --user 0 com.massivex.android.dev >/dev/null; if [ "$prior_airplane" = 0 ]; then "$adb" -s "$serial" shell cmd connectivity airplane-mode disable >/dev/null; fi; }
trap restore_network EXIT
"$adb" -s "$serial" shell cmd connectivity airplane-mode enable
"$adb" -s "$serial" install -r app/build/outputs/apk/debug/app-debug.apk
"$adb" -s "$serial" install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
result=$("$adb" -s "$serial" shell am instrument -w -e class com.massivex.android.KeystoreRuntimeTest com.massivex.android.dev.test/android.test.InstrumentationTestRunner)
printf '%s\n' "$result"
case "$result" in
    *"OK (5 tests)"*) ;;
    *) echo 'Instrumentation did not pass all five expected tests.' >&2; exit 1 ;;
esac
