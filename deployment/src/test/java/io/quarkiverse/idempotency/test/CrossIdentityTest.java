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
import static org.hamcrest.Matchers.nullValue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkus.test.QuarkusUnitTest;
import io.restassured.http.Header;

/**
 * SEC-01 regression: two distinct identities (here, tenants carried in a scope header) reusing the
 * SAME idempotency key must never observe each other's stored response.
 */
public class CrossIdentityTest {

    @RegisterExtension
    static final QuarkusUnitTest TEST = new QuarkusUnitTest()
            .withApplicationRoot(jar -> jar.addClass(PaymentResource.class))
            .overrideConfigKey("quarkus.idempotency.scope-header", "X-Tenant");

    private static final String BODY = "{\"amount\":1000}";

    @Test
    void sameKeyDifferentTenantsDoNotCrossReplay() {
        String tenantA = given()
                .header(new Header("Idempotency-Key", "shared-key"))
                .header(new Header("X-Tenant", "tenant-a"))
                .contentType("application/json").body(BODY)
                .when().post("/payments/charge")
                .then().statusCode(200)
                .header("Idempotent-Replayed", nullValue())
                .extract().asString();

        // Tenant B uses the very same key — must execute fresh, NOT replay tenant A's response.
        String tenantB = given()
                .header(new Header("Idempotency-Key", "shared-key"))
                .header(new Header("X-Tenant", "tenant-b"))
                .contentType("application/json").body(BODY)
                .when().post("/payments/charge")
                .then().statusCode(200)
                .header("Idempotent-Replayed", nullValue())
                .extract().asString();

        org.junit.jupiter.api.Assertions.assertNotEquals(tenantA, tenantB,
                "tenant B must not receive tenant A's stored response");

        // Tenant A retrying its own key still replays its own (and only its own) response.
        given()
                .header(new Header("Idempotency-Key", "shared-key"))
                .header(new Header("X-Tenant", "tenant-a"))
                .contentType("application/json").body(BODY)
                .when().post("/payments/charge")
                .then().statusCode(200)
                .header("Idempotent-Replayed", equalTo("true"))
                .body(equalTo(tenantA));
    }
}
