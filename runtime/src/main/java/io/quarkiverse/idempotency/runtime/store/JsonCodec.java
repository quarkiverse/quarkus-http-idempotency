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

/**
 * JSON rendering for the out-of-process stores, over whichever Jackson the application has: Jackson 2
 * ({@code com.fasterxml.jackson}, Quarkus 3) or Jackson 3 ({@code tools.jackson}, Quarkus 4). The
 * extension registers the implementation matching the classpath, so the stores never reference a
 * Jackson type directly. The application's own mapper is used, so a replayed JSON entity is rendered
 * exactly as the live response would have been.
 */
public interface JsonCodec {

    byte[] write(Object value) throws IOException;

    <T> T read(byte[] json, Class<T> type) throws IOException;
}
