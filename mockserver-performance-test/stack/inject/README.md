# Load-Injection Measurement Harness

Drive **N MockServer instances as HTTP load _generators_** at a single **Envoy
`direct_response` sink** and measure two things:

1. **Ceiling** — how much load *one* MockServer instance can inject before it
   saturates (offered-vs-achieved rps as the offered rate climbs a stair).
2. **Scaling** — how aggregate injected throughput grows as instances are added
   (1 → 2 → 4 → 6), and the per-instance breakdown.

The Envoy sink answers every request with `200 OK` from its own event loop — it
has **no upstream**, so it absorbs far more than the injectors can produce. That
makes the **injector the provable bottleneck**, which is the whole point: any
plateau is MockServer's injection ceiling, not the target's serving limit.

```mermaid
flowchart LR
  subgraph injectors["MockServer injectors (load generators)"]
    I1["injector-1"]
    I2["injector-2"]
    IN["injector-N ..."]
  end
  I1 --> E["Envoy\ndirect_response 200 OK\n(near-infinite sink)"]
  I2 --> E
  IN --> E
  P["Prometheus\nscrapes both sides"] -.-> injectors
  P -.-> E
  R["run-inject.sh\noffered vs achieved,\nper-instance by run_id,\nEnvoy received"] -.-> P
```

## Quick start (local)

Requires Docker, `jq`, and `curl` on the host.

```bash
cd mockserver-performance-test/stack/inject

# Bring up the sink + 2 injectors + Prometheus (profile selects how many injectors)
MOCKSERVER_IMAGE=mockserver/mockserver:mockserver-snapshot \
  docker compose -f docker-compose.inject.yml --profile n2 up -d

# ...or just let run-inject.sh manage compose up/down for you:
./run-inject.sh ceiling          # 1 injector, RATE stair -> inject-ceiling.json
./run-inject.sh scale 1 2        # aggregate rps at N=1 and N=2 -> inject-scale.json

# Tear everything down
docker compose -f docker-compose.inject.yml \
  --profile n1 --profile n2 --profile n4 --profile n6 down -v
```

`run-inject.sh` brings the stack up/down itself, so you normally only run the
two sub-commands. For a short laptop-safe smoke, point it at reduced-duration
scenarios and a shorter ladder:

```bash
CEILING_FILE=/path/to/short-ceiling.json CEILING_OFFERED="2000 4000 8000" \
  CEILING_HOLD_S=15 CEILING_SETTLE_S=11 RATE_WINDOW=10s ./run-inject.sh ceiling
```

| Profile | Injectors up |
|---------|--------------|
| `n1`    | injector-1 |
| `n2`    | injector-1, 2 |
| `n4`    | injector-1, 2, 3, 4 |
| `n6`    | injector-1 … 6 |

| Service | Host URL | Notes |
|---------|----------|-------|
| Envoy sink | `http://localhost:8000` | every request → `200 OK` |
| Envoy admin / stats | `http://localhost:9901` | `/ready`, `/stats/prometheus` |
| injector-K | `http://localhost:108K` | each injector's `1080` published as `1080+K` |
| Prometheus | `http://localhost:9090` | scrapes injectors + Envoy every 2s |

## What each measurement means

`inject-ceiling.json` (one injector, RATE stair):

- `offered_rps` — the arrival rate the RATE stage asked for.
- `achieved_rps` — `sum(rate(mock_server_load_requests_total[15s]))` — what the
  injector actually dispatched. While `achieved ≈ offered`, you are below the
  ceiling.
- `throttled_rps` — `mock_server_load_throttled_total` rate. **Must be ~0.** If
  it climbs, a cap is binding (the run prints a loud WARNING) and the point is
  not trustworthy.
- `error_rate` — connection/timeout errors as a fraction. Must be ~0.
- `injector_cpu_pct` / `envoy_cpu_pct` — `docker stats` snapshots. At the
  ceiling the injector should be CPU-bound (~100% of its pin) while Envoy has
  headroom.
- `target_received_rps` — Envoy's `envoy_http_downstream_rq_completed` rate;
  should track `achieved_rps` (proof the injected requests really landed).
- `ceiling_rps` — the highest offered rate the injector achieved **cleanly**
  (achieved within 10% of offered, no throttle, no errors).

`inject-scale.json` (sweep over N):

- `aggregate_rps` — total injected rps across all instances.
- `per_instance_rps` — `sum by (run_id)(rate(...))`; one entry per instance
  (`run_id` is a fresh UUID per trigger, so co-scraped instances stay distinct).
- `target_received_rps` — Envoy received; cross-checked against `aggregate_rps`.
- `scaling_efficiency.efficiency_pct` — `actual_at_max / (per_instance_offered ×
  instances)`. 100% means clean linear scaling (each added instance adds a full
  instance's worth of throughput).

## Caps — why they are raised

MockServer's load generator self-throttles to protect the server. The defaults
(`maxRequestsPerSecond=500`, `maxRate=500`, 50 VUs) would silently cap every
injector at ~500 rps and inflate `mock_server_load_throttled{reason="rate_limit"}`.
The compose file raises every cap **well above** any scenario peak so the cap
never binds; `run-inject.sh` then **asserts** `throttled ≈ 0` before trusting a
number. The raised caps (per injector) are:

```
MOCKSERVER_LOAD_GENERATION_MAX_REQUESTS_PER_SECOND=200000
MOCKSERVER_LOAD_GENERATION_MAX_RATE=200000
MOCKSERVER_LOAD_GENERATION_MAX_IN_FLIGHT_REQUESTS=20000
MOCKSERVER_LOAD_GENERATION_MAX_VIRTUAL_USERS=4000
MOCKSERVER_LOAD_GENERATION_MAX_DURATION_MILLIS=1800000
```

(The peak rate is validated **up front** at register/trigger time — if a
scenario's peak exceeds a cap the trigger is rejected — so the caps must be
raised *before* the scenario is registered, which the compose env does.)

## Measurement method

Rates are computed with the **Prometheus HTTP API** (`/api/v1/query`):

- aggregate: `sum(rate(mock_server_load_requests_total{scenario="X"}[15s]))`
- per-instance: `sum by (run_id)(rate(mock_server_load_requests_total{scenario="X"}[15s]))`
- Envoy received: `sum(rate(envoy_http_downstream_rq_completed{envoy_http_conn_manager_prefix="ingress_http"}[15s]))`

Each point is sampled **mid-way through a held stage** so the 15s `rate()` window
sits entirely inside the steady portion of the hold. CPU% comes from `docker
stats`.

## Honest note — there is no built-in cross-node coordinator

MockServer does **not** ship a distributed load-generation coordinator. Each
injector runs its own independent Load Scenario; this harness orchestrates the
**launch** (docker compose profiles) and the **aggregation** (Prometheus scrape +
`sum`/`sum by (run_id)`) **externally**. The per-instance `run_id` label is what
makes co-scraped instances separable. "Aggregate injected rps" is therefore a
measured external sum, not a single coordinated run.

## CI

`.buildkite/scripts/steps/perf-test-inject.sh` runs this on the pinned perf box
(Envoy on a couple of cores, injectors cpuset-pinned 2 cores each, scale sweep
`N ∈ {1,2,4,6}`) and uploads `inject-ceiling.json` + `inject-scale.json` as
Buildkite artifacts. It is **opt-in** — dispatched by `perf-test-guard.sh` only
when `PERF_INJECT=true` (build env) or the build message contains `[perf-inject]`
— so it stays off the lean daily regression build.
```
