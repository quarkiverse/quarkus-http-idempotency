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

import java.time.temporal.ChronoUnit;
import java.util.concurrent.atomic.AtomicInteger;

import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

import io.quarkiverse.idempotency.runtime.Idempotent;

/** Endpoints driven against {@link ControllableStore} to exercise conflict, store-failure, and TTL override. */
@Path("/ctl")
@Produces(MediaType.TEXT_PLAIN)
public class ControllableResource {

    static final AtomicInteger OP_RUNS = new AtomicInteger();

    @POST
    @Path("/op")
    public String op() {
        return "op#" + OP_RUNS.incrementAndGet();
    }

    /** Per-endpoint TTL override; the store records the resolved {@code Duration} passed to complete. */
    @POST
    @Path("/ttl")
    @Idempotent(ttl = 2, ttlUnit = ChronoUnit.HOURS)
    public String ttl() {
        return "ttl";
    }
}
