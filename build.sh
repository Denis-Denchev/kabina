#!/bin/bash
# Convenience wrapper: ./build.sh [brand]  ->  debug APK (with brands/<brand> overlaid when given).
set -euo pipefail
cd "$(dirname "$0")"
if [ -z "${JAVA_HOME:-}" ] && [ -d /opt/homebrew/opt/openjdk@17 ]; then
  export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
fi
if [ -n "${1:-}" ]; then
  ./gradlew assembleBrandedDebug -Pbrand="$1"
  echo "OK: app/build/outputs/apk/branded/debug/app-branded-debug.apk"
else
  ./gradlew assembleDebug
  echo "OK: app/build/outputs/apk/debug/app-debug.apk"
fi
