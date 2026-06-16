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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkus.test.QuarkusUnitTest;
import io.restassured.http.Header;

/**
 * A streaming (Multi) response cannot be captured for replay, so the extension must not make it
 * idempotent: the key is released, so a retry re-executes rather than getting a 409/replay. Confirms
 * the reactive path does not crash and does not pin the key.
 */
public class StreamingTest {

    @RegisterExtension
    static final QuarkusUnitTest TEST = new QuarkusUnitTest()
            .withApplicationRoot(jar -> jar.addClass(PaymentResource.class));

    private int executions() {
        return Integer.parseInt(given().when().get("/payments/executions")
                .then().statusCode(200).extract().asString().trim());
    }

    @Test
    void streamingResponseIsNotMadeIdempotent() {
        int before = executions();

        given().header(new Header("Idempotency-Key", "stream-key"))
                .contentType("application/json").body("{}")
                .when().post("/payments/charge-stream")
                .then().statusCode(200)
                .header("Idempotent-Replayed", nullValue());

        // Retry with the same key: must execute again (not 409, not a replay) since streams aren't cached.
        given().header(new Header("Idempotency-Key", "stream-key"))
                .contentType("application/json").body("{}")
                .when().post("/payments/charge-stream")
                .then().statusCode(200)
                .header("Idempotent-Replayed", nullValue());

        org.junit.jupiter.api.Assertions.assertEquals(2, executions() - before,
                "streaming handler must run on every call (not made idempotent)");
    }
}
