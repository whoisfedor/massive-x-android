#!/bin/sh
# Task-local environment selection; never changes shell profiles, IDE settings or SDK contents.
set -eu
root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
java_home=${JAVA_HOME:-"/Applications/Android Studio.app/Contents/jbr/Contents/Home"}
if [ ! -x "$java_home/bin/java" ]; then
    echo "Set JAVA_HOME to a compatible JDK (Android Studio bundled JBR 21 is supported)." >&2
    exit 1
fi
sdk=${ANDROID_HOME:-}
if [ ! -d "$sdk/platforms" ]; then sdk=${ANDROID_SDK_ROOT:-}; fi
if [ ! -d "$sdk/platforms" ]; then
    if [ -d /Library/Android/sdk/platforms ]; then sdk=/Library/Android/sdk; else sdk="$HOME/Library/Android/sdk"; fi
fi
if [ ! -d "$sdk/platforms" ]; then
    echo "Configure an existing Android SDK via Android Studio; no license is accepted by this helper." >&2
    exit 1
fi
cd "$root"
exec env JAVA_HOME="$java_home" ANDROID_HOME="$sdk" ANDROID_SDK_ROOT="$sdk" ./gradlew "$@"
