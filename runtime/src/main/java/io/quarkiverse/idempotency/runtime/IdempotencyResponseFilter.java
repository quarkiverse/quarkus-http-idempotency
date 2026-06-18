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

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Flow;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.container.ContainerResponseFilter;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.StreamingOutput;
import jakarta.ws.rs.ext.Provider;

import org.jboss.logging.Logger;

import io.quarkiverse.idempotency.runtime.metrics.IdempotencyMetrics;
import io.quarkiverse.idempotency.runtime.spi.IdempotencyStore;
import io.quarkiverse.idempotency.runtime.spi.StoredResponse;
import io.smallrye.mutiny.Uni;
import io.vertx.ext.web.RoutingContext;

/**
 * Captures the response of a request that reserved an idempotency key and stores it for replay. The
 * store write is reactive and fired without blocking the response (the reservation already protects
 * concurrent retries). The active key and fingerprint are read from the Vert.x
 * {@link RoutingContext}; this filter sets {@link IdempotencyRequestFilter#HANDLED_ATTR} so the
 * request filter's end-handler safety net knows completion was handled here.
 *
 * <ul>
 * <li>5xx (when not cached) releases the key so the client can retry.</li>
 * <li>Streaming responses are released by the request filter's end-handler (they bypass this filter);
 * a defensive check here also releases if a streaming entity is ever observed.</li>
 * <li>Responses larger than {@code max-stored-body} are not stored (key released).</li>
 * </ul>
 *
 * <p>
 * Only headers on the configured allow-list are captured, and a hard deny-list of credential-bearing
 * headers is enforced unconditionally so a stored response can never replay another caller's secrets.
 */
@Provider
@ApplicationScoped
public class IdempotencyResponseFilter implements ContainerResponseFilter {

    private static final Logger LOG = Logger.getLogger(IdempotencyResponseFilter.class);

    /** Credential/identity-bearing headers that must never be captured, regardless of config. */
    private static final Set<String> DENIED_HEADERS = Set.of(
            "set-cookie", "set-cookie2", "cookie", "authorization", "proxy-authorization",
            "www-authenticate", "proxy-authenticate");

    /** Name fragments that mark a header as secret-bearing; denied even if not in the literal set. */
    private static final String[] DENIED_FRAGMENTS = { "token", "secret", "api-key", "apikey",
            "password", "passwd", "credential", "private-key" };

    @Inject
    IdempotencyConfig config;

    @Inject
    Instance<IdempotencyStore> store;

    @Inject
    IdempotencyMetrics metrics;

    @Override
    public void filter(ContainerRequestContext requestContext, ContainerResponseContext responseContext) {
        // Read the RoutingContext the request filter captured while the request scope was active.
        // Resolving the @RequestScoped CurrentVertxRequest proxy here is unsafe: on an asynchronous
        // (suspend/resume) store path under concurrency the scope is no longer active and the proxy
        // throws ContextNotActiveException, turning every affected response into a 500.
        RoutingContext rc = (RoutingContext) requestContext.getProperty(IdempotencyRequestFilter.RC_ATTR);
        if (rc == null) {
            return;
        }
        String key = (String) rc.get(IdempotencyRequestFilter.KEY_ATTR);
        if (key == null) {
            return;
        }
        // Tell the request filter's end-handler that completion was handled here (not a stream).
        rc.put(IdempotencyRequestFilter.HANDLED_ATTR, Boolean.TRUE);
        String fingerprint = (String) rc.get(IdempotencyRequestFilter.FINGERPRINT_ATTR);

        int status = responseContext.getStatus();
        if (status >= 500 && !config.cacheErrorResponses()) {
            release(key);
            return;
        }

        if (isStreaming(responseContext)) {
            LOG.debug("Streaming response on an idempotent request; not caching (key released)");
            release(key);
            return;
        }

        long limit = config.maxStoredBody().asLongValue();
        if (limit > 0) {
            Long size = measuredBodySize(responseContext);
            if (size != null && size > limit) {
                LOG.debugf("Response body (%s bytes) exceeds max-stored-body (%s); not caching",
                        size, Long.valueOf(limit));
                release(key);
                return;
            }
        }

        Map<String, String> headers = capturedHeaders(responseContext);
        String mediaType = responseContext.getMediaType() != null
                ? responseContext.getMediaType().toString()
                : null;

        Object ttlAttr = rc.get(IdempotencyRequestFilter.TTL_ATTR);
        Duration ttl = ttlAttr instanceof Duration d ? d : config.responseTtl();
        fireComplete(key, store.get().complete(key, fingerprint,
                new StoredResponse(status, headers, responseContext.getEntity(), mediaType),
                ttl));
    }

    /**
     * Release a reserved key whose response will not be stored (5xx, streaming, or oversize body), so
     * the client can retry. Records the release for observability and frees the key asynchronously.
     */
    private void release(String key) {
        metrics.onReleased();
        fire(store.get().release(key));
    }

    /**
     * Subscribe to a reactive store release without blocking the response. The reservation made at
     * acquire time already guards concurrent retries, so the release does not need to complete before
     * the response is sent; failures are logged.
     */
    private void fire(Uni<Void> release) {
        release.subscribe().with(ignored -> {
        }, failure -> LOG.error("Failed to release idempotency key", failure));
    }

    /**
     * Persist the captured response. On success the response becomes replayable; on failure the
     * in-flight reservation is released so a failed persist does not leave the key 409-blocking
     * retries until the lock TTL expires.
     */
    private void fireComplete(String key, Uni<Void> write) {
        write.subscribe().with(
                ignored -> metrics.onStored(),
                failure -> {
                    LOG.error("Failed to persist idempotency state", failure);
                    metrics.onStoreError();
                    fire(store.get().release(key));
                });
    }

    /** A streamed body cannot be buffered for replay (reactive publisher, SSE, or StreamingOutput). */
    private static boolean isStreaming(ContainerResponseContext responseContext) {
        MediaType mt = responseContext.getMediaType();
        if (mt != null && "text".equalsIgnoreCase(mt.getType()) && "event-stream".equalsIgnoreCase(mt.getSubtype())) {
            return true;
        }
        Object entity = responseContext.getEntity();
        return entity instanceof Flow.Publisher || entity instanceof StreamingOutput;
    }

    private Map<String, String> capturedHeaders(ContainerResponseContext responseContext) {
        Map<String, String> headers = new HashMap<>();
        for (String name : config.capturedHeaders()) {
            if (name == null || name.isBlank() || isDenied(name)) {
                continue;
            }
            String value = responseContext.getHeaderString(name);
            if (value != null) {
                headers.put(name, value);
            }
        }
        return headers;
    }

    /** A header is denied if it is a known credential header or its name embeds a secret fragment. */
    private static boolean isDenied(String name) {
        String normalized = name.trim().toLowerCase(Locale.ROOT);
        if (DENIED_HEADERS.contains(normalized)) {
            return true;
        }
        for (String fragment : DENIED_FRAGMENTS) {
            if (normalized.contains(fragment)) {
                return true;
            }
        }
        return false;
    }

    /** Best-effort body size in bytes, or {@code null} when it cannot be determined cheaply. */
    private static Long measuredBodySize(ContainerResponseContext responseContext) {
        if (responseContext.getLength() >= 0) {
            return (long) responseContext.getLength();
        }
        Object entity = responseContext.getEntity();
        if (entity instanceof byte[] bytes) {
            return (long) bytes.length;
        }
        if (entity instanceof CharSequence text) {
            return (long) text.toString().getBytes(StandardCharsets.UTF_8).length;
        }
        return null;
    }
}
