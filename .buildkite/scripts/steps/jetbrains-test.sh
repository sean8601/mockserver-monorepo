#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# Plain JDK 17 image: the Gradle wrapper provides Gradle (9.5.1, required by the
# IntelliJ Platform plugin 2.16), so the base image only needs a JDK.
#
# Runs as the non-root default, so Gradle writes build/, .gradle/, .kotlin/ and
# .intellijPlatform/ into the mounted workspace as the agent UID — those stay
# cleanable by the next build's git checkout (no more root-owned files breaking
# it), so the /tmp-copy workaround is no longer needed. GRADLE_USER_HOME is
# $HOME/.gradle (/tmp/.gradle, in-container) so the big IDE download is discarded.
exec "$SCRIPT_DIR/../run-in-docker.sh" \
  -i eclipse-temurin:17-jdk \
  -w /build/mockserver-jetbrains \
  -- bash -c "./gradlew test --no-daemon"
