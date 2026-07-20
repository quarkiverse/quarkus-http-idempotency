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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkiverse.idempotency.runtime.spi.IdempotencyStore;
import io.quarkiverse.idempotency.runtime.spi.Reservation;
import io.quarkiverse.idempotency.runtime.spi.StoredEntry;
import io.quarkiverse.idempotency.runtime.spi.StoredResponse;
import io.quarkus.test.QuarkusUnitTest;

/**
 * Deterministic coverage of the JDBC store contract against a real (H2) database: the in-flight
 * (409) path, replay, release, expired-lock reclaim, and the atomicity guarantee that exactly one
 * of many concurrent callers reserves a fresh key.
 */
public class JdbcStoreContractTest {

    @RegisterExtension
    static final QuarkusUnitTest TEST = new QuarkusUnitTest()
            .withApplicationRoot(jar -> jar.addClass(PaymentResource.class))
            .withConfigurationResource("jdbc-store.properties");

    private static final Duration LOCK = Duration.ofSeconds(60);
    private static final Duration TTL = Duration.ofHours(24);

    @Inject
    Instance<IdempotencyStore> stores;

    private Reservation acquire(String key, String fp, Duration lock) {
        return stores.get().acquire(key, fp, lock).await().indefinitely();
    }

    @Test
    void firstAcquireWinsSecondSeesInFlight() {
        assertInstanceOf(Reservation.Acquired.class, acquire("k1", "fp", LOCK));
        StoredEntry entry = assertInstanceOf(Reservation.Existing.class, acquire("k1", "fp", LOCK)).entry();
        assertTrue(entry.inFlight(), "second concurrent caller must observe the in-flight reservation (→ 409)");
    }

    @Test
    void completeMakesEntryReplayable() {
        acquire("k2", "fp", LOCK);
        stores.get().complete("k2", "fp",
                new StoredResponse(201, Map.of("Location", "/r/1"), "ok", "text/plain"), TTL)
                .await().indefinitely();

        StoredEntry entry = assertInstanceOf(Reservation.Existing.class, acquire("k2", "fp", LOCK)).entry();
        assertFalse(entry.inFlight());
        assertEquals(201, entry.response().status());
        assertEquals("fp", entry.fingerprint());
        // Out-of-process stores pre-render the body to bytes on read (see StoredResponses).
        assertEquals("ok", new String((byte[]) entry.response().entity(), StandardCharsets.UTF_8));
    }

    @Test
    void releaseAllowsReacquire() {
        acquire("k3", "fp", LOCK);
        stores.get().release("k3").await().indefinitely();
        assertInstanceOf(Reservation.Acquired.class, acquire("k3", "fp", LOCK));
    }

    @Test
    void expiredReservationCanBeReacquired() throws InterruptedException {
        acquire("k4", "fp", Duration.ofMillis(1));
        Thread.sleep(40);
        assertInstanceOf(Reservation.Acquired.class, acquire("k4", "fp", LOCK));
    }

    @Test
    void concurrentAcquireYieldsExactlyOneOwner() throws Exception {
        int threads = 16;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            CyclicBarrier barrier = new CyclicBarrier(threads);
            AtomicInteger acquired = new AtomicInteger();
            Future<?>[] futures = new Future<?>[threads];
            for (int i = 0; i < threads; i++) {
                futures[i] = pool.submit(() -> {
                    barrier.await();
                    if (acquire("race", "fp", LOCK) instanceof Reservation.Acquired) {
                        acquired.incrementAndGet();
                    }
                    return null;
                });
            }
            for (Future<?> f : futures) {
                f.get();
            }
            assertEquals(1, acquired.get(), "exactly one concurrent caller may reserve a fresh key");
        } finally {
            pool.shutdownNow();
        }
    }
}
