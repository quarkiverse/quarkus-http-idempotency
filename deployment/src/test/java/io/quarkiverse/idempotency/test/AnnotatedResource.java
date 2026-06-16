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
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

import io.quarkiverse.idempotency.runtime.Idempotent;

/** Endpoints exercising {@link Idempotent}: opt-in a GET, opt-out a POST, and require the key. */
@Path("/annotated")
@Produces(MediaType.TEXT_PLAIN)
public class AnnotatedResource {

    static final AtomicInteger GET_RUNS = new AtomicInteger();
    static final AtomicInteger OPTOUT_RUNS = new AtomicInteger();

    /** GET is not globally guarded; the annotation opts it in. */
    @GET
    @Path("/report")
    @Idempotent
    public String report() {
        return "report#" + GET_RUNS.incrementAndGet();
    }

    /** POST is globally guarded; the annotation opts it out. */
    @POST
    @Path("/fire")
    @Idempotent(enabled = false)
    public String fire() {
        return "fire#" + OPTOUT_RUNS.incrementAndGet();
    }

    /** Require the key on this endpoint regardless of the global default. */
    @POST
    @Path("/strict")
    @Idempotent(requireKey = Idempotent.Require.REQUIRED)
    public String strict() {
        return "strict";
    }
}
