package com.hodi.modules.bookings;

import com.hodi.modules.notifications.NotificationService;
import com.hodi.modules.notifications.NotificationService.About;
import com.hodi.modules.notifications.NotificationService.Contact;
import com.hodi.modules.notifications.NotificationService.Notice;
import com.hodi.modules.users.UserRepository;
import com.hodi.security.hashid.HashIdUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Tells the buyer about their booking: the terms waiting for them, a hold about to lapse, a receipt.
 *
 * <p>A booking's buyer is not always a user. One made over the counter carries a name, a phone and
 * perhaps an email, and no account. So this reaches the account when there is one and the contact on the
 * booking when there is not — the notification service records both the same way.
 */
@Component
@RequiredArgsConstructor
public class BookingNotifier {

    public static final String EVENT = "BOOKINGS";

    private final NotificationService notifications;
    private final UserRepository users;

    /** The buyer, through their account or the booking's own contact. */
    public void toBuyer(UnitBooking booking, String subject, String line) {
        toBuyer(booking, EVENT, subject, line);
    }

    public void toBuyer(UnitBooking booking, String eventCode, String subject, String line) {
        About about = new About("BOOKING", booking.getId(), booking.getReference());
        if (booking.getBuyerUserId() != null) {
            // Hashed ids are salted per signed-in user: the link has to be encoded as the buyer, not as
            // whoever is acting, or it decodes to nothing when they open it.
            String path = users.findById(booking.getBuyerUserId())
                    .map(u -> "/account/bookings/" + HashIdUtil.encodeId(booking.getId(), u.getUsername()))
                    .orElse("/account/bookings");
            notifications.toUser(booking.getBuyerUserId(), Notice.transactional(eventCode, subject, line, path, about));
        } else {
            // No account, so no link that could open a booking of theirs; the list is where they will land.
            notifications.toContact(new Contact(booking.getBuyerName(), booking.getBuyerEmail(), booking.getBuyerPhone()),
                    Notice.transactional(eventCode, subject, line, "/account/bookings", about));
        }
    }
}
