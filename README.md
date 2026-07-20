# Quarkus HTTP Idempotency

[![Version](https://img.shields.io/maven-central/v/io.quarkiverse.idempotency/quarkus-http-idempotency?logo=apache-maven&style=flat-square)](https://central.sonatype.com/artifact/io.quarkiverse.idempotency/quarkus-http-idempotency)
[![License](https://img.shields.io/badge/License-Apache%202.0-yellow.svg?style=flat-square)](https://opensource.org/licenses/Apache-2.0)
[![Build](https://github.com/quarkiverse/quarkus-http-idempotency/actions/workflows/build.yml/badge.svg)](https://github.com/quarkiverse/quarkus-http-idempotency/actions/workflows/build.yml)

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
- **`jdbc`** — add `quarkus-agroal` and a JDBC driver (`quarkus-jdbc-postgresql`, `-mysql`,
  `-oracle`, `-mssql`, …), set `quarkus.idempotency.store=jdbc`. Works on any relational database:
  the reservation is atomic through the primary key (the `INSERT` either wins or fails with an
  integrity violation) and an expired key is reclaimed with a single conditional `UPDATE`. JDBC is
  blocking, so each call runs on a worker thread; prefer `redis` for fully-reactive, high-throughput
  workloads.

### JDBC schema

The extension never issues DDL; the application owns the table. Create it once with the shape below
before enabling the store. The table name is configurable with `quarkus.idempotency.jdbc.table`
(default `idempotency_entry`). The `response` column holds the serialized response as binary, and the
two `*_expires_at` columns store epoch-millis as `BIGINT`.

PostgreSQL:

```sql
CREATE TABLE idempotency_entry (
    id                  VARCHAR(255) PRIMARY KEY,
    fingerprint         VARCHAR(255),
    in_flight           SMALLINT     NOT NULL,
    response            BYTEA,
    lock_expires_at     BIGINT       NOT NULL,
    response_expires_at BIGINT
);
```

MySQL / MariaDB:

```sql
CREATE TABLE idempotency_entry (
    id                  VARCHAR(255) PRIMARY KEY,
    fingerprint         VARCHAR(255),
    in_flight           SMALLINT     NOT NULL,
    response            LONGBLOB,
    lock_expires_at     BIGINT       NOT NULL,
    response_expires_at BIGINT
);
```

Oracle (no `BIGINT`/`BOOLEAN` keywords, so `NUMBER` is used):

```sql
CREATE TABLE idempotency_entry (
    id                  VARCHAR2(255) PRIMARY KEY,
    fingerprint         VARCHAR2(255),
    in_flight           NUMBER(1)     NOT NULL,
    response            BLOB,
    lock_expires_at     NUMBER(19)    NOT NULL,
    response_expires_at NUMBER(19)
);
```

SQL Server:

```sql
CREATE TABLE idempotency_entry (
    id                  VARCHAR(255)   PRIMARY KEY,
    fingerprint         VARCHAR(255),
    in_flight           SMALLINT       NOT NULL,
    response            VARBINARY(MAX),
    lock_expires_at     BIGINT         NOT NULL,
    response_expires_at BIGINT
);
```

Expiry is enforced lazily on read: an expired entry is never replayed, it is reclaimed in place as a
new reservation, so the store stays correct with no background job. A row whose key never comes back
is only reclaimed if that key is seen again, so to bound table growth run a periodic purge (a cron
job, a scheduled task, or `quarkus-scheduler` if you already depend on it) binding the current
epoch-millis to both parameters:

```sql
DELETE FROM idempotency_entry
 WHERE (in_flight = 1 AND lock_expires_at < ?)
    OR (in_flight = 0 AND response_expires_at IS NOT NULL AND response_expires_at < ?);
```

## Build

```bash
mvn install
```

## License

[Apache License 2.0](LICENSE).
