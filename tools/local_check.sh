#!/bin/sh
set -eu
CHOCOLA_SDK="$HOME/.android/sdk"
CHOCOLA_GRADLE="$HOME/.gradle/wrapper/dists/gradle-8.13-bin/5xuhj0ry160q40clulazy9h7d/gradle-8.13/bin/gradle"
export ANDROID_HOME="$CHOCOLA_SDK"
exec "$CHOCOLA_GRADLE" --no-daemon -Dorg.gradle.native=false -Dorg.gradle.internal.native.integrations.enabled=false -Pandroid.aapt2FromMavenOverride="/data/data/com.termux/files/usr/bin/aapt2" "$@"
