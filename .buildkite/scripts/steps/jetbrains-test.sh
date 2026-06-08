#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# Plain JDK 17 image: the Gradle wrapper provides Gradle (9.5.1, required by the
# IntelliJ Platform plugin 2.16), so the base image only needs a JDK.
#
# The build runs inside an in-container copy under /tmp (NOT the mounted
# workspace). The container runs as root, and Gradle + the IntelliJ Platform
# plugin write build/, .gradle/, .kotlin/ and .intellijPlatform/ as root. Left
# in the workspace, those root-owned files break the NEXT build's git checkout/
# clean on the buildkite-agent user (a known buildkite elastic-stack issue) —
# the checkout hangs ~10 min and exits 128. Building in /tmp keeps the mounted
# workspace pristine, so it stays cleanable. GRADLE_USER_HOME defaults to the
# container HOME (/root/.gradle), also outside the workspace.
exec "$SCRIPT_DIR/../run-in-docker.sh" \
  -i eclipse-temurin:17-jdk \
  -w /build \
  -- bash -ec '
    cp -a mockserver-jetbrains /tmp/jb
    cd /tmp/jb
    ./gradlew test --no-daemon
  '
