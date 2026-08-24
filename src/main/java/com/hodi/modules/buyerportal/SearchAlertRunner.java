package com.hodi.modules.buyerportal;

import com.hodi.common.AppConstant;
import com.hodi.enums.ConfigKey;
import com.hodi.infra.notify.NotifyClient;
import com.hodi.infra.notify.NotifyResult;
import com.hodi.modules.audit.AuditService;
import com.hodi.modules.configurations.ConfigurationService;
import com.hodi.modules.consent.ConsentService;
import com.hodi.modules.properties.Property;
import com.hodi.modules.properties.PropertyDtos.PublicSearchRequest;
import com.hodi.modules.properties.PublicPropertyService;
import com.hodi.modules.users.User;
import com.hodi.modules.users.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.text.NumberFormat;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * One saved search: run it, decide whether anything may be sent, send it, record what happened.
 *
 * <h2>Consent decides, every time</h2>
 *
 * <p>Nothing goes out on a channel the consent store does not report as granted for
 * {@code PROPERTY_ALERTS}, and the question is asked per run rather than per alert. Somebody who opts out
 * this morning stops hearing from every search they have ever saved this morning, without anything having to
 * go back and edit those rows. An empty channel set means silence — never a fallback to email, which would
 * make the recorded refusal decorative.
 *
 * <h2>Its own transaction, whatever happens</h2>
 *
 * <p>{@code REQUIRES_NEW}, and the bookkeeping is written on every path including the ones that send
 * nothing. An alert whose window was not advanced would re-scan the same period on the next pass and mail
 * the same listings again — the failure a buyer actually notices.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SearchAlertRunner {

    /** How many matches one message reports. Beyond this it is a list nobody reads. */
    static final int MAX_MATCHES = 10;

    private final SearchAlertRepository repository;
    private final PublicPropertyService marketplace;
    private final ConsentService consent;
    private final UserRepository users;
    private final NotifyClient notify;
    private final ConfigurationService configs;
    private final AuditService audit;

    /**
     * @return whether a message was delivered — the dispatcher counts these for its log line
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean runOne(Long alertId) {
        // Re-read inside the transaction rather than trusted from the batch: the poll's snapshot may be
        // minutes old by the time this row's turn comes, and the person may have paused it in between.
        SearchAlert alert = repository.findById(alertId).orElse(null);
        if (alert == null || !alert.isRunning()) return false;

        OffsetDateTime ranAt = OffsetDateTime.now();
        List<Property> matches = marketplace.newMatches(
                criteriaOf(alert), alert.getLastRunAt(), MAX_MATCHES + 1);

        alert.setLastRunAt(ranAt);
        alert.setNextRunAt(alert.scheduleAfter(ranAt));
        alert.setLastMatchCount(matches.size());

        if (matches.isEmpty()) {
            alert.setLastOutcome(AppConstant.ALERT_OUTCOME_NO_MATCHES);
            repository.save(alert);
            return false;
        }

        Set<String> channels = consent.channelsFor(alert.getUserId(), AppConstant.CONSENT_PROPERTY_ALERTS);
        if (channels.isEmpty()) {
            // Recorded rather than silently dropped, so the buyer's own screen can say "saved, but you have
            // switched property alerts off" instead of leaving them to wonder why it is quiet.
            alert.setLastOutcome(AppConstant.ALERT_OUTCOME_NO_CONSENT);
            repository.save(alert);
            audit.record(AppConstant.AUDIT_ALERT_RUN, "SearchAlert", alert.getId(), null,
                    count(matches) + " held back — no channel consented");
            return false;
        }

        User owner = users.findById(alert.getUserId()).orElse(null);
        if (owner == null || !AppConstant.isLive(owner.getStatus())) {
            alert.setLastOutcome(AppConstant.ALERT_OUTCOME_FAILED);
            repository.save(alert);
            return false;
        }

        boolean sent = false;
        List<String> attempts = new ArrayList<>(2);
        if (channels.contains(AppConstant.CONSENT_CHANNEL_EMAIL)) {
            NotifyResult result = notify.sendEmail(owner.getEmail(), subject(alert, matches),
                    emailBody(alert, matches, owner), owner.fullName());
            sent |= result.success();
            attempts.add("EMAIL=" + describe(result));
        }
        if (channels.contains(AppConstant.CONSENT_CHANNEL_SMS)) {
            NotifyResult result = notify.sendSms(owner.getPhone(), smsBody(alert, matches),
                    owner.fullName());
            sent |= result.success();
            attempts.add("SMS=" + describe(result));
        }

        alert.setLastOutcome(sent ? AppConstant.ALERT_OUTCOME_SENT : AppConstant.ALERT_OUTCOME_FAILED);
        if (sent) alert.setTotalSent(alert.getTotalSent() + 1);
        repository.save(alert);

        /*
         * The per-channel reason goes in the audit row, not on the alert.
         *
         * From the buyer's side there are two outcomes — it arrived or it did not — and the row carries
         * that. Operations need the third distinction underneath it: a gateway that rejected the message is
         * worth investigating, and a channel switched off in configuration is worth a different
         * conversation entirely. That difference belongs where somebody looking into delivery will find it.
         */
        audit.record(AppConstant.AUDIT_ALERT_RUN, "SearchAlert", alert.getId(), null,
                count(matches) + ", " + String.join(", ", attempts));
        return sent;
    }

    private static String count(List<Property> matches) {
        return matches.size() + (matches.size() == 1 ? " match" : " matches");
    }

    /** "sent", "skipped: EMAIL_DISABLED", "failed: HTTP 502" — the outcome plus why, in one token. */
    private static String describe(NotifyResult result) {
        if (result.success()) return "sent";
        return (result.skipped() ? "skipped" : "failed") + ": " + result.error();
    }

    // ── the search ────────────────────────────────────────────────────────────

    /** The stored columns, back into the request object the marketplace's own endpoint is bound from. */
    private static PublicSearchRequest criteriaOf(SearchAlert alert) {
        PublicSearchRequest request = new PublicSearchRequest();
        request.setSearch(alert.getSearchTerm());
        request.setPropertyType(alert.getPropertyType());
        request.setCounty(alert.getCounty());
        request.setTown(alert.getTown());
        request.setMinPrice(alert.getMinPrice());
        request.setMaxPrice(alert.getMaxPrice());
        request.setMinBedrooms(alert.getMinBedrooms());
        request.setMaxBedrooms(alert.getMaxBedrooms());
        request.setGreenOnly(alert.isGreenOnly());
        return request;
    }

    // ── the messages ──────────────────────────────────────────────────────────

    private String subject(SearchAlert alert, List<Property> matches) {
        return matches.size() == 1
                ? "A new listing matches “" + alert.getName() + "”"
                : matches.size() + " new listings match “" + alert.getName() + "”";
    }

    /**
     * The email.
     *
     * <p>Inlined styles, no external stylesheet and no image the recipient has to load to read it — every
     * mail client blocks those by default, and a listing alert that renders as a column of broken frames is
     * worse than a plain one.
     */
    private String emailBody(SearchAlert alert, List<Property> matches, User owner) {
        String base = publicUrl();
        StringBuilder html = new StringBuilder(1024);
        html.append("<div style=\"font-family:Helvetica,Arial,sans-serif;color:#12211c;max-width:560px\">");
        html.append("<p>Hello ").append(escape(owner.getFirstName())).append(",</p>");
        html.append("<p>").append(matches.size() == 1 ? "A new listing matches" : "New listings match")
                .append(" your saved search <strong>").append(escape(alert.getName()))
                .append("</strong>:</p>");

        for (Property property : matches.stream().limit(MAX_MATCHES).toList()) {
            html.append("<div style=\"border:1px solid #e2e8e5;border-radius:10px;padding:14px;")
                    .append("margin:0 0 10px\">")
                    .append("<div style=\"font-weight:600;font-size:15px\">")
                    .append("<a style=\"color:#12211c;text-decoration:none\" href=\"")
                    .append(base).append("/property/").append(property.getReference()).append("\">")
                    .append(escape(property.getTitle())).append("</a></div>")
                    .append("<div style=\"color:#5c6b66;font-size:13px;margin-top:3px\">")
                    .append(escape(where(property))).append("</div>")
                    .append("<div style=\"font-weight:600;margin-top:6px\">")
                    .append(money(property.getPrice(), property.getCurrency())).append("</div>")
                    .append("</div>");
        }

        if (matches.size() > MAX_MATCHES) {
            html.append("<p style=\"color:#5c6b66;font-size:13px\">…and more. ")
                    .append("<a href=\"").append(base).append("\">See the full search</a>.</p>");
        }

        // Every non-transactional message carries the way to stop receiving it. A preference somebody has to
        // go looking for is a preference they will report as spam instead.
        html.append("<hr style=\"border:none;border-top:1px solid #e2e8e5;margin:20px 0\">")
                .append("<p style=\"color:#5c6b66;font-size:12px\">")
                .append("You are receiving this because you saved a search on Hodi Market Place. ")
                .append("<a href=\"").append(base).append("/account/alerts\">Manage your saved searches</a>")
                .append(" or <a href=\"").append(base)
                .append("/account/notifications\">change what we send you</a>.</p></div>");
        return html.toString();
    }

    /**
     * The text.
     *
     * <p>One listing named and a link, never a list: an SMS that runs to four parts costs four times as much
     * and reads as one badly formatted mess.
     */
    private String smsBody(SearchAlert alert, List<Property> matches) {
        Property first = matches.getFirst();
        String more = matches.size() > 1 ? " and " + (matches.size() - 1) + " more" : "";
        return "Hodi: " + first.getTitle() + " — " + money(first.getPrice(), first.getCurrency())
                + " in " + where(first) + more + ". Matches your saved search \"" + alert.getName()
                + "\". " + publicUrl() + "/property/" + first.getReference();
    }

    private String publicUrl() {
        String url = configs.getString(ConfigKey.PUBLIC_URL);
        if (url == null || url.isBlank()) return "";
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    private static String where(Property property) {
        if (property.getTown() != null && property.getCounty() != null) {
            return property.getTown() + ", " + property.getCounty();
        }
        if (property.getTown() != null) return property.getTown();
        return property.getCounty() == null ? "" : property.getCounty();
    }

    private static String money(BigDecimal amount, String currency) {
        if (amount == null) return "";
        NumberFormat format = NumberFormat.getIntegerInstance(Locale.UK);
        return (currency == null ? "KES" : currency) + " " + format.format(amount);
    }

    /**
     * Escapes text that came from a seller into an email body.
     *
     * <p>A listing title is somebody else's input and the recipient's mail client renders whatever arrives.
     * The title is validated for length on the way in and for nothing else, so this is the boundary at which
     * it stops being markup.
     */
    private static String escape(String value) {
        if (value == null) return "";
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }
}
