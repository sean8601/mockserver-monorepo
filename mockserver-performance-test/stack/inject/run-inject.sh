#!/usr/bin/env bash
set -euo pipefail

# ==============================================================================
# run-inject.sh — drive MockServer load injectors at an Envoy direct_response
# sink and measure (a) a single injector's injection CEILING and (b) how
# aggregate injected throughput SCALES as injectors are added.
#
#   ./run-inject.sh ceiling                 # profile n1, RATE stair, -> inject-ceiling.json
#   ./run-inject.sh scale [N ...]           # profiles nN, RATE hold,  -> inject-scale.json
#       e.g. ./run-inject.sh scale 1 2      # local default (1 and 2 injectors)
#
# The script assumes the stack is reachable on the published host ports:
#   Prometheus  http://localhost:9090   (queried via the HTTP API)
#   Envoy admin http://localhost:9901
#   injector-K  http://localhost:108K   (each injector's 1080 published as 1080+K)
# The MockServer + Envoy images are distroless (no in-container curl/sh), so the
# load control-plane is driven from the HOST via the per-injector published port,
# and readiness/CPU are probed from the host too.
#
# Measurement method: rates are computed with the Prometheus HTTP API
#   sum(rate(mock_server_load_requests_total{scenario="X"}[15s]))           (aggregate)
#   sum by (run_id)(rate(mock_server_load_requests_total{scenario="X"}[15s])) (per-instance)
# sampled mid-way through each held stage (so the 15s window sits entirely
# inside the hold). CPU% comes from `docker stats`. Envoy-received rate comes
# from rate() over the Envoy downstream completed counter.
#
# ASSERTIONS: a measured point is only trusted when throttled_rps ~ 0 and
# error_rate ~ 0. If a cap is binding (throttle > epsilon) a loud WARNING is
# emitted into the output and stderr.
# ==============================================================================

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
COMPOSE_FILE="$SCRIPT_DIR/docker-compose.inject.yml"
COMPOSE=(docker compose -f "$COMPOSE_FILE")

PROM_URL="${PROM_URL:-http://localhost:9090}"
ENVOY_ADMIN="${ENVOY_ADMIN:-http://localhost:9901}"
MOCKSERVER_IMAGE="${MOCKSERVER_IMAGE:-mockserver/mockserver:mockserver-snapshot}"
export MOCKSERVER_IMAGE

SCENARIO_CEILING="${SCENARIO_CEILING:-inject-ceiling}"
SCENARIO_SCALE="${SCENARIO_SCALE:-inject-scale}"

# Scenario JSON file paths (overridable so a short smoke run can point at
# reduced-duration fixtures without editing the committed ones).
CEILING_FILE="${CEILING_FILE:-$SCRIPT_DIR/scenario-ceiling.json}"
SCALE_FILE="${SCALE_FILE:-$SCRIPT_DIR/scenario-scale.json}"

# Tolerances for the "is the number trustworthy" assertions.
THROTTLE_EPS="${THROTTLE_EPS:-5}"       # rps of throttle we treat as "~0"
ERROR_EPS="${ERROR_EPS:-0.005}"         # 0.5% error rate we treat as "~0"
RATE_WINDOW="${RATE_WINDOW:-15s}"       # rate() lookback; must be < hold duration

# Per-stage hold geometry for the ceiling stair. Keep in sync with
# scenario-ceiling.json (offered ladder + 30s holds). We sample at SETTLE secs
# into each hold so the RATE_WINDOW sits inside the steady portion.
# Space-separated so a smoke run can shorten the ladder: CEILING_OFFERED="2000 4000 8000"
# (must match the stage rates in the ceiling scenario file, in order).
read -r -a CEILING_OFFERED <<<"${CEILING_OFFERED:-2000 4000 8000 16000 32000 64000}"
CEILING_HOLD_S="${CEILING_HOLD_S:-30}"
CEILING_SETTLE_S="${CEILING_SETTLE_S:-22}"   # sample 22s into each 30s hold

