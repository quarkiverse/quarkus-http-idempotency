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

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Build-time-resolved {@link Idempotent} policies, keyed by resource method and class. Populated once
 * at static init by the recorder (so it is fixed in native images) and read-only thereafter, so the
 * request filter can resolve an endpoint's policy with a plain map lookup — no runtime reflection on
 * annotations.
 */
public final class IdempotencyMethodRegistry {

    /** Resolved per-endpoint policy. {@code ttlMillis <= 0} means "inherit the global response TTL". */
    public record MethodPolicy(boolean enabled, Idempotent.Require requireKey, long ttlMillis) {
    }

    private static final Map<String, MethodPolicy> METHODS = new ConcurrentHashMap<>();
    private static final Map<String, MethodPolicy> CLASSES = new ConcurrentHashMap<>();

    private IdempotencyMethodRegistry() {
    }

    /** Method identity stable across build-time (Jandex) and runtime (reflection): class#name/arity. */
    static String methodId(String declaringClass, String methodName, int parameterCount) {
        return declaringClass + "#" + methodName + "/" + parameterCount;
    }

    public static void registerMethod(String declaringClass, String methodName, int parameterCount,
            MethodPolicy policy) {
        METHODS.put(methodId(declaringClass, methodName, parameterCount), policy);
    }

    public static void registerClass(String className, MethodPolicy policy) {
        CLASSES.put(className, policy);
    }

    /** True when at least one {@link Idempotent} annotation was found (lets the filter skip lookups). */
    public static boolean isEmpty() {
        return METHODS.isEmpty() && CLASSES.isEmpty();
    }

    /**
     * Resolve the effective policy for a matched resource method: the method-level annotation wins,
     * else the resource class's annotation, else {@code null} (no annotation → global behaviour).
     */
    public static MethodPolicy resolve(String declaringClass, String methodName, int parameterCount,
            String resourceClass) {
        MethodPolicy method = METHODS.get(methodId(declaringClass, methodName, parameterCount));
        if (method != null) {
            return method;
        }
        MethodPolicy onClass = CLASSES.get(resourceClass);
        if (onClass != null) {
            return onClass;
        }
        return CLASSES.get(declaringClass);
    }
}
