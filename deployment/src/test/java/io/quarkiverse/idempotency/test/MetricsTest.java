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
import static org.junit.jupiter.api.Assertions.assertEquals;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.quarkus.test.QuarkusUnitTest;
import io.restassured.http.Header;

/**
 * When {@code quarkus-micrometer} is on the classpath, idempotency outcomes are recorded on the
 * {@code idempotency.requests} counter (tagged by {@code outcome}) plus the {@code entries.stored} and
 * {@code entries.released} counters. Outcome counters are incremented synchronously while the request
 * is resolved; the persist/release counters are recorded fire-and-forget, so they are awaited.
 */
public class MetricsTest {

    @RegisterExtension
    static final QuarkusUnitTest TEST = new QuarkusUnitTest()
            .withApplicationRoot(jar -> jar.addClasses(PaymentResource.class, Await.class));

    @Inject
    MeterRegistry registry;

    private double outcome(String outcome) {
        Counter c = registry.find("idempotency.requests").tag("outcome", outcome).counter();
        return c == null ? 0d : c.count();
    }

    private double counter(String name) {
        Counter c = registry.find(name).counter();
        return c == null ? 0d : c.count();
    }

    @Test
    void outcomesAreCounted() {
        String body = "{\"amount\":1000}";
        double fresh0 = outcome("fresh");
        double replay0 = outcome("replay");
        double mismatch0 = outcome("mismatch");

        // Fresh: new key, runs the operation.
        given().header(new Header("Idempotency-Key", "mk-1")).contentType("application/json").body(body)
                .when().post("/payments/charge").then().statusCode(200);
        // Replay: same key + same body.
        given().header(new Header("Idempotency-Key", "mk-1")).contentType("application/json").body(body)
                .when().post("/payments/charge").then().statusCode(200)
                .header("Idempotent-Replayed", "true");
        // Mismatch: same key, different body.
        given().header(new Header("Idempotency-Key", "mk-1")).contentType("application/json").body("{\"amount\":2}")
                .when().post("/payments/charge").then().statusCode(422);

        assertEquals(fresh0 + 1, outcome("fresh"), 0.001, "fresh outcome counted");
        assertEquals(replay0 + 1, outcome("replay"), 0.001, "replay outcome counted");
        assertEquals(mismatch0 + 1, outcome("mismatch"), 0.001, "mismatch outcome counted");
    }

    @Test
    void storedIsCountedOnFreshSuccess() {
        double stored0 = counter("idempotency.entries.stored");

        given().header(new Header("Idempotency-Key", "st-1")).contentType("application/json").body("{}")
                .when().post("/payments/charge").then().statusCode(200);

        Await.until(() -> counter("idempotency.entries.stored") >= stored0 + 1, "stored counted after persist");
    }

    @Test
    void releasedIsCountedForStreaming() {
        double released0 = counter("idempotency.entries.released");

        // A streaming response cannot be captured, so the reserved key is released, not stored.
        given().header(new Header("Idempotency-Key", "rl-1")).contentType("application/json").body("{}")
                .when().post("/payments/charge-stream").then().statusCode(200);

        Await.until(() -> counter("idempotency.entries.released") >= released0 + 1, "released counted for stream");
    }
}
