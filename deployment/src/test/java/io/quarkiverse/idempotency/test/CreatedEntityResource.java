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
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/**
 * Test app for the canonical "create a resource" shape: a {@code Response} carrying a POJO entity
 * and a 201, with the media type coming from {@code @Produces} rather than from an explicit
 * {@code .type(...)} on the builder.
 */
@Path("/charges")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class CreatedEntityResource {

    static final AtomicInteger EXECUTIONS = new AtomicInteger();

    public record Charge(int id, String status) {
    }

    @POST
    public Response create(String body) {
        int n = EXECUTIONS.incrementAndGet();
        return Response.status(Response.Status.CREATED)
                .entity(new Charge(n, "SETTLED"))
                .build();
    }
}
