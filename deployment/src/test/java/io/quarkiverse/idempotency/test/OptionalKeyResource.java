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

import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

import io.quarkiverse.idempotency.runtime.Idempotent;

/** Exercises {@code @Idempotent(requireKey = OPTIONAL)} overriding a global {@code require-key=true}. */
@Path("/opt")
@Produces(MediaType.TEXT_PLAIN)
public class OptionalKeyResource {

    /** Opts out of the global key requirement: a missing key passes through instead of 400. */
    @POST
    @Path("/loose")
    @Idempotent(requireKey = Idempotent.Require.OPTIONAL)
    public String loose() {
        return "loose";
    }

    /** No annotation: inherits the global require-key=true, so a missing key is rejected. */
    @POST
    @Path("/strict")
    public String strict() {
        return "strict";
    }
}
