#!/usr/bin/env bash
# Release the mockserver-maven-plugin to Maven Central.
#
# The plugin inherits its version from the mockserver parent pom and uses
# ${project.version} for its internal mockserver-* dependency references, so
# version bumps are handled by the main maven-central release component. This
# script just builds the core mockserver (so the plugin can resolve it), then
# tags, deploys and publishes the plugin to Maven Central.
#
# Dry-run: build + verify only; skip tag + deploy.

set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source "$SCRIPT_DIR/_lib.sh"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --dry-run) DRY_RUN=true; shift ;;
    --execute) DRY_RUN=false; shift ;;
    -h|--help) echo "Usage: $0 [--dry-run|--execute]"; exit 0 ;;
    *) log_error "Unknown arg: $1"; exit 2 ;;
  esac
done

require_cmd docker
require_cmd git
require_cmd curl
require_cmd jq
require_release_inputs
skip_unless_release_type "maven-plugin" full,post-maven

log_step "Release mockserver-maven-plugin $RELEASE_VERSION (dry-run=$DRY_RUN)"
sync_to_origin_master

# Idempotent: if this version is already on Maven Central the plugin release
# is done. A re-run must not redeploy an existing version - Central rejects it.
if ! is_dry_run && curl -fsI -o /dev/null \
    "https://repo1.maven.org/maven2/org/mock-server/mockserver-maven-plugin/$RELEASE_VERSION/mockserver-maven-plugin-$RELEASE_VERSION.pom"; then
  log_info "mockserver-maven-plugin $RELEASE_VERSION already on Maven Central - skipping"
  exit 0
fi

log_info "Build core MockServer (in Docker)"
in_maven -w /build/mockserver \
  -- mvn clean install -DskipTests

if is_dry_run; then
  log_info "Verify maven-plugin (in Docker, tests skipped in dry-run)"
  in_maven -w /build/mockserver/mockserver-maven-plugin -- mvn clean install -DskipTests
else
  log_info "Verify maven-plugin (in Docker)"
  in_maven -w /build/mockserver/mockserver-maven-plugin -- mvn clean verify
fi

if is_dry_run; then
  log_dry "skip: tag, deploy"
  log_info "maven-plugin dry-run complete"
  exit 0
fi

git_tag_and_push "maven-plugin-$RELEASE_VERSION"

log_info "Deploy + publish maven-plugin to Maven Central (GPG-sign in container)"
GPG_KEY_B64=$(load_secret "mockserver-release/gpg-key" "key")
GPG_PASSPHRASE=$(load_secret "mockserver-release/gpg-key" "passphrase")
SONATYPE_USERNAME=$(load_secret "mockserver-build/sonatype" "username")
SONATYPE_PASSWORD=$(load_secret "mockserver-build/sonatype" "password")

# Write the mvn output to a tmpfile under .tmp/ (per AGENTS.md policy) so we
# can both stream it to the agent's stdout AND extract the deploymentId
# afterwards. Capturing in `$(...)` would obscure mvn's exit code: under
# `set -o pipefail` the pipeline's exit status is the rightmost non-zero,
# so a `$(cmd | tee /dev/stderr)` returns tee's exit code (always 0) — a
# failed `mvn deploy` would be silently swallowed (same class of bug as
# F-MC-01 in maven-central.sh). Explicit PIPESTATUS[0] check after the
# pipe makes the failure surface immediately. tmpfile is rm'd as soon as
# DEPLOYMENT_ID is captured because it can contain GPG/Sonatype output if
# any subprocess accidentally enables `set -x`.
mkdir -p "$REPO_ROOT/.tmp"
DEPLOY_LOG=$(mktemp "$REPO_ROOT/.tmp/mockserver-maven-plugin-deploy.XXXXXX")
trap 'rm -f "$DEPLOY_LOG"' EXIT

in_docker "$MAVEN_IMAGE" \
  -w /build/mockserver/mockserver-maven-plugin \
  -v mockserver-m2-cache:/root/.m2 \
  -e "GPG_KEY_B64=$GPG_KEY_B64" \
  -e "GPG_PASSPHRASE=$GPG_PASSPHRASE" \
  -e "SONATYPE_USERNAME=$SONATYPE_USERNAME" \
  -e "SONATYPE_PASSWORD=$SONATYPE_PASSWORD" \
  -- bash -ec '
    apt-get update -qq >/dev/null
    apt-get install -y -qq gnupg >/dev/null
    set +x
    echo "$GPG_KEY_B64" | base64 -d | gpg --batch --import
    mkdir -p ~/.gnupg
    echo "allow-loopback-pinentry" >> ~/.gnupg/gpg-agent.conf
    gpgconf --reload gpg-agent 2>/dev/null || true
    cat > /tmp/settings.xml <<SETTINGS
