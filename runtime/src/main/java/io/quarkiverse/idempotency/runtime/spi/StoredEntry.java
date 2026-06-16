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
package io.quarkiverse.idempotency.runtime.spi;

import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * The state held for a known idempotency key: the payload fingerprint plus, once the operation
 * has finished, the response to replay. A {@code null} response means the original request is
 * still in flight.
 *
 * @param fingerprint the request payload fingerprint
 * @param response the completed response, or {@code null} while in flight
 */
@RegisterForReflection
public record StoredEntry(String fingerprint, StoredResponse response) {

    /**
     * @return {@code true} when the original request has not yet completed
     */
    public boolean inFlight() {
        return response == null;
    }
}
