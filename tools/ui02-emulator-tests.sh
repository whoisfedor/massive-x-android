#!/bin/sh
# Public in-memory fixtures; guarded task AVD, no signing or network write.
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
fixture=$(mktemp /tmp/mxa-ui02-public-yaml.XXXXXX)
python3 - "$fixture" <<'PYFIXTURE'
import json,sys
from pathlib import Path
source=json.loads(Path('massa-core/src/test/resources/ios-parity.json').read_text())
# Public encrypted fixture only; never print its contents or the synthetic private key.
Path(sys.argv[1]).write_text(source['yamlCases'][0]['yaml'])
PYFIXTURE
"$adb" -s "$serial" push "$fixture" /sdcard/Download/mxa-ui02-public-wallet.yaml >/dev/null
rm -f "$fixture"
result=$("$adb" -s "$serial" shell am instrument -w -e class com.massivex.android.Ui02RuntimeTest com.massivex.android.dev.test/android.test.InstrumentationTestRunner)
"$adb" -s "$serial" shell rm -f /sdcard/Download/mxa-ui02-public-wallet.yaml
printf '%s\n' "$result"
case "$result" in *"OK (7 tests)"*) ;; *) exit 1 ;; esac
