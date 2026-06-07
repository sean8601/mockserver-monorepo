#!/usr/bin/env bash
# Post-release verification: hit every public URL we publish to and confirm
# $RELEASE_VERSION is actually live. This is the safety net against silent-
# skip / silent-success failure modes that individual component scripts may
# not surface themselves — exactly the class of bug that left 6.1.0 with
# Javadoc unpublished while the Javadoc step reported "passed" in build #36.
#
# Hard checks (failure aborts the build):
#   Maven Central core + plugin, brew-tar artifact, Docker Hub, npm × 2,
#   PyPI, RubyGems, GitHub Release, Helm chart (tarball + index.yaml),
#   Website, JSON Schema, Javadoc, SwaggerHub (version + default).
#
# Soft checks (warn, don't fail):
#   Homebrew formula — bumped asynchronously by BrewTestBot, may not be
#   live for a few hours after release.
#
# Versioned-site check is skipped when CREATE_VERSIONED_SITE != yes.
#
# Dry-run: skip all URL checks (the artifacts aren't actually published).

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

require_cmd curl
require_cmd jq
require_release_inputs
skip_unless_release_type "verify" full,post-maven

log_step "Post-release verification $RELEASE_VERSION (dry-run=$DRY_RUN)"

if is_dry_run; then
  log_dry "skip: every public URL check"
  exit 0
fi

V="$RELEASE_VERSION"

# SwaggerHub registers specs under the major.minor ".x" label (e.g. 7.0.x), not
# the full patch version — see swaggerhub.sh. Verify against that label.
V_MINOR_REST="${V#*.}"
API_V="${V%%.*}.${V_MINOR_REST%%.*}.x"
# Dot-escaped form for grep -E containment checks of the website's spec links.
API_V_RE="${API_V//./\\.}"

# Failure accumulators — collect ALL failures rather than abort on the first,
# so the operator sees the full picture in one pass. Hard failures fail the
# build; soft failures emit a warning summary.
declare -a HARD_FAILS=()
declare -a SOFT_FAILS=()

# check_http <label> <url> [expected_codes_regex]
# Default expected: 200|301|302 (HEAD redirects on some CDNs).
check_http() {
  local label="$1" url="$2"
  local expected="${3:-200|301|302}"
  local code
  code=$(curl -sS --connect-timeout 10 --max-time 30 -o /dev/null -w '%{http_code}' -L -I "$url" 2>/dev/null || echo "000")
  if [[ "$code" =~ ^(${expected})$ ]]; then
    log_info "  PASS  $label  (HTTP $code)"
  else
    log_error "  FAIL  $label  (HTTP $code, expected $expected) — $url"
    HARD_FAILS+=("$label")
  fi
}

# check_http_soft — same as check_http but logs WARN and routes failures
# to SOFT_FAILS instead of HARD_FAILS.
check_http_soft() {
  local label="$1" url="$2"
  local expected="${3:-200|301|302}"
  local code
  code=$(curl -sS --connect-timeout 10 --max-time 30 -o /dev/null -w '%{http_code}' -L -I "$url" 2>/dev/null || echo "000")
  if [[ "$code" =~ ^(${expected})$ ]]; then
    log_info "  PASS  $label  (HTTP $code)"
  else
    log_info "  WARN  $label  (HTTP $code, expected $expected) — $url [soft check]"
    SOFT_FAILS+=("$label")
  fi
}

# check_json <label> <url> <jq-filter-expecting-true>
# Treats a curl-level failure or a jq filter that doesn't return truthy as
# a hard failure.
check_json() {
  local label="$1" url="$2" filter="$3"
  local response
  response=$(curl -sS --connect-timeout 10 --max-time 30 "$url" 2>/dev/null || echo "")
  if [[ -z "$response" ]]; then
    log_error "  FAIL  $label  (empty response from $url)"
    HARD_FAILS+=("$label")
    return
  fi
  if echo "$response" | jq -e "$filter" >/dev/null 2>&1; then
    log_info "  PASS  $label"
  else
    log_error "  FAIL  $label  (jq filter \"$filter\" not truthy) — $url"
    HARD_FAILS+=("$label")
  fi
}

# check_body_contains <label> <url> <grep-pattern>
# Plain-text containment check, for non-JSON endpoints (e.g. Helm index.yaml, HTML pages).
check_body_contains() {
  local label="$1" url="$2" pattern="$3"
  local response
  response=$(curl -sS --connect-timeout 10 --max-time 30 "$url" 2>/dev/null || echo "")
  if [[ -z "$response" ]]; then
    log_error "  FAIL  $label  (empty response from $url)"
    HARD_FAILS+=("$label")
    return
  fi
  if echo "$response" | grep -qE "$pattern"; then
    log_info "  PASS  $label"
  else
    log_error "  FAIL  $label  (pattern not found: $pattern) — $url"
    HARD_FAILS+=("$label")
  fi
}

log_info ""
log_info "Verifying $V is live on every public channel..."

log_info ""
log_info "== Maven Central =="
check_http "mockserver-netty $V pom" \
  "https://repo1.maven.org/maven2/org/mock-server/mockserver-netty/$V/mockserver-netty-$V.pom"
check_http "mockserver-maven-plugin $V pom" \
  "https://repo1.maven.org/maven2/org/mock-server/mockserver-maven-plugin/$V/mockserver-maven-plugin-$V.pom"
check_http "mockserver-netty $V brew-tar (for Homebrew livecheck)" \
  "https://repo1.maven.org/maven2/org/mock-server/mockserver-netty/$V/mockserver-netty-$V-brew-tar.tar"

