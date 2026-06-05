#!/usr/bin/env bash
# Shared helper: resolve the commit SHA of the last PASSED build of a Buildkite
# pipeline, so a build can diff against it for change detection.
#
# Sourced by:
#   - generate-pipeline.sh   (path-based pipeline dispatch)
#   - steps/perf-test-guard.sh (skip the daily perf run when master hasn't moved)
#
# Reads the Buildkite API token from AWS Secrets Manager. Echoes the SHA on
# success; returns non-zero (with a `reason:` on stderr) when it cannot
# determine one, so callers can fall back conservatively.
#
# Honours these env vars (all optional, with sensible BUILDKITE_* defaults):
#   BUILDKITE_API_TOKEN_SECRET_ID, AWS_REGION, BUILDKITE_ORGANIZATION_SLUG,
#   BUILDKITE_PIPELINE_SLUG, BUILDKITE_BRANCH, BUILDKITE_BUILD_NUMBER

last_successful_commit() {
  local secret_id="${BUILDKITE_API_TOKEN_SECRET_ID:-mockserver-build/buildkite-api-token}"
  local region="${AWS_REGION:-eu-west-2}"
  local org="${BUILDKITE_ORGANIZATION_SLUG:-mockserver}"
  local pipeline="${BUILDKITE_PIPELINE_SLUG:-mockserver}"
  local branch="${BUILDKITE_BRANCH:-master}"
  local current_build="${BUILDKITE_BUILD_NUMBER:-}"

  local token
  { set +x; } 2>/dev/null  # F-BK-04: suppress xtrace before secret fetch
  token=$(aws secretsmanager get-secret-value \
    --secret-id "$secret_id" --region "$region" \
    --query SecretString --output text 2>/dev/null) || { echo "    reason: secrets manager unavailable" >&2; return 1; }
  [ -n "$token" ] || { echo "    reason: empty API token" >&2; return 1; }

  local api_base="https://api.buildkite.com/v2/organizations/${org}/pipelines/${pipeline}/builds"

  local response
  response=$(curl -sS --max-time 10 --connect-timeout 5 \
    --get "$api_base" \
    --data-urlencode "branch=${branch}" \
    --data-urlencode "state=passed" \
    --data-urlencode "per_page=10" \
    -H "Authorization: Bearer ${token}" 2>/dev/null) || { echo "    reason: Buildkite API request failed" >&2; return 1; }

  if ! printf '%s' "$response" | jq -e 'type == "array"' >/dev/null 2>&1; then
    echo "    reason: Buildkite API returned non-array response" >&2
    return 1
  fi

  local commit
  if [ -n "$current_build" ] && [[ "$current_build" =~ ^[0-9]+$ ]]; then
    commit=$(printf '%s' "$response" | jq -r \
      --argjson current "$current_build" \
      '[.[] | select(.number < $current)][0].commit // empty' 2>/dev/null)
  else
    commit=$(printf '%s' "$response" | jq -r '.[0].commit // empty' 2>/dev/null)
  fi

  if [ -z "$commit" ]; then
    echo "    reason: no previous successful build found" >&2
    return 1
  fi

  if ! git cat-file -t "$commit" >/dev/null 2>&1; then
    echo "    reason: commit ${commit:0:10} not in local history (shallow clone?)" >&2
    return 1
  fi

  echo "$commit"
}
