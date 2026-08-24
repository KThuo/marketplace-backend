package com.hodi.config;

import com.hodi.tenant.TenantContext;
import org.springframework.cache.interceptor.KeyGenerator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.lang.reflect.Method;
import java.util.Arrays;

/**
 * A cache key generator that prefixes every key with the current tenant.
 *
 * <p>Opt-in rather than global, and deliberately so. Most caches hold tenant data and must be
 * partitioned — a key without a tenant prefix is a cross-tenant read waiting to happen. But the
 * tenant-resolution cache itself must <em>not</em> be prefixed: it is the lookup that establishes
 * which tenant we are, so prefixing it by tenant would be circular. Making this global would break
 * that one cache in a way that is hard to see.
 *
 * <p>Usage: {@code @Cacheable(cacheNames = "x", keyGenerator = "tenantAwareKeyGenerator")}.
 */
@Configuration
public class CacheConfig {

    public static final String TENANT_AWARE = "tenantAwareKeyGenerator";

    @Bean(TENANT_AWARE)
    public KeyGenerator tenantAwareKeyGenerator() {
        return new KeyGenerator() {
            @Override
            public Object generate(Object target, Method method, Object... params) {
                Long tenantId = TenantContext.getTenantId();
                String scope = tenantId == null ? "platform" : "t" + tenantId;
                return scope + ':' + method.getName() + ':' + Arrays.deepToString(params);
            }
        };
    }
}
