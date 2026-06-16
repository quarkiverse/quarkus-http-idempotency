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

import java.time.Duration;
import java.util.List;
import java.util.Optional;

import io.quarkus.runtime.annotations.ConfigPhase;
import io.quarkus.runtime.annotations.ConfigRoot;
import io.quarkus.runtime.configuration.MemorySize;
import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

/**
 * Runtime configuration for the HTTP idempotency extension.
 */
@ConfigMapping(prefix = "quarkus.idempotency")
@ConfigRoot(phase = ConfigPhase.RUN_TIME)
public interface IdempotencyConfig {

    /**
     * Whether the idempotency filter is active.
     *
     * @return {@code true} to enable idempotency handling
     */
    @WithDefault("true")
    boolean enabled();

    /**
     * Name of the request header carrying the idempotency key.
     *
     * @return the header name
     */
    @WithDefault("Idempotency-Key")
    String headerName();

    /**
     * HTTP methods subject to idempotency when the key header is present. Replaying a stored
     * response for a safe/naturally-idempotent method would mask fresh data, so only unsafe
     * methods belong here.
     *
     * @return the methods to guard
     */
    @WithDefault("POST,PATCH")
    List<String> methods();

    /**
     * How endpoints are selected for idempotency.
     *
     * <ul>
     * <li>{@code all-methods} (default): every request using a guarded {@link #methods()} is handled;
     * an {@link Idempotent} annotation can opt individual endpoints in or out.</li>
     * <li>{@code annotated}: only endpoints carrying {@link Idempotent} are handled, regardless of
     * HTTP method.</li>
     * </ul>
     *
     * @return the endpoint selection strategy
     */
    @WithDefault("all-methods")
    Strategy strategy();

    /** Endpoint selection strategy for {@link #strategy()}. */
    enum Strategy {
        /** Guard every request using a method in {@link IdempotencyConfig#methods()}. */
        ALL_METHODS,
        /** Guard only endpoints annotated with {@link Idempotent}. */
        ANNOTATED
    }

    /**
     * Whether a guarded request MUST carry the key header. When {@code true}, a matching request
     * without a valid key is rejected with HTTP 400.
     *
     * @return {@code true} to require the key on guarded methods
     */
    @WithDefault("false")
    boolean requireKey();

    /**
     * Maximum accepted length of an idempotency key. Longer keys are rejected with HTTP 400.
     *
     * @return the maximum key length
     */
    @WithDefault("255")
    int maxKeyLength();

    /**
     * Whether a guarded, keyed request MUST come from an authenticated principal. When {@code true},
     * a request carrying the key header but no authenticated identity is rejected with HTTP 401.
     * This closes the cross-caller replay window on anonymous traffic: with no identity to scope by,
     * all anonymous callers would otherwise share one namespace. Leave {@code false} only when
     * idempotent endpoints are intentionally anonymous and their responses carry no per-caller data.
     *
     * @return {@code true} to require an authenticated identity on keyed requests
     */
    @WithDefault("false")
    boolean requireIdentity();

    /**
     * Optional request header whose value adds a second isolation dimension (e.g. a tenant id) to
     * the storage namespace, on top of the authenticated principal. Use this for multi-tenant
     * deployments where the tenant is carried in a trusted, gateway-validated header. When unset,
     * scoping is by principal only.
     *
     * @return the name of the tenant/scope header, if any
     */
    Optional<String> scopeHeader();

    /**
     * Maximum number of entries the in-memory store retains. Acts as a hard memory ceiling: once
     * reached, the least-recently-used entries are evicted. Has no effect on the {@code redis} store.
     *
     * @return the maximum number of in-memory entries
     */
    @WithDefault("100000")
    int maxEntries();

    /**
     * Maximum size of a response body that will be stored for replay. Responses larger than this are
     * not cached (the request passes through and the key is released), bounding per-entry memory.
     * Measured from {@code Content-Length} or a {@code byte[]}/{@code String} entity; entities whose
     * size cannot be determined cheaply are stored regardless.
     *
     * @return the maximum stored response body size
     */
    @WithDefault("256K")
    MemorySize maxStoredBody();

    /**
     * Maximum number of request-body bytes fed into the payload fingerprint. Caps the CPU and
     * allocation an attacker can drive with a large allowed body; bytes beyond this bound are not
     * hashed (the true body length is still mixed in, so truncation does not cause collisions).
     *
     * @return the fingerprint body byte cap
     */
    @WithDefault("1M")
    MemorySize maxFingerprintBody();

    /**
     * Response headers captured and replayed, as an explicit allow-list. Security-sensitive headers
     * ({@code Set-Cookie}, {@code Authorization}, {@code WWW-Authenticate}, and similar) are always
     * denied even if listed here, so a stored response can never leak credentials on replay.
     *
     * @return the allow-list of response headers to capture
     */
    @WithDefault("Location")
    List<String> capturedHeaders();

    /**
     * How long a completed response remains replayable.
     *
     * @return the response retention duration
     */
    @WithDefault("24h")
    Duration responseTtl();

    /**
     * How long an in-flight reservation is held before it is considered stale (so a crashed
     * request does not block retries forever).
     *
     * @return the in-flight lock duration
     */
    @WithDefault("60s")
    Duration lockTtl();

    /**
     * Whether to fingerprint the request payload. When enabled, reusing a key with a different
     * payload is rejected with HTTP 422; when disabled, the key alone identifies the request.
     *
     * @return {@code true} to fingerprint the payload
     */
    @WithDefault("true")
    boolean fingerprintEnabled();

    /**
     * Whether to store and replay 5xx responses. When {@code false} (the default), a server error
     * releases the key so the client can genuinely retry a transient failure.
     *
     * @return {@code true} to cache server-error responses
     */
    @WithDefault("false")
    boolean cacheErrorResponses();

    /**
     * Header added to a replayed response so clients and observability can distinguish replays.
     * Set to empty to disable.
     *
     * @return the replay marker header name
     */
    @WithDefault("Idempotent-Replayed")
    String replayedHeader();

    /**
     * Store backend: {@code in-memory} (default, single node) or {@code redis} (distributed,
     * requires {@code quarkus-redis-client} on the classpath).
     *
     * @return the store backend identifier
     */
    @WithDefault("in-memory")
    String store();

    /**
     * Base documentation URI used to build the {@code type} field (and {@code Link} header) of the
     * RFC 9457 {@code application/problem+json} error responses. A per-problem fragment is appended
     * (e.g. {@code #idempotency-key-conflict}) so clients can follow the link to the relevant
     * documentation, as the Idempotency-Key draft recommends.
     *
     * @return the documentation base URI for problem types
     */
    @WithDefault("https://docs.quarkiverse.io/quarkus-http-idempotency/dev/")
    String problemBaseUri();
}
