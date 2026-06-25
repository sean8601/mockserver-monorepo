#!/usr/bin/env bash
set -euo pipefail

# Load-INJECTION measurement step (perf queue). Drives N MockServer instances as
# HTTP load *generators* at an Envoy direct_response sink and produces TWO result
# JSONs (uploaded as Buildkite artifacts):
#   inject-ceiling.json  — one injector's offered-vs-achieved injection ceiling
#   inject-scale.json    — aggregate injected rps as injectors are added (N=1,2,4,6)
#
# The sink answers every request from Envoy's own event loop (no upstream), so it
# absorbs far more than the injectors can produce — making the INJECTOR the
# provable bottleneck. run-inject.sh asserts throttle ~ 0 and errors ~ 0 before
# trusting any number, and cross-checks Envoy-received ~ aggregate-injected.
#
# Co-located generators: on a >=16 vCPU box Envoy + Prometheus sit on a few cores
# and each injector is cpuset-pinned to its own 2 cores (disjoint) so they don't
# steal cycles — the single biggest factor in number quality. On a smaller box
# pinning is skipped with a warning (numbers noisier, harness still works).
#
# Modelled on perf-test-run.sh. The MockServer image is MOCKSERVER_IMAGE (default
# snapshot); the Envoy image is ENVOY_IMAGE.

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../../.." && pwd)"
INJECT_DIR="$REPO_ROOT/mockserver-performance-test/stack/inject"
RUN_INJECT="$INJECT_DIR/run-inject.sh"

export MOCKSERVER_IMAGE="${MOCKSERVER_IMAGE:-mockserver/mockserver:mockserver-snapshot}"
export ENVOY_IMAGE="${ENVOY_IMAGE:-envoyproxy/envoy:v1.31-latest}"

cleanup() {
  # Tear down whatever profile is up (idempotent across all profiles).
  docker compose -f "$INJECT_DIR/docker-compose.inject.yml" \
    --profile n1 --profile n2 --profile n4 --profile n6 down -v >/dev/null 2>&1 || true
}
trap cleanup EXIT

# --- core pinning --------------------------------------------------------------
# The two phases run SEQUENTIALLY (never concurrently), so they pin DIFFERENTLY:
#
#   CEILING phase — ONE FAT injector pinned to 8 cores (8-15) AND Envoy given
#   HEADROOM on 6 cores (2-7, --concurrency 6), so the measured ceiling reflects
#   the INJECTOR, not Envoy. (CI #71 showed the complex ceiling was Envoy-bound:
#   envoy_cpu 113% on a 2-core Envoy while the injector still had ~6 cores of
#   headroom — so Envoy, not the injector, was the bottleneck. Fat injector +
#   wide Envoy fixes that.) Cores 0-1 left for Prometheus/OS/sampler.
#
#   SCALE phase — N LEAN injectors pinned to 2 cores each (i1=2-3 … i6=12-13),
#   each driven at ~80% of the measured LEAN 2-core ceiling (derived by
#   run-inject.sh's lean probe). Envoy on 0-1 (the scale phase is clean as-is and
#   is intentionally LEFT UNCHANGED). Cores 14-15 for kernel/docker/sampler.
#
# Each cpuset is overridable via the matching *_CPUS env.
CORES="$(getconf _NPROCESSORS_ONLN 2>/dev/null || echo 0)"
SCALE_SET="1 2 4 6"
# Ceiling-phase pins (Envoy with headroom + fat injector). These apply ONLY to the
# two ceiling invocations; the scale sweep uses the lean ENVOY_CPUS/I*_CPUS below.
CEILING_ENVOY_CPUS="2-7"          # 6 cores for the sink so it is never the bottleneck
CEILING_ENVOY_CONCURRENCY="6"     # match Envoy worker threads to its core budget
CEILING_I1_CPUS="8-15"            # fat 8-core injector for the ceiling phase
if [ "$CORES" -ge 16 ]; then
  export ENVOY_CPUS="${ENVOY_CPUS:-0-1}"
  # lean 2-core pins used by the SCALE sweep (unchanged)
  export I1_CPUS="${I1_CPUS:-2-3}"  I2_CPUS="${I2_CPUS:-4-5}"
  export I3_CPUS="${I3_CPUS:-6-7}"  I4_CPUS="${I4_CPUS:-8-9}"
  export I5_CPUS="${I5_CPUS:-10-11}" I6_CPUS="${I6_CPUS:-12-13}"
  echo "--- core-pinning enabled (${CORES} vCPU): ceiling=[envoy=$CEILING_ENVOY_CPUS (6c) + fat injector=$CEILING_I1_CPUS (8c)]  scale=[envoy=$ENVOY_CPUS + lean injectors 2..13 (2c each)]"
