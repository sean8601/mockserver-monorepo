#!/usr/bin/env bash
# Build per-platform, JVM-less MockServer binary bundles and upload them as assets
# on a rolling GitHub pre-release tagged "mockserver-snapshot". Each master build
# replaces the previous assets (--clobber).
#
# Guard: skips when the pom version is NOT *-SNAPSHOT (mirrors java-deploy-snapshot.sh).
#
# Agent requirements: JDK 21 providing jlink, curl, tar, unzip, gh, jq, and
# ~2 GB scratch for the cached target JDKs. Runs on the default queue.
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
# Verify jlink is available (JDK 21 required for cross-build)
# ---------------------------------------------------------------------------
echo "--- :java: Verifying jlink availability"
if ! command -v jlink >/dev/null 2>&1; then
  echo "Error: jlink not found. Binary bundle cross-build requires JDK 21 with jlink on the PATH."
  echo "The CI image must include a JDK 21 installation."
  exit 1
fi
JLINK_MAJOR="$(jlink --version 2>&1 | grep -oE '^[0-9]+' | head -1 || true)"
if [[ "$JLINK_MAJOR" != "21" ]]; then
  echo "Error: binary bundles require JDK 21 jlink (found: '${JLINK_MAJOR:-none}')."
  echo "Install Temurin 21 on the CI agent or update the Docker image."
  exit 1
fi

# ---------------------------------------------------------------------------
# Build all platform bundles
# ---------------------------------------------------------------------------
echo "--- :gear: Building bundles for all platforms"
BUNDLE_OUT="$REPO_ROOT/.tmp/bundles"
rm -rf "$BUNDLE_OUT"
mkdir -p "$BUNDLE_OUT"

"$REPO_ROOT/scripts/build-all-bundles.sh" \
  --jar "$SHADED_JAR" \
  --version "$POM_VERSION" \
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
