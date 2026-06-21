#!/usr/bin/env bash
# Runs INSIDE the android-build container. Builds the debug APK (or runs unit tests).
set -euo pipefail
cd /workspace

API_KEY="${BACKEND_API_KEY:-dev-key}"
BASE_URL="${BACKEND_BASE_URL:-https://dl.jni.my.id/}"
MODE="${1:-assemble}"

chmod +x ./gradlew

if [ "$MODE" = "test" ]; then
  ./gradlew :app:testDebugUnitTest --no-daemon --stacktrace \
    -PbackendApiKey="$API_KEY" -PbackendBaseUrl="$BASE_URL"
  echo "UNIT TESTS DONE"
else
  ./gradlew :app:assembleDebug --no-daemon --stacktrace \
    -PbackendApiKey="$API_KEY" -PbackendBaseUrl="$BASE_URL"
  echo "APK at app/build/outputs/apk/debug/app-debug.apk"
fi
