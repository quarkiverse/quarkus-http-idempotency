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

import java.time.Duration;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

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

    private final ReactiveValueCommands<String, StoredEntry> values;
    private final ReactiveKeyCommands<String> keys;

    @Inject
    public RedisIdempotencyStore(ReactiveRedisDataSource dataSource) {
        this.values = dataSource.value(StoredEntry.class);
        this.keys = dataSource.key();
    }

    @Override
    public Uni<Reservation> acquire(String key, String fingerprint, Duration lockTtl) {
        // Single atomic round-trip: SET key val NX GET PX ttl. Returns the previous value (the
        // existing entry) or null when we won the reservation. Requires Redis 7.0+ (GET with NX).
        return values.setGet(PREFIX + key, new StoredEntry(fingerprint, null),
                new SetArgs().nx().px(lockTtl.toMillis()))
                .map(previous -> previous == null
                        ? new Reservation.Acquired()
                        : new Reservation.Existing(previous));
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
