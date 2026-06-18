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

import java.lang.reflect.Method;
import java.net.URI;
import java.security.Principal;
import java.time.Duration;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.ResourceInfo;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.SecurityContext;
import jakarta.ws.rs.ext.Provider;

import org.jboss.logging.Logger;
import org.jboss.resteasy.reactive.server.spi.ResteasyReactiveContainerRequestContext;

import io.quarkiverse.httpproblem.HttpProblem;
import io.quarkiverse.idempotency.runtime.metrics.IdempotencyMetrics;
import io.quarkiverse.idempotency.runtime.spi.IdempotencyStore;
import io.quarkiverse.idempotency.runtime.spi.Reservation;
import io.quarkiverse.idempotency.runtime.spi.StoredEntry;
import io.quarkiverse.idempotency.runtime.spi.StoredResponse;
import io.quarkus.vertx.http.runtime.CurrentVertxRequest;
import io.vertx.core.buffer.Buffer;
import io.vertx.ext.web.RequestBody;
import io.vertx.ext.web.RoutingContext;

/**
 * Resolves idempotency for guarded requests carrying the configured key header. Runs after
 * authentication. Synchronous validation (missing/invalid key, anonymous) aborts immediately; the
 * store lookup is asynchronous — the request is {@code suspend()}ed and {@code resume()}d on the
 * reactive store result, so this works on reactive endpoints ({@code Uni}/{@code Multi}) without
 * blocking the event loop.
 *
 * <p>
 * The reservation and the resolved fingerprint travel on the Vert.x {@link RoutingContext} (rather
 * than a CDI request-scoped bean) so they are reliably visible across the async store callback and
 * the response path. A response end-handler is the safety net for streaming responses: those bypass
 * the {@link IdempotencyResponseFilter}, so the handler releases the still-in-flight key on response
 * completion — a streamed body cannot be captured/replayed, so the request is not made idempotent.
 *
 * <ul>
 * <li>New key — reserve it and let the request proceed (the response filter stores the result).</li>
 * <li>Same key, same payload, completed — replay the stored response.</li>
 * <li>Same key, still in flight — 409 Conflict.</li>
 * <li>Same key, different payload — 422 Unprocessable Entity.</li>
 * <li>Required key missing/invalid — 400 Bad Request.</li>
 * <li>Identity required but request is anonymous — 401 Unauthorized.</li>
 * </ul>
 */
@Provider
@Priority(Priorities.HEADER_DECORATOR)
@ApplicationScoped
public class IdempotencyRequestFilter implements ContainerRequestFilter {

    static final int UNPROCESSABLE_ENTITY = 422;

    /** RoutingContext attribute holding the active (acquired) storage key for the response path. */
    static final String KEY_ATTR = "io.quarkiverse.idempotency.key";
    /** RoutingContext attribute holding the request fingerprint for the response path. */
    static final String FINGERPRINT_ATTR = "io.quarkiverse.idempotency.fingerprint";
    /** RoutingContext attribute set once the response filter has handled completion. */
    static final String HANDLED_ATTR = "io.quarkiverse.idempotency.handled";
    /** RoutingContext attribute holding the effective response TTL (may be overridden per method). */
    static final String TTL_ATTR = "io.quarkiverse.idempotency.ttl";
    /**
     * {@link ContainerRequestContext} property holding the {@link RoutingContext} captured while the
     * CDI request scope is still active. The response filter reads it from here instead of resolving
     * the {@code @RequestScoped CurrentVertxRequest} proxy, which is not active when the response
     * filter runs after an asynchronous (suspend/resume) store path under concurrency.
     */
    static final String RC_ATTR = "io.quarkiverse.idempotency.routingContext";

    private static final Logger LOG = Logger.getLogger(IdempotencyRequestFilter.class);

    @Inject
    IdempotencyConfig config;

    @Inject
    Instance<IdempotencyStore> store;

    @Inject
    CurrentVertxRequest currentRequest;

    @Inject
    IdempotencyMetrics metrics;

    @Context
    ResourceInfo resourceInfo;

    /** Guarded HTTP methods, upper-cased once so method matching is case-insensitive. */
    private volatile Set<String> guardedMethods;

    @PostConstruct
    void init() {
        guardedMethods = config.methods().stream()
                .map(method -> method.trim().toUpperCase(Locale.ROOT))
                .collect(Collectors.toUnmodifiableSet());
    }

