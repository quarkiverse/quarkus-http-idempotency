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

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Derives the opaque storage key under which a request's idempotency state is held.
 *
 * <p>
 * The raw client-supplied key is namespaced by the caller's identity — the authenticated principal
 * plus an optional tenant/scope dimension — so two different callers reusing the same raw key can
 * never observe each other's stored response (cross-identity replay). Components are length-framed
 * before hashing, so no choice of identity or key can be crafted to forge another caller's namespace
 * (e.g. principal {@code "a"} + key {@code "b:c"} and principal {@code "a:b"} + key {@code "c"}
 * produce distinct keys). The result is an opaque hex digest, which also keeps human-meaningful raw
 * keys and any identity out of the backing store's key space.
 */
final class StorageKey {

    private StorageKey() {
    }

    static String derive(String principal, String scope, String rawKey) {
        MessageDigest digest = sha256();
        update(digest, principal);
        update(digest, scope);
        update(digest, rawKey);
        return HexFormat.of().formatHex(digest.digest());
    }

    private static void update(MessageDigest digest, String value) {
        byte[] bytes = value == null ? new byte[0] : value.getBytes(StandardCharsets.UTF_8);
        // Big-endian length prefix so component boundaries are unambiguous (frames the field).
        digest.update((byte) (bytes.length >>> 24));
        digest.update((byte) (bytes.length >>> 16));
        digest.update((byte) (bytes.length >>> 8));
        digest.update((byte) bytes.length);
        digest.update(bytes);
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is required by every JVM; this cannot happen.
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
