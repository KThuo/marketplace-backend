package com.hodi.security.hashid;

import com.hodi.common.exception.HodiException;
import com.hodi.security.PublicMarketplace;
import org.hashids.Hashids;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Per-user HashId obfuscation. The salt is {@code username + saltSuffix} where {@code username} is
 * read from the Spring Security context (or {@code "system"} for unauthenticated paths).
 *
 * <p>Two different users encoding the same DB id therefore see two different hashes, and one user
 * cannot decode another user's hash. Database IDs are never exposed in transit — every
 * {@code Long id} field on a response DTO is encoded by {@link HashIdMapper}, every incoming
 * {@code String id} field on a request DTO is decoded back via the same mapper.
 *
 * <p>The public marketplace is the one surface that opts out, by way of {@link PublicMarketplace}: there the
 * salt is a fixed identity so the same id reads the same for every visitor, signed in or not.
 *
 * <p>The suffix and minimum length come from {@code hodi.hashids.salt} and
 * {@code hodi.hashids.min-length}, applied once at startup by {@link HashIdConfig}. HashIds are
 * obfuscation, not encryption — a known salt is reversible — so the suffix is environment-specific
 * and the compiled default below exists only so local dev works out of the box.
 */
public final class HashIdUtil {

    private static final String ALPHABET = "abcdefghijkmnopqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final String DEFAULT_SALT_SUFFIX = "hOdiHaSh1dSecReTSuffix4kPw";
    private static final int DEFAULT_MIN_LENGTH = 10;

    private static volatile String saltSuffix = DEFAULT_SALT_SUFFIX;
    private static volatile int minLength = DEFAULT_MIN_LENGTH;

    private HashIdUtil() {}

    /** Applied once at startup; blank or non-positive values keep the compiled default. */
    static void configure(String suffix, int length) {
        if (suffix != null && !suffix.isBlank()) saltSuffix = suffix;
        if (length > 0) minLength = length;
    }

    private static Hashids initHashId(String salt) {
        return new Hashids(salt, minLength, ALPHABET);
    }

    private static String username() {
        // The public storefront salts for everybody alike: a property link has to decode for whoever it was
        // sent to, and a saved search has to survive its owner signing in halfway through.
        if (PublicMarketplace.isActive()) return PublicMarketplace.IDENTITY;

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getName() != null) {
            return auth.getName();
        }
        return "system";
    }

    public static String encodeId(Long id) {
        if (id == null) return null;
        Hashids hashids = initHashId(username() + saltSuffix);
        return hashids.encode(id);
    }

    /**
     * The id behind a hash, or null when there was nothing to decode.
     *
     * <h2>Null means "you gave me nothing", never "you gave me nonsense"</h2>
     *
     * <p>Absent and blank stay null, because half the call sites in this product pass an optional query
     * parameter straight in — {@code locationId} on a list, a customer on a sale — and null is the honest
     * answer to a question nobody asked.
     *
     * <p>A non-empty string that decodes to nothing is different: somebody sent an id, and it is not one of
     * ours. That used to return null too, and seventeen call sites pass this result directly to
     * {@code findById}, where null raises deep inside JPA and surfaces as a 500. A truncated link pasted out
     * of WhatsApp, a stale bookmark, or an id from another user's salt would all have crashed a page instead
     * of being told they were not found.
     */
    public static Long decodeId(String hashedId) {
        if (hashedId == null || hashedId.isEmpty()) return null;
        return decoded(hashedId, initHashId(username() + saltSuffix));
    }

    public static String encodeId(Long id, String username) {
        if (id == null) return null;
        Hashids hashids = initHashId(username + saltSuffix);
        return hashids.encode(id);
    }

    public static Long decodeId(String hashedId, String username) {
        if (hashedId == null || hashedId.isEmpty()) return null;
        return decoded(hashedId, initHashId(username + saltSuffix));
    }

    /**
     * Refused at the boundary rather than passed on as null.
     *
     * <p>{@code BAD_REQUEST} rather than {@code NOT_FOUND}, and the distinction is deliberate: this is not a
     * real id that points at nothing, it is a string that was never an id. Saying "not found" would tell a
     * caller their id is valid and the row is gone, which is a different problem to go looking for.
     */
    private static Long decoded(String hashedId, Hashids hashids) {
        long[] numbers = hashids.decode(hashedId);
        if (numbers.length == 0) {
            throw new HodiException("That link is not one we recognise.", HttpStatus.BAD_REQUEST);
        }
        return numbers[0];
    }
}
