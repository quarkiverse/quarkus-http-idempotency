/*
 * Copyright the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.quarkiverse.idempotency.runtime.store;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.agroal.api.AgroalDataSource;
import io.quarkiverse.idempotency.runtime.IdempotencyConfig;
import io.quarkiverse.idempotency.runtime.spi.IdempotencyStore;
import io.quarkiverse.idempotency.runtime.spi.Reservation;
import io.quarkiverse.idempotency.runtime.spi.StoredEntry;
import io.quarkiverse.idempotency.runtime.spi.StoredResponse;
import io.quarkus.arc.lookup.LookupIfProperty;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.infrastructure.Infrastructure;

/**
 * Distributed {@link IdempotencyStore} backed by a relational database through the standard JDBC
 * datasource (Agroal), so it works with any JDBC driver (PostgreSQL, MySQL/MariaDB, Oracle, SQL
 * Server, Db2, H2, …) rather than a single vendor. Only active when
 * {@code quarkus.idempotency.store=jdbc} and {@code quarkus-agroal} plus a JDBC driver are present.
 *
 * <p>
 * The SQL is intentionally vendor-neutral: positional {@code ?} placeholders, no {@code ON CONFLICT}
 * / {@code MERGE}, expiries stored as epoch-millis {@code BIGINT} (no cross-vendor {@code TIMESTAMP}
 * semantics), and {@code in_flight} as a {@code SMALLINT} 0/1 (Oracle has no portable {@code BOOLEAN}).
 * Atomicity of {@link #acquire} comes from the primary-key constraint (the {@code INSERT} either wins
 * or fails with an integrity violation) and from a single conditional {@code UPDATE} when reclaiming
 * an expired entry (the row lock lets exactly one racing caller match the "expired" predicate).
 *
 * <p>
 * JDBC is blocking, so every operation is dispatched to a worker thread to honour the non-blocking
 * store contract on reactive endpoints. Prefer the {@code redis} store for fully-reactive, high
 * throughput; this store trades that for portability. Expiry is enforced lazily on read (an expired
 * entry is never replayed, it is reclaimed as a new reservation); a periodic cleanup of expired rows
 * is recommended to bound table growth (see the documentation).
 */
@ApplicationScoped
@LookupIfProperty(name = "quarkus.idempotency.store", stringValue = "jdbc")
public class JdbcIdempotencyStore implements IdempotencyStore {

    /** SQLState class 23 is "integrity constraint violation" across vendors (unique/PK conflict). */
    private static final String SQLSTATE_INTEGRITY_VIOLATION = "23";

    private final Instance<AgroalDataSource> dataSource;
    private final ObjectMapper mapper;

    private final String insertSql;
    private final String selectSql;
    private final String reclaimSql;
    private final String completeSql;
    private final String releaseSql;

    @Inject
    public JdbcIdempotencyStore(Instance<AgroalDataSource> dataSource, ObjectMapper mapper, IdempotencyConfig config) {
        this.dataSource = dataSource;
        this.mapper = mapper;
        String table = sanitizeTableName(config.jdbc().table());
        this.insertSql = "INSERT INTO " + table
                + " (id, fingerprint, in_flight, response, lock_expires_at, response_expires_at)"
                + " VALUES (?, ?, 1, NULL, ?, NULL)";
        this.selectSql = "SELECT fingerprint, in_flight, response, lock_expires_at, response_expires_at"
                + " FROM " + table + " WHERE id = ?";
        // Reclaim only when the existing row is expired; the predicate is re-checked atomically here so
        // exactly one racing caller wins the reservation of an abandoned/expired key.
        this.reclaimSql = "UPDATE " + table
                + " SET fingerprint = ?, in_flight = 1, response = NULL, lock_expires_at = ?,"
                + " response_expires_at = NULL WHERE id = ?"
                + " AND ((in_flight = 1 AND lock_expires_at < ?)"
                + " OR (in_flight = 0 AND response_expires_at IS NOT NULL AND response_expires_at < ?))";
        this.completeSql = "UPDATE " + table
                + " SET in_flight = 0, response = ?, response_expires_at = ? WHERE id = ?";
        this.releaseSql = "DELETE FROM " + table + " WHERE id = ? AND in_flight = 1";
    }

    @Override
    public Uni<Reservation> acquire(String key, String fingerprint, Duration lockTtl) {
        return Uni.createFrom().item(() -> acquireBlocking(key, fingerprint, lockTtl))
                .runSubscriptionOn(Infrastructure.getDefaultWorkerPool());
    }

    @Override
    public Uni<Void> complete(String key, String fingerprint, StoredResponse response, Duration ttl) {
        return Uni.createFrom().<Void> item(() -> {
            completeBlocking(key, response, ttl);
            return null;
        }).runSubscriptionOn(Infrastructure.getDefaultWorkerPool());
    }

    @Override
    public Uni<Void> release(String key) {
        return Uni.createFrom().<Void> item(() -> {
            releaseBlocking(key);
            return null;
        }).runSubscriptionOn(Infrastructure.getDefaultWorkerPool());
    }