log_info ""
log_info "== npm =="
check_http "mockserver-node@$V on registry.npmjs.org" \
  "https://registry.npmjs.org/mockserver-node/$V"
check_http "mockserver-client@$V on registry.npmjs.org" \
  "https://registry.npmjs.org/mockserver-client/$V"

log_info ""
log_info "== PyPI =="
check_http "mockserver-client $V on pypi.org" \
  "https://pypi.org/pypi/mockserver-client/$V/json"

log_info ""
log_info "== RubyGems =="
check_json "mockserver-client $V on rubygems.org" \
  "https://rubygems.org/api/v1/versions/mockserver-client.json" \
  "any(.[]?; .number == \"$V\")"

log_info ""
log_info "== Docker Hub =="
check_http "mockserver/mockserver:$V tag" \
  "https://hub.docker.com/v2/repositories/mockserver/mockserver/tags/$V/"

log_info ""
log_info "== Helm =="
check_http "mockserver-$V.tgz" \
  "https://www.mock-server.com/mockserver-$V.tgz"
check_body_contains "$V listed in Helm index.yaml" \
  "https://www.mock-server.com/index.yaml" \
  "^[[:space:]]+version:[[:space:]]+\"?${V}\"?$"

log_info ""
log_info "== GitHub Release =="
check_http "release tag mockserver-$V" \
  "https://github.com/mock-server/mockserver-monorepo/releases/tag/mockserver-$V"

log_info ""
log_info "== Website =="
check_http "main mock-server.com" "https://www.mock-server.com/"
check_http "Javadoc $V apidocs" \
  "https://www.mock-server.com/versions/$V/apidocs/index.html"
# The deployed docs must link to THIS release's OpenAPI spec label (X.Y.x), not
# a stale one. update-version-references.sh bumps mockserver_api_version before
# the website build, but a stale-config build or a botched versioned-site
# snapshot would silently ship docs that point at the previous version's spec
# (as the legacy 6-0 -> 5.15.x and 5-15 -> 5.14.x sites still do). Fail loudly.
check_body_contains "live site links to OpenAPI spec $API_V" \
  "https://www.mock-server.com/mock_server/clearing_and_resetting.html" \
  "mock-server-openapi/${API_V_RE}[\"#/]"

log_info ""
log_info "== JSON Schema =="
check_http "expectation schema" "https://www.mock-server.com/schema/expectation.json"
check_http "expectations schema" "https://www.mock-server.com/schema/expectations.json"

log_info ""
log_info "== SwaggerHub =="
check_http "spec $API_V" \
  "https://api.swaggerhub.com/apis/jamesdbloom/mock-server-openapi/$API_V"
# Default-version check — SwaggerHub's settings/default endpoint returns
# `{"version":"X.Y.x"}` for the current default; if swaggerhub.sh's PUT
# /settings/default step silently failed, this is where we'd surface it.
default_version=$(curl -sS --connect-timeout 10 --max-time 30 \
  "https://api.swaggerhub.com/apis/jamesdbloom/mock-server-openapi/settings/default" 2>/dev/null \
  | jq -r '.version // empty' 2>/dev/null)
if [[ "$default_version" == "$API_V" ]]; then
  log_info "  PASS  SwaggerHub default version is $API_V"
else
  log_error "  FAIL  SwaggerHub default version is '${default_version:-<empty>}', expected '$API_V'"
  HARD_FAILS+=("SwaggerHub default version")
fi

if [[ "$CREATE_VERSIONED_SITE" == "yes" ]]; then
  log_info ""
  log_info "== Versioned site =="
  SUBDOMAIN=$(version_to_subdomain "$V")
  check_http "${SUBDOMAIN}.mock-server.com" "https://${SUBDOMAIN}.mock-server.com/"
  # The frozen versioned snapshot must also link to this release's spec label.
  check_body_contains "${SUBDOMAIN}.mock-server.com links to OpenAPI spec $API_V" \
    "https://${SUBDOMAIN}.mock-server.com/mock_server/clearing_and_resetting.html" \
    "mock-server-openapi/${API_V_RE}[\"#/]"
fi

log_info ""
log_info "== Homebrew (soft — bumped asynchronously by BrewTestBot) =="
homebrew_stable=$(curl -sS --connect-timeout 10 --max-time 30 \
  "https://formulae.brew.sh/api/formula/mockserver.json" 2>/dev/null \
  | jq -r '.versions.stable // empty' 2>/dev/null)
if [[ "$homebrew_stable" == "$V" ]]; then
  log_info "  PASS  Homebrew formula at $V"
else
  log_info "  WARN  Homebrew formula at '${homebrew_stable:-<empty>}' (expected $V) — BrewTestBot bumps within a few hours, check again later [soft check]"
  SOFT_FAILS+=("Homebrew formula")
fi

log_info ""
log_info "== Summary =="
if [[ ${#HARD_FAILS[@]} -eq 0 ]]; then
  log_info "  All hard checks passed for $V"
  if [[ ${#SOFT_FAILS[@]} -gt 0 ]]; then
    log_info "  Soft check(s) not (yet) green: ${SOFT_FAILS[*]}"
  fi
else
  log_error "  ${#HARD_FAILS[@]} hard check(s) failed: ${HARD_FAILS[*]}"
  if [[ ${#SOFT_FAILS[@]} -gt 0 ]]; then
    log_error "  ${#SOFT_FAILS[@]} soft check(s) also not green: ${SOFT_FAILS[*]}"
  fi
  exit 1
fi

log_info "Post-release verification complete"
