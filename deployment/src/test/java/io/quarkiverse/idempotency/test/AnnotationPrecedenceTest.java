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

/** A class-level {@code @Idempotent} guards every method, and a method-level annotation overrides it. */
public class AnnotationPrecedenceTest {

    @RegisterExtension
    static final QuarkusUnitTest TEST = new QuarkusUnitTest()
            .withApplicationRoot(jar -> jar.addClass(ClassAnnotatedResource.class));

    @Test
    void classLevelGuardsUnannotatedMethod() {
        // GET is not globally guarded; the class-level annotation opts the unannotated method in.
        String first = given().header(new Header("Idempotency-Key", "ci1"))
                .when().get("/cls/inherited")
                .then().statusCode(200).header("Idempotent-Replayed", nullValue())
                .extract().asString();

        given().header(new Header("Idempotency-Key", "ci1"))
                .when().get("/cls/inherited")
                .then().statusCode(200)
                .header("Idempotent-Replayed", equalTo("true"))
                .body(equalTo(first));
    }

    @Test
    void methodLevelOverridesClassLevel() {
        // The method-level @Idempotent(enabled=false) wins over the class-level opt-in: never replays.
        given().header(new Header("Idempotency-Key", "co1")).when().get("/cls/overridden")
                .then().statusCode(200).header("Idempotent-Replayed", nullValue());
        given().header(new Header("Idempotency-Key", "co1")).when().get("/cls/overridden")
                .then().statusCode(200).header("Idempotent-Replayed", nullValue());
    }
}
