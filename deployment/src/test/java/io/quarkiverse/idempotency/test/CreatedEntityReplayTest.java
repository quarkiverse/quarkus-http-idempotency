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
 * Replay of a {@code Response} whose entity is a POJO and whose media type comes from
 * {@code @Produces} instead of an explicit {@code .type(...)}. Such a response reaches the response
 * filter with a null media type, and must still replay as JSON rather than as the entity's
 * {@code toString()}.
 */
public class CreatedEntityReplayTest {

    @RegisterExtension
    static final QuarkusUnitTest TEST = new QuarkusUnitTest()
            .withApplicationRoot(jar -> jar.addClasses(CreatedEntityResource.class, CreatedEntityResource.Charge.class))
            .withConfigurationResource("jdbc-store.properties");

    private static final String BODY = "{\"amount\":1000}";

    @Test
    void replayOfAPojoEntityIsStillJson() {
        String first = given()
                .header(new Header("Idempotency-Key", "created-A"))
                .contentType("application/json").body(BODY)
                .when().post("/charges")
                .then().statusCode(201)
                .header("Idempotent-Replayed", nullValue())
                .contentType("application/json")
                .body("id", equalTo(1))
                .body("status", equalTo("SETTLED"))
                .extract().asString();

        given()
                .header(new Header("Idempotency-Key", "created-A"))
                .contentType("application/json").body(BODY)
                .when().post("/charges")
                .then().statusCode(201)
                .header("Idempotent-Replayed", equalTo("true"))
                .contentType("application/json")
                .body("id", equalTo(1))
                .body("status", equalTo("SETTLED"))
                .body(equalTo(first));
    }
}
