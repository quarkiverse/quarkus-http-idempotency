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

import io.vertx.core.buffer.Buffer;

/**
 * Computes a stable fingerprint of a request so that reusing an idempotency key for a different
 * payload can be detected. The fingerprint covers the method, a normalized request path, the query
 * string, and the request body bytes (capped). Each component is length-framed so two requests
 * cannot be made to collide by shifting bytes across the path/query/body boundaries.
 */
final class Fingerprint {

    private Fingerprint() {
    }

    /**
     * @param method the HTTP method (null treated as empty)
     * @param path the request path (null treated as empty; normalized before hashing)
     * @param query the raw query string (null treated as empty)
     * @param body the request body, or {@code null}
     * @param maxBodyBytes upper bound on body bytes hashed; {@code <= 0} means unbounded
     */
    static String compute(String method, String path, String query, Buffer body, long maxBodyBytes) {
        MessageDigest digest = sha256();
        updateFramed(digest, (method == null ? "" : method).getBytes(StandardCharsets.UTF_8));
        updateFramed(digest, normalizePath(path).getBytes(StandardCharsets.UTF_8));
        updateFramed(digest, (query == null ? "" : query).getBytes(StandardCharsets.UTF_8));
        updateBody(digest, body, maxBodyBytes);
        return HexFormat.of().formatHex(digest.digest());
    }

    private static void updateBody(MessageDigest digest, Buffer body, long maxBodyBytes) {
        int total = (body == null) ? 0 : body.length();
        // Mix in the true body length first, so a truncated hash of a long body cannot collide with
        // the full hash of a short body that happens to share a prefix.
        digest.update(longToBytes(total));
        if (total <= 0) {
            return;
        }
        int hashed = (maxBodyBytes > 0 && total > maxBodyBytes) ? (int) maxBodyBytes : total;
        // getBytes(start, end) copies only the capped slice, bounding allocation to maxBodyBytes.
        digest.update(body.getBytes(0, hashed));
    }

    /**
     * Light RFC-3986-style normalization: collapse runs of {@code /} and drop a trailing slash
     * (except the root). Reduces false 422s when a proxy or client varies only cosmetically. Full
     * dot-segment resolution is intentionally left to the proxy/container.
     */
    static String normalizePath(String path) {
        if (path == null || path.isEmpty()) {
            return "/";
        }
        StringBuilder sb = new StringBuilder(path.length());
        boolean lastSlash = false;
        for (int i = 0; i < path.length(); i++) {
            char c = path.charAt(i);
            if (c == '/') {
                if (!lastSlash) {
                    sb.append('/');
                }
                lastSlash = true;
            } else {
                sb.append(c);
                lastSlash = false;
            }
        }
        int len = sb.length();
        if (len > 1 && sb.charAt(len - 1) == '/') {
            sb.setLength(len - 1);
        }
        return sb.length() == 0 ? "/" : sb.toString();
    }

    private static void updateFramed(MessageDigest digest, byte[] bytes) {
        digest.update(longToBytes(bytes.length));
        digest.update(bytes);
    }

    private static byte[] longToBytes(long value) {
        byte[] out = new byte[8];
        for (int i = 7; i >= 0; i--) {
            out[i] = (byte) value;
            value >>>= 8;
        }
        return out;
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
