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
package io.quarkiverse.idempotency.test;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;

import io.quarkiverse.idempotency.runtime.spi.IdempotencyStore;
import io.quarkiverse.idempotency.runtime.spi.Reservation;
import io.quarkiverse.idempotency.runtime.spi.StoredEntry;
import io.quarkiverse.idempotency.runtime.spi.StoredResponse;
import io.smallrye.mutiny.Uni;

/**
 * Test fake store with controllable behavior, selected over the default in-memory store via
 * {@code @Alternative @Priority}. Lets a test force a persist failure, observe the TTL passed to
 * {@link #complete}, count releases, and seed an in-flight entry to drive the 409 conflict path.
 * Deterministic and in-memory, so it is a fake rather than a mock.
 */
@Alternative
@Priority(1)
@ApplicationScoped
public class ControllableStore implements IdempotencyStore {

    private final Map<String, StoredEntry> entries = new ConcurrentHashMap<>();
    private final AtomicReference<Duration> lastTtl = new AtomicReference<>();
    private final AtomicInteger releases = new AtomicInteger();
    private volatile boolean failComplete;
    private volatile boolean forceConflict;

    /** Restore a clean slate between tests so cases do not share mutable state. */
    void reset() {
        entries.clear();
        lastTtl.set(null);
        releases.set(0);
        failComplete = false;
        forceConflict = false;
    }

    /** Make the next acquire report an in-flight reservation, driving the 409 conflict path. */
    void forceConflict() {
        forceConflict = true;
    }

    void failNextComplete() {
        failComplete = true;
    }

    Duration lastTtl() {
        return lastTtl.get();
    }

    int releaseCount() {
        return releases.get();
    }

    @Override
    public Uni<Reservation> acquire(String key, String fingerprint, Duration lockTtl) {
        if (forceConflict) {
            return Uni.createFrom().item(new Reservation.Existing(new StoredEntry(fingerprint, null)));
        }
        StoredEntry existing = entries.putIfAbsent(key, new StoredEntry(fingerprint, null));
        return Uni.createFrom().item(existing == null
                ? new Reservation.Acquired()
                : new Reservation.Existing(existing));
    }

    @Override
    public Uni<Void> complete(String key, String fingerprint, StoredResponse response, Duration ttl) {
        if (failComplete) {
            return Uni.createFrom().failure(new RuntimeException("forced persist failure"));
        }
        lastTtl.set(ttl);
        entries.put(key, new StoredEntry(fingerprint, response));
        return Uni.createFrom().voidItem();
    }

    @Override
    public Uni<Void> release(String key) {
        entries.remove(key);
        releases.incrementAndGet();
        return Uni.createFrom().voidItem();
    }
}
