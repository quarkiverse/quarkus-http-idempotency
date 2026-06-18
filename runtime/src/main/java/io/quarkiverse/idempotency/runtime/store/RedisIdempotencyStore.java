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

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.jboss.logging.Logger;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.quarkiverse.idempotency.runtime.spi.IdempotencyStore;
import io.quarkiverse.idempotency.runtime.spi.Reservation;
import io.quarkiverse.idempotency.runtime.spi.StoredEntry;
import io.quarkiverse.idempotency.runtime.spi.StoredResponse;
import io.quarkus.arc.lookup.LookupIfProperty;
import io.quarkus.redis.datasource.ReactiveRedisDataSource;
import io.quarkus.redis.datasource.keys.ReactiveKeyCommands;
import io.quarkus.redis.datasource.value.ReactiveValueCommands;
import io.quarkus.redis.datasource.value.SetArgs;
import io.smallrye.mutiny.Uni;

/**
 * Distributed {@link IdempotencyStore} backed by Redis via the non-blocking reactive client, so it
 * is safe to call from reactive endpoints without blocking the event loop. The in-flight
 * reservation uses an atomic {@code SET NX GET PX} so exactly one node wins a given key; the loser
 * observes the existing entry. Only active when {@code quarkus.idempotency.store=redis} and
 * {@code quarkus-redis-client} is present.
 */
@ApplicationScoped
@LookupIfProperty(name = "quarkus.idempotency.store", stringValue = "redis")
public class RedisIdempotencyStore implements IdempotencyStore {

    private static final String PREFIX = "idempotency:";
    private static final Logger LOG = Logger.getLogger(RedisIdempotencyStore.class);

    private final ReactiveValueCommands<String, StoredEntry> values;
    private final ReactiveKeyCommands<String> keys;
    private final ObjectMapper mapper;

    @Inject
    public RedisIdempotencyStore(ReactiveRedisDataSource dataSource, ObjectMapper mapper) {
        this.values = dataSource.value(StoredEntry.class);
        this.keys = dataSource.key();
        this.mapper = mapper;
    }

    @Override
    public Uni<Reservation> acquire(String key, String fingerprint, Duration lockTtl) {
        // Single atomic round-trip: SET key val NX GET PX ttl. Returns the previous value (the
        // existing entry) or null when we won the reservation. Requires Redis 7.0+ (GET with NX).
        return values.setGet(PREFIX + key, new StoredEntry(fingerprint, null),
                new SetArgs().nx().px(lockTtl.toMillis()))
                .map(previous -> previous == null
                        ? new Reservation.Acquired()
                        : new Reservation.Existing(materializeBody(previous)));
    }

    /**
     * Render a replayed entry's body to a self-contained {@code byte[]} before it reaches the
     * response path. The codec deserializes the body into a live object (e.g. a {@code Map}) that
     * RESTEasy would re-serialize lazily while writing the response. On the asynchronous (suspend
     * /resume) replay path that lazy serialization runs on an event-loop thread, and under
     * concurrent replays of the same key it races on a pooled Netty buffer — surfacing as an empty
     * body (and occasional {@code IllegalReferenceCountException}). Pre-rendering to bytes here, on
     * the read, removes the write-time serialization so each replay writes a fixed, private buffer.
     */
    private StoredEntry materializeBody(StoredEntry entry) {
        StoredResponse response = entry == null ? null : entry.response();
        if (response == null || response.entity() == null || response.entity() instanceof byte[]) {
            return entry; // in-flight marker, no body, or already bytes
        }
        try {
            Object entity = response.entity();
            String mediaType = response.mediaType();
            byte[] body;
            if (entity instanceof String text) {
                // A String entity is written verbatim by RESTEasy (it is NOT re-encoded as a JSON
                // string even for a JSON producer), so mirror that: take its bytes as-is.
                body = text.getBytes(StandardCharsets.UTF_8);
            } else if (mediaType != null && mediaType.toLowerCase(Locale.ROOT).contains("json")) {
                body = mapper.writeValueAsBytes(entity);
            } else {
                body = String.valueOf(entity).getBytes(StandardCharsets.UTF_8);
            }
            return new StoredEntry(entry.fingerprint(),
                    new StoredResponse(response.status(), response.headers(), body, response.mediaType()));
        } catch (RuntimeException | JsonProcessingException e) {
            // Best-effort optimization: any serialization failure must degrade to the existing lazy
            // path, never break the replay — so the catch is intentionally broad.
            LOG.debugf(e, "Could not pre-render replay body; falling back to the deserialized entity");
            return entry;
        }
    }

    @Override
    public Uni<Void> complete(String key, String fingerprint, StoredResponse response, Duration ttl) {
        return values.set(PREFIX + key, new StoredEntry(fingerprint, response),
                new SetArgs().px(ttl.toMillis()));
    }

    @Override
    public Uni<Void> release(String key) {
        return keys.del(PREFIX + key).replaceWithVoid();
    }
}
