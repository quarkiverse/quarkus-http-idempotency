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
package io.quarkiverse.idempotency.runtime.metrics;

/**
 * Observability hooks for idempotency outcomes. Implemented by a no-op bean by default and by a
 * Micrometer-backed bean when {@code quarkus-micrometer} is on the classpath, so the filters can
 * record metrics without a hard dependency on (or null checks for) a {@code MeterRegistry}.
 */
public interface IdempotencyMetrics {

    /** A new key was reserved and the request proceeded (a fresh operation). */
    void onFresh();

    /** A completed response was replayed for a repeated key. */
    void onReplay();

    /** A request arrived while another with the same key was still in flight (409). */
    void onConflict();

    /** A key was reused with a different payload fingerprint (422). */
    void onMismatch();

    /** A response was captured and persisted for future replay. */
    void onStored();

    /**
     * A reserved key was released without storing a response — a 5xx (when error responses are not
     * cached), a streaming response, or a body over {@code max-stored-body}. The operation ran but is
     * not replayable, so the client may retry.
     */
    void onReleased();

    /** A store operation failed. */
    void onStoreError();
}
