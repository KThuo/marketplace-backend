package com.hodi.security.jwt;

import com.hodi.config.CacheRegions;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

/**
 * Redis-backed JWT blacklist. Logout adds the token here with TTL = its remaining lifetime, and
 * the JWT authentication filter rejects any token in the set.
 *
 * <p>Keys are NOT tenant-prefixed, deliberately: a JWT is globally unique, and a revoked token must
 * stay revoked no matter which tenant context the next request resolves to.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TokenBlacklistService {

    /** Derived from {@link CacheRegions#APP}, so the application's name is spelled in exactly one place. */
    private static final String PREFIX = CacheRegions.BLACKLIST_KEYS;
    private final StringRedisTemplate redis;

    public void blacklist(String token, long remainingTtlMs) {
        if (token == null || remainingTtlMs <= 0) return;
        try {
            redis.opsForValue().set(PREFIX + token, "1", remainingTtlMs, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            log.warn("Failed to blacklist token in Redis", e);
        }
    }

    public boolean isBlacklisted(String token) {
        if (token == null) return false;
        try {
            return Boolean.TRUE.equals(redis.hasKey(PREFIX + token));
        } catch (Exception e) {
            log.warn("Failed to query token blacklist; failing open", e);
            return false;
        }
    }
}
