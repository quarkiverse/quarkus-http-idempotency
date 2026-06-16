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

public class IdempotencyTest {

    @RegisterExtension
    static final QuarkusUnitTest TEST = new QuarkusUnitTest()
            .withApplicationRoot(jar -> jar.addClass(PaymentResource.class));

    private static final String BODY = "{\"amount\":1000}";

    private int executions() {
        return Integer.parseInt(given().when().get("/payments/executions")
                .then().statusCode(200).extract().asString().trim());
    }

    @Test
    void retryWithSameKeyReplaysAndRunsOnce() {
        int before = executions();

        String first = given()
                .header(new Header("Idempotency-Key", "key-A"))
                .contentType("application/json").body(BODY)
                .when().post("/payments/charge")
                .then().statusCode(200)
                .header("Idempotent-Replayed", nullValue())
                .extract().asString();

        given()
                .header(new Header("Idempotency-Key", "key-A"))
                .contentType("application/json").body(BODY)
                .when().post("/payments/charge")
                .then().statusCode(200)
                .header("Idempotent-Replayed", equalTo("true"))
                .body(equalTo(first));

        // The handler ran exactly once across the two calls.
        org.junit.jupiter.api.Assertions.assertEquals(1, executions() - before);
    }

    @Test
    void sameKeyDifferentPayloadIsRejected() {
        given().header(new Header("Idempotency-Key", "key-B"))
                .contentType("application/json").body("{\"amount\":1}")
                .when().post("/payments/charge")
                .then().statusCode(200);

        given().header(new Header("Idempotency-Key", "key-B"))
                .contentType("application/json").body("{\"amount\":999}")
                .when().post("/payments/charge")
                .then().statusCode(422);
    }

    @Test
    void differentKeysBothExecute() {
        int before = executions();

        given().header(new Header("Idempotency-Key", "key-C1"))
                .contentType("application/json").body(BODY)
                .when().post("/payments/charge").then().statusCode(200);

        given().header(new Header("Idempotency-Key", "key-C2"))
                .contentType("application/json").body(BODY)
                .when().post("/payments/charge").then().statusCode(200);

        org.junit.jupiter.api.Assertions.assertEquals(2, executions() - before);
    }

    @Test
    void withoutKeyPassesThrough() {
        int before = executions();
        given().contentType("application/json").body(BODY)
                .when().post("/payments/charge")
                .then().statusCode(200)
                .header("Idempotent-Replayed", nullValue());
        org.junit.jupiter.api.Assertions.assertEquals(1, executions() - before);
    }

    @Test
    void reactiveEndpointReplaysAndRunsOnce() {
        int before = executions();

        String first = given()
                .header(new Header("Idempotency-Key", "key-R"))
                .contentType("application/json").body(BODY)
                .when().post("/payments/charge-reactive")
                .then().statusCode(200)
                .header("Idempotent-Replayed", nullValue())
                .extract().asString();

        given()
                .header(new Header("Idempotency-Key", "key-R"))
                .contentType("application/json").body(BODY)
                .when().post("/payments/charge-reactive")
                .then().statusCode(200)
                .header("Idempotent-Replayed", equalTo("true"))
                .body(equalTo(first));

        org.junit.jupiter.api.Assertions.assertEquals(1, executions() - before);
    }
}
