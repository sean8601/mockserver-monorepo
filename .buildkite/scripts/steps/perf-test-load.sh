#!/usr/bin/env bash
set -euo pipefail

# Opt-in k6 load test against a containerised MockServer. This step runs ONLY on
# manual ("New Build") or scheduled builds (gated by the `if:` in
# pipeline-perf-test.yml) — never on every commit — so it does not keep
# scale-to-zero agents busy. The thresholds in k6/load.js are the pass/fail gate
# (k6 exits non-zero when a threshold is crossed).
#
# Durations default to short CI-friendly values; override via K6_* env vars.
# Override the MockServer image with MOCKSERVER_IMAGE (defaults to the snapshot).

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../../.." && pwd)"

K6_IMAGE="grafana/k6:1.7.1@sha256:4fd3a694926b064d3491d9b02b01cde886583c4931f1223816e3d9a7bdfa7e0f"
MOCKSERVER_IMAGE="${MOCKSERVER_IMAGE:-mockserver/mockserver:mockserver-snapshot}"
NETWORK="mockserver-perf-${BUILDKITE_BUILD_ID:-local}-$$"

cleanup() {
  docker rm -f mockserver-perf >/dev/null 2>&1 || true
  docker network rm "$NETWORK" >/dev/null 2>&1 || true
}
trap cleanup EXIT

docker network create "$NETWORK" >/dev/null

echo "--- starting MockServer ($MOCKSERVER_IMAGE)"
docker run -d --rm --name mockserver-perf --network "$NETWORK" \
  -e MOCKSERVER_LOG_LEVEL=ERROR \
  -e MOCKSERVER_DISABLE_SYSTEM_OUT=true \
  -e MOCKSERVER_METRICS_ENABLED=true \
  "$MOCKSERVER_IMAGE" -serverPort 1080 >/dev/null

echo "--- waiting for MockServer to be ready"
ready=false
for _ in $(seq 1 60); do
  status="$(docker inspect --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}nohealth{{end}}' mockserver-perf 2>/dev/null || echo missing)"
  case "$status" in
    healthy) echo "MockServer healthy"; ready=true; break ;;
    nohealth) echo "image has no HEALTHCHECK; applying 10s warmup"; sleep 10; ready=true; break ;;
    missing) echo "ERROR: MockServer container exited early" >&2; break ;;
  esac
  sleep 2
done
if [ "$ready" = false ]; then
  echo "ERROR: MockServer did not become ready" >&2
  docker logs mockserver-perf 2>&1 | tail -20 >&2 || true
  exit 1
fi

echo "--- running k6 load test (thresholds are the gate)"
# k6 seeds/resets MockServer itself (setup/teardown); it self-fails if the
# server is unreachable. A crossed threshold makes k6 exit non-zero -> step red.
# NOTE: do NOT `exec` here — that would replace this shell and skip the EXIT
# trap, leaking the MockServer container + network. Run normally so cleanup
# fires; `set -e` propagates k6's non-zero exit (threshold failure) after it.
docker run --rm --network "$NETWORK" \
  -v "$REPO_ROOT/mockserver-performance-test/k6:/k6:ro" \
  -e BASE_URL=http://mockserver-perf:1080 \
  -e K6_RAMP_UP="${K6_RAMP_UP:-10s}" \
  -e K6_HOLD="${K6_HOLD:-20s}" \
  -e K6_RAMP_DOWN="${K6_RAMP_DOWN:-5s}" \
  -e K6_PEAK_RATE="${K6_PEAK_RATE:-300}" \
  "$K6_IMAGE" run /k6/load.js
