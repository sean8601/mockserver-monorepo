#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" >/dev/null && pwd)"
source "${SCRIPT_DIR}/../docker-compose.sh"
source "${SCRIPT_DIR}/../logging.sh"

printMessage "Start: \"${SCRIPT_DIR/\//}\""

function cleanup() {
  tear-down 2>/dev/null || true
}

# Retry a client-side check until it passes or attempts run out — absorbs the
# Prometheus scrape interval and the OTLP export interval on a cold runner
# without a brittle fixed sleep. Sets TEST_EXIT_CODE=1 on exhaustion.
function assert_eventually() {
  local desc="${1}" check="${2}" attempts="${3:-12}"
  for _ in $(seq 1 "${attempts}"); do
    if docker-exec-client "${check}"; then
      printMessage "PASS: ${desc}"
      return 0
    fi
    sleep 2
  done
  printMessage "FAIL: ${desc}"
  TEST_EXIT_CODE=1
  return 1
}

function integration_test() {
  trap cleanup EXIT
  start-up
  TEST_EXIT_CODE=0

  # wait for MockServer to be ready
  local ready=false
  for _ in $(seq 1 30); do
    if docker-exec-client "curl -sf -o /dev/null -X PUT http://mockserver:1080/mockserver/status"; then
      ready=true
      break
    fi
    sleep 1
  done
  if [[ "${ready}" != "true" ]]; then
    printMessage "FAIL: MockServer did not become ready"
    container-logs || true
    return 1
  fi

  # Drive some traffic. requests_received_count increments on ANY request, so
  # no expectation is needed (the unmatched requests return 404 but still count).
  for _ in $(seq 1 5); do
    docker-exec-client "curl -s -o /dev/null http://mockserver:1080/anything" || true
  done

  # Assert each metric path, retrying to absorb the scrape + OTLP-export
  # intervals rather than relying on a single brittle sleep.

  # 1 — Prometheus scraped MockServer's request counter (the scrape path)
  assert_eventually "Prometheus scraped requests_received_count" \
    "curl -sf 'http://prometheus:9090/api/v1/query?query=requests_received_count' | jq -e '.data.result[0].value[1] | tonumber > 0' >/dev/null" || true

  # 2 — JVM gauges present on the scrape path (validates JvmMetricsCollector)
  assert_eventually "JVM metrics present in Prometheus" \
    "curl -sf 'http://prometheus:9090/api/v1/query?query=jvm_memory_used_bytes' | jq -e '.data.result | length > 0' >/dev/null" || true

  # 3 — the same metric arrived via OTLP at the collector, which re-exports it
  # on its own Prometheus endpoint (the push path)
  assert_eventually "requests_received_count exported via OTLP to the collector" \
    "curl -sf http://otel-collector:8889/metrics | grep -q requests_received_count" || true

  if [[ "${TEST_EXIT_CODE}" != "0" ]]; then
    container-logs || true
  fi
  logTestResult "${TEST_EXIT_CODE}" "${TEST_CASE}"
  return "${TEST_EXIT_CODE}"
}

integration_test