    private Reservation acquireBlocking(String key, String fingerprint, Duration lockTtl) {
        long now = System.currentTimeMillis();
        long lockExpiresAt = now + lockTtl.toMillis();
        try (Connection conn = dataSource.get().getConnection()) {
            // Fast path: try to own the key outright. The primary key makes this the atomic gate.
            try (PreparedStatement insert = conn.prepareStatement(insertSql)) {
                insert.setString(1, key);
                insert.setString(2, fingerprint);
                insert.setLong(3, lockExpiresAt);
                insert.executeUpdate();
                return new Reservation.Acquired();
            } catch (SQLException e) {
                if (!isIntegrityViolation(e)) {
                    throw e;
                }
                // Key already exists: read it, and reclaim it if it has expired.
            }
            StoredEntry existing = select(conn, key, now);
            if (existing == null) {
                // Expired and reclaimed by us (or vanished): take the reservation.
                if (reclaim(conn, key, fingerprint, now, lockExpiresAt)) {
                    return new Reservation.Acquired();
                }
                // Lost the reclaim race; the winner's entry is now current.
                StoredEntry current = select(conn, key, now);
                return current == null
                        ? new Reservation.Acquired()
                        : new Reservation.Existing(StoredResponses.materializeBody(current, mapper));
            }
            return new Reservation.Existing(StoredResponses.materializeBody(existing, mapper));
        } catch (SQLException e) {
            throw new IllegalStateException("Idempotency JDBC acquire failed for key", e);
        }
    }

    /**
     * Read the current, non-expired entry for a key. Returns {@code null} when the row is absent or
     * expired (so the caller reclaims it), which keeps stale responses from ever being replayed.
     */
    private StoredEntry select(Connection conn, String key, long now) throws SQLException {
        try (PreparedStatement select = conn.prepareStatement(selectSql)) {
            select.setString(1, key);
            try (ResultSet rs = select.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                String fingerprint = rs.getString("fingerprint");
                boolean inFlight = rs.getInt("in_flight") != 0;
                long lockExpiresAt = rs.getLong("lock_expires_at");
                long responseExpiresAt = rs.getLong("response_expires_at");
                boolean responseExpiresAtIsNull = rs.wasNull();
                if (inFlight) {
                    if (lockExpiresAt < now) {
                        return null; // stale in-flight lock: reclaimable
                    }
                    return new StoredEntry(fingerprint, null);
                }
                if (!responseExpiresAtIsNull && responseExpiresAt < now) {
                    return null; // completed but expired: reclaimable
                }
                byte[] response = rs.getBytes("response");
                StoredResponse stored = response == null ? null : mapper.readValue(response, StoredResponse.class);
                return new StoredEntry(fingerprint, stored);
            }
        } catch (java.io.IOException e) {
            throw new SQLException("Could not deserialize stored idempotency response", e);
        }
    }

    private boolean reclaim(Connection conn, String key, String fingerprint, long now, long lockExpiresAt)
            throws SQLException {
        try (PreparedStatement reclaim = conn.prepareStatement(reclaimSql)) {
            reclaim.setString(1, fingerprint);
            reclaim.setLong(2, lockExpiresAt);
            reclaim.setString(3, key);
            reclaim.setLong(4, now);
            reclaim.setLong(5, now);
            return reclaim.executeUpdate() == 1;
        }
    }

    private void completeBlocking(String key, StoredResponse response, Duration ttl) {
        try (Connection conn = dataSource.get().getConnection();
                PreparedStatement complete = conn.prepareStatement(completeSql)) {
            complete.setBytes(1, mapper.writeValueAsBytes(response));
            complete.setLong(2, System.currentTimeMillis() + ttl.toMillis());
            complete.setString(3, key);
            complete.executeUpdate();
        } catch (SQLException | com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("Idempotency JDBC complete failed for key", e);
        }
    }

    private void releaseBlocking(String key) {
        try (Connection conn = dataSource.get().getConnection();
                PreparedStatement release = conn.prepareStatement(releaseSql)) {
            release.setString(1, key);
            release.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Idempotency JDBC release failed for key", e);
        }
    }

    private static boolean isIntegrityViolation(SQLException e) {
        for (SQLException current = e; current != null; current = current.getNextException()) {
            String state = current.getSQLState();
            if (state != null && state.startsWith(SQLSTATE_INTEGRITY_VIOLATION)) {
                return true;
            }
        }
        return false;
    }

    /**
     * The table name is interpolated into the SQL (an identifier cannot be a bind parameter), so it
     * must be a plain identifier. Reject anything else at startup rather than risk SQL injection.
     */
    private static String sanitizeTableName(String table) {
        if (table == null || !table.matches("[A-Za-z_][A-Za-z0-9_]*")) {
            throw new IllegalStateException(
                    "quarkus.idempotency.jdbc.table must be a simple SQL identifier ([A-Za-z_][A-Za-z0-9_]*), was: "
                            + table);
        }
        return table;
    }
}
