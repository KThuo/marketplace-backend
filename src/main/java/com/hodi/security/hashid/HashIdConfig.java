package com.hodi.security.hashid;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

/**
 * Pushes {@code hodi.hashids.*} into {@link HashIdUtil}'s static state at startup.
 *
 * <p>{@code HashIdUtil} is a static utility because it is called from MapStruct mappers and DTO
 * factories that are not Spring beans, so the settings cannot be injected per call site. This
 * bean is the single place the configuration crosses into it.
 */
@Slf4j
@Configuration
public class HashIdConfig {

    @Value("${hodi.hashids.salt:}")
    private String salt;

    @Value("${hodi.hashids.min-length:10}")
    private int minLength;

    @PostConstruct
    void apply() {
        HashIdUtil.configure(salt, minLength);
        if (salt == null || salt.isBlank()) {
            log.warn("hodi.hashids.salt is not set — falling back to the built-in development salt");
        }
    }
}
