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

import java.time.Duration;

import io.smallrye.mutiny.Uni;

/**
 * Backing store for idempotency keys. Implementations must make {@link #acquire} atomic: exactly
 * one concurrent caller for a given key may receive {@link Reservation.Acquired}.
 *
 * <p>
 * The API is non-blocking ({@link Uni}-returning) so the filters work on reactive endpoints
 * ({@code Uni}/{@code Multi}) without blocking the event loop. The default implementation is
 * in-memory (single node); a distributed implementation (Redis with {@code SET key val NX GET PX})
 * is provided as an alternative bean. A purely in-memory implementation may compute synchronously
 * and wrap the result with {@link Uni#createFrom()}.
 */
public interface IdempotencyStore {

    /**
     * Atomically reserve a key for an in-flight request, or report the existing entry.
     *
     * @param key the idempotency key
     * @param fingerprint the request payload fingerprint
     * @param lockTtl how long the in-flight reservation is valid
     * @return a {@link Uni} emitting {@link Reservation.Acquired} if reserved, otherwise
     *         {@link Reservation.Existing}
     */
    Uni<Reservation> acquire(String key, String fingerprint, Duration lockTtl);

    /**
     * Persist the final response for a key, replacing the in-flight reservation.
     *
     * @param key the idempotency key
     * @param fingerprint the request payload fingerprint (preserved for mismatch detection on replay)
     * @param response the response to replay on future retries
     * @param ttl how long the response remains replayable
     * @return a {@link Uni} completing when the response has been stored
     */
    Uni<Void> complete(String key, String fingerprint, StoredResponse response, Duration ttl);

    /**
     * Release an in-flight reservation that produced no cacheable response, so the key can be
     * retried.
     *
     * @param key the idempotency key
     * @return a {@link Uni} completing when the reservation has been released
     */
    Uni<Void> release(String key);
}
