#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# Plain JDK 17 image: the Gradle wrapper provides Gradle (9.5.1, required by the
# IntelliJ Platform plugin 2.16), so the base image only needs a JDK.
exec "$SCRIPT_DIR/../run-in-docker.sh" \
  -i eclipse-temurin:17-jdk \
  -w /build/mockserver-jetbrains \
  -- bash -c "./gradlew test --no-daemon"
