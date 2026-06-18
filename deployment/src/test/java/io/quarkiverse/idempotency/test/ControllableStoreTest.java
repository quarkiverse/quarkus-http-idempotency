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

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Duration;

import jakarta.inject.Inject;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.quarkus.test.QuarkusUnitTest;
import io.restassured.http.Header;

/**
 * Drives the conflict, store-failure, and TTL-override paths against {@link ControllableStore}.
 * Fingerprinting is disabled so a seeded in-flight entry resolves straight to the conflict branch.
 */
public class ControllableStoreTest {

    @RegisterExtension
    static final QuarkusUnitTest TEST = new QuarkusUnitTest()
            .withApplicationRoot(jar -> jar
                    .addClasses(ControllableResource.class, ControllableStore.class, Await.class))
            .overrideConfigKey("quarkus.idempotency.fingerprint-enabled", "false");

    @Inject
    ControllableStore store;

    @Inject
    MeterRegistry registry;

    @BeforeEach
    void resetStore() {
        store.reset();
    }

    private double count(String meter, String outcomeTag) {
        Counter c = outcomeTag == null
                ? registry.find(meter).counter()
                : registry.find(meter).tag("outcome", outcomeTag).counter();
        return c == null ? 0d : c.count();
    }

    @Test
    void conflictIsCounted() {
        double conflict0 = count("idempotency.requests", "conflict");
        store.forceConflict();

        given().header(new Header("Idempotency-Key", "c1")).when().post("/ctl/op")
                .then().statusCode(409);

        // onConflict is recorded synchronously while the request is resolved.
        assertEquals(conflict0 + 1, count("idempotency.requests", "conflict"), 0.001, "conflict counted");
    }

    @Test
    void storeFailureReleasesTheKey() {
        double errors0 = count("idempotency.store.errors", null);
        store.failNextComplete();

        // The persist is fire-and-forget, so the request still succeeds.
        given().header(new Header("Idempotency-Key", "sf1")).when().post("/ctl/op")
                .then().statusCode(200);

        Await.until(() -> count("idempotency.store.errors", null) >= errors0 + 1, "store error counted");
        Await.until(() -> store.releaseCount() >= 1, "key released after failed persist");

        // Because the key was released (not left 409-blocking), a retry re-executes instead of conflicting.
        given().header(new Header("Idempotency-Key", "sf1")).when().post("/ctl/op")
                .then().statusCode(200).header("Idempotent-Replayed", nullValue());
    }

    @Test
    void ttlOverrideIsApplied() {
        given().header(new Header("Idempotency-Key", "t1")).when().post("/ctl/ttl")
                .then().statusCode(200);

        // @Idempotent(ttl = 2, ttlUnit = HOURS) must resolve to a 2-hour Duration passed to the store.
        Await.until(() -> store.lastTtl() != null, "response stored with a TTL");
        assertEquals(Duration.ofHours(2), store.lastTtl(), "per-endpoint TTL override applied");
    }
}
