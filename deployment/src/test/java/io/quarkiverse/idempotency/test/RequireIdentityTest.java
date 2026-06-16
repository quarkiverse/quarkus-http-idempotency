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
 * SEC-01 hardening: with {@code require-identity=true}, a keyed request from an anonymous caller is
 * rejected (401) rather than being filed under the shared anonymous namespace.
 */
public class RequireIdentityTest {

    @RegisterExtension
    static final QuarkusUnitTest TEST = new QuarkusUnitTest()
            .withApplicationRoot(jar -> jar.addClass(PaymentResource.class))
            .overrideConfigKey("quarkus.idempotency.require-identity", "true");

    @Test
    void anonymousKeyedRequestIsRejected() {
        given().header(new Header("Idempotency-Key", "anon-key"))
                .contentType("application/json").body("{\"amount\":5}")
                .when().post("/payments/charge")
                .then().statusCode(401);
    }

    @Test
    void unkeyedRequestStillPassesThrough() {
        given().contentType("application/json").body("{\"amount\":5}")
                .when().post("/payments/charge")
                .then().statusCode(200);
    }
}
