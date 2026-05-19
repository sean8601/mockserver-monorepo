#!/usr/bin/env bash
set -euo pipefail

echo "--- :buildkite: Downloading shaded JAR artifact"
buildkite-agent artifact download "mockserver/mockserver-netty-no-dependencies/target/mockserver-netty-no-dependencies-*.jar" .

shopt -s nullglob
SHADED_JAR=""
for f in mockserver/mockserver-netty-no-dependencies/target/mockserver-netty-no-dependencies-*.jar; do
  case "$(basename "$f")" in
    *-sources.jar|*-javadoc.jar|original-*) continue ;;
  esac
  SHADED_JAR="$f"
  break
done
shopt -u nullglob
if [ -z "$SHADED_JAR" ]; then
  echo "Error: shaded JAR not found after artifact download"
  exit 1
fi

echo "--- :package: Copying shaded JAR as jar-with-dependencies"
JAR_DIR="mockserver/mockserver-netty/target"
mkdir -p "$JAR_DIR"
VERSION=$(basename "$SHADED_JAR" | sed -E 's/^mockserver-netty-no-dependencies-(.+)\.jar$/\1/')
if [ -z "$VERSION" ] || [ "$VERSION" = "$(basename "$SHADED_JAR")" ]; then
  echo "Error: could not extract version from $SHADED_JAR"
  exit 1
fi
JAR_NAME="mockserver-netty-${VERSION}-jar-with-dependencies.jar"
cp "$SHADED_JAR" "$JAR_DIR/$JAR_NAME"

echo "--- :docker: Running container integration tests (Docker Compose only)"
export SKIP_JAVA_BUILD=true
export SKIP_HELM_TESTS=true
export SKIP_DOCKER_REBUILD_CLIENT=false

exec container_integration_tests/integration_tests.sh
