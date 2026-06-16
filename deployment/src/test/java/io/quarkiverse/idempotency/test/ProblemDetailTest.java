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
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.startsWith;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkus.test.QuarkusUnitTest;
import io.restassured.http.Header;

/**
 * RFC 9457 (problem+json) compliance: every idempotency rejection is rendered by the
 * quarkus-http-problem extension as {@code application/problem+json} with a {@code type} URI
 * pointing to the documentation, plus {@code title} and {@code status}.
 */
public class ProblemDetailTest {

    @RegisterExtension
    static final QuarkusUnitTest TEST = new QuarkusUnitTest()
            .withApplicationRoot(jar -> jar.addClass(PaymentResource.class))
            .overrideConfigKey("quarkus.idempotency.require-key", "true")
            .overrideConfigKey("quarkus.idempotency.problem-base-uri", "https://example.test/docs");

    private static final String BODY = "{\"amount\":1000}";

    @Test
    void missingKeyReturnsProblemJson() {
        given().contentType("application/json").body(BODY)
                .when().post("/payments/charge")
                .then().statusCode(400)
                .contentType("application/problem+json")
                .body("type", equalTo("https://example.test/docs#idempotency-key-required"))
                .body("title", equalTo("Idempotency-Key required"))
                .body("status", equalTo(400));
    }

    @Test
    void payloadMismatchReturnsProblemJson() {
        given().header(new Header("Idempotency-Key", "pj-1"))
                .contentType("application/json").body(BODY)
                .when().post("/payments/charge").then().statusCode(200);

        given().header(new Header("Idempotency-Key", "pj-1"))
                .contentType("application/json").body("{\"amount\":2}")
                .when().post("/payments/charge")
                .then().statusCode(422)
                .contentType("application/problem+json")
                .body("type", startsWith("https://example.test/docs#idempotency-key-mismatch"))
                .body("status", equalTo(422));
    }
}
