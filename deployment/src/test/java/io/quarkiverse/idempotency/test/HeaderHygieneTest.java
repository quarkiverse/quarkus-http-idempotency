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
 * SEC-03 regression: even if a sensitive header is (mis)configured into the capture allow-list, the
 * hard deny-list must keep it out of the stored response, so a replay never leaks credentials.
 */
public class HeaderHygieneTest {

    @RegisterExtension
    static final QuarkusUnitTest TEST = new QuarkusUnitTest()
            .withApplicationRoot(jar -> jar.addClass(HeaderHygieneResource.class))
            // Operator mistakenly tries to capture credential headers; deny-list must override.
            .overrideConfigKey("quarkus.idempotency.captured-headers",
                    "X-Custom,Set-Cookie,Authorization,X-Auth-Token");

    @Test
    void sensitiveHeadersAreNeverReplayed() {
        given().header(new Header("Idempotency-Key", "hdr-key"))
                .when().post("/secure/charge")
                .then().statusCode(200);

        // Replay: benign captured header is present; denied credential headers are gone.
        given().header(new Header("Idempotency-Key", "hdr-key"))
                .when().post("/secure/charge")
                .then().statusCode(200)
                .header("Idempotent-Replayed", equalTo("true"))
                .header("X-Custom", equalTo("custom-1"))
                .header("Set-Cookie", nullValue())
                .header("Authorization", nullValue())
                .header("X-Auth-Token", nullValue());
    }
}
