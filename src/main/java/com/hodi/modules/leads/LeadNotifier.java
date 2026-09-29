package com.hodi.modules.leads;

import com.hodi.modules.notifications.NotificationService;
import com.hodi.modules.notifications.NotificationService.About;
import com.hodi.modules.notifications.NotificationService.Event;
import com.hodi.modules.profiles.UserProfileRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Tells the other side that something happened (M4).
 *
 * <p>Thin: this names the event and who is told, and hands over the figures; the catalogue holds the
 * words and the organisation's say, the notification service asks consent, writes the log and the
 * inbox, sends and retries. Best effort, never in the way of the row being written.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LeadNotifier {

    private final UserProfileRepository profiles;
    private final NotificationService notifications;

    /** The buyer, about something their seller did. The seller is the organisation whose event it is. */
    public void toBuyer(Long userId, String code, Map<String, ?> model, String path, About about, Long sellerTenantId) {
        notifications.event(userId, Event.of(code, model, path, about).forOrganisation(sellerTenantId, null));
    }

    /**
     * The seller, about something a buyer did.
     *
     * <p>Sent to every live person at that organisation who could act on it. Not to an "enquiries@" address:
     * the platform does not have one, and a shared mailbox nobody owns is how leads go cold.
     */
    public void toSeller(Long tenantId, String code, Map<String, ?> model, String path, About about) {
        List<Long> staff = profiles.findLiveUserIdsByTenant(tenantId);
        if (staff.isEmpty()) {
            log.warn("Tenant {} has no staff to notify about {}", tenantId, code);
            return;
        }
        notifications.event(staff, Event.of(code, model, path, about).forOrganisation(tenantId, null));
    }
}