    @Override
    public void filter(ContainerRequestContext requestContext) {
        if (!config.enabled()) {
            return;
        }

        IdempotencyMethodRegistry.MethodPolicy policy = resolvePolicy();
        if (!isGuarded(requestContext.getMethod(), policy)) {
            return;
        }

        String rawKey = requestContext.getHeaderString(config.headerName());
        if (rawKey == null || rawKey.isBlank()) {
            if (effectiveRequireKey(policy)) {
                throw problem(400, "idempotency-key-required", "Idempotency-Key required",
                        "This endpoint requires a " + config.headerName() + " header.");
            }
            return;
        }

        String key = unquote(rawKey.trim());
        if (key.isEmpty() || key.length() > config.maxKeyLength() || hasInvalidChars(key)) {
            throw problem(400, "idempotency-key-invalid", "Invalid Idempotency-Key",
                    "The " + config.headerName() + " header is empty, too long, or contains characters "
                            + "outside printable US-ASCII (0x20-0x7E).");
        }

        String principal = principalName(requestContext.getSecurityContext());
        if (config.requireIdentity() && principal.isEmpty()) {
            throw problem(401, "authentication-required", "Authentication required",
                    "An authenticated identity is required to use " + config.headerName() + ".");
        }

        String scope = scopeValue(requestContext);
        String storageKey = StorageKey.derive(principal, scope, key);
        String fingerprint = config.fingerprintEnabled()
                ? Fingerprint.compute(requestContext.getMethod(), requestContext.getUriInfo().getPath(),
                        rawQuery(requestContext), readBody(), config.maxFingerprintBody().asLongValue())
                : "";

        RoutingContext rc = currentRequest.getCurrent();
        registerStreamingSafetyNet(rc);
        if (rc != null) {
            rc.put(TTL_ATTR, effectiveTtl(policy));
            // Capture the RoutingContext now (request scope is active here) so the response filter
            // can read it without resolving the @RequestScoped proxy after an async resume.
            requestContext.setProperty(RC_ATTR, rc);
        }

        // Asynchronous store lookup: suspend the request and resume on the reactive store result.
        ResteasyReactiveContainerRequestContext rrCtx = (ResteasyReactiveContainerRequestContext) requestContext;
        rrCtx.suspend();
        store.get().acquire(storageKey, fingerprint, config.lockTtl()).subscribe().with(
                reservation -> {
                    try {
                        Response replay = onReservation(reservation, storageKey, fingerprint, rc);
                        if (replay != null) {
                            requestContext.abortWith(replay);
                        }
                        rrCtx.resume();
                    } catch (RuntimeException problem) {
                        rrCtx.resume(problem);
                    }
                },
                failure -> rrCtx.resume(failure));
    }

    /**
     * Releases the key when the response completes without the response filter having handled it —
     * i.e. streaming responses, which bypass {@link IdempotencyResponseFilter}. For normal responses
     * the response filter sets {@link #HANDLED_ATTR} and this no-ops.
     */
    private void registerStreamingSafetyNet(RoutingContext rc) {
        if (rc == null) {
            return;
        }
        rc.addEndHandler(ar -> {
            Object activeKey = rc.get(KEY_ATTR);
            if (activeKey != null && rc.get(HANDLED_ATTR) == null) {
                LOG.debug("Response completed without capture (streaming?); releasing idempotency key");
                metrics.onReleased();
                store.get().release((String) activeKey).subscribe().with(ignored -> {
                }, t -> LOG.debug("Idempotency key release on response end failed", t));
            }
        });
    }

    /** @return a replay {@link Response}, or {@code null} to proceed; throws {@link HttpProblem} to reject. */
    private Response onReservation(Reservation reservation, String storageKey, String fingerprint, RoutingContext rc) {
        if (reservation instanceof Reservation.Acquired) {
            if (rc != null) {
                rc.put(KEY_ATTR, storageKey);
                rc.put(FINGERPRINT_ATTR, fingerprint);
            }
            metrics.onFresh();
            return null;
        }
        StoredEntry entry = ((Reservation.Existing) reservation).entry();
        if (config.fingerprintEnabled() && !entry.fingerprint().equals(fingerprint)) {
            metrics.onMismatch();
            throw problem(UNPROCESSABLE_ENTITY, "idempotency-key-mismatch",
                    "Idempotency-Key reused with a different payload",
                    "The " + config.headerName() + " was already used for a request with a different "
                            + "method, path, query, or body.");
        }
        if (entry.inFlight()) {
            metrics.onConflict();
            throw problem(409, "idempotency-key-conflict", "Request already in progress",
                    "A request with this " + config.headerName() + " is still being processed.");
        }
        metrics.onReplay();
        return buildReplay(entry.response());
    }

