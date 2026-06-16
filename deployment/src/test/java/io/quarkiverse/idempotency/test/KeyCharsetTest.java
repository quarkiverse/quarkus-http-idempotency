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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkus.test.QuarkusUnitTest;
import io.restassured.http.Header;

/**
 * The Idempotency-Key draft defines the value as an RFC 8941 sf-string: printable US-ASCII
 * ({@code 0x20}-{@code 0x7E}). A key carrying a non-ASCII byte must be rejected with HTTP 400, while
 * a plain-ASCII key is accepted.
 */
public class KeyCharsetTest {

    @RegisterExtension
    static final QuarkusUnitTest TEST = new QuarkusUnitTest()
            .withApplicationRoot(jar -> jar.addClass(PaymentResource.class));

    private static final String BODY = "{\"amount\":1000}";

    @Test
    void nonAsciiKeyIsRejected() {
        given().header(new Header("Idempotency-Key", "café-key")) // é (0xE9) is not sf-string
                .contentType("application/json").body(BODY)
                .when().post("/payments/charge")
                .then().statusCode(400)
                .contentType("application/problem+json");
    }

    @Test
    void plainAsciiKeyIsAccepted() {
        given().header(new Header("Idempotency-Key", "ascii-key-123"))
                .contentType("application/json").body(BODY)
                .when().post("/payments/charge")
                .then().statusCode(200);
    }
}