<?xml version="1.0" encoding="UTF-8"?>
<settings>
  <servers>
    <server>
      <id>central-portal</id>
      <username>${SONATYPE_USERNAME}</username>
      <password>${SONATYPE_PASSWORD}</password>
    </server>
    <server>
      <id>gpg.passphrase</id>
      <passphrase>${GPG_PASSPHRASE}</passphrase>
    </server>
  </servers>
</settings>
SETTINGS
    # central-publishing-maven-plugin is configured in the parent pom with
    # autoPublish=false / waitUntil=validated. Those are explicit <configuration>
    # values, which Maven gives precedence over -D user properties, so they
    # cannot be flipped from the command line. `mvn deploy` therefore only
    # uploads and validates the deployment and prints its deploymentId; the
    # promote-to-published step is done via the Central Portal API below.
    mvn deploy -P release -DskipTests \
      -Dgpg.passphraseServerId=gpg.passphrase \
      -Dgpg.useagent=false \
      --settings /tmp/settings.xml
  ' 2>&1 | tee "$DEPLOY_LOG"
mvn_exit=${PIPESTATUS[0]}
if [[ $mvn_exit -ne 0 ]]; then
  log_error "mvn deploy failed (in_docker exit $mvn_exit)"
  exit "$mvn_exit"
fi

# Promote the validated deployment to PUBLISHED. central-publishing-maven-plugin
# logs "deploymentId: <uuid>" on upload — extract it, POST it to the Central
# Portal publish endpoint, then poll until the deployment leaves VALIDATED.
# Strict UUID shape: 8-4-4-4-12 hex (java.util.UUID.randomUUID() is lowercase
# but accept both cases defensively); validate the captured value before any
# API call.
DEPLOYMENT_ID=$(grep -oE 'deploymentId: [0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\b' "$DEPLOY_LOG" \
  | head -1 | awk '{print $2}')
rm -f "$DEPLOY_LOG"
trap - EXIT
if [[ ! "$DEPLOYMENT_ID" =~ ^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$ ]]; then
  log_error "Could not extract a well-formed deploymentId from mvn deploy output (got: '${DEPLOYMENT_ID:-<empty>}')"
  exit 1
fi
log_info "Central Portal deployment: $DEPLOYMENT_ID"

CP_AUTH=$(printf '%s:%s' "$SONATYPE_USERNAME" "$SONATYPE_PASSWORD" | base64 | tr -d '\n')

log_info "Publishing deployment $DEPLOYMENT_ID"
mkdir -p "$REPO_ROOT/.tmp"
publish_response_file=$(mktemp "$REPO_ROOT/.tmp/mockserver-maven-plugin-publish.XXXXXX")
publish_http=$(curl -sS --connect-timeout 10 --max-time 30 -X POST -H "Authorization: Basic $CP_AUTH" \
  -o "$publish_response_file" -w '%{http_code}' \
  "https://central.sonatype.com/api/v1/publisher/deployment/$DEPLOYMENT_ID" 2>/dev/null || echo "000")
if [[ "$publish_http" != "204" && "$publish_http" != "200" ]]; then
  log_error "Failed to publish deployment $DEPLOYMENT_ID (HTTP $publish_http)"
  cat "$publish_response_file" || true
  rm -f "$publish_response_file"
  exit 1
fi
rm -f "$publish_response_file"
log_info "  publish acknowledged (HTTP $publish_http)"

log_info "Waiting for the deployment to leave VALIDATED"
state=""
published=false
for i in $(seq 1 20); do   # 20 × 15s = 5 min
  state=$(curl -fsS --connect-timeout 10 --max-time 30 -X POST -H "Authorization: Basic $CP_AUTH" \
    "https://central.sonatype.com/api/v1/publisher/status?id=$DEPLOYMENT_ID" \
    2>/dev/null | jq -r '.deploymentState' 2>/dev/null || true)
  log_info "  attempt $i: state=${state:-<empty>}"
  case "$state" in
    PUBLISHING|PUBLISHED) published=true; break ;;
    FAILED)               log_error "Central Portal deployment FAILED"; exit 1 ;;
  esac
  sleep 15
done
if ! $published; then
  log_error "Deployment $DEPLOYMENT_ID did not start publishing within 5 minutes"
  exit 1
fi

log_info "maven-plugin release complete (deployment $DEPLOYMENT_ID is $state)"
