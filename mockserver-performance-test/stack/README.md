# MockServer observability + load stack

A self-contained [docker-compose](docker-compose.yml) stack that runs MockServer
with metrics enabled, scrapes it with **Prometheus**, and visualises it in
**Grafana** — plus an optional **k6** load test and **cAdvisor**.

Useful two ways:
- **Consumers** — point it at MockServer to *see its metrics nicely* in Grafana
  with zero setup.
- **Internal** — drive load with k6 and watch throughput, match rates, and JVM
  health while it runs (or during a soak).

## Run

```bash
cd mockserver-performance-test/stack

# monitoring only: MockServer + Prometheus + Grafana
docker compose up -d

# also fire the k6 load test at MockServer (pushes k6 metrics to Prometheus)
docker compose --profile load up

# also start cAdvisor (container CPU/mem)
docker compose --profile monitoring up -d

docker compose down
```

| Service | URL | Notes |
|---------|-----|-------|
| Grafana | http://localhost:3000 | anonymous Admin; "MockServer" dashboard auto-provisioned |
| Prometheus | http://localhost:9090 | scrapes `mockserver:1080/mockserver/metrics`; remote-write receiver enabled for k6 |
| MockServer | http://localhost:1080 | `metricsEnabled=true` |
| cAdvisor | http://localhost:8080 | only with `--profile monitoring` |

## Notes

- **MockServer image** — defaults to `mockserver/mockserver:latest`. The
  request/action counters work on any recent release; the **JVM gauges**
  (`jvm_memory_*`, `jvm_threads_*`, `jvm_gc_*`) and any future latency histogram
  need a build that includes them — point at one with
  `MOCKSERVER_IMAGE=mockserver/mockserver:mockserver-snapshot docker compose up`.
- **Throughput / rate panels** (requests, actions, GC) use `rate(...)` over
  MockServer's gauges; since the counts and GC totals are monotonic gauges this
  is approximate (a server reset shows as a dip). Exact rates arrive once
  `_total` counters land.
- **k6 panels** only populate under `--profile load`. Trend stats are exported
  as `k6_http_req_duration_p95` / `_p99` / `_avg` (see `K6_PROMETHEUS_RW_TREND_STATS`).
</content>
