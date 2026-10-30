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

import java.io.IOException;

import jakarta.inject.Inject;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * {@link JsonCodec} over the application's Jackson 2 {@link ObjectMapper}; registered when Jackson 3 is
 * not on the classpath (Quarkus 3).
 */
@SuppressWarnings("unused")
public class Jackson2JsonCodec implements JsonCodec {

    private final ObjectMapper mapper;

    @Inject
    public Jackson2JsonCodec(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public byte[] write(Object value) throws IOException {
        return mapper.writeValueAsBytes(value);
    }

    @Override
    public <T> T read(byte[] json, Class<T> type) throws IOException {
        return mapper.readValue(json, type);
    }
}
