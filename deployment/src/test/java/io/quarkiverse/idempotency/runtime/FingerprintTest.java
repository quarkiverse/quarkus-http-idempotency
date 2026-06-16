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
package io.quarkiverse.idempotency.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import org.junit.jupiter.api.Test;

import io.vertx.core.buffer.Buffer;

/** SEC-06 / SEC-10 coverage: query inclusion, path normalization, and bounded body hashing. */
class FingerprintTest {

    private static String fp(String method, String path, String query, String body) {
        return Fingerprint.compute(method, path, query, body == null ? null : Buffer.buffer(body), 0);
    }

    @Test
    void queryStringChangesFingerprint() {
        assertNotEquals(fp("POST", "/orders", "a=1", "{}"),
                fp("POST", "/orders", "a=2", "{}"));
    }

    @Test
    void cosmeticPathDifferencesNormalizeEqual() {
        assertEquals(fp("POST", "/orders", "", "{}"),
                fp("POST", "/orders/", "", "{}"));
        assertEquals(fp("POST", "/orders", "", "{}"),
                fp("POST", "//orders", "", "{}"));
    }

    @Test
    void differentBodyChangesFingerprint() {
        assertNotEquals(fp("POST", "/o", "", "{\"a\":1}"),
                fp("POST", "/o", "", "{\"a\":2}"));
    }

    @Test
    void bodyByteCapDistinguishesByTrueLength() {
        // With a 4-byte cap, two long bodies sharing the first 4 bytes but differing in length must
        // still differ, because the true length is mixed in.
        String a = "AAAABBBB";
        String b = "AAAACCCCDDDD";
        String fa = Fingerprint.compute("POST", "/o", "", Buffer.buffer(a), 4);
        String fb = Fingerprint.compute("POST", "/o", "", Buffer.buffer(b), 4);
        assertNotEquals(fa, fb);
    }
}
