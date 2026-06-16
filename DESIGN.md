# quarkus-http-idempotency — Design Document

Status: **DRAFT** · 2026-06-12 · target Quarkus 3.36.x · Java 21

A Quarkus extension that makes unsafe HTTP requests (POST/PATCH) **idempotent** via the
`Idempotency-Key` header — Stripe/IETF style. When a client retries a request with the same
key, the server replays the original response instead of executing the operation twice.

---

## 1. Why

`Idempotency-Key` is the de-facto standard for safe retries of non-idempotent HTTP
operations (payments, order creation, any "exactly once" command API). Spring has multiple
starters (Jdempotent, spring-idempotency-kit, idempotent); **Quarkus has none** — verified
2026-06-12: `quarkiverse/quarkus-http-idempotency-key` and `quarkus-http-idempotency` both 404, and core
issue [quarkusio/quarkus#49663](https://github.com/quarkusio/quarkus/issues/49663) is open with
maintainer endorsement ("no work has been done towards implementing it") plus cross-project
interest (Apache Polaris). Today every team hand-rolls a filter + store + fingerprint.

This extension turns that repeated boilerplate into a drop-in: add the dependency, and any
request carrying an `Idempotency-Key` is deduplicated and replayed.

### Goals
- Header-driven idempotency for configurable methods (default `POST`, `PATCH`).
- Pluggable store, **SPI-first**: in-memory/Quarkus Cache by default, Redis for distributed.
- Correct behavior on the four spec scenarios: first / replay / in-flight / payload-mismatch.
- Works identically on **blocking and reactive** endpoints (no blocking-read-on-IO-thread bug).
- Native-image friendly. Zero code change to resources beyond (optionally) one annotation.

### Non-goals (v1)
- Per-resource-method `@Idempotent` annotation routing (roadmap v2 — needs build-time
  annotation→route mapping; v1 is config + header-presence driven).
- Message/consumer idempotency (inbox pattern) — different extension.
- Cross-region replication of the store. Distributed = single Redis/cluster.

---

## 2. The spec we implement

Primary: IETF draft-ietf-httpapi-idempotency-key-header **-07** (expired, pre-WGLC; status
codes are SHOULD). Cross-checked against Stripe and Spring starters.

| Scenario | Behavior | Status |
|---|---|---|
| New key | Reserve key, run handler, store + return response | handler's (2xx/4xx/…) |
| Same key, same fingerprint, completed | **Replay** stored status + body + headers | stored status |
| Same key, **same fingerprint**, still in flight | Reject (concurrent retry) | **409 Conflict** |
| Same key, **different fingerprint** | Reject (key reused for a different request) | **422 Unprocessable Content** |
| Key required on endpoint but missing/invalid | Reject | **400 Bad Request** |
| No key, not required | Pass through (not idempotent) | — |

Header value: draft-07 mandates an RFC 8941 **sf-string** (double-quoted); Stripe and most
real clients send a **bare** token. We accept **both** (strip surrounding quotes; configurable
strict mode). No length cap by the draft; we enforce a sane configurable max (default 255, per
Stripe).

**Fingerprint** (optional in the draft, required for the 422 behavior): `SHA-256(method + '\n' +
normalizedPath + '\n' + bodyBytes)`. Configurable to include/exclude selected headers. Stored
alongside the entry so a reused key with a different payload yields 422.

**TTL**: default **24h** (de-facto standard, Stripe ≥24h). After expiry the key is forgotten;
reuse = brand-new request.

**What we store**: the *serialized* response bytes + status + a configurable allow-list of
response headers (not the entity object — avoids re-serialization drift and shrinks native
reflection surface).

---

## 3. Architecture — the key decision

### 3.1 Intercept at the Vert.x layer, not JAX-RS

Reading the request body in a JAX-RS `ContainerRequestFilter`/`@ServerRequestFilter` is the
**wrong** layer on Quarkus REST: on non-blocking endpoints it throws
`Attempting a blocking read on io thread`, and draining `getEntityStream()` nulls out the
resource method's entity ([#17280](https://github.com/quarkusio/quarkus/issues/17280),
[#17430](https://github.com/quarkusio/quarkus/issues/17430),
[#23263](https://github.com/quarkusio/quarkus/issues/23263)). `@ServerResponseFilter` is also
skipped for streamed responses.

**Decision: intercept with a Vert.x `@RouteFilter`** and force the body to be buffered with
**`RequireBodyHandlerBuildItem`** (produced from the deployment module). By the time the filter
runs, the body is already fully in memory, so `rc.body().buffer()` is a **synchronous,
non-blocking** read that works the same on blocking and reactive endpoints — in front of the
Quarkus REST machinery, so the resource entity is never drained.

```java
// runtime: the interceptor
@RouteFilter(IdempotencyRouteFilter.PRIORITY)   // high priority, before auth as configured
void filter(RoutingContext rc) { ... rc.next() / short-circuit ... }

// deployment: force body buffering for all routes
@BuildStep
RequireBodyHandlerBuildItem requireBody() { return new RequireBodyHandlerBuildItem(); }
```

**Tradeoff (must document):** `RequireBodyHandlerBuildItem` buffers *every* request body in
memory, not just idempotent ones. Bound it with `quarkus.http.limits.max-body-size` and only
fingerprint when the header is present. If this global cost is unacceptable we revisit a
narrower buffering hook, but per Quarkus design this is the extension-correct lever.

> Open verification: confirm the exact `RoutingContext` body accessor on the Vert.x 4.5.x that
> ships with Quarkus 3.36.x (`rc.body().buffer()` vs legacy `rc.getBody()`). Both return the
> buffered body when a BodyHandler ran.

### 3.2 Request lifecycle (state machine, per key K, fingerprint F)

```
key absent ──► required? ──yes──► 400
                 └─no──► rc.next()  (not idempotent)

key present:
  reserve = store.acquire(K, F, lockTtl)      // atomic SET-NX-PX / computeIfAbsent
  ├─ ACQUIRED (we own it) ──► rc.next(); on response end:
  │        store.complete(K, {status, headers, bodyBytes, F}, responseTtl)
  │        (on handler error / no response → store.release(K) so a retry can proceed)
  │
  └─ EXISTS ──► entry = store.get(K)
        ├─ entry.F != F ───────────────► 422   (key reused, different payload)
        ├─ entry.state == IN_FLIGHT ───► 409   (concurrent retry)
        └─ entry.state == COMPLETED ──► replay entry (status+headers+body, +Idempotent-Replayed)
```

- **Atomicity** is the whole game. Redis: `SET K val NX PX lockTtl` (winner proceeds, loser
  reads). Caffeine/in-memory: `ConcurrentMap#computeIfAbsent` / `Cache.get` mapping function.
- **5xx handling** (config `cache-error-responses`, default **false**): by default a 5xx is
  *not* pinned — the key is released so the client can genuinely retry a transient failure.
  Set true for Stripe-exact semantics (store everything, including 500s).
- **Lock TTL vs response TTL**: lock TTL (default 60s) bounds a crashed in-flight request;
  response TTL (default 24h) bounds the stored replay.

### 3.3 Response capture & replay

- Capture at the same Vert.x layer: tee the bytes written to `rc.response()` (wrap the
  response or accumulate via a write interception) + status + allow-listed headers, persist on
  body-end.
- Replay: short-circuit in the request-side filter — write stored status/headers/body straight
  to `rc.response()` and **do not** call `rc.next()`. Add `Idempotent-Replayed: true`
  (configurable header name) and echo the `Idempotency-Key`.

---

## 4. Public surface

### 4.1 Configuration (`@ConfigMapping(prefix="quarkus.idempotency")`, `@ConfigRoot(RUN_TIME)`)

| Key | Default | Meaning |
|---|---|---|
| `enabled` | `true` | Master switch. |
| `header-name` | `Idempotency-Key` | Request header carrying the key. |
| `methods` | `POST,PATCH` | Methods subject to idempotency when the header is present. |
| `include-paths` | (all) | Optional path patterns to scope idempotency to. |
| `exclude-paths` | (none) | Path patterns to skip. |
| `require-key` | `false` | If true, matched endpoints **require** the header → 400 when absent. |
| `max-key-length` | `255` | Reject longer keys (400). |
| `strict-key-format` | `false` | If true, require RFC 8941 sf-string (quoted); else accept bare. |
| `fingerprint.enabled` | `true` | Compute payload fingerprint (needed for 422-on-mismatch). |
| `fingerprint.headers` | (none) | Extra request headers folded into the fingerprint. |
| `response-ttl` | `24h` | How long a completed response is replayable. |
| `lock-ttl` | `60s` | In-flight reservation timeout. |
| `cache-error-responses` | `false` | Store/replay 5xx too (Stripe-exact when true). |
| `replayed-header` | `Idempotent-Replayed` | Marker header added on replay (empty = disabled). |
| `store` | `cache` | Store backend: `cache` \| `redis` \| (SPI name). |

### 4.2 Store SPI

```java
public interface IdempotencyStore {
    /** Atomically reserve K for an in-flight request, or report the existing entry. */
    Uni<Reservation> acquire(String key, String fingerprint, Duration lockTtl);
    /** Persist the final response for K. */
    Uni<Void> complete(String key, StoredResponse response, Duration ttl);
    /** Release a reservation (handler produced no cacheable response). */
    Uni<Void> release(String key);
    Uni<Optional<StoredEntry>> get(String key);
}
```
- `Reservation` = `ACQUIRED` | `EXISTING(StoredEntry)`.
- `StoredEntry` = `{ state: IN_FLIGHT|COMPLETED, fingerprint, response?: StoredResponse, createdAt }`.
- `StoredResponse` = `{ int status, Map<String,String> headers, byte[] body }` —
  `@RegisterForReflection` for native.
- Reactive SPI (`Uni`); a blocking adapter is provided for simple stores.

### 4.3 Built-in stores (v1)
- **`cache`** (default): Quarkus Cache / Caffeine, single-node. TTL via
  `quarkus.cache.caffeine."quarkus-http-idempotency".expire-after-write`. Atomic via mapping
  function. Good for single-instance / dev.
- **`redis`**: raw Quarkus Redis client (`ValueCommands` + `SetArgs.nx().px(...)`). Distributed,
  per-key TTL, true atomic in-flight lock. The production store.

### 4.4 Annotation (v1: optional, v2: routing)
- v1 ships a `@Idempotent` marker for documentation/intent and to set `required=true` per
  resource (enforced via config path mapping). Full annotation→route discovery (so the
  annotation alone scopes idempotency without path config) is **v2**.

---

## 5. Module layout

```
quarkus-http-idempotency-parent (pom, quarkiverse-parent:NN)
├── runtime/      io.quarkiverse.idempotency
│   ├── IdempotencyConfig            (@ConfigMapping, RUN_TIME)
│   ├── IdempotencyRouteFilter       (@RouteFilter, orchestrates the state machine)
│   ├── Fingerprint                  (SHA-256 helper)
│   ├── spi/ IdempotencyStore, StoredResponse, StoredEntry, Reservation
│   └── store/ CacheIdempotencyStore, RedisIdempotencyStore
└── deployment/   io.quarkiverse.idempotency.deployment
    └── IdempotencyProcessor
        ├── FeatureBuildItem("quarkus-http-idempotency")
        ├── RequireBodyHandlerBuildItem        (force body buffering)
        ├── AdditionalBeanBuildItem            (filter + active store)
        └── ReflectiveClassBuildItem           (StoredResponse/Entry for native)
```
Same build-time + runtime shape as `quarkus-multitenancy`. Deployment depends on
`quarkus-vertx-http-deployment` (+ `quarkus-redis-client-deployment` / `quarkus-cache-deployment`
as optional per store).

---

## 6. Edge cases & risks

1. **Reactive body read** — solved by Vert.x buffering (§3.1). The single biggest risk; design
   hinges on `RequireBodyHandlerBuildItem`. Spike this first.
2. **Global buffering cost** — every body buffered. Bound with max-body-size; document.
3. **Response capture for streamed/chunked responses** — capturing arbitrary streamed bodies is
   hard; v1 may cap stored body size (`max-stored-body`, default 256 KiB) and **not** cache
   responses exceeding it (pass through, log). Document the limit.
4. **Crash between handler-success and store.complete** — small window where the operation ran
   but wasn't recorded; a retry re-runs it. Acceptable (at-least-once with best-effort replay);
   note it. Redis MULTI/pipeline narrows it.
5. **Clock/TTL skew across nodes** — Redis TTL is server-side, fine. Caffeine is per-node (don't
   use `cache` store in a cluster — validate at startup: warn if `cache` + replicas hint).
6. **Key as PII** — never log raw keys/bodies; sanitize like multitenancy's log hygiene.
7. **Native image** — register `StoredResponse`/`StoredEntry`; verify Redis codec for the value
   type under native.
8. **Method safety** — only POST/PATCH by default; never auto-apply to GET/PUT/DELETE unless
   configured (replaying a stored GET would mask fresh data).

---

## 7. MVP scope & roadmap

**MVP core — DONE (2026-06-12), 11 tests green:**
1. ~~Spike~~ ✅ go/no-go green (buffered body via `RequireBodyHandlerBuildItem`).
2. ✅ `IdempotencyConfig` (`@ConfigMapping`, RUN_TIME) + state machine in `IdempotencyRequestFilter`
   (replay / 409 in-flight / 422 mismatch / 400 missing / passthrough) + capture in
   `IdempotencyResponseFilter`.
3. ✅ `IdempotencyStore` SPI + `InMemoryIdempotencyStore` (atomic `compute`, lazy TTL).
4. ✅ SHA-256 fingerprint; response capture/replay (entity + status + Location); 5xx-release.
5. ✅ Tests: blocking + reactive replay-runs-once, mismatch 422, passthrough, require-key 400,
   store in-flight/complete/release/TTL. **Note:** filters registered via
   `io.quarkus.resteasy.reactive.spi.ContainerRequestFilterBuildItem` (not `@Provider` alone —
   that does not register from an extension jar).

**Done since:** Redis store (lab-verified), startup-store log, declarative lab (docker-compose) +
benchmarks + PERFORMANCE.md, EN/ES PDF reports, **demo** (`integration-tests` module, 2 QuarkusTests),
**Antora docs** (`docs/`), **native** (`@RegisterForReflection`; native image builds and the native IT
passes — startup 0.139s, image 2m21s), 409 HTTP concurrency verified live in the lab.

**Remaining for a shippable v1 (next increments):**
- Response **body size cap** + streamed-response handling (store currently keeps the entity object).
- Metrics (`idempotency_hits/replays/conflicts`).
- `@Idempotent` annotation for per-endpoint scoping.
- Git init + Quarkiverse incubation; offer to implement upstream on #49663.

**v1.1 — `redis` store: DONE + lab-verified (2026-06-12).** `RedisIdempotencyStore` (optional
`quarkus-redis-client` dep; `setnx`+`pexpire` atomic in-flight lock, `set PX` complete, `del`
release). Store selected via `quarkus.idempotency.store=in-memory|redis` using
`@LookupIfProperty` + `Instance<IdempotencyStore>.get()` (direct injection is ambiguous). Verified
against real Redis: replay single-exec, 422, 409 over `SET NX`, real keys with fingerprint +
serialized response + TTL. Still pending for v1.1: DevServices wiring, metrics
(`idempotency_hits/replays/conflicts`).

**v2:** `@Idempotent` annotation→route discovery (header-less scoping), per-endpoint TTL,
pluggable key sources (beyond header), OpenTelemetry span attributes.

**Upstream path:** comment on [#49663](https://github.com/quarkusio/quarkus/issues/49663) once
the spike is green (offer the extension), target Quarkiverse like multitenancy.

---

## Spike result (2026-06-12) — GO ✅
Built a 2-module extension (`runtime` + `deployment`) with `@RouteFilter` (from
`quarkus-reactive-routes`) + `RequireBodyHandlerBuildItem`. A `@QuarkusUnitTest` POSTs a body to
a **reactive** (`Uni`, event-loop) and a **blocking** endpoint and asserts the filter read the
full body. Both green: on the reactive endpoint the filter ran on `vert.x-eventloop-thread-0` and
read `bodyLen=15` with **no blocking-read exception**. Confirms: `rc.body().buffer()` returns the
buffered body synchronously on the event loop once `RequireBodyHandlerBuildItem` is produced.
Note: `@RouteFilter` lives in `quarkus-reactive-routes`, not `quarkus-vertx-http`.

## 8. Open questions (resolve before/early in build)
1. ~~Confirm `RoutingContext.body().buffer()` accessor + behavior on Quarkus 3.36.x / Vert.x 4.5.x.~~
   **RESOLVED by the spike** — works, non-blocking, both endpoint types.
2. Response capture mechanism at the Vert.x layer — response wrapping vs end-handler; prototype both.
3. Accept bare keys by default (Stripe-compat) vs strict sf-string — leaning **lenient default**.
4. Default `cache-error-responses=false` (retry-friendly) vs Stripe-exact `true` — leaning false.
5. Quarkiverse vs personal repo first — likely incubate as personal, propose to Quarkiverse on traction.

---

### Verified facts behind this design (2026-06-12 research)
IETF draft-07 status codes (409 in-flight / 422 mismatch / 400 missing) · Stripe ≥24h TTL,
stores status+body even on 5xx · Vert.x `@RouteFilter` + `RequireBodyHandlerBuildItem` =
non-blocking buffered body (avoids #17280/#17430) · Quarkus Cache TTL is per-cache-name
(Caffeine) · Redis `SET NX PX` = atomic in-flight lock · server filter build items exist
(`ContainerRequestFilterBuildItem`) but Vert.x route filter chosen for body access · no existing
Quarkus(iverse) idempotency extension.
