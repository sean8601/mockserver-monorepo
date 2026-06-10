#!/usr/bin/env bash
# Build per-platform, JVM-less MockServer binary bundles and upload them as assets
# on a rolling GitHub pre-release tagged "mockserver-snapshot". Each master build
# replaces the previous assets (--clobber).
#
# Guard: skips when the pom version is NOT *-SNAPSHOT (mirrors java-deploy-snapshot.sh).
#
# Agent requirements: curl, tar, unzip, gh, jq, and ~2 GB scratch. A host JDK 21
# (for jlink only) is bootstrapped on demand if not already present, so the Maven
# build keeps running on JDK 17 (this step never touches the Maven JDK). Runs on
# the default queue.
#
# GH auth: reads a GitHub PAT from AWS Secrets Manager at
# "mockserver-build/github-token" (key: "token"). This is a BUILD-queue secret,
# separate from the release-queue's "mockserver-release/github-token". The default
# queue must have IAM access to this secret. If absent, the step fails with an
# actionable message.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../../.." && pwd)"

# ---------------------------------------------------------------------------
# Guard: skip when the pom is not on a -SNAPSHOT version
# ---------------------------------------------------------------------------
POM_VERSION=$(grep -m1 -oE '<version>[^<]+</version>' mockserver/pom.xml | head -1 | sed -E 's#</?version>##g')
if [[ "$POM_VERSION" != *-SNAPSHOT ]]; then
  echo "--- :fast_forward: Skipping snapshot bundles — pom is on release version $POM_VERSION (not a -SNAPSHOT)"
  exit 0
fi
echo "--- :package: Building snapshot binary bundles for $POM_VERSION"

# ---------------------------------------------------------------------------
# Locate the shaded JAR (built by the earlier Maven build step)
# ---------------------------------------------------------------------------
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

if [[ -z "$SHADED_JAR" ]]; then
  echo "Error: shaded JAR not found after artifact download."
  echo "The Maven build step must have completed successfully and uploaded the artifact."
  exit 1
fi
echo "Using JAR: $SHADED_JAR"

# ---------------------------------------------------------------------------
# Ensure a host JDK 21 jlink (the bundle cross-build needs jlink major 21).
# IMPORTANT: this is used ONLY by this step. The Maven build keeps running on
# JDK 17 inside the maven Docker image — we never change its JDK. We bootstrap
# Temurin 21 on demand (no agent AMI change required) so adding 21 here cannot
# pull the 17 build onto a newer JDK.
# ---------------------------------------------------------------------------
echo "--- :java: Ensuring host JDK 21 for jlink (Maven build stays on 17)"
jlink_major() { "$1" --version 2>&1 | grep -oE '^[0-9]+' | head -1 || true; }

JDK21_HOME=""
if [[ -n "${JAVA_HOME:-}" && -x "$JAVA_HOME/bin/jlink" && "$(jlink_major "$JAVA_HOME/bin/jlink")" == "21" ]]; then
  JDK21_HOME="$JAVA_HOME"
elif command -v jlink >/dev/null 2>&1 && [[ "$(jlink_major "$(command -v jlink)")" == "21" ]]; then
  JDK21_HOME="$(dirname "$(dirname "$(readlink -f "$(command -v jlink)")")")"
else
  case "$(uname -s)/$(uname -m)" in
    Linux/x86_64)  AOS=linux; AARCH=x64 ;;
    Linux/aarch64) AOS=linux; AARCH=aarch64 ;;
    Darwin/x86_64) AOS=mac;   AARCH=x64 ;;
    Darwin/arm64)  AOS=mac;   AARCH=aarch64 ;;
    *) echo "Error: unsupported host platform $(uname -s)/$(uname -m) for JDK 21 bootstrap"; exit 1 ;;
  esac
  HOST_JDK_DIR="$REPO_ROOT/.tmp/jdks/host-jdk-21"
  if [[ ! -x "$HOST_JDK_DIR/bin/jlink" && ! -x "$HOST_JDK_DIR/Contents/Home/bin/jlink" ]]; then
    echo "No host JDK 21 found — bootstrapping Temurin 21 ($AOS/$AARCH) for jlink only..."
    mkdir -p "$HOST_JDK_DIR"
    _arch="$REPO_ROOT/.tmp/jdks/host-jdk-21.tar.gz"
    curl -fsSL -o "$_arch" \
      "https://api.adoptium.net/v3/binary/latest/21/ga/${AOS}/${AARCH}/jdk/hotspot/normal/eclipse?project=jdk" \
      || { echo "Error: failed to download Temurin 21 for the host"; exit 1; }
    tar -xzf "$_arch" -C "$HOST_JDK_DIR" --strip-components=1
    rm -f "$_arch"
  fi
  if [[ -x "$HOST_JDK_DIR/bin/jlink" ]]; then
    JDK21_HOME="$HOST_JDK_DIR"
  else
    JDK21_HOME="$HOST_JDK_DIR/Contents/Home"   # macOS layout
  fi
fi

[[ -x "$JDK21_HOME/bin/jlink" ]] || { echo "Error: could not provision a JDK 21 jlink"; exit 1; }
echo "Using JDK 21 for jlink (Maven unaffected): $JDK21_HOME"

# ---------------------------------------------------------------------------
# Build all platform bundles
# ---------------------------------------------------------------------------
echo "--- :gear: Building bundles for all platforms"
BUNDLE_OUT="$REPO_ROOT/.tmp/bundles"
rm -rf "$BUNDLE_OUT"
mkdir -p "$BUNDLE_OUT"

