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
package io.quarkiverse.idempotency.runtime.store;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

import org.jboss.logging.Logger;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.quarkiverse.idempotency.runtime.spi.StoredEntry;
import io.quarkiverse.idempotency.runtime.spi.StoredResponse;

/**
 * Helpers shared by the out-of-process {@link io.quarkiverse.idempotency.runtime.spi.IdempotencyStore}
 * implementations (Redis, JDBC), which persist an entry and read it back as a plain deserialized
 * object rather than the live value the framework produced.
 */
final class StoredResponses {

    private static final Logger LOG = Logger.getLogger(StoredResponses.class);

    private StoredResponses() {
    }

    /**
     * Render a replayed entry's body to a self-contained {@code byte[]} before it reaches the
     * response path. A persisted body deserializes into a live object (e.g. a {@code Map}) that
     * RESTEasy would re-serialize lazily while writing the response. On the asynchronous
     * (suspend/resume) replay path that lazy serialization runs on an event-loop thread, and under
     * concurrent replays of the same key it races on a pooled Netty buffer, surfacing as an empty
     * body (and occasional {@code IllegalReferenceCountException}). Pre-rendering to bytes here, on
     * the read, removes the write-time serialization so each replay writes a fixed, private buffer.
     *
     * <p>
     * Best-effort: any serialization failure degrades to the deserialized entity (the lazy path),
     * never breaks the replay, so the catch is intentionally broad.
     *
     * @param entry the entry read back from the store, or {@code null}
     * @param mapper the JSON mapper used to render a JSON entity
     * @return an equivalent entry whose response body is a {@code byte[]}, or the original entry when
     *         there is nothing to pre-render or rendering failed
     */
    static StoredEntry materializeBody(StoredEntry entry, ObjectMapper mapper) {
        StoredResponse response = entry == null ? null : entry.response();
        if (response == null || response.entity() == null || response.entity() instanceof byte[]) {
            return entry; // in-flight marker, no body, or already bytes
        }
        try {
            Object entity = response.entity();
            String mediaType = response.mediaType();
            byte[] body;
            if (entity instanceof String text) {
                // A String entity is written verbatim by RESTEasy (it is NOT re-encoded as a JSON
                // string even for a JSON producer), so mirror that: take its bytes as-is.
                body = text.getBytes(StandardCharsets.UTF_8);
            } else if (mediaType != null && mediaType.toLowerCase(Locale.ROOT).contains("json")) {
                body = mapper.writeValueAsBytes(entity);
            } else {
                body = String.valueOf(entity).getBytes(StandardCharsets.UTF_8);
            }
            return new StoredEntry(entry.fingerprint(),
                    new StoredResponse(response.status(), response.headers(), body, response.mediaType()));
        } catch (RuntimeException | JsonProcessingException e) {
            LOG.debugf(e, "Could not pre-render replay body; falling back to the deserialized entity");
            return entry;
        }
    }
}
