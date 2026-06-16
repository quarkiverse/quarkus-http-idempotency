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

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import org.eclipse.microprofile.config.ConfigProvider;
import org.jboss.logging.Logger;

import io.quarkiverse.idempotency.runtime.spi.IdempotencyStore;
import io.quarkus.runtime.StartupEvent;

/**
 * Logs the effective idempotency configuration at startup. Surfacing the active store
 * implementation makes a config/packaging mismatch (e.g. {@code store=redis} not actually applied)
 * immediately visible in the logs instead of silently behaving like the in-memory default.
 */
@ApplicationScoped
public class IdempotencyStartup {

    private static final Logger LOG = Logger.getLogger(IdempotencyStartup.class);

    @Inject
    IdempotencyConfig config;

    @Inject
    Instance<IdempotencyStore> store;

    void onStart(@Observes StartupEvent event) {
        if (!config.enabled()) {
            LOG.info("Idempotency disabled (quarkus.idempotency.enabled=false)");
            return;
        }
        validate();
        warnIfIdentityScopingUnreliable();
        LOG.infof("Idempotency active: store=%s (%s), methods=%s, header=%s, response-ttl=%s, "
                + "max-entries=%d, require-identity=%s",
                config.store(),
                implName(store.get()),
                config.methods(),
                config.headerName(),
                config.responseTtl(),
                config.maxEntries(),
                config.requireIdentity());
    }

    /** Fail fast on out-of-range bounds rather than misbehaving silently at request time. */
    private void validate() {
        require(config.maxKeyLength() >= 1 && config.maxKeyLength() <= 8192,
                "quarkus.idempotency.max-key-length must be in [1, 8192], was " + config.maxKeyLength());
        require(config.maxEntries() >= 1,
                "quarkus.idempotency.max-entries must be >= 1, was " + config.maxEntries());
        require(config.maxStoredBody().asLongValue() >= 0,
                "quarkus.idempotency.max-stored-body must be >= 0");
        require(config.maxFingerprintBody().asLongValue() >= 0,
                "quarkus.idempotency.max-fingerprint-body must be >= 0");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }

    /**
     * Per-principal scoping relies on the authenticated identity being resolved before this filter
     * runs. That holds with proactive authentication (the Quarkus default). With it disabled,
     * authentication is lazy and an authenticated caller may still read as anonymous here — silently
     * collapsing distinct callers into the shared anonymous namespace. Warn loudly rather than let
     * the isolation guarantee degrade unnoticed.
     */
    private void warnIfIdentityScopingUnreliable() {
        boolean proactive = ConfigProvider.getConfig()
                .getOptionalValue("quarkus.http.auth.proactive", Boolean.class)
                .orElse(Boolean.TRUE);
        if (!proactive && config.scopeHeader().isEmpty()) {
            LOG.warn("quarkus.http.auth.proactive=false: idempotency keys are scoped by the request "
                    + "principal, but with proactive authentication disabled an authenticated caller "
                    + "may resolve as anonymous in the filter, weakening cross-caller isolation. "
                    + "Enable proactive auth, or set quarkus.idempotency.scope-header to a "
                    + "trusted, gateway-validated tenant header.");
        }
    }

    /** Strips the CDI proxy/subclass suffix so the log shows the real store implementation name. */
    private static String implName(IdempotencyStore store) {
        return store.getClass().getSimpleName().replaceAll("_(ClientProxy|Subclass)$", "");
    }
}
