# mockserver-benchmark

JMH (Java Microbenchmark Harness) micro-benchmarks for MockServer's
request-matching **hot path**.

This module is **not part of the default build**. It is deliberately left out of
the parent `pom.xml` `<modules>` list so `mvn install` and CI never compile it —
JMH's annotation processor and the benchmark classpath must not touch production
artifacts. Build and run it on demand with `./run.sh`.

## Why it exists

The Part-A hot-path optimizations (see `docs/plans/…performance…`) are mostly
**allocation** reductions — e.g. a `MatchDifference` (backed by a
`ConcurrentHashMap`) allocated *per matcher, per request*, and a sorted matcher
list rebuilt on every request. k6 (the end-to-end load harness) proves the
*user-visible* throughput/latency win but is too noisy to prove an allocation
reduction. JMH with the GC profiler (`-prof gc`) measures
**bytes-allocated-per-op** for a single method in isolation, handling JIT
warmup, dead-code elimination and constant folding.

**Gate:** no A1/A2 allocation "win" is committed without before/after
`gc.alloc.rate.norm` numbers from this module.

## Running

```bash
# one-time: install the module under test
(cd .. && mvn -o -pl mockserver-core install -DskipTests)

# the important run — allocation profile across scan lengths and matcher shapes
./run.sh -prof gc

# a focused, faster run
./run.sh -prof gc -f 1 -wi 3 -i 5 -p expectationCount=100 -p matcherType=EXACT

# list benchmarks / pass any other JMH option
./run.sh -l
```

## What is measured

`MatchingBenchmark.firstMatchingExpectation_noMatch` calls
`RequestMatchers.firstMatchingExpectation(...)` with a request that matches
**none** of the registered expectations — the worst case that forces a full
scan of all N matchers (every matcher allocates a `MatchDifference` and is
evaluated).

Parameters:

| Param | Values | Meaning |
|-------|--------|---------|
| `expectationCount` | 1, 10, 100, 1000 | number of registered expectations (scan length) |
| `matcherType` | EXACT, REGEX, JSON_BODY | shape of the registered matchers |

It uses the default `Configuration` (metrics off, `detailedMatchFailures` off,
INFO logging off) — the common case Part A optimizes. The headline number for
the allocation work is **`gc.alloc.rate.norm`** (B/op); `ns/op` (shown as µs/op)
is the secondary signal.

## Workflow for a Part-A change

1. `./run.sh -prof gc | tee before.txt`
2. Make the A1/A2 change in `mockserver-core`, `mvn -o -pl mockserver-core install -DskipTests`.
3. `./run.sh -prof gc | tee after.txt`
4. Compare `gc.alloc.rate.norm` (and `ns/op`). Quote the delta in the commit.
