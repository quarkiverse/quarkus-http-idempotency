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

import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

import org.jboss.resteasy.reactive.RestStreamElementType;

import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;

/**
 * Test app: a non-idempotent "charge" operation whose side effect is counted, so tests can prove
 * the handler runs exactly once across retries with the same idempotency key.
 */
@Path("/payments")
public class PaymentResource {

    static final AtomicInteger EXECUTIONS = new AtomicInteger();

    @POST
    @Path("/charge")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.TEXT_PLAIN)
    public String charge(String body) {
        int n = EXECUTIONS.incrementAndGet();
        return "charged#" + n + ":" + body.length();
    }

    /** Reactive (event-loop) variant — exercises body fingerprinting on a non-blocking endpoint. */
    @POST
    @Path("/charge-reactive")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.TEXT_PLAIN)
    public Uni<String> chargeReactive(String body) {
        int n = EXECUTIONS.incrementAndGet();
        return Uni.createFrom().item("charged#" + n + ":" + body.length());
    }

    /** Streaming (Multi) variant — its response cannot be captured, so it must not be made idempotent. */
    @POST
    @Path("/charge-stream")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @RestStreamElementType(MediaType.TEXT_PLAIN)
    public Multi<String> chargeStream(String body) {
        int n = EXECUTIONS.incrementAndGet();
        return Multi.createFrom().items("a" + n, "b" + n);
    }

    @GET
    @Path("/executions")
    @Produces(MediaType.TEXT_PLAIN)
    public int executions() {
        return EXECUTIONS.get();
    }
}