else
  # Not enough cores to pin a fat injector + wide Envoy + 6 lean injectors —
  # disable pinning and cap the scale sweep so we don't oversubscribe a small agent.
  echo "--- WARNING: ${CORES} vCPU (<16) — core-pinning skipped; numbers will be noisier"
  export ENVOY_CPUS="" I1_CPUS="" I2_CPUS="" I3_CPUS="" I4_CPUS="" I5_CPUS="" I6_CPUS=""
  CEILING_ENVOY_CPUS="" CEILING_ENVOY_CONCURRENCY="" CEILING_I1_CPUS=""
  SCALE_SET="1 2"
fi

# Allow CI to override the scale sweep explicitly (e.g. PERF_INJECT_SCALE="1 2 4").
SCALE_SET="${PERF_INJECT_SCALE:-$SCALE_SET}"

# --- ceiling (N=1, FAT injector + WIDE Envoy, SIMPLE GET) ----------------------
# run-inject.sh manages compose up/down itself; it leaves inject-ceiling.json in
# the inject dir. The fat injector + wide-Envoy cpusets are applied only here.
echo "--- inject ceiling (N=1 fine RATE stair, SIMPLE GET, fat injector I1_CPUS=$CEILING_I1_CPUS, envoy=$CEILING_ENVOY_CPUS)"
ENVOY_CPUS="$CEILING_ENVOY_CPUS" ENVOY_CONCURRENCY="$CEILING_ENVOY_CONCURRENCY" I1_CPUS="$CEILING_I1_CPUS" "$RUN_INJECT" ceiling

# --- ceiling (N=1, FAT injector + WIDE Envoy, COMPLEX templated POST) ----------
# Same fat injector + wide Envoy + same stair as the simple ceiling, but a heavier
# request (feeder + ~1KB Velocity-templated JSON body) so each request costs real
# CPU. Comparable rps/core data point: with ceiling_rps + injector_cpu_pct from
# both JSONs, rps/core_complex < rps/core_simple, and the factor ~ R'/R is derivable.
# Adds ~5 min; the simple ceiling + scale ~18-22 min, so well inside the 60-min step.
echo "--- inject ceiling (N=1 fine RATE stair, COMPLEX templated POST, fat injector I1_CPUS=$CEILING_I1_CPUS, envoy=$CEILING_ENVOY_CPUS)"
ENVOY_CPUS="$CEILING_ENVOY_CPUS" ENVOY_CONCURRENCY="$CEILING_ENVOY_CONCURRENCY" I1_CPUS="$CEILING_I1_CPUS" "$RUN_INJECT" ceiling-complex

# --- scale sweep (lean injectors, rate derived from the lean 2-core ceiling) ---
# run-inject.sh runs a lean 2-core probe first, sets per-instance rate to ~80% of
# the measured lean ceiling, then sweeps N. The lean I*_CPUS exported above apply.
echo "--- inject scale sweep (N in: $SCALE_SET; lean injectors, derived per-instance rate)"
# shellcheck disable=SC2086
"$RUN_INJECT" scale $SCALE_SET

CEILING_JSON="$INJECT_DIR/inject-ceiling.json"
CEILING_COMPLEX_JSON="$INJECT_DIR/inject-ceiling-complex.json"
SCALE_JSON="$INJECT_DIR/inject-scale.json"

echo "--- inject-ceiling.json"
cat "$CEILING_JSON" 2>/dev/null || echo "(missing)"
echo "--- inject-ceiling-complex.json"
cat "$CEILING_COMPLEX_JSON" 2>/dev/null || echo "(missing)"
echo "--- inject-scale.json"
cat "$SCALE_JSON" 2>/dev/null || echo "(missing)"

# Stage artifacts at the repo root with stable names for the chart renderer.
cp "$CEILING_JSON" "$REPO_ROOT/inject-ceiling.json" 2>/dev/null || true
cp "$CEILING_COMPLEX_JSON" "$REPO_ROOT/inject-ceiling-complex.json" 2>/dev/null || true
cp "$SCALE_JSON" "$REPO_ROOT/inject-scale.json" 2>/dev/null || true

if command -v buildkite-agent >/dev/null 2>&1; then
  buildkite-agent artifact upload "inject-ceiling.json" || true
  buildkite-agent artifact upload "inject-ceiling-complex.json" || true
  buildkite-agent artifact upload "inject-scale.json" || true
else
  echo "(local run) results at $REPO_ROOT/inject-ceiling.json, $REPO_ROOT/inject-ceiling-complex.json, $REPO_ROOT/inject-scale.json"
fi
