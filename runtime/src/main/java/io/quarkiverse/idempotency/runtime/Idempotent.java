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

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.time.temporal.ChronoUnit;

/**
 * Declarative, per-endpoint control over idempotency, on a JAX-RS resource method or class.
 *
 * <p>
 * The extension discovers this annotation at build time (it never reflects on it at runtime), so it
 * works in native images. It does not change how the key is resolved — the {@code Idempotency-Key}
 * header remains the request identity, and the secure-by-default scoping (per principal + tenant
 * header) still applies. It only tunes the HTTP filter's decision for the annotated endpoint:
 *
 * <ul>
 * <li>Opt an endpoint <em>in</em> even if its HTTP method is not globally guarded (e.g. a {@code GET}
 * or {@code PUT}).</li>
 * <li>Opt an endpoint <em>out</em> with {@code @Idempotent(enabled = false)} even if its method is
 * globally guarded.</li>
 * <li>Override {@link #requireKey()} and the response {@link #ttl()} for that endpoint.</li>
 * </ul>
 *
 * <p>
 * A method-level annotation takes precedence over a class-level one. When
 * {@code quarkus.idempotency.strategy=annotated}, only annotated endpoints are guarded.
 *
 * <p>
 * <strong>Caveats.</strong> The annotation only tunes the filter decision; it never relaxes the
 * per-caller storage scoping, so it cannot leak one caller's response to another. It does, however,
 * carry the same trade-offs the global configuration does, plus a few specific to per-endpoint use:
 *
 * <ul>
 * <li><strong>Not inherited.</strong> Discovery is by direct annotation target, so the annotation must
 * sit on the concrete resource class or method that JAX-RS dispatches to — it is not inherited from a
 * superclass or a resource interface. A class-level annotation applies only to methods declared on
 * that same class.</li>
 * <li><strong>Safe-method opt-in masks content negotiation.</strong> Opting a {@code GET} (or other
 * safe method) in replays the stored representation regardless of the retry's {@code Accept}/
 * {@code Accept-Language}, since those headers are not part of the fingerprint. Use it only where a
 * single representation is served.</li>
 * <li><strong>{@code requireKey = OPTIONAL} overrides a global {@code require-key=true}.</strong> An
 * endpoint can opt out of a global key requirement; review the annotation alongside that setting.</li>
 * <li><strong>{@link #ttl()} can exceed the global {@code response-ttl},</strong> prolonging how long a
 * stored response (and any sensitive data in it) is retained. Keep it within your retention policy.</li>
 * <li><strong>Replays short-circuit in-method authorization.</strong> A replay returns before the
 * resource method runs, so authorization performed inside the method body is not re-evaluated. This is
 * safe for the intended "same caller, same operation" use, but avoid annotating endpoints whose access
 * control is time- or state-sensitive and enforced inside the method — opting a {@code PUT}/
 * {@code DELETE} in is the highest-risk case. See the extension's "Security model" documentation.</li>
 * </ul>
 */
@Target({ ElementType.TYPE, ElementType.METHOD })
@Retention(RetentionPolicy.RUNTIME)
public @interface Idempotent {

    /**
     * Whether idempotency applies to this endpoint. Set {@code false} to exclude an endpoint whose
     * HTTP method would otherwise be guarded.
     *
     * @return {@code true} to guard the endpoint
     */
    boolean enabled() default true;

    /**
     * Whether the {@code Idempotency-Key} header is mandatory on this endpoint. {@link Require#DEFAULT}
     * inherits {@code quarkus.idempotency.require-key}.
     *
     * @return the per-endpoint key requirement
     */
    Require requireKey() default Require.DEFAULT;

    /**
     * Response retention for this endpoint. A value {@code <= 0} inherits
     * {@code quarkus.idempotency.response-ttl}. The unit is {@link #ttlUnit()}.
     *
     * @return the response retention amount, or {@code <= 0} to inherit
     */
    long ttl() default -1;

    /**
     * Unit for {@link #ttl()}.
     *
     * @return the time unit of {@link #ttl()}
     */
    ChronoUnit ttlUnit() default ChronoUnit.SECONDS;

    /** Tri-state override for the key requirement, so "unset" is distinct from "false". */
    enum Require {
        /** Inherit {@code quarkus.idempotency.require-key}. */
        DEFAULT,
        /** Require the key on this endpoint. */
        REQUIRED,
        /** Make the key optional on this endpoint. */
        OPTIONAL
    }
}
