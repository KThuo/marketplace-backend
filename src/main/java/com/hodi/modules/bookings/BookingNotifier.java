package com.hodi.modules.bookings;

import com.hodi.common.AppConstant;
import com.hodi.enums.ConfigKey;
import com.hodi.infra.notify.MailTemplate;
import com.hodi.infra.notify.NotifyClient;
import com.hodi.modules.configurations.ConfigurationService;
import com.hodi.modules.consent.ConsentService;
import com.hodi.modules.users.User;
import com.hodi.modules.users.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * Tells the buyer about their booking: the terms waiting for them, a hold about to lapse.
 *
 * <p>A booking's buyer is not always a user. One made over the counter carries a name, a phone and
 * perhaps an email, and no account. So this reaches the account when there is one — through the consent
 * store, as the leads notifier does — and the contact on the booking when there is not, because the
 * terms have to reach the person whichever way they came in. Best effort, never in the way.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BookingNotifier {

    private final UserRepository users;
    private final ConsentService consent;
    private final NotifyClient notify;
    private final MailTemplate mail;
    private final ConfigurationService configs;

    /** The buyer, through their account or the booking's own contact. */
    public void toBuyer(UnitBooking booking, String subject, String line) {
        String path = "/account/bookings/" + com.hodi.security.hashid.HashIdUtil.encodeId(booking.getId());
        try {
            User user = booking.getBuyerUserId() == null ? null : users.findById(booking.getBuyerUserId()).orElse(null);
            String link = publicUrl() + path;
            if (user != null && AppConstant.isLive(user.getStatus())) {
                Set<String> channels = consent.channelsFor(user.getId(), AppConstant.CONSENT_TRANSACTIONAL);
                if (channels.contains(AppConstant.CONSENT_CHANNEL_EMAIL)) {
                    notify.sendEmail(user.getEmail(), subject,
                            mail.notice(user.getFirstName(), line, "Open your booking", link), user.fullName());
                }
                if (channels.contains(AppConstant.CONSENT_CHANNEL_SMS)) {
                    notify.sendSms(user.getPhone(), "Hodi: " + line + " " + link, user.fullName());
                }
                return;
            }
            // No account: the contact the sales office took. The link invites them to sign in or sign up.
            String firstName = booking.getBuyerName() == null ? "" : booking.getBuyerName().trim().split("\\s+")[0];
            if (booking.getBuyerEmail() != null && !booking.getBuyerEmail().isBlank()) {
                notify.sendEmail(booking.getBuyerEmail(), subject,
                        mail.notice(firstName, line, "Open your booking", link), booking.getBuyerName());
            }
            if (booking.getBuyerPhone() != null && !"-".equals(booking.getBuyerPhone().trim())) {
                notify.sendSms(booking.getBuyerPhone(), "Hodi: " + line + " " + link, booking.getBuyerName());
            }
        } catch (Exception e) {
            log.warn("Could not notify the buyer of booking {} about '{}': {}", booking.getReference(), subject,
                    e.getMessage());
        }
    }

    private String publicUrl() {
        String url = configs.getString(ConfigKey.PUBLIC_URL);
        if (url == null || url.isBlank()) return "";
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
