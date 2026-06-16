# quarkus-http-idempotency — Performance

Living performance document: what the extension costs, what it saves, and the improvements made
over time. All numbers are **measured against the running lab app over real HTTP** (not estimated)
with the harness in `quarkus-http-idempotency-lab/benchmarks/`.

## Methodology

- **Harness:** `benchmarks/bench.py` — N requests across C keep-alive worker threads; reports
  throughput and latency percentiles (p50/p95/p99). Orchestrated by `benchmarks/run-benchmarks.sh`,
  which runs the same lab app under three configs via `-D` overrides.
- **Scenarios** (key strategy): `none` = no key (baseline / passthrough); `unique` = a fresh key
  per request (always first-execution: acquire + store + capture); `fixed` = one warmed key (every
  call is a replay).
- **Endpoints:** `/bench` ≈ no-op handler (isolates extension overhead); `/bench/costly` sleeps
  ~50 ms (simulates real work, so the replay benefit is visible).
- **Environment:** WSL2, 4 cores, JVM (Quarkus 3.36.2), Redis 7 in Docker on localhost:6380.
  Fast scenarios: N=4000, C=32. Costly: N=400, C=8.
- **Caveat:** a developer box has real run-to-run variance (JVM warm-up, scheduler jitter). Treat
  absolute throughput as indicative; the **order-of-magnitude** signals (replay benefit, error
  presence) are the reliable conclusions. Re-run on target hardware for SLA numbers.

## Results (2026-06-12, store v0.2)

| Scenario | Store | thr (req/s) | p50 (ms) | p95 | p99 | status |
|---|---|--:|--:|--:|--:|---|
| baseline-fast (ext disabled) | — | 2347 | 11.1 | 28.9 | 39.4 | 200 |
| passthrough (no key) | in-memory | 3182 | 7.0 | 22.8 | 32.4 | 200 |
| first-exec (unique) | in-memory | 1789 | 15.4 | 31.4 | 46.1 | 200 |
| replay (fixed) | in-memory | 2334 | 11.7 | 26.3 | 38.6 | 200 |
| first-exec (unique) | redis | 2953 | 9.1 | 19.6 | 27.0 | 200 |
| replay (fixed) | redis | 3894 | 6.4 | 15.7 | 21.9 | 200 |
| **costly first-exec** (unique) | in-memory | 151 | **52.1** | 55.4 | 64.7 | 200 |
| **costly replay** (fixed) | in-memory | 2402 | **2.4** | 6.7 | 8.7 | 200 |
| costly replay (fixed) | redis | 2467 | 2.5 | 7.2 | 9.3 | 200 |

## What the extension contributes

**Cost (overhead).** On a trivial no-op endpoint the per-request overhead — read header, fingerprint
the body (SHA-256), one store reservation, capture the response — is **low single-digit
milliseconds and within the measurement noise** of this box (the passthrough/first-exec/replay rows
overlap the baseline band). It is not free, but it is small relative to any real handler.

**Benefit (savings).** The value shows up when the guarded operation does real work. On the ~50 ms
`costly` endpoint, a retry with the same key **skips the handler entirely**:

- p50 latency **52.1 ms → 2.4 ms (~21× faster)**
- throughput **151 → 2402 req/s (~16×)**

i.e. the extension trades a few ms of bookkeeping on the first call for ~50 ms saved on every
retry — and, more importantly, guarantees the side effect runs **exactly once**. The benefit grows
with the cost of the operation being protected (payments, external calls, DB writes).

**Store choice.** `in-memory` and `redis` are comparable for the replay path; on the protected
operation the saved work dwarfs the store round-trip either way. `redis` adds network round-trips on
first-execution but enables correctness across multiple instances.

## Improvement log

| Date | Store ver | Change | Effect (measured) |
|---|---|---|---|
| 2026-06-12 | v0.1 | Initial Redis store: `acquire` = `SETNX` + `PEXPIRE` (2 round-trips, set/expire race); default Redis pool | At C=32: **915 × HTTP 500** (`ConnectionPoolTooBusyException`), p99 188 ms, max 1.4 s — pool (6 + 24 wait) saturated by blocking store calls |
| 2026-06-12 | v0.2 | `acquire` → `SET key val NX GET PX ttl` (**1 atomic round-trip**, removes the get + the race); document `quarkus.redis.max-pool-size ≥ peak concurrency` | At C=32: **0 errors**, redis first-exec 2953 req/s (p99 27 ms), replay 3894 req/s (p99 22 ms) |

### Operational guidance learned
- **Size the Redis pool**: `quarkus.redis.max-pool-size` must be ≳ peak concurrent guarded
  requests, because the store path is blocking (default 6 + 24 wait queue overflows under load).
- The Redis `acquire` requires **Redis 7.0+** (the `GET` option combined with `NX`).

## Reproduce
```bash
cd ../quarkus-http-idempotency && mvn -o install -DskipTests          # publish the extension
cd ../quarkus-http-idempotency-lab
docker compose up -d                                             # Redis on :6380
mvn -o clean package
bash benchmarks/run-benchmarks.sh                                # writes benchmarks/results.txt
docker compose down
```
