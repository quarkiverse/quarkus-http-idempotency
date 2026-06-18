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

/** With a global {@code require-key=true}, {@code @Idempotent(requireKey = OPTIONAL)} relaxes it per endpoint. */
public class OptionalRequireKeyTest {

    @RegisterExtension
    static final QuarkusUnitTest TEST = new QuarkusUnitTest()
            .withApplicationRoot(jar -> jar.addClass(OptionalKeyResource.class))
            .overrideConfigKey("quarkus.idempotency.require-key", "true");

    @Test
    void optionalOverridesGlobalRequireKey() {
        // OPTIONAL beats the global require-key=true: a missing key passes through instead of 400.
        given().when().post("/opt/loose").then().statusCode(200);
    }

    @Test
    void globalRequireKeyStillAppliesWithoutOverride() {
        // The unannotated endpoint inherits require-key=true: a missing key is rejected.
        given().when().post("/opt/strict").then().statusCode(400);
    }
}
