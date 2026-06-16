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

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.quarkiverse.idempotency.runtime.spi.Reservation;
import io.quarkiverse.idempotency.runtime.spi.StoredResponse;

/**
 * SEC-02 regression: the in-memory store must be bounded so a flood of unique keys cannot exhaust
 * the heap. Lives in the store package to read the test-only {@code estimatedSize()} hook.
 */
class BoundedStoreTest {

    private static final Duration LOCK = Duration.ofSeconds(60);
    private static final Duration TTL = Duration.ofHours(24);

    @Test
    void floodOfUniqueKeysStaysBounded() {
        long maxEntries = 50;
        InMemoryIdempotencyStore store = new InMemoryIdempotencyStore(maxEntries);

        for (int i = 0; i < 10_000; i++) {
            String key = "flood-" + i;
            store.acquire(key, "fp", LOCK).await().indefinitely();
            store.complete(key, "fp", new StoredResponse(201, Map.of(), "ok", "text/plain"), TTL)
                    .await().indefinitely();
        }

        long size = store.estimatedSize();
        assertTrue(size <= maxEntries,
                "store must stay within max-entries (" + maxEntries + "), was " + size);
    }

    @Test
    void evictedKeyCanBeAcquiredAgain() {
        InMemoryIdempotencyStore store = new InMemoryIdempotencyStore(10);
        store.acquire("first", "fp", LOCK).await().indefinitely();
        store.complete("first", "fp", new StoredResponse(201, Map.of(), "ok", "text/plain"), TTL)
                .await().indefinitely();

        for (int i = 0; i < 1_000; i++) {
            String key = "later-" + i;
            store.acquire(key, "fp", LOCK).await().indefinitely();
            store.complete(key, "fp", new StoredResponse(201, Map.of(), "ok", "text/plain"), TTL)
                    .await().indefinitely();
        }

        // "first" was long since evicted by size pressure, so it is a fresh acquisition again.
        assertInstanceOf(Reservation.Acquired.class, store.acquire("first", "fp", LOCK).await().indefinitely());
    }
}
