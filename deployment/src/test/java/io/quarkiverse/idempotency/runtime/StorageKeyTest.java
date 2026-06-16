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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import org.junit.jupiter.api.Test;

/**
 * SEC-01 unit coverage: the storage key must isolate callers and resist boundary-shifting collisions.
 * Lives in the runtime package to reach the package-private {@link StorageKey}.
 */
class StorageKeyTest {

    @Test
    void sameInputsAreStable() {
        assertEquals(StorageKey.derive("alice", "t1", "order-1"),
                StorageKey.derive("alice", "t1", "order-1"));
    }

    @Test
    void differentPrincipalIsolatesNamespace() {
        assertNotEquals(StorageKey.derive("alice", "", "order-1"),
                StorageKey.derive("bob", "", "order-1"));
    }

    @Test
    void differentTenantIsolatesNamespace() {
        assertNotEquals(StorageKey.derive("alice", "t1", "order-1"),
                StorageKey.derive("alice", "t2", "order-1"));
    }

    @Test
    void anonymousIsItsOwnNamespaceNotCollidingWithKeyPrefix() {
        // Length-framing must prevent principal "a"+key "b" from colliding with principal ""+key "ab".
        assertNotEquals(StorageKey.derive("a", "", "b"),
                StorageKey.derive("", "", "ab"));
    }

    @Test
    void boundaryShiftBetweenScopeAndKeyDoesNotCollide() {
        assertNotEquals(StorageKey.derive("u", "tenant", "X"),
                StorageKey.derive("u", "tena", "ntX"));
    }
}