# JAVA_HOME points build-all-bundles.sh at the bootstrapped JDK 21 jlink for THIS
# invocation only (a subshell env), so the Maven 17 build is never affected.
JAVA_HOME="$JDK21_HOME" "$REPO_ROOT/scripts/build-all-bundles.sh" \
  --jar "$SHADED_JAR" \
  --version "$POM_VERSION" \
  --jdk-version 21 \
  --cache "$REPO_ROOT/.tmp/jdks" \
  --output "$BUNDLE_OUT"

# Collect produced assets
ASSETS=()
while IFS= read -r _asset; do
  [[ -n "$_asset" ]] && ASSETS+=("$_asset")
done < <(ls "$BUNDLE_OUT"/mockserver-"$POM_VERSION"-*.tar.gz \
            "$BUNDLE_OUT"/mockserver-"$POM_VERSION"-*.zip \
            "$BUNDLE_OUT"/mockserver-"$POM_VERSION"-*.sha256 2>/dev/null || true)

if [[ ${#ASSETS[@]} -eq 0 ]]; then
  echo "Error: no bundle assets were produced."
  exit 1
fi

echo "Built ${#ASSETS[@]} assets:"
printf '    %s\n' "${ASSETS[@]##*/}"

# ---------------------------------------------------------------------------
# Publish to the rolling "mockserver-snapshot" GitHub pre-release
# ---------------------------------------------------------------------------
echo "--- :github: Publishing bundles to rolling snapshot release"

# Fetch GitHub token from Secrets Manager.
# This is the BUILD-queue token, not the release-queue token.
{ set +x; } 2>/dev/null  # suppress xtrace before secret fetch
GITHUB_TOKEN=$(aws secretsmanager get-secret-value \
  --secret-id "mockserver-build/github-token" \
  --region eu-west-2 \
  --query SecretString \
  --output text 2>/dev/null | jq -r '.token' 2>/dev/null) || true

if [[ -z "$GITHUB_TOKEN" || "$GITHUB_TOKEN" == "null" ]]; then
  echo ""
  echo "=========================================================================="
  echo "ERROR: GitHub token not found in AWS Secrets Manager."
  echo ""
  echo "  Secret:  mockserver-build/github-token"
  echo "  Key:     token"
  echo "  Region:  eu-west-2"
  echo ""
  echo "This secret must be provisioned with a GitHub PAT that has:"
  echo "  - Scope: contents (write) on mock-server/mockserver-monorepo"
  echo "  - Purpose: publish snapshot bundle assets to the rolling GitHub Release"
  echo ""
  echo "The default-queue agents need IAM access to this secret via the"
  echo "read_build_secrets_default policy in terraform/buildkite-agents/build-secrets.tf."
  echo ""
  echo "Steps to provision:"
  echo "  1. Create a fine-grained GitHub PAT with 'Contents: Read and write'"
  echo "     scoped to mock-server/mockserver-monorepo"
  echo "  2. Store it in Secrets Manager:"
  echo '     aws secretsmanager create-secret --name "mockserver-build/github-token" --region eu-west-2 --secret-string '"'"'{"token":"ghp_..."}'"'"
  echo "  3. Add its ARN to the read_build_secrets_default IAM policy in"
  echo "     terraform/buildkite-agents/build-secrets.tf"
  echo "  4. Run terraform apply in terraform/buildkite-agents/"
  echo "=========================================================================="
  echo ""
  exit 1
fi

export GH_TOKEN="$GITHUB_TOKEN"
GH_REPO="mock-server/mockserver-monorepo"
SNAPSHOT_TAG="mockserver-snapshot"
COMMIT_SHA="${BUILDKITE_COMMIT:-$(git rev-parse HEAD)}"

# Ensure the rolling pre-release exists (create if absent, update if present).
if gh release view "$SNAPSHOT_TAG" --repo "$GH_REPO" >/dev/null 2>&1; then
  echo "Updating existing rolling snapshot release"
  gh release edit "$SNAPSHOT_TAG" \
    --repo "$GH_REPO" \
    --prerelease \
    --title "MockServer SNAPSHOT" \
    --notes "Rolling snapshot binary bundles from master.

Version: \`$POM_VERSION\`
Commit: \`$COMMIT_SHA\`

These bundles are rebuilt on every successful master build. Download the bundle for your platform and extract it — the archive contains a self-contained JVM runtime and launcher script.

**This is a development snapshot, not a stable release.**" \
    --target "$COMMIT_SHA" || true
else
  echo "Creating rolling snapshot release"
  gh release create "$SNAPSHOT_TAG" \
    --repo "$GH_REPO" \
    --prerelease \
    --title "MockServer SNAPSHOT" \
    --notes "Rolling snapshot binary bundles from master.

Version: \`$POM_VERSION\`
Commit: \`$COMMIT_SHA\`

These bundles are rebuilt on every successful master build. Download the bundle for your platform and extract it — the archive contains a self-contained JVM runtime and launcher script.

**This is a development snapshot, not a stable release.**" \
    --target "$COMMIT_SHA"
fi

# Upload assets, replacing any existing ones from the previous build.
echo "Uploading ${#ASSETS[@]} assets (--clobber)..."
gh release upload "$SNAPSHOT_TAG" \
  --repo "$GH_REPO" \
  --clobber \
  "${ASSETS[@]}"

echo "--- :white_check_mark: Snapshot bundles published to https://github.com/$GH_REPO/releases/tag/$SNAPSHOT_TAG"

# ---------------------------------------------------------------------------
# Cleanup
# ---------------------------------------------------------------------------
rm -rf "$BUNDLE_OUT"
