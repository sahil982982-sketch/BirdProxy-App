#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
./FETCH_NATIVE_CORE.sh
./gradlew clean assembleDebug
printf '\nAPK: app/build/outputs/apk/debug/app-debug.apk\n'
