package com.hodi.modules.leads;

import com.hodi.modules.notifications.NotificationService;
import com.hodi.modules.notifications.NotificationService.About;
import com.hodi.modules.notifications.NotificationService.Notice;
import com.hodi.modules.profiles.UserProfileRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Tells the other side that something happened (M4).
 *
 * <p>Thin since the notification service arrived: this names who is told and hands over the words; the
 * service asks consent, writes the log and the inbox, sends, and retries. Transactional, and that is not
 * a loophole — a reply to a question you asked, a decision on an offer you made — and best effort,
 * never in the way of the row being written.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LeadNotifier {

    public static final String EVENT = "LEADS";

    private final UserProfileRepository profiles;
    private final NotificationService notifications;

    /** The buyer, about something their seller did. */
    public void toBuyer(Long userId, String subject, String line, String path) {
        toBuyer(userId, subject, line, path, null);
    }

    public void toBuyer(Long userId, String subject, String line, String path, About about) {
        notifications.toUser(userId, Notice.transactional(EVENT, subject, line, path, about));
    }

    /**
     * The seller, about something a buyer did.
     *
     * <p>Sent to every live person at that organisation who could act on it. Not to an "enquiries@" address:
     * the platform does not have one, and a shared mailbox nobody owns is how leads go cold.
     */
    public void toSeller(Long tenantId, String subject, String line, String path) {
        toSeller(tenantId, subject, line, path, null);
    }

    public void toSeller(Long tenantId, String subject, String line, String path, About about) {
        List<Long> staff = profiles.findLiveUserIdsByTenant(tenantId);
        if (staff.isEmpty()) {
            log.warn("Tenant {} has no staff to notify about: {}", tenantId, subject);
            return;
        }
        notifications.toUsers(staff, Notice.transactional(EVENT, subject, line, path, about));
    }
}
