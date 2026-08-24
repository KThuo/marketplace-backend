package com.hodi.config;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.jsontype.BasicPolymorphicTypeValidator;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.cache.CacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import java.time.Duration;

/**
 * Redis wiring, and one thing the axis original left out.
 *
 * <h2>Every region carries the application's name</h2>
 *
 * <p>Spring's {@code RedisCacheManager} keys entries as {@code <cacheName>::<key>}, with nothing
 * identifying the application. Every project in this family runs against the same local Redis and more
 * than one of them declares a region called {@code configValues}, so they were all writing to the same
 * keys. The failure is not subtle when it lands: booting Hodi against a Redis that axis had used produced
 * <em>"Could not resolve type id 'com.axis.modules.configurations.ConfigurationCache$GlobalEntry' as a
 * subtype of java.lang.Object"</em> on the first login, because Hodi read a value axis wrote and the typed
 * serializer named a class that does not exist here.
 *
 * <p>The class-name mismatch is what made it loud. The quieter version is the one worth preventing: two
 * applications whose cached record shapes <em>do</em> line up would deserialize each other's values
 * happily, and one platform's configuration would silently answer the other's reads.
 *
 * <p>The name lives in the <strong>region</strong> rather than being bolted on by this class — see
 * {@link CacheRegions}. That way it is present everywhere a region name is read by a person: in the
 * {@code @Cacheable} annotation, in Spring's own logging, in {@code getCacheNames()}, and in the error
 * text when a region is missing. This class only appends the {@code ::} separator, which is what Spring
 * would have done anyway.
 *
 * <h2>Unknown regions are refused</h2>
 *
 * <p>{@code initialCacheNames} plus {@code disableCreateOnMissingCache} means the manager serves exactly
 * the declared set. A {@code @Cacheable("configValues")} that skipped {@link CacheRegions} — i.e. one
 * without the application's name — now fails on first use with "cannot find cache", rather than quietly
 * creating the unnamespaced region this whole arrangement exists to prevent. Failing loudly in
 * development is the point; the alternative is a bug that only appears when two applications are running.
 */
@Configuration
public class RedisConfig {

    @Bean
    public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory connectionFactory) {
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);
        template.setKeySerializer(new StringRedisSerializer());
        template.setHashKeySerializer(new StringRedisSerializer());
        template.setValueSerializer(jacksonSerializer());
        template.setHashValueSerializer(jacksonSerializer());
        template.afterPropertiesSet();
        return template;
    }

    @Bean
    public CacheManager cacheManager(RedisConnectionFactory connectionFactory) {
        RedisCacheConfiguration cacheConfig = RedisCacheConfiguration.defaultCacheConfig()
                .entryTtl(Duration.ofMinutes(10))
                .disableCachingNullValues()
                /*
                 * Just the separator. The application's name is already in the region name, so a stored key
                 * reads "hodimp:configValues::security.session.timeout.minutes.admin" — and it reads that
                 * way whether the key was built here, by a @CacheEvict, or by anything else that knows the
                 * region. Prefixing here as well would double it.
                 */
                .computePrefixWith(cacheName -> cacheName + "::")
                .serializeKeysWith(RedisSerializationContext.SerializationPair.fromSerializer(new StringRedisSerializer()))
                .serializeValuesWith(RedisSerializationContext.SerializationPair.fromSerializer(jacksonSerializer()));
        return RedisCacheManager.builder(connectionFactory)
                .cacheDefaults(cacheConfig)
                .initialCacheNames(CacheRegions.ALL)
                .disableCreateOnMissingCache()
                .build();
    }

    /**
     * Values are read back as {@code Object}, so the stored JSON has to carry its own type — the cache
     * abstraction has no declared type to deserialize into.
     *
     * <p>Typing is {@code EVERYTHING}, not the more usual {@code NON_FINAL}, because our cached payloads
     * are records and a record is implicitly final: {@code NON_FINAL} omits the type wrapper on write and
     * then demands it on read, so the first successful cache hit fails with "expected START_ARRAY". The
     * write and read sides have to agree, and {@code EVERYTHING} is the setting where they do. The cost is
     * a type wrapper around scalars too, which is a few bytes per entry.
     */
    private GenericJackson2JsonRedisSerializer jacksonSerializer() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.setVisibility(PropertyAccessor.ALL, JsonAutoDetect.Visibility.ANY);
        // ANY visibility also picks up derived getters — TenantRef.isReady() is written as "ready", which
        // no canonical record constructor accepts on the way back. A cache read must tolerate a payload
        // that carries more than the type needs, otherwise the first cache hit fails; the same tolerance
        // covers entries written by a previous deploy whose shape has since changed.
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        mapper.activateDefaultTyping(
                BasicPolymorphicTypeValidator.builder()
                        .allowIfBaseType(Object.class)
                        .build(),
                ObjectMapper.DefaultTyping.EVERYTHING);
        return new GenericJackson2JsonRedisSerializer(mapper);
    }
}
