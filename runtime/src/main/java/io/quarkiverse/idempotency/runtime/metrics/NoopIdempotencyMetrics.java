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

import io.quarkus.arc.DefaultBean;

/**
 * Default {@link IdempotencyMetrics} used when no metrics backend is present. Every hook is a no-op,
 * so the filters carry no conditional logic. Overridden by {@code MicrometerIdempotencyMetrics} when
 * {@code quarkus-micrometer} is on the classpath.
 */
@DefaultBean
@ApplicationScoped
public class NoopIdempotencyMetrics implements IdempotencyMetrics {

    @Override
    public void onFresh() {
    }

    @Override
    public void onReplay() {
    }

    @Override
    public void onConflict() {
    }

    @Override
    public void onMismatch() {
    }

    @Override
    public void onStored() {
    }

    @Override
    public void onReleased() {
    }

    @Override
    public void onStoreError() {
    }
}
