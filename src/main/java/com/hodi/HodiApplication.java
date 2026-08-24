package com.hodi;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Hodi Market Place — property selling and mortgage platform.
 *
 * <p>Scheduling is enabled for the refresh-token reaper. Caching is enabled for the configuration
 * layer, whose keys are tenant-prefixed (see {@code CacheConfig}) — an unprefixed cache key in this
 * application is a cross-tenant leak, because every organisation's rows share one schema.
 */
@SpringBootApplication
@EnableCaching
@EnableScheduling
public class HodiApplication {

    public static void main(String[] args) {
        SpringApplication.run(HodiApplication.class, args);
    }
}
