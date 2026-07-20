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

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkus.test.QuarkusUnitTest;
import io.restassured.http.Header;

/**
 * End-to-end idempotency over the {@code jdbc} store, against an in-memory H2 database. Proves the
 * store is wired (bean selected by {@code store=jdbc}, Agroal present) and that the spec scenarios
 * (replay + run-once, payload-mismatch → 422) hold when the state lives in a relational database.
 */
public class JdbcStoreTest {

    @RegisterExtension
    static final QuarkusUnitTest TEST = new QuarkusUnitTest()
            .withApplicationRoot(jar -> jar.addClass(PaymentResource.class))
            .withConfigurationResource("jdbc-store.properties");

    private static final String BODY = "{\"amount\":1000}";

    private int executions() {
        return Integer.parseInt(given().when().get("/payments/executions")
                .then().statusCode(200).extract().asString().trim());
    }

    @Test
    void retryWithSameKeyReplaysAndRunsOnce() {
        int before = executions();

        String first = given()
                .header(new Header("Idempotency-Key", "jdbc-A"))
                .contentType("application/json").body(BODY)
                .when().post("/payments/charge")
                .then().statusCode(200)
                .header("Idempotent-Replayed", nullValue())
                .extract().asString();

        given()
                .header(new Header("Idempotency-Key", "jdbc-A"))
                .contentType("application/json").body(BODY)
                .when().post("/payments/charge")
                .then().statusCode(200)
                .header("Idempotent-Replayed", equalTo("true"))
                .body(equalTo(first));

        Assertions.assertEquals(1, executions() - before, "handler must run exactly once across retries");
    }

    @Test
    void sameKeyDifferentPayloadIsRejected() {
        given().header(new Header("Idempotency-Key", "jdbc-B"))
                .contentType("application/json").body("{\"amount\":1}")
                .when().post("/payments/charge")
                .then().statusCode(200);

        given().header(new Header("Idempotency-Key", "jdbc-B"))
                .contentType("application/json").body("{\"amount\":999}")
                .when().post("/payments/charge")
                .then().statusCode(422);
    }
}
