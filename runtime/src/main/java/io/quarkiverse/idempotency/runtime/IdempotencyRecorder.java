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
package io.quarkiverse.idempotency.runtime;

import io.quarkiverse.idempotency.runtime.IdempotencyMethodRegistry.MethodPolicy;
import io.quarkus.runtime.annotations.Recorder;

/**
 * Records the {@link Idempotent} policies discovered at build time into the
 * {@link IdempotencyMethodRegistry}. The recorded calls replay at static init (in JVM and native),
 * so no annotation is reflected on at runtime.
 */
@Recorder
public class IdempotencyRecorder {

    public void registerMethod(String declaringClass, String methodName, int parameterCount,
            boolean enabled, Idempotent.Require requireKey, long ttlMillis) {
        IdempotencyMethodRegistry.registerMethod(declaringClass, methodName, parameterCount,
                new MethodPolicy(enabled, requireKey, ttlMillis));
    }

    public void registerClass(String className, boolean enabled, Idempotent.Require requireKey, long ttlMillis) {
        IdempotencyMethodRegistry.registerClass(className,
                new MethodPolicy(enabled, requireKey, ttlMillis));
    }
}
