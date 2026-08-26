package com.hodi.modules.leads;

import com.hodi.common.AppConstant;
import com.hodi.enums.ConfigKey;
import com.hodi.infra.notify.MailTemplate;
import com.hodi.infra.notify.NotifyClient;
import com.hodi.modules.configurations.ConfigurationService;
import com.hodi.modules.consent.ConsentService;
import com.hodi.modules.profiles.UserProfileRepository;
import com.hodi.modules.users.User;
import com.hodi.modules.users.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

/**
 * Tells the other side that something happened (M4).
 *
 * <h2>Transactional, and that is not a loophole</h2>
 *
 * <p>Everything sent here goes under {@code TRANSACTIONAL} in the consent store — a reply to a question you
 * asked, an answer about a viewing you requested, a decision on an offer you made. The consent store is
 * still asked every time; it simply always says yes for this purpose, because the table's own CHECK refuses
 * a row that says otherwise. Routing around it and sending directly would work identically today and would
 * be the exact shape that stops working correctly the day somebody adds a purpose.
 *
 * <h2>Best effort, never in the way</h2>
 *
 * <p>Nothing here throws. A gateway that is down must not stop a buyer from asking a question or a seller
 * from replying — the row is the record and the message is a courtesy on top of it. Failures are logged.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LeadNotifier {

    private final UserRepository users;
    private final UserProfileRepository profiles;
    private final ConsentService consent;
    private final NotifyClient notify;
    private final MailTemplate mail;
    private final ConfigurationService configs;

    /** The buyer, about something their seller did. */
    public void toBuyer(Long userId, String subject, String line, String path) {
        users.findById(userId).ifPresent(user -> send(user, subject, line, path));
    }

    /**
     * The seller, about something a buyer did.
     *
     * <p>Sent to every live person at that organisation who could act on it. Not to an "enquiries@" address:
     * the platform does not have one, and a shared mailbox nobody owns is how leads go cold.
     */
    public void toSeller(Long tenantId, String subject, String line, String path) {
        List<Long> staff = profiles.findLiveUserIdsByTenant(tenantId);
        if (staff.isEmpty()) {
            log.warn("Tenant {} has no staff to notify about: {}", tenantId, subject);
            return;
        }
        for (Long userId : staff) {
            users.findById(userId).ifPresent(user -> send(user, subject, line, path));
        }
    }

    private void send(User user, String subject, String line, String path) {
        if (!AppConstant.isLive(user.getStatus())) return;
        try {
            Set<String> channels = consent.channelsFor(user.getId(), AppConstant.CONSENT_TRANSACTIONAL);
            String link = publicUrl() + path;

            if (channels.contains(AppConstant.CONSENT_CHANNEL_EMAIL)) {
                notify.sendEmail(user.getEmail(), subject,
                        mail.notice(user.getFirstName(), line, "Open it on " + platformName(), link),
                        user.fullName());
            }
            if (channels.contains(AppConstant.CONSENT_CHANNEL_SMS)) {
                notify.sendSms(user.getPhone(), "Hodi: " + line + " " + link, user.fullName());
            }
        } catch (Exception e) {
            // Courtesy, not correctness. The row is already written.
            log.warn("Could not notify user {} about '{}': {}", user.getId(), subject, e.getMessage());
        }
    }

    private String publicUrl() {
        String url = configs.getString(ConfigKey.PUBLIC_URL);
        if (url == null || url.isBlank()) return "";
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    /** The link label names the destination rather than saying "here", which reads as spam. */
    private String platformName() {
        return configs.getString(ConfigKey.COMPANY_NAME);
    }
}
