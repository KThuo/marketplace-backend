package com.hodi.security.password;

import com.hodi.common.exception.PasswordPolicyException;
import com.hodi.enums.ConfigKey;
import com.hodi.modules.auth.PasswordHistory;
import com.hodi.modules.auth.PasswordHistoryRepository;
import com.hodi.modules.configurations.ConfigurationService;
import com.hodi.modules.users.User;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Limit;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Password policy, driven entirely from configuration so an operator can tighten it without a
 * redeploy. Enforces length and character-class rules, blocks reuse of the last N passwords, and
 * stamps the expiry date.
 */
@Service
@RequiredArgsConstructor
public class PasswordService {

    private final ConfigurationService configs;
    private final PasswordEncoder encoder;
    private final PasswordHistoryRepository history;

    /** Validate a candidate against policy and history. Throws with every failure listed at once. */
    public void validate(User user, String raw) {
        if (raw == null || raw.isBlank()) throw new PasswordPolicyException("Password is required");

        List<String> failures = new ArrayList<>();
        int minLength = configs.getInt(ConfigKey.AUTH_PASSWORD_MIN_LENGTH);
        if (raw.length() < minLength) {
            failures.add("must be at least " + minLength + " characters");
        }
        if (configs.getBoolean(ConfigKey.AUTH_PASSWORD_REQUIRE_UPPER)
                && raw.chars().noneMatch(Character::isUpperCase)) {
            failures.add("must contain an uppercase letter");
        }
        if (configs.getBoolean(ConfigKey.AUTH_PASSWORD_REQUIRE_NUMBER)
                && raw.chars().noneMatch(Character::isDigit)) {
            failures.add("must contain a number");
        }
        if (configs.getBoolean(ConfigKey.AUTH_PASSWORD_REQUIRE_SYMBOL)
                && raw.chars().allMatch(Character::isLetterOrDigit)) {
            failures.add("must contain a symbol");
        }
        if (!failures.isEmpty()) {
            // All at once: a form that reveals one rule per submission is a bad experience.
            throw new PasswordPolicyException("Password " + String.join(", ", failures));
        }
        assertNotReused(user, raw);
    }

    /** Hash, record the outgoing password in history, and set the new expiry. */
    public String applyTo(User user, String raw) {
        validate(user, raw);
        if (user.getId() != null && user.getPassword() != null) {
            history.save(PasswordHistory.builder()
                    .userId(user.getId())
                    .password(user.getPassword())
                    .build());
        }
        String hash = encoder.encode(raw);
        user.setPassword(hash);
        user.setPasswordChangedAt(OffsetDateTime.now());
        user.setMustChangePassword(false);

        int expiryDays = configs.getInt(ConfigKey.AUTH_PASSWORD_EXPIRY_DAYS);
        user.setPasswordExpiresAt(expiryDays > 0 ? OffsetDateTime.now().plusDays(expiryDays) : null);
        return hash;
    }

    public boolean matches(String raw, String hash) {
        return raw != null && hash != null && encoder.matches(raw, hash);
    }

    /**
     * Spends a bcrypt comparison and discards the result.
     *
     * <p>For the login path's "no such user" branch. Bcrypt is deliberately slow, so a login that skips
     * it answers measurably faster than one that does not — which turns response time into an oracle for
     * whether an account exists. Since a tenant's users are now a per-schema population, that oracle
     * would also answer "does this person have an account with *this* business", which is worth rather
     * more to an attacker than it was when the namespace was global.
     *
     * <p>The hash is produced by this same encoder at startup from a random value, so its cost factor
     * matches a live one by construction rather than by a hardcoded constant somebody would have to
     * remember to update when the strength changes. Nothing can present the value: it is discarded.
     */
    public void wasteComparison(String raw) {
        encoder.matches(raw == null ? "" : raw, dummyHash());
    }

    private volatile String dummyHash;

    private String dummyHash() {
        String cached = dummyHash;
        if (cached == null) {
            synchronized (this) {
                cached = dummyHash;
                if (cached == null) {
                    byte[] noise = new byte[32];
                    new java.security.SecureRandom().nextBytes(noise);
                    cached = encoder.encode(java.util.Base64.getEncoder().encodeToString(noise));
                    dummyHash = cached;
                }
            }
        }
        return cached;
    }

    public String hash(String raw) {
        return encoder.encode(raw);
    }

    private void assertNotReused(User user, String raw) {
        if (user.getId() == null) return;
        int depth = configs.getInt(ConfigKey.AUTH_PASSWORD_HISTORY_COUNT);
        if (depth <= 0) return;

        if (user.getPassword() != null && encoder.matches(raw, user.getPassword())) {
            throw new PasswordPolicyException("New password must differ from the current one");
        }
        boolean reused = history.findRecent(user.getId(), Limit.of(depth)).stream()
                .anyMatch(h -> encoder.matches(raw, h.getPassword()));
        if (reused) {
            throw new PasswordPolicyException(
                    "Password was used recently — choose one of your last " + depth + " unused passwords");
        }
    }
}
