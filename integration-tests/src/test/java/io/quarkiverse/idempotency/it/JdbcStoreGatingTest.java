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
package io.quarkiverse.idempotency.it;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;

import io.quarkiverse.idempotency.runtime.spi.IdempotencyStore;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;

/**
 * Regression test for the JDBC store gating.
 *
 * <p>
 * The deployment processor registers the JDBC store bean based on the {@code io.quarkus.agroal}
 * capability. An earlier version gated on an {@code io.quarkus.jdbc.<db>} capability, which only
 * {@code quarkus-jdbc-h2} registers — no production driver (PostgreSQL, MySQL, MariaDB, SQL Server,
 * Oracle, Db2) does. That made {@code quarkus.idempotency.store=jdbc} fail at startup with
 * {@code No bean found for IdempotencyStore} for every real database, while the H2-only test suite
 * kept passing and hid the defect.
 *
 * <p>
 * This module has no H2 on its classpath; the profile puts only {@code quarkus-agroal} there (the
 * capability every JDBC driver contributes). If the gating ever regresses to requiring an
 * {@code io.quarkus.jdbc} capability, the JDBC store bean is not registered, {@code store.get()} in
 * {@code IdempotencyStartup} throws, the application does not boot, and this test fails.
 */
@QuarkusTest
@TestProfile(JdbcStoreGatingTest.AgroalOnlyJdbcProfile.class)
class JdbcStoreGatingTest {

    @Inject
    Instance<IdempotencyStore> store;

    @Test
    void jdbcStoreIsRegisteredFromTheAgroalCapabilityAlone() {
        assertTrue(store.isResolvable(),
                "with quarkus.idempotency.store=jdbc and Agroal present, the JDBC store must be a "
                        + "registered bean — gating must not require an io.quarkus.jdbc.<db> capability "
                        + "that only quarkus-jdbc-h2 provides");
        assertTrue(store.get().getClass().getName().contains("Jdbc"),
                "the selected store must be the JDBC store, was " + store.get().getClass().getName());
    }

    public static class AgroalOnlyJdbcProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("quarkus.idempotency.store", "jdbc");
        }
    }
}
