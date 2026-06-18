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

import java.util.concurrent.atomic.AtomicInteger;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

import io.quarkiverse.idempotency.runtime.Idempotent;

/**
 * Class-level {@link Idempotent} opting every method in (both GETs, not globally guarded), with one
 * method overriding the class policy. Exercises class-level application and method-over-class precedence.
 */
@Path("/cls")
@Produces(MediaType.TEXT_PLAIN)
@Idempotent
public class ClassAnnotatedResource {

    static final AtomicInteger INHERITED_RUNS = new AtomicInteger();
    static final AtomicInteger OVERRIDDEN_RUNS = new AtomicInteger();

    /** No method annotation: guarded by the class-level policy. */
    @GET
    @Path("/inherited")
    public String inherited() {
        return "inherited#" + INHERITED_RUNS.incrementAndGet();
    }

    /** Method-level opt-out wins over the class-level opt-in. */
    @GET
    @Path("/overridden")
    @Idempotent(enabled = false)
    public String overridden() {
        return "overridden#" + OVERRIDDEN_RUNS.incrementAndGet();
    }
}
