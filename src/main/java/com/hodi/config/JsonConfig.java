package com.hodi.config;

import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.DeserializationFeature;

/**
 * How request bodies bind.
 *
 * <h2>Why this is Java and not a property</h2>
 *
 * <p>It began as {@code spring.jackson.deserialization.fail-on-null-for-primitives=false} in
 * {@code config/application.properties} — and that folder is <strong>deliberately not committed</strong>
 * (see {@code .gitignore}: runtime configuration is a deployment concern). So the fix worked on one laptop and
 * would have been absent from every fresh clone, where an omitted flag would 400 again and the test pinning it
 * would fail with nothing to point at.
 *
 * <p>Which is the right signal about what this is. How a request binds is <em>application behaviour</em>, the
 * same for every environment, and not something an operator should be able to change by editing a file. Config
 * is for what differs between deployments.
 *
 * <h2>What it does</h2>
 *
 * <p>Jackson 3 defaults {@link DeserializationFeature#FAIL_ON_NULL_FOR_PRIMITIVES} to <em>true</em>, where
 * Jackson 2 defaulted it to false. A record component declared {@code boolean} rather than {@code Boolean}
 * therefore throws when the key is absent from the body — there is no null to hand a primitive — and the caller
 * gets {@code 400 Malformed request body} naming no field. Around eighty request-record components across every
 * module are declared that way, so <strong>any</strong> omitted optional flag was a 400 on <strong>every</strong>
 * endpoint.
 *
 * <p>Turned off, because for a flag "absent" and "false" are the same statement. It went unnoticed because the
 * web client fills its forms completely and always sends every key; it surfaced creating a service from the API
 * with the payload a service actually needs, where {@code trackBatches} and {@code trackSerials} are meaningless
 * and omitting them is the correct request rather than a lazy one.
 *
 * <p>Pinned by {@code JsonRequestBindingTest}, which fails if this bean is removed.
 */
@Configuration
public class JsonConfig {

    @Bean
    JsonMapperBuilderCustomizer requestBindingCustomizer() {
        return builder -> builder.disable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES);
    }
}
