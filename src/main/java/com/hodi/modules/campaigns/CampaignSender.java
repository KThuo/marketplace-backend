package com.hodi.modules.campaigns;

import com.hodi.common.AppConstant;
import com.hodi.enums.ConfigKey;
import com.hodi.infra.notify.MailTemplate;
import com.hodi.infra.notify.NotifyResult;
import com.hodi.modules.configurations.ConfigurationService;
import com.hodi.modules.consent.ConsentService;
import com.hodi.modules.notifications.NotificationCatalogue;
import com.hodi.modules.notifications.NotificationService;
import com.hodi.modules.notifications.NotificationService.About;
import com.hodi.modules.notifications.NotificationService.Notice;
import com.hodi.modules.notifications.UnsubscribeService;
import com.hodi.modules.users.User;
import com.hodi.modules.users.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.*;
import java.util.*;

/**
 * Sends approved campaigns, a batch a minute, inside the window, under the cap.
 *
 * <p>Each recipient goes through the notification service like any other message — consent asked again
 * at the moment of sending, because a person who unsubscribed between approval and their turn is not sent
 * to; a row in the log per channel; an inbox line when in-app is among the channels — and every email and
 * text carries the one-click way out. A campaign that runs out of window or cap resumes at the next
 * opening.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CampaignSender {

    private static final long LOCK_KEY = 7_260_929_005L;
    private static final ZoneId NAIROBI = ZoneId.of("Africa/Nairobi");

    private final CampaignRepository campaigns;
    private final CampaignSendRepository sends;
    private final UserRepository users;
    private final ConsentService consent;
    private final NotificationCatalogue catalogue;
    private final NotificationService notifications;
    private final UnsubscribeService unsubscribe;
    private final MailTemplate mail;
    private final ConfigurationService configs;
    private final TransactionTemplate newTransaction;
    private final JdbcTemplate jdbc;

    @Scheduled(fixedRate = 60_000, initialDelay = 45_000)
    public void sweep() {
        Boolean acquired = newTransaction.execute(status ->
                jdbc.queryForObject("select pg_try_advisory_xact_lock(?)", Boolean.class, LOCK_KEY));
        if (!Boolean.TRUE.equals(acquired)) return;
        try {
            newTransaction.executeWithoutResult(status -> pass(OffsetDateTime.now()));
        } catch (Exception e) {
            log.warn("Campaign sweep failed: {}", e.getMessage());
        }
    }

    /**
     * One minute's work: the next batch of the campaign whose turn it is, if the window is open and the cap
     * has room. Runs in whatever transaction the caller holds — the sweep opens one; a test uses its own.
     */
    public int pass(OffsetDateTime now) {
        if (!insideWindow(now.atZoneSameInstant(NAIROBI).toLocalTime(), window(ConfigKey.NOTIFY_CAMPAIGN_WINDOW_START, LocalTime.of(8, 0)),
                window(ConfigKey.NOTIFY_CAMPAIGN_WINDOW_END, LocalTime.of(20, 0)))) return 0;
        long cap = Math.max(0, configs.getInt(ConfigKey.NOTIFY_CAMPAIGN_DAILY_CAP, 2000));
        long sentToday = sends.countSentSince(now.atZoneSameInstant(NAIROBI).toLocalDate().atStartOfDay(NAIROBI).toOffsetDateTime());
        long room = cap - sentToday;
        if (room <= 0) return 0;
        int batch = (int) Math.min(room, Math.max(1, configs.getInt(ConfigKey.NOTIFY_CAMPAIGN_BATCH_SIZE, 100)));

        int done = 0;
        for (Campaign campaign : campaigns.findDue(now)) {
            if (done >= batch) break;
            if (Campaign.APPROVED.equals(campaign.getState())) {
                campaign.setState(Campaign.SENDING);
                campaign.setStartedAt(now);
                campaigns.save(campaign);
            }
            List<CampaignSend> pending = sends.findPending(campaign.getId(), PageRequest.of(0, batch - done));
            for (CampaignSend send : pending) {
                deliver(campaign, send, now);
                done++;
            }
            if (sends.countByCampaignIdAndState(campaign.getId(), CampaignSend.PENDING) == 0) {
                campaign.setState(Campaign.SENT);
                campaign.setFinishedAt(now);
                campaigns.save(campaign);
                log.info("Campaign {} finished: {} sent, {} failed, {} skipped", campaign.getReference(),
                        campaign.getSentCount(), campaign.getFailedCount(), campaign.getSkippedCount());
            }
        }
        return done;
    }

    /** One recipient: consent now, the campaign's channels within the catalogue's, and the log. */
    private void deliver(Campaign campaign, CampaignSend send, OffsetDateTime now) {
        User user = users.findById(send.getUserId()).orElse(null);
        if (user == null || !AppConstant.isLive(user.getStatus())) {
            skip(campaign, send, "NOT_LIVE", now);
            return;
        }
        Set<String> channels = new LinkedHashSet<>(consent.channelsFor(user.getId(), AppConstant.CONSENT_PROMOTIONAL));
        channels.retainAll(Arrays.asList(campaign.getChannels().split(",")));
        channels.retainAll(catalogue.resolve("CAMPAIGN", campaign.getTenantId(), campaign.getInstitutionId())
                .filter(NotificationCatalogue.Resolved::enabled).map(NotificationCatalogue.Resolved::channels).orElse(Set.of()));
        if (channels.isEmpty()) {
            skip(campaign, send, "NO_CONSENT", now);
            return;
        }
        boolean any = false;
        List<String> outcomes = new ArrayList<>();
        for (String channel : channels) {
            NotifyResult result = sendOn(campaign, user, channel);
            outcomes.add(channel + "=" + (result.success() ? "sent" : result.skipped() ? "skipped" : "failed"));
            any |= result.success();
        }
        send.setChannels(String.join(",", channels));
        send.setSentAt(now);
        send.setState(any ? CampaignSend.SENT : CampaignSend.FAILED);
        send.setError(any ? null : String.join(", ", outcomes));
        sends.save(send);
        if (any) campaign.setSentCount(campaign.getSentCount() + 1);
        else campaign.setFailedCount(campaign.getFailedCount() + 1);
        campaigns.save(campaign);
    }

    private void skip(Campaign campaign, CampaignSend send, String why, OffsetDateTime now) {
        send.setState(CampaignSend.SKIPPED);
        send.setError(why);
        send.setSentAt(now);
        sends.save(send);
        campaign.setSkippedCount(campaign.getSkippedCount() + 1);
        campaigns.save(campaign);
    }

    private NotifyResult sendOn(Campaign campaign, User user, String channel) {
        Notice notice = new Notice("CAMPAIGN", AppConstant.CONSENT_PROMOTIONAL, campaign.getSubject(), firstLine(campaign.getBody()),
                campaign.getCtaPath() == null ? "/account/notifications" : campaign.getCtaPath(),
                new About("CAMPAIGN", campaign.getId(), campaign.getReference()));
        String out = unsubscribe.linkFor(user.getId(), AppConstant.CONSENT_PROMOTIONAL);
        return switch (channel) {
            case AppConstant.CONSENT_CHANNEL_EMAIL -> notifications.composed(user.getId(), notice, channel, user.getEmail(), emailBody(campaign, user, out));
            case AppConstant.CONSENT_CHANNEL_SMS -> notifications.composed(user.getId(), notice, channel, user.getPhone(), smsBody(campaign, out));
            default -> notifications.composed(user.getId(), notice, channel, null, null);
        };
    }

    /** To the author alone, now, whatever their consent says: they are reading it as a recipient would. */
    @Transactional
    public void sendTestTo(Campaign campaign, Long userId) {
        User user = users.findById(userId).orElseThrow();
        String out = unsubscribe.linkFor(user.getId(), AppConstant.CONSENT_PROMOTIONAL);
        Notice notice = new Notice("CAMPAIGN", AppConstant.CONSENT_TRANSACTIONAL, "[Test] " + campaign.getSubject(), firstLine(campaign.getBody()),
                campaign.getCtaPath() == null ? "/account/notifications" : campaign.getCtaPath(),
                new About("CAMPAIGN", campaign.getId(), campaign.getReference()));
        for (String channel : campaign.getChannels().split(",")) {
            switch (channel) {
                case AppConstant.CONSENT_CHANNEL_EMAIL -> notifications.composed(user.getId(), notice, channel, user.getEmail(), emailBody(campaign, user, out));
                case AppConstant.CONSENT_CHANNEL_SMS -> notifications.composed(user.getId(), notice, channel, user.getPhone(), smsBody(campaign, out));
                default -> notifications.composed(user.getId(), notice, channel, null, null);
            }
        }
    }

    // ── words ─────────────────────────────────────────────────────────────────

    String emailBody(Campaign campaign, User user, String unsubscribeLink) {
        List<String> paragraphs = Arrays.stream(campaign.getBody().split("\\n\\s*\\n")).map(String::trim).filter(p -> !p.isEmpty()).toList();
        String href = campaign.getCtaPath() == null ? null : (campaign.getCtaPath().startsWith("http") ? campaign.getCtaPath() : publicUrl() + campaign.getCtaPath());
        String footer = "<p style=\"margin:0 0 8px\">You are receiving this because you agreed to hear about offers and news. "
                + MailTemplate.link(mail.palette(), "Stop these messages", unsubscribeLink) + " — one click, no sign-in — or "
                + MailTemplate.link(mail.palette(), "change what we send you", publicUrl() + "/account/notifications") + ".</p>";
        return mail.campaign(user.getFirstName(), paragraphs, campaign.getCtaLabel(), href, footer);
    }

    String smsBody(Campaign campaign, String unsubscribeLink) {
        String text = "Hodi: " + firstLine(campaign.getBody()) + " Stop: " + unsubscribeLink;
        return text.length() <= 480 ? text : text.substring(0, 480);
    }

    private static String firstLine(String body) {
        String first = body.split("\\n")[0].trim();
        return first.length() <= 200 ? first : first.substring(0, 197) + "…";
    }

    static boolean insideWindow(LocalTime now, LocalTime start, LocalTime end) {
        return !now.isBefore(start) && now.isBefore(end);
    }

    private LocalTime window(ConfigKey key, LocalTime fallback) {
        try {
            String raw = configs.getString(key);
            return raw == null || raw.isBlank() ? fallback : LocalTime.parse(raw.trim());
        } catch (Exception e) {
            return fallback;
        }
    }

    private String publicUrl() {
        String url = configs.getString(ConfigKey.PUBLIC_URL);
        if (url == null || url.isBlank()) return "";
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