    /** Resolve the {@link Idempotent} policy for the matched resource method, or {@code null}. */
    private IdempotencyMethodRegistry.MethodPolicy resolvePolicy() {
        if (IdempotencyMethodRegistry.isEmpty() || resourceInfo == null) {
            return null;
        }
        Method method = resourceInfo.getResourceMethod();
        if (method == null) {
            return null;
        }
        Class<?> resourceClass = resourceInfo.getResourceClass();
        String resourceClassName = resourceClass != null
                ? resourceClass.getName()
                : method.getDeclaringClass().getName();
        return IdempotencyMethodRegistry.resolve(method.getDeclaringClass().getName(), method.getName(),
                method.getParameterCount(), resourceClassName);
    }

    /** Whether this endpoint is guarded: the annotation is authoritative, else the strategy decides. */
    private boolean isGuarded(String httpMethod, IdempotencyMethodRegistry.MethodPolicy policy) {
        if (policy != null) {
            return policy.enabled();
        }
        if (config.strategy() == IdempotencyConfig.Strategy.ANNOTATED) {
            return false;
        }
        return httpMethod != null && guardedMethods.contains(httpMethod.toUpperCase(Locale.ROOT));
    }

    private boolean effectiveRequireKey(IdempotencyMethodRegistry.MethodPolicy policy) {
        if (policy != null && policy.requireKey() != Idempotent.Require.DEFAULT) {
            return policy.requireKey() == Idempotent.Require.REQUIRED;
        }
        return config.requireKey();
    }

    private Duration effectiveTtl(IdempotencyMethodRegistry.MethodPolicy policy) {
        if (policy != null && policy.ttlMillis() > 0) {
            return Duration.ofMillis(policy.ttlMillis());
        }
        return config.responseTtl();
    }

    private String scopeValue(ContainerRequestContext ctx) {
        if (config.scopeHeader().isEmpty()) {
            return "";
        }
        String value = ctx.getHeaderString(config.scopeHeader().get());
        return value == null ? "" : value;
    }

    private static String principalName(SecurityContext securityContext) {
        if (securityContext == null) {
            return "";
        }
        Principal principal = securityContext.getUserPrincipal();
        return principal == null ? "" : principal.getName();
    }

    private static String rawQuery(ContainerRequestContext ctx) {
        return ctx.getUriInfo().getRequestUri().getRawQuery();
    }

    private Buffer readBody() {
        RoutingContext rc = currentRequest.getCurrent();
        if (rc == null) {
            return null;
        }
        RequestBody body = rc.body();
        return body != null ? body.buffer() : null;
    }

    private Response buildReplay(StoredResponse stored) {
        Response.ResponseBuilder rb = Response.status(stored.status());
        if (stored.entity() != null) {
            rb.entity(stored.entity());
        }
        if (stored.mediaType() != null) {
            rb.type(stored.mediaType());
        }
        stored.headers().forEach(rb::header);
        String marker = config.replayedHeader();
        if (marker != null && !marker.isBlank()) {
            rb.header(marker, "true");
        }
        return rb.build();
    }

    /**
     * Builds an RFC 9457 problem for a rejection, rendered by the quarkus-http-problem mapper as
     * {@code application/problem+json}; the {@code type} URI points at the relevant documentation.
     */
    private HttpProblem problem(int status, String slug, String title, String detail) {
        return HttpProblem.builder()
                .withType(URI.create(typeUri(slug)))
                .withTitle(title)
                .withStatus(status)
                .withDetail(detail)
                .build();
    }

    private String typeUri(String slug) {
        String base = config.problemBaseUri();
        if (base == null || base.isBlank()) {
            return "urn:quarkus-http-idempotency:" + slug;
        }
        return base + (base.contains("#") ? "-" : "#") + slug;
    }

    /**
     * Rejects keys that are not an RFC 8941 sf-string: the value must be printable US-ASCII
     * ({@code 0x20}–{@code 0x7E}). Control characters and any non-ASCII byte ({@code >= 0x7F}) are
     * rejected, since the {@code Idempotency-Key} draft defines the value as an sf-string.
     */
    private static boolean hasInvalidChars(String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c < 0x20 || c > 0x7E) {
                return true;
            }
        }
        return false;
    }

    private static String unquote(String value) {
        if (value.length() >= 2 && value.charAt(0) == '"' && value.charAt(value.length() - 1) == '"') {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }
}
