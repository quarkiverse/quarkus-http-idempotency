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
package io.quarkiverse.idempotency.test;

import java.time.Duration;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.Assertions;

/**
 * Polls a condition until it holds or a short deadline passes. The store write and release run
 * fire-and-forget after the response is sent, so the metric/release effects of a request are observed
 * shortly after — this returns as soon as the condition is true and fails the test only on timeout.
 */
final class Await {

    private static final Duration TIMEOUT = Duration.ofSeconds(5);
    private static final long POLL_MILLIS = 20;

    private Await() {
    }

    static void until(BooleanSupplier condition, String message) {
        long deadlineNanos = System.nanoTime() + TIMEOUT.toNanos();
        while (System.nanoTime() < deadlineNanos) {
            if (condition.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(POLL_MILLIS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while awaiting: " + message, interrupted);
            }
        }
        Assertions.assertTrue(condition.getAsBoolean(), message);
    }
}
