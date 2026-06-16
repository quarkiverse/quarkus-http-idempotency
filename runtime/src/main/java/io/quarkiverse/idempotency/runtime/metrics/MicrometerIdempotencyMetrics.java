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

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * Micrometer-backed {@link IdempotencyMetrics}. Registered only when {@code quarkus-micrometer} is on
 * the classpath, so it can reference Micrometer types unconditionally and a {@code MeterRegistry} is
 * always available. Counters are resolved once at construction.
 *
 * <p>
 * Request outcomes share one counter {@code idempotency.requests} tagged by {@code outcome}
 * (fresh / replay / conflict / mismatch) to keep cardinality bounded; persistence and store failures
 * are separate counters.
 */
@ApplicationScoped
public class MicrometerIdempotencyMetrics implements IdempotencyMetrics {

    private static final String REQUESTS = "idempotency.requests";

    private final Counter fresh;
    private final Counter replay;
    private final Counter conflict;
    private final Counter mismatch;
    private final Counter stored;
    private final Counter storeErrors;

    @Inject
    public MicrometerIdempotencyMetrics(MeterRegistry registry) {
        this.fresh = outcome(registry, "fresh");
        this.replay = outcome(registry, "replay");
        this.conflict = outcome(registry, "conflict");
        this.mismatch = outcome(registry, "mismatch");
        this.stored = Counter.builder("idempotency.entries.stored")
                .description("Responses captured and persisted for replay")
                .register(registry);
        this.storeErrors = Counter.builder("idempotency.store.errors")
                .description("Idempotency store operation failures")
                .register(registry);
    }

    private static Counter outcome(MeterRegistry registry, String value) {
        return Counter.builder(REQUESTS)
                .description("Idempotency-guarded requests by resolved outcome")
                .tag("outcome", value)
                .register(registry);
    }

    @Override
    public void onFresh() {
        fresh.increment();
    }

    @Override
    public void onReplay() {
        replay.increment();
    }

    @Override
    public void onConflict() {
        conflict.increment();
    }

    @Override
    public void onMismatch() {
        mismatch.increment();
    }

    @Override
    public void onStored() {
        stored.increment();
    }

    @Override
    public void onStoreError() {
        storeErrors.increment();
    }
}
