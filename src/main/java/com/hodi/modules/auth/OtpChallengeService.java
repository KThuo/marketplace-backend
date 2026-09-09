package com.hodi.modules.auth;

import com.hodi.common.exception.HodiException;
import com.hodi.common.exception.UnauthorizedException;
import com.hodi.enums.ConfigKey;
import com.hodi.infra.notify.MailTemplate;
import com.hodi.infra.notify.NotifyClient;
import com.hodi.modules.configurations.ConfigurationService;
import com.hodi.modules.users.User;
import com.hodi.security.totp.TotpService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.HexFormat;

/**
 * One-time codes, for both things that need one: the second half of a challenged login, and a buyer proving
 * they own the email address or phone number they registered with.
 *
 * <p>One service and one table, because they are the same mechanism — issue a code, tie it to a purpose and
 * a user, count the wrong guesses, expire it — and two implementations would mean two places to get the
 * attempt counting wrong. {@code purpose} keeps them from being interchangeable: a code issued to verify an
 * email address cannot be presented to complete a login.
 *
 * <p><strong>Stored in Postgres rather than Redis</strong>, which is where the axis original kept it. Two
 * reasons, both about buyer verification rather than login: a verification code must survive a Redis restart
 * (losing one strands a half-registered account behind a code that will never be accepted), and attempts
 * have to be counted durably or the limit is advisory. A login challenge is short-lived enough not to care
 * either way, so the durable store wins on the case that does.
 *
 * <h2>What the challenge token is not</h2>
 *
 * <p>It is not a session and grants nothing. It names a challenge, and a challenge is only useful together
 * with the code — which went to a different channel. Holding it lets somebody submit guesses against one
 * user, which is why the attempt limit is enforced here and why exceeding it consumes the challenge outright
 * rather than merely rejecting the guess.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OtpChallengeService {

    public static final String PURPOSE_LOGIN = "LOGIN";
    public static final String PURPOSE_EMAIL_VERIFY = "EMAIL_VERIFY";
    public static final String PURPOSE_PHONE_VERIFY = "PHONE_VERIFY";
    /** Changing where an organisation's money lands. The code goes to the organisation, not the caller. */
    public static final String PURPOSE_PAYMENT_ACCOUNT = "PAYMENT_ACCOUNT";

    public static final String CHANNEL_TOTP = "TOTP";
    public static final String CHANNEL_SMS = "SMS";
    public static final String CHANNEL_EMAIL = "EMAIL";

    private static final SecureRandom RANDOM = new SecureRandom();

    private final OtpChallengeRepository repository;
    private final ConfigurationService configs;
    private final TotpService totp;
    private final NotifyClient notify;
    private final MailTemplate mail;

    /** What the caller needs to tell the client where to look for the code. */
    public record Challenge(String token, String channel, String sentToMasked) {}

    // ── issuing ───────────────────────────────────────────────────────────────

    /**
     * Issue a login challenge for a user whose password has already been checked.
     *
     * <p>TOTP wins over SMS when the user has enrolled it: it is the stronger factor and costs nothing to
     * send. For TOTP no code is stored — there is nothing to store, because the code is derived from the
     * shared secret at the moment it is checked rather than issued by us.
     */
    @Transactional
    public Challenge issueLogin(User user) {
        retireOutstanding(user.getId(), PURPOSE_LOGIN);

        boolean useTotp = user.isTotpEnabled() && user.getTotpConfirmedAt() != null;
        if (useTotp) {
            OtpChallenge saved = repository.save(base(user, PURPOSE_LOGIN, CHANNEL_TOTP)
                    .sentToMasked("your authenticator app")
                    .build());
            return new Challenge(saved.getChallengeToken(), CHANNEL_TOTP, saved.getSentToMasked());
        }

        String code = numericCode();
        String masked = maskPhone(user.getPhone());
        OtpChallenge saved = repository.save(base(user, PURPOSE_LOGIN, CHANNEL_SMS)
                .codeHash(hash(code))
                .sentToMasked(masked)
                .build());
        // Sensitive variant: the code reaches the handset but never the application log, and it ignores
        // the notify.sms.enabled preference — a sign-in code is not a notification somebody can opt out of
        // and still complete the sign-in it gates.
        notify.sendSensitiveSms(user.getPhone(),
                "Your Hodi sign-in code is " + code + ". It expires in " + ttlMinutes() + " minutes.",
                user.fullName());
        return new Challenge(saved.getChallengeToken(), CHANNEL_SMS, masked);
    }

    /** Issue an email-verification code to a freshly registered buyer. */
    @Transactional
    public Challenge issueEmailVerification(User user) {
        retireOutstanding(user.getId(), PURPOSE_EMAIL_VERIFY);
        String code = numericCode();
        String masked = maskEmail(user.getEmail());
        OtpChallenge saved = repository.save(base(user, PURPOSE_EMAIL_VERIFY, CHANNEL_EMAIL)
                .codeHash(hash(code))
                .sentToMasked(masked)
                .build());
        notify.sendSensitiveEmail(user.getEmail(), "Confirm your email address",
                mail.code(user.getFirstName(), "Use this code to confirm your email address.", code,
                        "The code expires in " + ttlMinutes() + " minutes."),
                user.fullName());
        return new Challenge(saved.getChallengeToken(), CHANNEL_EMAIL, masked);
    }

    @Transactional
    public Challenge issuePhoneVerification(User user) {
        if (user.getPhone() == null || user.getPhone().isBlank()) {
            throw new HodiException("Add a phone number before verifying one", HttpStatus.BAD_REQUEST);
        }
        retireOutstanding(user.getId(), PURPOSE_PHONE_VERIFY);
        String code = numericCode();
        String masked = maskPhone(user.getPhone());
        OtpChallenge saved = repository.save(base(user, PURPOSE_PHONE_VERIFY, CHANNEL_SMS)
                .codeHash(hash(code))
                .sentToMasked(masked)
                .build());
        notify.sendSensitiveSms(user.getPhone(),
                "Your Hodi confirmation code is " + code + ".", user.fullName());
        return new Challenge(saved.getChallengeToken(), CHANNEL_SMS, masked);
    }

    /**
     * A code sent somewhere other than the caller's own handset.
     *
     * @param challengeToken the handle the caller carries back with the code
     * @param sentToMasked   where it went, masked for display
     */
    public record IssuedCode(String challengeToken, String sentToMasked, OffsetDateTime expiresAt,
                             int validForMinutes) {}

    /**
     * Issue a code to a phone that is not the caller's — an organisation's contact number.
     *
     * <p>Bound to the caller's user id so the same person cannot hold two live challenges of one purpose and
     * so {@link #consumeCode} can insist the code is redeemed by whoever asked for it. The point of sending it
     * elsewhere is the point of the control: somebody holding a staff login does not hold the office phone.
     *
     * @param about one sentence saying what the code confirms, put in front of it in the text
     */
    @Transactional
    public IssuedCode issueToPhone(Long userId, String purpose, String phone, String recipientName,
                                   String about) {
        if (phone == null || phone.isBlank()) {
            throw new HodiException("There is no phone number to send the code to", HttpStatus.BAD_REQUEST);
        }
        retireOutstanding(userId, purpose);
        String code = numericCode();
        String masked = maskPhone(phone);
        OffsetDateTime expires = OffsetDateTime.now().plusMinutes(ttlMinutes());
        OtpChallenge saved = repository.save(OtpChallenge.builder()
                .challengeToken(freshToken())
                .userId(userId)
                .purpose(purpose)
                .channel(CHANNEL_SMS)
                .codeHash(hash(code))
                .sentToMasked(masked)
                .expiresAt(expires)
                .build());
        notify.sendSensitiveSms(phone,
                about + " Your Hodi confirmation code is " + code + ". It expires in " + ttlMinutes()
                        + " minutes.",
                recipientName);
        return new IssuedCode(saved.getChallengeToken(), masked, expires, ttlMinutes());
    }

    /**
     * Check and consume a code issued by {@link #issueToPhone}.
     *
     * <p>Refused as a bad request rather than as unauthorised: the caller <em>is</em> signed in, and a 401
     * here would send the client into its token-refresh path and end the session over a mistyped digit. The
     * message always says "code", so a form can put it under the boxes.
     */
    @Transactional
    public void consumeCode(String challengeToken, String code, String purpose, Long userId) {
        OtpChallenge challenge = challengeToken == null || challengeToken.isBlank() ? null
                : repository.findByChallengeToken(challengeToken).orElse(null);
        if (challenge == null || !challenge.isUsable() || !purpose.equals(challenge.getPurpose())
                || !challenge.getUserId().equals(userId) || CHANNEL_TOTP.equals(challenge.getChannel())) {
            throw new HodiException("That code has expired — ask for a new one.", HttpStatus.BAD_REQUEST);
        }

        int maxAttempts = Math.max(1, configs.getInt(ConfigKey.AUTH_OTP_MAX_ATTEMPTS));
        if (challenge.getAttempts() >= maxAttempts) {
            consume(challenge);
            throw new HodiException("Too many incorrect codes — ask for a new one.", HttpStatus.BAD_REQUEST);
        }
        if (!constantTimeEquals(challenge.getCodeHash(), hash(normalise(code)))) {
            challenge.setAttempts(challenge.getAttempts() + 1);
            repository.save(challenge);
            throw new HodiException("That code is not right.", HttpStatus.BAD_REQUEST);
        }
        consume(challenge);
    }

    // ── consuming ─────────────────────────────────────────────────────────────

    /**
     * Check a submitted code against a challenge, and consume the challenge on success.
     *
     * <p>A wrong code increments the attempt counter rather than consuming the challenge, so a mistyped
     * digit does not cost a whole round trip through login. Running out of attempts <em>does</em> consume it,
     * which is what stops the token being an unlimited guessing budget against one account.
     *
     * @param purpose the purpose the caller expects. A mismatch is refused with the same message as an
     *                unknown token: telling the caller their token is real but for something else confirms
     *                the token exists.
     * @return the user id the challenge was bound to
     */
    @Transactional
    public Long consume(String challengeToken, String code, String purpose, User user) {
        if (challengeToken == null || challengeToken.isBlank()) {
            throw new UnauthorizedException("Confirmation code request expired or invalid");
        }
        OtpChallenge challenge = repository.findByChallengeToken(challengeToken)
                .orElseThrow(() -> new UnauthorizedException(
                        "Confirmation code request expired or invalid"));

        if (!challenge.isUsable() || !purpose.equals(challenge.getPurpose())) {
            throw new UnauthorizedException("Confirmation code request expired or invalid");
        }

        int maxAttempts = Math.max(1, configs.getInt(ConfigKey.AUTH_OTP_MAX_ATTEMPTS));
        if (challenge.getAttempts() >= maxAttempts) {
            consume(challenge);
            throw new UnauthorizedException("Too many incorrect codes — start again");
        }

        boolean ok = CHANNEL_TOTP.equals(challenge.getChannel())
                // The stored secret is ciphertext; decrypt it for the comparison. Nothing is stored for a
                // TOTP challenge, which is why this branch reads the user rather than the challenge row.
                ? totp.verifyCode(totp.decryptSecret(user.getTotpSecret()), code)
                : constantTimeEquals(challenge.getCodeHash(), hash(normalise(code)));

        if (!ok) {
            challenge.setAttempts(challenge.getAttempts() + 1);
            repository.save(challenge);
            throw new UnauthorizedException("That code is not right");
        }

        consume(challenge);
        return challenge.getUserId();
    }

    /** The user a challenge is bound to, without consuming it — the caller needs the row to verify against. */
    @Transactional(readOnly = true)
    public Long boundUserId(String challengeToken) {
        return repository.findByChallengeToken(challengeToken)
                .filter(OtpChallenge::isUsable)
                .map(OtpChallenge::getUserId)
                .orElseThrow(() -> new UnauthorizedException(
                        "Confirmation code request expired or invalid"));
    }

    // ── internals ─────────────────────────────────────────────────────────────

    private OtpChallenge.OtpChallengeBuilder base(User user, String purpose, String channel) {
        return OtpChallenge.builder()
                .challengeToken(freshToken())
                .userId(user.getId())
                .purpose(purpose)
                .channel(channel)
                .expiresAt(OffsetDateTime.now().plusMinutes(ttlMinutes()));
    }

    /** Issuing a new code retires the old one, so only the most recent code ever works. */
    private void retireOutstanding(Long userId, String purpose) {
        repository.consumeOutstanding(userId, purpose, OffsetDateTime.now());
    }

    private void consume(OtpChallenge challenge) {
        challenge.setConsumedAt(OffsetDateTime.now());
        repository.save(challenge);
    }

    private int ttlMinutes() {
        return Math.max(1, configs.getInt(ConfigKey.AUTH_OTP_TTL_MINUTES));
    }

    private static String numericCode() {
        return String.format("%06d", RANDOM.nextInt(1_000_000));
    }

    private static String freshToken() {
        byte[] buf = new byte[24];
        RANDOM.nextBytes(buf);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(buf);
    }

    private static String normalise(String code) {
        return code == null ? "" : code.trim();
    }

    private static String hash(String raw) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /**
     * Compared without an early exit. Both operands are fixed-length hex digests of the same length, so
     * there is no length leak to worry about — this is about not letting the comparison time reveal how many
     * leading characters were right.
     */
    private static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) return false;
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * {@code j••@example.com} — enough for somebody to recognise their own address, not to learn a new one.
     *
     * <p>Public because the registration flow masks an address it is deliberately <em>not</em> sending to:
     * when somebody registers with an address that already has an account, the response has to look exactly
     * like a success, mask included, or the difference becomes an account-enumeration oracle.
     */
    public static String maskEmail(String email) {
        if (email == null || !email.contains("@")) return "your email";
        int at = email.indexOf('@');
        String local = email.substring(0, at);
        String domain = email.substring(at);
        String head = local.isEmpty() ? "" : local.substring(0, 1);
        return head + "•".repeat(Math.max(1, local.length() - 1)) + domain;
    }

    /** {@code •••••1234} — the last four, which is what somebody checks against their own handset. */
    public static String maskPhone(String phone) {
        if (phone == null || phone.length() < 4) return "your phone";
        return "•".repeat(Math.max(1, phone.length() - 4)) + phone.substring(phone.length() - 4);
    }
}
