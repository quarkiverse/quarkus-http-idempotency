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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.quarkiverse.idempotency.runtime.spi.StoredEntry;
import io.quarkiverse.idempotency.runtime.spi.StoredResponse;

/**
 * Regression coverage for the out-of-process replay body. A JSON response persisted to an external
 * store (Redis, JDBC) reads back as a plain deserialized object (a {@link Map}, never the original
 * type), so it must be re-rendered to bytes on read or the replay breaks. The in-memory store never
 * serializes, which is why this path had no coverage until now.
 */
class StoredResponsesTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void reRendersAJsonMapBodyToBytes() throws Exception {
        // What Jackson hands back after a JSON round-trip through the store: a Map, not the POJO.
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("orderId", 42);
        body.put("status", "CONFIRMED");
        StoredEntry entry = new StoredEntry("fp",
                new StoredResponse(200, Map.of(), body, "application/json"));

        StoredEntry rendered = StoredResponses.materializeBody(entry, mapper);

        Object entity = rendered.response().entity();
        assertInstanceOf(byte[].class, entity);
        assertArrayEquals(mapper.writeValueAsBytes(body), (byte[]) entity);
    }

    @Test
    void writesAStringBodyVerbatim() {
        // RESTEasy writes a String entity verbatim, so it must not be re-encoded as a JSON string.
        StoredEntry entry = new StoredEntry("fp",
                new StoredResponse(200, Map.of(), "plain text", "application/json"));

        StoredEntry rendered = StoredResponses.materializeBody(entry, mapper);

        Object entity = rendered.response().entity();
        assertInstanceOf(byte[].class, entity);
        assertArrayEquals("plain text".getBytes(StandardCharsets.UTF_8), (byte[]) entity);
    }

    @Test
    void leavesAByteArrayBodyUntouched() {
        byte[] bytes = "already-bytes".getBytes(StandardCharsets.UTF_8);
        StoredEntry entry = new StoredEntry("fp",
                new StoredResponse(200, Map.of(), bytes, "application/json"));

        assertSame(entry, StoredResponses.materializeBody(entry, mapper));
    }

    @Test
    void leavesAnInFlightEntryUntouched() {
        StoredEntry inFlight = new StoredEntry("fp", null);

        assertSame(inFlight, StoredResponses.materializeBody(inFlight, mapper));
    }
}