# Scale hold geometry. Keep in sync with scenario-scale.json (90s hold).
SCALE_PER_INSTANCE_RPS="${SCALE_PER_INSTANCE_RPS:-30000}"
SCALE_HOLD_S="${SCALE_HOLD_S:-90}"
SCALE_SETTLE_S="${SCALE_SETTLE_S:-45}"       # sample 45s into the 90s hold

log()  { printf '%s\n' "--- $*" >&2; }
warn() { printf '%s\n' "!!! WARNING: $*" >&2; }
die()  { printf '%s\n' "ERROR: $*" >&2; exit 1; }

need() { command -v "$1" >/dev/null 2>&1 || die "'$1' is required on PATH"; }
need docker
need jq
need curl

# --- Prometheus instant-query helper ------------------------------------------
# Echoes the scalar value of the first result series (or "0" when no data).
prom_query() {
  local q="$1" out
  out="$(curl -fsS --max-time 10 --data-urlencode "query=${q}" "${PROM_URL}/api/v1/query" 2>/dev/null || echo '')"
  if [ -z "$out" ]; then echo "0"; return; fi
  printf '%s' "$out" | jq -r '
    if .status=="success" and (.data.result|length)>0
    then (.data.result[0].value[1] // "0")
    else "0" end' 2>/dev/null || echo "0"
}

# Echoes one "run_id value" line per series (for per-instance breakdown).
prom_query_by_runid() {
  local q="$1" out
  out="$(curl -fsS --max-time 10 --data-urlencode "query=${q}" "${PROM_URL}/api/v1/query" 2>/dev/null || echo '')"
  if [ -z "$out" ]; then return; fi
  printf '%s' "$out" | jq -r '
    if .status=="success"
    then (.data.result[] | "\(.metric.run_id // "?") \(.value[1])")
    else empty end' 2>/dev/null || true
}

# round a float to an integer (banker-ish: nearest)
rnd() { awk -v x="${1:-0}" 'BEGIN{printf "%d", (x<0?x-0.5:x+0.5)}'; }
# round a float to N decimals
rndf() { awk -v x="${1:-0}" -v n="${2:-4}" 'BEGIN{printf "%.*f", n, x}'; }
gt()  { awk -v a="${1:-0}" -v b="${2:-0}" 'BEGIN{exit !(a>b)}'; }

# --- container CPU% (docker stats, single snapshot) ---------------------------
container_cpu_pct() {
  local name="$1"
  docker stats --no-stream --format '{{.CPUPerc}}' "$name" 2>/dev/null \
    | tr -d '% ' | head -1 || echo ""
}

# --- wait for the chosen profile's injectors to report metrics to Prometheus --
wait_injectors_scraped() {
  local expected="$1"   # how many injector targets we expect UP
  log "waiting for $expected injector(s) + envoy to be scraped by Prometheus"
  for _ in $(seq 1 60); do
    local up
    up="$(curl -fsS --max-time 5 "${PROM_URL}/api/v1/query" \
            --data-urlencode 'query=up{job="injectors"}==1' 2>/dev/null \
          | jq -r '.data.result|length' 2>/dev/null || echo 0)"
    if [ "${up:-0}" -ge "$expected" ]; then
      log "Prometheus sees $up injector target(s) up"
      return 0
    fi
    sleep 2
  done
  die "timed out waiting for $expected injectors to be scraped (saw fewer)"
}

# --- map an injector service name to its published host control port ----------
# The MockServer image is distroless (no in-container curl/sh), so the control
# plane is driven from the host via the per-injector published port (1080+K).
injector_host_url() {
  local svc="$1"
  local n="${svc#injector-}"
  echo "http://localhost:$((1080 + n))"
}

# --- register a scenario file on an injector and trigger it -------------------
register_and_trigger() {
  local svc="$1" scenario_file="$2" scenario_name="$3"
  local base; base="$(injector_host_url "$svc")"
  local body; body="$(cat "$scenario_file")"
  log "[$svc] registering scenario '$scenario_name' at $base"
  curl -fsS -X PUT "$base/mockserver/loadScenario" \
      -H 'Content-Type: application/json' -d "$body" >/dev/null \
    || die "[$svc] failed to register scenario (is the cap rejecting it? check logs)"
  log "[$svc] triggering '$scenario_name'"
  curl -fsS -X PUT "$base/mockserver/loadScenario/start" \
      -H 'Content-Type: application/json' -d "{\"name\":\"$scenario_name\"}" >/dev/null \
    || die "[$svc] failed to trigger scenario (loadGenerationEnabled? 403?)"
}

stop_all_injectors() {
  local svc base
  for svc in "$@"; do
    base="$(injector_host_url "$svc")"
    curl -fsS -X PUT "$base/mockserver/loadScenario/stop" \
        -d '{"all":true}' >/dev/null 2>&1 || true
  done
}

# --- sample the metrics for a scenario at the current instant ------------------
# Emits a JSON object for one measured point. Args: scenario, offered_rps,
# space-separated injector container names (for CPU).
sample_point() {
  local scenario="$1" offered="$2"; shift 2
  local injectors=("$@")

  local achieved throttled errors inflight recv_rps err_rate
  achieved="$(prom_query "sum(rate(mock_server_load_requests_total{scenario=\"$scenario\"}[$RATE_WINDOW]))")"
  throttled="$(prom_query "sum(rate(mock_server_load_throttled_total{scenario=\"$scenario\"}[$RATE_WINDOW]))")"
  errors="$(prom_query "sum(rate(mock_server_load_errors_total{scenario=\"$scenario\"}[$RATE_WINDOW]))")"
  inflight="$(prom_query "sum(mock_server_load_inflight_requests{scenario=\"$scenario\"})")"

  # Envoy received: rate over the downstream completed counter for the ingress
  # listener. envoy_http_downstream_rq_completed is the per-HCM completed count.
  recv_rps="$(prom_query "sum(rate(envoy_http_downstream_rq_completed{envoy_http_conn_manager_prefix=\"ingress_http\"}[$RATE_WINDOW]))")"
  if ! gt "$recv_rps" 0; then
    # Fallback to the 2xx response-class counter if the completed series is absent.
    recv_rps="$(prom_query "sum(rate(envoy_http_downstream_rq_xx{envoy_response_code_class=\"2\",envoy_http_conn_manager_prefix=\"ingress_http\"}[$RATE_WINDOW]))")"
  fi

  # error_rate = errors / (achieved + errors)
  err_rate="$(awk -v e="$errors" -v a="$achieved" 'BEGIN{ d=a+e; printf "%.5f", (d>0? e/d : 0) }')"

  # CPU: max across the chosen injectors (the ceiling injector is index 0).
  local cpu_inj=0 c
  for name in "${injectors[@]}"; do
    c="$(container_cpu_pct "$name")"; c="${c:-0}"
    if gt "$c" "$cpu_inj"; then cpu_inj="$c"; fi
  done
  local cpu_envoy; cpu_envoy="$(container_cpu_pct "$(envoy_container)")"; cpu_envoy="${cpu_envoy:-0}"

  if gt "$throttled" "$THROTTLE_EPS"; then
    warn "scenario '$scenario' offered=$offered: throttled=$(rnd "$throttled") rps (> $THROTTLE_EPS) — a CAP IS BINDING, this point is NOT trustworthy"
  fi
  if gt "$err_rate" "$ERROR_EPS"; then
    warn "scenario '$scenario' offered=$offered: error_rate=$err_rate (> $ERROR_EPS) — errors present, point suspect"
  fi

  jq -n \
    --argjson offered "$offered" \
    --argjson achieved "$(rnd "$achieved")" \
    --argjson throttled "$(rnd "$throttled")" \
    --argjson error_rate "$(rndf "$err_rate" 5)" \
    --argjson injector_cpu_pct "$(rnd "$cpu_inj")" \
    --argjson envoy_cpu_pct "$(rnd "$cpu_envoy")" \
    --argjson max_inflight "$(rnd "$inflight")" \
    --argjson target_received_rps "$(rnd "$recv_rps")" \
    '{offered_rps:$offered, achieved_rps:$achieved, throttled_rps:$throttled,
      error_rate:$error_rate, injector_cpu_pct:$injector_cpu_pct,
      envoy_cpu_pct:$envoy_cpu_pct, max_inflight:$max_inflight,
      target_received_rps:$target_received_rps}'
}

# container-name helpers (compose default project naming may add -1 suffix; ask compose)
svc_container() { "${COMPOSE[@]}" ps -q "$1" 2>/dev/null | head -1; }
envoy_container() { svc_container envoy; }

# Wait for the Envoy admin /ready and the first N injector control ports to
# answer on the host before doing anything (the distroless images have no
# in-container healthcheck).
wait_stack_ready() {
  local n="$1"
  log "waiting for Envoy admin /ready"
  for _ in $(seq 1 60); do
    [ "$(curl -fsS --max-time 2 "${ENVOY_ADMIN}/ready" 2>/dev/null || echo X)" = "LIVE" ] && break
    sleep 2
  done
  [ "$(curl -fsS --max-time 2 "${ENVOY_ADMIN}/ready" 2>/dev/null || echo X)" = "LIVE" ] \
    || die "Envoy did not become ready"
  local k base
  for k in $(seq 1 "$n"); do
    base="http://localhost:$((1080 + k))"
    log "waiting for injector-$k control plane at $base"
    for _ in $(seq 1 60); do
      curl -fsS --max-time 2 "$base/mockserver/metrics" >/dev/null 2>&1 && break
      sleep 2
    done
    curl -fsS --max-time 2 "$base/mockserver/metrics" >/dev/null 2>&1 \
      || die "injector-$k control plane did not answer at $base"
  done
}

compose_up() {
  local profile="$1"
  log "bringing up profile $profile (image $MOCKSERVER_IMAGE)"
  "${COMPOSE[@]}" --profile "$profile" up -d
}

compose_down() {
  log "tearing down stack"
  "${COMPOSE[@]}" --profile n1 --profile n2 --profile n4 --profile n6 down -v >/dev/null 2>&1 || true
}

# ==============================================================================
# ceiling: bring up n1, run the RATE stair on injector-1, sample each held stage
# ==============================================================================
cmd_ceiling() {
  compose_up n1
  wait_stack_ready 1
  local inj1; inj1="$(svc_container injector-1)"
  [ -n "$inj1" ] || die "injector-1 container not found"
  wait_injectors_scraped 1

  register_and_trigger injector-1 "$CEILING_FILE" "$SCENARIO_CEILING"

  local points="[]"
  local ceiling_rps=0
  local last_clean_evidence='{}'
  local i offered
  for i in "${!CEILING_OFFERED[@]}"; do
    offered="${CEILING_OFFERED[$i]}"
    log "ceiling stage $((i+1))/${#CEILING_OFFERED[@]}: offered=$offered rps — settling ${CEILING_SETTLE_S}s into the ${CEILING_HOLD_S}s hold"
    sleep "$CEILING_SETTLE_S"
    local pt; pt="$(sample_point "$SCENARIO_CEILING" "$offered" "$inj1")"
    points="$(jq -c --argjson p "$pt" '. + [$p]' <<<"$points")"

    # Track the highest offered rate that the injector actually achieved cleanly
    # (achieved ~ offered, no throttle, no errors) as the ceiling estimate.
    local ach thr er inf
    ach="$(jq -r '.achieved_rps' <<<"$pt")"
    thr="$(jq -r '.throttled_rps' <<<"$pt")"
    er="$(jq -r '.error_rate' <<<"$pt")"
    inf="$(jq -r '.max_inflight' <<<"$pt")"
    # "clean" = achieved within 10% of offered, throttle ~0, errors ~0
    if awk -v a="$ach" -v o="$offered" -v t="$thr" -v te="$THROTTLE_EPS" -v e="$er" -v ee="$ERROR_EPS" \
         'BEGIN{exit !(a >= 0.90*o && t <= te && e <= ee)}'; then
      ceiling_rps="$ach"
      last_clean_evidence="$(jq -n \
        --argjson injector_cpu_pct "$(jq '.injector_cpu_pct' <<<"$pt")" \
        --argjson envoy_cpu_pct "$(jq '.envoy_cpu_pct' <<<"$pt")" \
        --argjson throttled_rps "$(jq '.throttled_rps' <<<"$pt")" \
        --argjson error_rate "$(jq '.error_rate' <<<"$pt")" \
        --argjson inflight_below_cap "$( [ "$inf" -lt 20000 ] && echo true || echo false )" \
        '{injector_cpu_pct:$injector_cpu_pct, envoy_cpu_pct:$envoy_cpu_pct,
          throttled_rps:$throttled_rps, error_rate:$error_rate,
          inflight_below_cap:$inflight_below_cap}')"
      log "  -> clean: achieved=$ach (offered=$offered) — ceiling candidate"
    else
      log "  -> NOT clean (achieved=$ach offered=$offered throttle=$thr err=$er) — ladder top reached"
    fi
    # remaining tail of this hold before the next stage's offered changes
    local tail=$((CEILING_HOLD_S - CEILING_SETTLE_S))
    [ "$tail" -gt 0 ] && sleep "$tail"
  done

  stop_all_injectors injector-1

  local out="$SCRIPT_DIR/inject-ceiling.json"
  jq -n \
    --argjson points "$points" \
    --argjson ceiling_rps "$(rnd "$ceiling_rps")" \
    --argjson evidence "$last_clean_evidence" \
    '{proto:"http", target:"envoy-direct-response",
      caps:{max_requests_per_second:200000, max_in_flight_requests:20000, client_nio_threads:8},
      points:$points,
      ceiling_rps:$ceiling_rps,
      ceiling_evidence:$evidence}' > "$out"

  log "wrote $out"
  cat "$out"
}

# ==============================================================================
# scale: for each N, bring up nN, trigger scenario-scale on each injector
# together, sample the aggregate + per-instance during the hold.
# ==============================================================================
cmd_scale() {
  local sizes=("$@")
  [ "${#sizes[@]}" -gt 0 ] || sizes=(1 2)

  local series="[]"
  local max_instances=0 actual_at_max=0
  local n
  for n in "${sizes[@]}"; do
    local profile="n$n"
    case "$n" in 1|2|4|6) ;; *) die "unsupported instance count '$n' (use 1,2,4,6)";; esac

    # fresh stack per N so run_ids and counters don't carry over
    compose_down
    compose_up "$profile"
    wait_stack_ready "$n"
    wait_injectors_scraped "$n"

    # collect the injector services for this N
    local svcs=() conts=()
    local k
    for k in $(seq 1 "$n"); do svcs+=("injector-$k"); done
    for s in "${svcs[@]}"; do conts+=("$(svc_container "$s")"); done

    # register on each, then trigger each (they start ~together; small skew ok)
    for s in "${svcs[@]}"; do
      register_and_trigger "$s" "$SCALE_FILE" "$SCENARIO_SCALE"
    done

    log "scale N=$n: settling ${SCALE_SETTLE_S}s into the ${SCALE_HOLD_S}s hold"
    sleep "$SCALE_SETTLE_S"

    # aggregate + per-instance + envoy
    local agg recv err err_rate
    agg="$(prom_query "sum(rate(mock_server_load_requests_total{scenario=\"$SCENARIO_SCALE\"}[$RATE_WINDOW]))")"
    err="$(prom_query "sum(rate(mock_server_load_errors_total{scenario=\"$SCENARIO_SCALE\"}[$RATE_WINDOW]))")"
    local thr; thr="$(prom_query "sum(rate(mock_server_load_throttled_total{scenario=\"$SCENARIO_SCALE\"}[$RATE_WINDOW]))")"
    recv="$(prom_query "sum(rate(envoy_http_downstream_rq_completed{envoy_http_conn_manager_prefix=\"ingress_http\"}[$RATE_WINDOW]))")"
    if ! gt "$recv" 0; then
      recv="$(prom_query "sum(rate(envoy_http_downstream_rq_xx{envoy_response_code_class=\"2\",envoy_http_conn_manager_prefix=\"ingress_http\"}[$RATE_WINDOW]))")"
    fi
    err_rate="$(awk -v e="$err" -v a="$agg" 'BEGIN{ d=a+e; printf "%.5f", (d>0? e/d : 0) }')"

    # per-instance array (by run_id)
    local per_instance_json="[]"
    while read -r _rid val; do
      [ -z "${val:-}" ] && continue
      per_instance_json="$(jq -c --argjson v "$(rnd "$val")" '. + [$v]' <<<"$per_instance_json")"
    done < <(prom_query_by_runid "sum by (run_id)(rate(mock_server_load_requests_total{scenario=\"$SCENARIO_SCALE\"}[$RATE_WINDOW]))")

    if gt "$thr" "$THROTTLE_EPS"; then
      warn "scale N=$n: throttled=$(rnd "$thr") rps (> $THROTTLE_EPS) — a CAP IS BINDING, aggregate is throttled"
    fi
    if gt "$err_rate" "$ERROR_EPS"; then
      warn "scale N=$n: error_rate=$err_rate (> $ERROR_EPS)"
    fi
    # cross-check envoy received ~ aggregate injected (within 10%)
    if ! awk -v r="$recv" -v a="$agg" 'BEGIN{ exit !(a>0 && r >= 0.9*a && r <= 1.1*a) }'; then
      warn "scale N=$n: envoy received ($(rnd "$recv")) != aggregate injected ($(rnd "$agg")) within 10% — investigate"
    fi

    local entry; entry="$(jq -n \
      --argjson instances "$n" \
      --argjson aggregate_rps "$(rnd "$agg")" \
      --argjson per_instance_rps "$per_instance_json" \
      --argjson error_rate "$(rndf "$err_rate" 5)" \
      --argjson target_received_rps "$(rnd "$recv")" \
      '{instances:$instances, aggregate_rps:$aggregate_rps,
        per_instance_rps:$per_instance_rps, error_rate:$error_rate,
        target_received_rps:$target_received_rps}')"
    series="$(jq -c --argjson e "$entry" '. + [$e]' <<<"$series")"
    log "  -> N=$n aggregate=$(rnd "$agg") rps  per-instance=$per_instance_json  envoy=$(rnd "$recv")"

    if [ "$n" -gt "$max_instances" ]; then max_instances="$n"; actual_at_max="$(rnd "$agg")"; fi

    stop_all_injectors "${svcs[@]}"
    # remaining hold tail
    local tail=$((SCALE_HOLD_S - SCALE_SETTLE_S))
    [ "$tail" -gt 0 ] && sleep "$tail"
  done

  # scaling efficiency = actual_at_max / (per_instance_offered * max_instances)
  local ideal_at_max efficiency
  ideal_at_max=$(( SCALE_PER_INSTANCE_RPS * max_instances ))
  efficiency="$(awk -v a="$actual_at_max" -v i="$ideal_at_max" 'BEGIN{ printf "%d", (i>0? (a/i*100)+0.5 : 0) }')"

  local out="$SCRIPT_DIR/inject-scale.json"
  jq -n \
    --argjson per_instance_offered_rps "$SCALE_PER_INSTANCE_RPS" \
    --argjson series "$series" \
    --argjson ideal_at_max "$ideal_at_max" \
    --argjson actual_at_max "$actual_at_max" \
    --argjson efficiency_pct "$efficiency" \
    '{proto:"http", target:"envoy-direct-response",
      per_instance_offered_rps:$per_instance_offered_rps,
      series:$series,
      scaling_efficiency:{ideal_at_max:$ideal_at_max, actual_at_max:$actual_at_max, efficiency_pct:$efficiency_pct}}' > "$out"

  log "wrote $out"
  cat "$out"
}

usage() {
  cat >&2 <<EOF
Usage: $0 <ceiling | scale [N ...]>
  ceiling          bring up 1 injector, run the RATE stair, emit inject-ceiling.json
  scale [N ...]    for each N in {1,2,4,6} (default "1 2"), measure aggregate rps
                   and emit inject-scale.json

  The stack is brought up/torn down by this script. Requires docker, jq, curl.
  Override the image with MOCKSERVER_IMAGE=...; Prometheus at PROM_URL.
EOF
  exit 2
}

main() {
  local cmd="${1:-}"; shift || true
  case "$cmd" in
    ceiling) cmd_ceiling ;;
    scale)   cmd_scale "$@" ;;
    *)       usage ;;
  esac
}

main "$@"
