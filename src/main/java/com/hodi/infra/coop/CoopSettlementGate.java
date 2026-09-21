package com.hodi.infra.coop;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.TreeSet;
import java.util.function.Supplier;

/**
 * One message about a payment at a time, and a memory of the ones just handled.
 *
 * <h2>The race the database cannot see</h2>
 *
 * <p>An STK payment reaches us twice within the same second: Co-op's callback confirms the prompt, and the
 * bank's credit notification follows for the same money. Each path checks the database for the other's
 * row before writing its own — but the first is still inside its transaction when the second looks, so
 * neither sees the other and both insert. The unique index catches two rows under one key; it cannot catch
 * a callback filed under our own reference beside a notification filed under the receipt.
 *
 * <h2>Two things, borrowed from the older platform</h2>
 *
 * <p>A <b>lock</b> in Redis on every name the money goes by — the receipt, our reference for the prompt,
 * the bank's reference — held while a message is written and committed, so the second message waits and
 * then finds the first's row. And a <b>marker</b> of what was just written, keyed the same way, so the
 * second message is answered from Redis before it queries the database at all. Both are hints on top of
 * the database, never instead of it: a marker is always verified against the row it points at, and a lock
 * that cannot be taken — Redis down, or the wait exhausted — lets the work proceed, because the
 * constraints and the merge in the writers remain the last word and money must never be refused for want
 * of a cache.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CoopSettlementGate {

    private static final String LOCK = "hodi:coop:lock:";
    private static final String SEEN = "hodi:coop:seen:";

    /** Longer than any write takes, shorter than a person would notice if a holder died without releasing. */
    private static final Duration LOCK_TTL = Duration.ofSeconds(30);
    /** How long the second message waits for the first before going ahead on the database's guarantees. */
    private static final Duration WAIT = Duration.ofSeconds(8);
    private static final long POLL_MS = 40;
    /** Long enough to cover Co-op's retries of a notification it believes went unacknowledged. */
    private static final Duration SEEN_TTL = Duration.ofHours(48);

    private final StringRedisTemplate redis;

    /**
     * Runs the work while holding a lock on each of the given names; the names are taken in one order
     * everywhere, so two messages naming the same money in a different order cannot deadlock.
     */
    public <T> T serialised(Collection<String> names, Supplier<T> work) {
        List<String> keys = new ArrayList<>(new TreeSet<>(names.stream()
                .filter(Objects::nonNull).map(String::trim).filter(n -> !n.isEmpty()).map(String::toUpperCase).toList()));
        List<String> held = new ArrayList<>(keys.size());
        try {
            for (String key : keys) {
                if (acquire(LOCK + key)) held.add(LOCK + key);
            }
            return work.get();
        } finally {
            for (String key : held) {
                try {
                    redis.delete(key);
                } catch (RuntimeException e) {
                    log.debug("Could not release settlement lock {}: {}", key, e.getMessage());
                }
            }
        }
    }

    /** What was just written under this name, so the next message about it is answered without a query. */
    public void remember(String name, Long statementId) {
        if (name == null || name.isBlank() || statementId == null) return;
        try {
            redis.opsForValue().set(SEEN + name.trim().toUpperCase(), String.valueOf(statementId), SEEN_TTL);
        } catch (RuntimeException e) {
            log.debug("Could not remember settlement {} in Redis: {}", name, e.getMessage());
        }
    }

    /** The statement id last written under this name, or null. A hint: the caller verifies it exists. */
    public Long recall(String name) {
        if (name == null || name.isBlank()) return null;
        try {
            String value = redis.opsForValue().get(SEEN + name.trim().toUpperCase());
            return value == null ? null : Long.valueOf(value);
        } catch (RuntimeException e) {
            log.debug("Could not read settlement marker {} from Redis: {}", name, e.getMessage());
            return null;
        }
    }

    /**
     * Takes the lock, waiting for a holder to finish; true when held. False means the work goes ahead
     * without it, and the log says why — Redis unreachable, or a holder that outlived the wait.
     */
    private boolean acquire(String key) {
        long deadline = System.nanoTime() + WAIT.toNanos();
        try {
            while (true) {
                if (Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(key, "1", LOCK_TTL))) return true;
                if (System.nanoTime() > deadline) {
                    log.warn("Settlement lock {} still held after {}s; proceeding on the database's guarantees",
                            key, WAIT.toSeconds());
                    return false;
                }
                Thread.sleep(POLL_MS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (RuntimeException e) {
            log.warn("Settlement lock {} unavailable ({}); proceeding on the database's guarantees", key, e.getMessage());
            return false;
        }
    }
}
