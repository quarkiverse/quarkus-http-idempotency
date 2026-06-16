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
package io.quarkiverse.idempotency.it;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.Header;

@QuarkusTest
class IdempotencyTest {

    private int count() {
        return Integer.parseInt(given().when().get("/payments/count")
                .then().statusCode(200).extract().asString().trim());
    }

    @Test
    void retrySameKeyReplaysAndRunsOnce() {
        int before = count();
        String body = "{\"amount\":500}";

        String first = given()
                .header(new Header("Idempotency-Key", "it-1"))
                .contentType("application/json").body(body)
                .when().post("/payments/charge")
                .then().statusCode(201)
                .header("Idempotent-Replayed", nullValue())
                .extract().asString();

        given()
                .header(new Header("Idempotency-Key", "it-1"))
                .contentType("application/json").body(body)
                .when().post("/payments/charge")
                .then().statusCode(201)
                .header("Idempotent-Replayed", equalTo("true"))
                .body(equalTo(first));

        assertEquals(1, count() - before, "the charge must run exactly once across the retry");
    }

    @Test
    void annotatedGetIsOptedInAndReplays() {
        // GET is not a globally guarded method; @Idempotent opts it in. Proves build-time annotation
        // discovery + the method registry + ResourceInfo resolution work in the native image.
        String first = given().header(new Header("Idempotency-Key", "it-rep"))
                .when().get("/payments/report")
                .then().statusCode(200).header("Idempotent-Replayed", nullValue())
                .extract().asString();

        given().header(new Header("Idempotency-Key", "it-rep"))
                .when().get("/payments/report")
                .then().statusCode(200)
                .header("Idempotent-Replayed", equalTo("true"))
                .body(equalTo(first));
    }

    @Test
    void differentPayloadSameKeyRejected() {
        given().header(new Header("Idempotency-Key", "it-2"))
                .contentType("application/json").body("{\"amount\":1}")
                .when().post("/payments/charge").then().statusCode(201);

        given().header(new Header("Idempotency-Key", "it-2"))
                .contentType("application/json").body("{\"amount\":2}")
                .when().post("/payments/charge").then().statusCode(422);
    }
}
