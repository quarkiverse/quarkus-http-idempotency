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
package io.quarkiverse.idempotency.deployment;

import java.time.temporal.ChronoUnit;

import org.jboss.jandex.AnnotationInstance;
import org.jboss.jandex.AnnotationTarget;
import org.jboss.jandex.AnnotationValue;
import org.jboss.jandex.DotName;
import org.jboss.jandex.MethodInfo;

import io.quarkiverse.idempotency.runtime.IdempotencyConfig;
import io.quarkiverse.idempotency.runtime.IdempotencyRecorder;
import io.quarkiverse.idempotency.runtime.IdempotencyRequestFilter;
import io.quarkiverse.idempotency.runtime.IdempotencyResponseFilter;
import io.quarkiverse.idempotency.runtime.IdempotencyStartup;
import io.quarkiverse.idempotency.runtime.Idempotent;
import io.quarkiverse.idempotency.runtime.store.InMemoryIdempotencyStore;
import io.quarkus.arc.deployment.AdditionalBeanBuildItem;
import io.quarkus.deployment.annotations.BuildProducer;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.annotations.ExecutionTime;
import io.quarkus.deployment.annotations.Record;
import io.quarkus.deployment.builditem.CombinedIndexBuildItem;
import io.quarkus.deployment.builditem.ConfigMappingBuildItem;
import io.quarkus.deployment.builditem.FeatureBuildItem;
import io.quarkus.resteasy.reactive.spi.ContainerRequestFilterBuildItem;
import io.quarkus.resteasy.reactive.spi.ContainerResponseFilterBuildItem;
import io.quarkus.vertx.http.deployment.RequireBodyHandlerBuildItem;

class IdempotencyProcessor {

    private static final String FEATURE = "quarkus-http-idempotency";

    @BuildStep
    FeatureBuildItem feature() {
        return new FeatureBuildItem(FEATURE);
    }

    @BuildStep
    ConfigMappingBuildItem config() {
        return new ConfigMappingBuildItem(IdempotencyConfig.class, "quarkus.idempotency");
    }

    /**
     * Force a Vert.x body handler on all routes so the request body is fully buffered before the
     * request filter reads it — this is what lets the filter fingerprint the body without a
     * blocking read on reactive endpoints. Gated behind {@code buffer-request-body} so the app-wide
     * buffering can be turned off when body fingerprinting is not needed.
     */
    @BuildStep
    void requireBodyHandler(IdempotencyBuildTimeConfig buildTimeConfig,
            BuildProducer<RequireBodyHandlerBuildItem> producer) {
        if (buildTimeConfig.bufferRequestBody()) {
            producer.produce(new RequireBodyHandlerBuildItem());
        }
    }

    @BuildStep
    void registerFilters(BuildProducer<ContainerRequestFilterBuildItem> requestFilters,
            BuildProducer<ContainerResponseFilterBuildItem> responseFilters) {
        requestFilters.produce(new ContainerRequestFilterBuildItem.Builder(
                IdempotencyRequestFilter.class.getName()).setRegisterAsBean(true).build());
        responseFilters.produce(new ContainerResponseFilterBuildItem.Builder(
                IdempotencyResponseFilter.class.getName()).setRegisterAsBean(true).build());
    }

    @BuildStep
    void beans(BuildProducer<AdditionalBeanBuildItem> beans) {
        AdditionalBeanBuildItem.Builder builder = AdditionalBeanBuildItem.builder()
                .addBeanClass(IdempotencyStartup.class)
                .addBeanClass(InMemoryIdempotencyStore.class)
                // Default no-op metrics; replaced by the Micrometer-backed bean when present.
                .addBeanClass("io.quarkiverse.idempotency.runtime.metrics.NoopIdempotencyMetrics");

        // Register the Redis store only when the redis client is on the classpath, by class name so
        // this processor never loads the redis-dependent class when redis is absent.
        if (isPresent("io.quarkus.redis.datasource.RedisDataSource")) {
            builder.addBeanClass("io.quarkiverse.idempotency.runtime.store.RedisIdempotencyStore");
        }

        // Register the Micrometer-backed metrics only when Micrometer is on the classpath, by class
        // name so the micrometer-dependent class is never loaded when micrometer is absent.
        if (isPresent("io.micrometer.core.instrument.MeterRegistry")) {
            builder.addBeanClass("io.quarkiverse.idempotency.runtime.metrics.MicrometerIdempotencyMetrics");
        }

        beans.produce(builder.build());
    }

    /**
     * Discover {@link Idempotent} on resource methods and classes at build time and record each
     * resolved policy into the runtime registry. Build-time discovery (not runtime annotation
     * reflection) keeps this native-image safe.
     */
    @BuildStep
    @Record(ExecutionTime.STATIC_INIT)
    void registerIdempotentEndpoints(CombinedIndexBuildItem index, IdempotencyRecorder recorder) {
        DotName annotation = DotName.createSimple(Idempotent.class.getName());
        for (AnnotationInstance instance : index.getIndex().getAnnotations(annotation)) {
            boolean enabled = boolValue(instance.value("enabled"), true);
            Idempotent.Require requireKey = instance.value("requireKey") == null
                    ? Idempotent.Require.DEFAULT
                    : Idempotent.Require.valueOf(instance.value("requireKey").asEnum());
            long ttl = instance.value("ttl") == null ? -1L : instance.value("ttl").asLong();
            ChronoUnit unit = instance.value("ttlUnit") == null
                    ? ChronoUnit.SECONDS
                    : ChronoUnit.valueOf(instance.value("ttlUnit").asEnum());
            // ChronoUnit#getDuration is exact for SECONDS..DAYS and estimated above that — fine for a TTL,
            // and avoids Duration.of throwing on estimated units.
            long ttlMillis = ttl > 0 ? unit.getDuration().multipliedBy(ttl).toMillis() : -1L;

            AnnotationTarget target = instance.target();
            if (target.kind() == AnnotationTarget.Kind.METHOD) {
                MethodInfo method = target.asMethod();
                recorder.registerMethod(method.declaringClass().name().toString(), method.name(),
                        method.parametersCount(), enabled, requireKey, ttlMillis);
            } else if (target.kind() == AnnotationTarget.Kind.CLASS) {
                recorder.registerClass(target.asClass().name().toString(), enabled, requireKey, ttlMillis);
            }
        }
    }

    private static boolean boolValue(AnnotationValue value, boolean defaultValue) {
        return value == null ? defaultValue : value.asBoolean();
    }

    private static boolean isPresent(String className) {
        try {
            Class.forName(className, false, Thread.currentThread().getContextClassLoader());
            return true;
        } catch (Throwable t) {
            return false;
        }
    }
}
