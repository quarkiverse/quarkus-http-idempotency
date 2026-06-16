# Quarkus HTTP Idempotency

Make unsafe HTTP requests safe to retry. This Quarkus extension implements the `Idempotency-Key`
header (the [Stripe](https://docs.stripe.com/api/idempotent_requests) / [IETF
draft](https://datatracker.ietf.org/doc/html/draft-ietf-httpapi-idempotency-key-header) pattern):
when a client retries a `POST` or `PATCH` with the same key, the server replays the original
response instead of executing the operation twice — so the side effect happens **exactly once**.

## Installation

```xml
<dependency>
    <groupId>io.quarkiverse.idempotency</groupId>
    <artifactId>quarkus-http-idempotency</artifactId>
    <version>${quarkus-http-idempotency.version}</version>
</dependency>
```

With the extension on the classpath, any `POST`/`PATCH` carrying an `Idempotency-Key` header is
handled idempotently — no code changes required.

## Quick start

```bash
# First call — runs the operation
curl -i -H "Idempotency-Key: 8e039f93" -H "Content-Type: application/json" \
     -d '{"item":"widget"}' https://api.example.com/orders
# HTTP/1.1 201 Created · Location: /orders/order-1

# Retry with the same key — replays the stored response, the order is NOT created again
curl -i -H "Idempotency-Key: 8e039f93" -H "Content-Type: application/json" \
     -d '{"item":"widget"}' https://api.example.com/orders
# HTTP/1.1 201 Created · Idempotent-Replayed: true
```

## What it does

| Situation | Behavior | Status |
|---|---|---|
| New key | Reserve, run the handler, store and return the response | the handler's |
| Same key, same payload, completed | Replay the stored status, body and headers | the stored one |
| Same key, same payload, still in flight | Reject — a concurrent retry is in progress | `409` |
| Same key, **different** payload | Reject — key reused for a different request | `422` |
| Key required but missing/invalid | Reject | `400` |

## Highlights

- **Spec-aligned.** Follows the IETF Idempotency-Key draft: `409`/`422`/`400` semantics, payload
  fingerprint, a documented expiry policy, and RFC 9457 `application/problem+json` error bodies with
  a `Link` to the docs.
- **Safe for multi-user / multi-tenant APIs.** The store key is a per-caller composite —
  `SHA-256(principal ⊕ scope ⊕ raw-key)` — so one caller can never be served another caller's stored
  response. Optional per-tenant isolation via a trusted scope header.
- **Pluggable stores.** Bounded in-memory (Caffeine) for a single node, or distributed **Redis** for
  clusters — behind a small SPI.
- **Hardened.** Bounded memory, capped fingerprint/stored-body sizes, and an unconditional deny-list
  that keeps credential headers (`Set-Cookie`, `Authorization`, `*-token`…) out of stored responses.
- **Reactive-ready.** Works with `Uni`/async return types.

## Configuration

All properties live under the `quarkus.idempotency.*` prefix (header name, guarded methods, TTLs,
store backend, per-tenant scope, resource bounds, …). See the
[documentation](https://docs.quarkiverse.io/quarkus-http-idempotency/dev/) for the full reference and the
security model.

## Stores

- **`in-memory`** (default) — single-node, bounded by `max-entries`.
- **`redis`** — add `quarkus-redis-client`, set `quarkus.idempotency.store=redis`. Reserves a key
  with a single atomic `SET NX GET PX` round-trip (requires Redis 7.0+).

## Build

```bash
mvn install
```

## License

[Apache License 2.0](LICENSE).
