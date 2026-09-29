package com.hodi.modules.notifications;

import com.hodi.common.AppConstant;
import com.hodi.common.exception.HodiException;
import com.hodi.enums.ConfigKey;
import com.hodi.modules.audit.AuditService;
import com.hodi.modules.configurations.ConfigurationService;
import com.hodi.modules.consent.ConsentPreference;
import com.hodi.modules.consent.ConsentRepository;
import com.hodi.modules.consent.ConsentService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.util.Base64;

/**
 * The one-click way out of promotional messages, which works without signing in.
 *
 * <p>A token is the user's id and the purpose, signed with the platform's secret: it can be pasted into a
 * browser from a phone and it can be forged by nobody. Following it records a refusal of the purpose on
 * every channel, with the source saying it came from the link, and says so on a page that needs no
 * account. It does not sign the person in and it reveals nothing about them.
 */
@Service
@RequiredArgsConstructor
public class UnsubscribeService {

    @Value("${hodi.jwt.secret}")
    private String secret;

    private final ConsentRepository consent;
    private final AuditService audit;
    private final ConfigurationService configs;

    /** The link for one person and one purpose, absolute. */
    public String linkFor(Long userId, String purpose) {
        return publicUrl() + "/unsubscribe?t=" + token(userId, purpose);
    }

    public String token(Long userId, String purpose) {
        String payload = userId + ":" + purpose;
        return base64(payload.getBytes(StandardCharsets.UTF_8)) + "." + base64(sign(payload));
    }

    /** Records the refusal the token stands for. Idempotent; a stale link still lands on "you are unsubscribed". */
    @Transactional
    public String unsubscribe(String token) {
        String payload;
        Long userId;
        String purpose;
        try {
            String[] parts = token == null ? new String[0] : token.trim().split("\\.", 2);
            if (parts.length != 2) throw new IllegalArgumentException("shape");
            payload = new String(Base64.getUrlDecoder().decode(parts[0]), StandardCharsets.UTF_8);
            if (!MessageDigest.isEqual(sign(payload), Base64.getUrlDecoder().decode(parts[1]))) throw new IllegalArgumentException("signature");
            String[] fields = payload.split(":", 2);
            userId = Long.valueOf(fields[0]);
            purpose = fields[1];
        } catch (RuntimeException e) {
            // A truncated paste, a forged link, or nonsense: the same sentence for all three, and never a 500.
            throw new HodiException("That link is not one we recognise.", HttpStatus.BAD_REQUEST);
        }
        if (AppConstant.CONSENT_TRANSACTIONAL.equals(purpose) || !ConsentService.PURPOSES.contains(purpose)) {
            throw new HodiException("That kind of message cannot be switched off from a link.", HttpStatus.BAD_REQUEST);
        }
        int changed = 0;
        for (String channel : ConsentService.CHANNELS) {
            ConsentPreference row = consent.findByUserIdAndChannelAndPurpose(userId, channel, purpose)
                    .orElseGet(() -> ConsentPreference.builder().userId(userId).channel(channel).purpose(purpose).granted(true).build());
            if (!row.isGranted() && row.getId() != null) continue;
            row.setGranted(false);
            row.setSource(AppConstant.CONSENT_SOURCE_UNSUBSCRIBE);
            row.setCapturedAt(OffsetDateTime.now());
            row.setCreatedBy(row.getCreatedBy() == null ? "unsubscribe-link" : row.getCreatedBy());
            row.setUpdatedBy("unsubscribe-link");
            consent.save(row);
            changed++;
        }
        if (changed > 0) {
            audit.record(AppConstant.AUDIT_CONSENT_UPDATE, "ConsentPreference", userId, null,
                    purpose + " refused on every channel from the unsubscribe link");
        }
        return purpose;
    }

    private byte[] sign(String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(("unsubscribe:" + secret).getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("Cannot sign", e);
        }
    }

    private static String base64(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String publicUrl() {
        String url = configs.getString(ConfigKey.PUBLIC_URL);
        if (url == null || url.isBlank()) return "";
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
