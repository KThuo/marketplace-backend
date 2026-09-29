package com.hodi.modules.campaigns;

import com.hodi.common.AppConstant;
import com.hodi.common.PagedResponse;
import com.hodi.common.dto.PagedDataRequest;
import com.hodi.common.exception.HodiException;
import com.hodi.common.exception.ResourceNotFoundException;
import com.hodi.common.util.RrnGenerator;
import com.hodi.common.util.SearchSpecs;
import com.hodi.enums.ConfigKey;
import com.hodi.modules.approvals.ApprovalService;
import com.hodi.modules.approvals.ChangeSet;
import com.hodi.modules.audit.AuditService;
import com.hodi.modules.configurations.ConfigurationService;
import com.hodi.modules.notifications.NotificationCatalogue;
import com.hodi.security.principal.AuthContext;
import com.hodi.security.principal.UserPrincipal;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.*;

/**
 * Campaigns: written, checked, and sent to the people who agreed to hear from us (plan §3.6).
 *
 * <h2>Whose campaign</h2>
 *
 * <p>The platform's go to every buyer with promotional consent, narrowed by county or by when they joined.
 * An organisation's go to its own buyers — the people who enquired about, offered on or booked its homes —
 * and only when the platform has opened that door ({@code notify.organisation.wording.enabled}).
 *
 * <h2>Checked before it goes</h2>
 *
 * <p>A submitted campaign is an approval like any other: a second person with {@code CAMPAIGNS_APPROVE}
 * approves it, and at that moment the audience is fixed as a row per recipient so the number the approver
 * saw is the number it goes to. The sweep does the sending.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CampaignService {

    private final CampaignRepository campaigns;
    private final CampaignSendRepository sends;
    private final ApprovalService approvals;
    private final NotificationCatalogue catalogue;
    private final ConfigurationService configs;
    private final CampaignSender sender;
    private final AuditService audit;
    private final JdbcTemplate jdbc;

    // ── DTOs ──────────────────────────────────────────────────────────────────

    public record SaveRequest(
            @NotBlank(message = "Give the campaign a title") @Size(max = 160) String title,
            @Size(max = 64) String county,
            LocalDate registeredSince,
            List<String> channels,
            @NotBlank(message = "A subject is required") @Size(max = 255) String subject,
            @NotBlank(message = "Say something") String body,
            @Size(max = 80) String ctaLabel,
            @Size(max = 255) String ctaPath,
            OffsetDateTime scheduledFor) {}

    public record CampaignResponse(
            String reference, String title, String ownerName, String audience, String county, LocalDate registeredSince,
            List<String> channels, String subject, String body, String ctaLabel, String ctaPath, String state,
            OffsetDateTime scheduledFor, Integer audienceCount, int sentCount, int failedCount, int skippedCount,
            OffsetDateTime startedAt, OffsetDateTime finishedAt, String approvedBy, OffsetDateTime approvedAt,
            String cancelReason, String createdBy, OffsetDateTime createdAt,
            boolean mayEdit, boolean maySubmit, boolean mayApprove, boolean mayCancel, boolean mayTest) {}

    public record SendRow(String userId, String state, String channels, OffsetDateTime sentAt, String error) {}

    @Getter @Setter
    public static class CampaignListRequest extends PagedDataRequest {
        private String state;
    }

    // ── drafting ──────────────────────────────────────────────────────────────

    @Transactional
    public CampaignResponse create(SaveRequest request) {
        UserPrincipal caller = AuthContext.require();
        requireMayRun(caller);
        Campaign campaign = campaigns.save(apply(Campaign.builder()
                .reference(nextReference())
                .tenantId(caller.getTenantId())
                .institutionId(caller.getTenantId() == null ? caller.getInstitutionId() : null)
                .createdBy(caller.getUsername())
                .build(), request));
        audit.record(AppConstant.ACTION_CREATE, "Campaign", campaign.getId(), null, campaign.getReference() + " " + campaign.getTitle());
        return toResponse(campaign);
    }

    @Transactional
    public CampaignResponse update(String reference, SaveRequest request) {
        Campaign campaign = requireOwn(reference);
        if (!campaign.isEditable()) throw new HodiException("A campaign is edited while it is a draft.", HttpStatus.CONFLICT);
        String before = campaign.getSubject() + " / " + campaign.getBody().length() + " chars";
        campaigns.save(apply(campaign, request));
        audit.record(AppConstant.ACTION_UPDATE, "Campaign", campaign.getId(), before, campaign.getSubject() + " / " + campaign.getBody().length() + " chars");
        return toResponse(campaign);
    }

    private Campaign apply(Campaign c, SaveRequest r) {
        c.setTitle(r.title().trim());
        c.setCounty(r.county() == null || r.county().isBlank() ? null : r.county().trim());
        c.setRegisteredSince(r.registeredSince());
        c.setChannels(channelCsv(r.channels()));
        c.setSubject(r.subject().trim());
        c.setBody(r.body().trim());
        c.setCtaLabel(r.ctaLabel() == null || r.ctaLabel().isBlank() ? null : r.ctaLabel().trim());
        c.setCtaPath(r.ctaPath() == null || r.ctaPath().isBlank() ? null : r.ctaPath().trim());
        c.setScheduledFor(r.scheduledFor());
        c.setUpdatedBy(AuthContext.username());
        return c;
    }

    /** The channels a campaign may use: what the author chose, within what the catalogue allows CAMPAIGN. */
    private String channelCsv(List<String> chosen) {
        Set<String> allowed = catalogue.resolve("CAMPAIGN", null, null).map(NotificationCatalogue.Resolved::channels)
                .orElse(Set.of(AppConstant.CONSENT_CHANNEL_EMAIL, AppConstant.CONSENT_CHANNEL_IN_APP));
        Set<String> out = new LinkedHashSet<>();
        for (String c : chosen == null ? List.<String>of() : chosen) {
            String v = c == null ? "" : c.trim().toUpperCase();
            if (allowed.contains(v)) out.add(v);
        }
        if (out.isEmpty()) throw new HodiException("Choose at least one channel the platform allows for campaigns: "
                + String.join(", ", allowed) + ".", HttpStatus.BAD_REQUEST);
        return String.join(",", out);
    }

    // ── the audience ──────────────────────────────────────────────────────────

    /**
     * Who would receive it today: live buyers with promotional consent on at least one of the campaign's
     * channels, narrowed by the filters — and, for an organisation's campaign, only its own buyers.
     */
    @Transactional(readOnly = true)
    public int audienceCount(String reference) {
        return audienceIds(requireOwn(reference)).size();
    }

    List<Long> audienceIds(Campaign c) {
        List<Object> args = new ArrayList<>();
        StringBuilder sql = new StringBuilder("""
                select distinct u.id
                  from users u
                  join user_profiles p on p.user_id = u.id and p.profile_type = 'BUYER' and p.status not in (4, 5)
                 where u.status not in (4, 5)
                   and exists (select 1 from consent_preferences cp where cp.user_id = u.id and cp.purpose = 'PROMOTIONAL'
                                 and cp.granted and cp.channel in (%s))
                """.formatted(String.join(",", Collections.nCopies(c.getChannels().split(",").length, "?"))));
        args.addAll(Arrays.asList(c.getChannels().split(",")));
        if (c.getRegisteredSince() != null) {
            sql.append(" and u.created_at >= ?");
            args.add(java.sql.Date.valueOf(c.getRegisteredSince()));
        }
        if (c.getCounty() != null) {
            sql.append(" and (exists (select 1 from search_alerts a where a.user_id = u.id and a.status <> 5 and upper(a.county) = ?)"
                    + " or exists (select 1 from enquiry_tickets e join properties pr on pr.id = e.property_id where e.user_id = u.id and upper(pr.county) = ?))");
            args.add(c.getCounty().toUpperCase());
            args.add(c.getCounty().toUpperCase());
        }
        if (c.getTenantId() != null) {
            sql.append(" and (exists (select 1 from enquiry_tickets e where e.user_id = u.id and e.tenant_id = ?)"
                    + " or exists (select 1 from purchase_requests o where o.user_id = u.id and o.tenant_id = ?)"
                    + " or exists (select 1 from unit_bookings b where b.buyer_user_id = u.id and b.tenant_id = ?))");
            args.add(c.getTenantId()); args.add(c.getTenantId()); args.add(c.getTenantId());
        } else if (c.getInstitutionId() != null) {
            sql.append(" and exists (select 1 from unit_bookings b where b.buyer_user_id = u.id and b.institution_id = ?)");
            args.add(c.getInstitutionId());
        }
        sql.append(" order by u.id");
        return jdbc.queryForList(sql.toString(), Long.class, args.toArray());
    }

    // ── the road to sending ───────────────────────────────────────────────────

    /** To the checker. The audience count is what they see beside the words. */
    @Transactional
    public CampaignResponse submit(String reference) {
        Campaign campaign = requireOwn(reference);
        if (!campaign.isEditable()) throw new HodiException("That campaign is already " + campaign.getState().toLowerCase() + ".", HttpStatus.CONFLICT);
        int count = audienceIds(campaign).size();
        if (count == 0) throw new HodiException("Nobody would receive this campaign today: nobody in its audience has agreed to "
                + "promotional messages on its channels.", HttpStatus.CONFLICT);
        campaign.setAudienceCount(count);
        campaign.setState(Campaign.SUBMITTED);
        campaign.setUpdatedBy(AuthContext.username());
        campaigns.save(campaign);
        ChangeSet.Snapshot what = ChangeSet.of()
                .put("title", "Title", campaign.getTitle())
                .put("audience", "Audience", count + " " + (campaign.getTenantId() != null ? "of this organisation's buyers" : "buyers")
                        + (campaign.getCounty() == null ? "" : " in " + campaign.getCounty())
                        + (campaign.getRegisteredSince() == null ? "" : " who joined since " + campaign.getRegisteredSince()))
                .put("channels", "Channels", campaign.getChannels())
                .put("subject", "Subject", campaign.getSubject())
                .put("body", "Body", campaign.getBody())
                .put("cta", "Button", campaign.getCtaLabel() == null ? null : campaign.getCtaLabel() + " → " + campaign.getCtaPath())
                .put("when", "Send", campaign.getScheduledFor() == null ? "as soon as approved, inside the sending window" : campaign.getScheduledFor().toString());
        approvals.submitOrRestate(AppConstant.APPROVAL_ENTITY_CAMPAIGN, campaign.getId(), AppConstant.APPROVAL_ACTION_SEND,
                campaign.getTenantId(), campaign.getInstitutionId(),
                campaign.getReference() + " — " + campaign.getTitle() + " to " + count + " people",
                "A campaign under promotional consent. Read the words as a recipient would; once approved it is sent "
                        + "in batches inside the sending window and cannot be recalled.", null, what);
        audit.record(AppConstant.ACTION_UPDATE, "Campaign", campaign.getId(), Campaign.DRAFT, Campaign.SUBMITTED + " to " + count);
        return toResponse(campaign);
    }

    /** The checker's decision from the campaign's own page; the approvals queue works too. */
    @Transactional
    public CampaignResponse decide(String reference, ApprovalService.DecisionRequest decision) {
        Campaign campaign = requireOwn(reference);
        if (!Campaign.SUBMITTED.equals(campaign.getState())) throw new HodiException("That campaign is not awaiting approval.", HttpStatus.CONFLICT);
        approvals.decideFor(AppConstant.APPROVAL_ENTITY_CAMPAIGN, campaign.getId(), AppConstant.APPROVAL_ACTION_SEND, decision);
        return toResponse(campaigns.findById(campaign.getId()).orElseThrow());
    }

    /** Approved: the audience is fixed now, a row per recipient, and the sweep takes it from here. */
    @Transactional
    public void applyApproval(Long campaignId, String checker) {
        Campaign campaign = campaigns.findById(campaignId).orElseThrow();
        if (!Campaign.SUBMITTED.equals(campaign.getState())) return;
        List<Long> audience = audienceIds(campaign);
        for (Long userId : audience) {
            sends.save(CampaignSend.builder().campaignId(campaign.getId()).userId(userId).build());
        }
        campaign.setAudienceCount(audience.size());
        campaign.setState(Campaign.APPROVED);
        campaign.setApprovedBy(checker);
        campaign.setApprovedAt(OffsetDateTime.now());
        campaign.setUpdatedBy(checker);
        campaigns.save(campaign);
        audit.record(AppConstant.ACTION_UPDATE, "Campaign", campaign.getId(), Campaign.SUBMITTED, Campaign.APPROVED + " for " + audience.size() + " by " + checker);
        log.info("Campaign {} approved by {} for {} recipients", campaign.getReference(), checker, audience.size());
    }

    @Transactional
    public void applyRefusal(Long campaignId, String checker, String reason) {
        Campaign campaign = campaigns.findById(campaignId).orElseThrow();
        if (!Campaign.SUBMITTED.equals(campaign.getState())) return;
        campaign.setState(Campaign.DRAFT);
        campaign.setUpdatedBy(checker);
        campaigns.save(campaign);
        audit.record(AppConstant.ACTION_UPDATE, "Campaign", campaign.getId(), Campaign.SUBMITTED, Campaign.DRAFT + " (sent back by " + checker
                + (reason == null ? ")" : ": " + reason + ")"));
    }

    /** Before it has finished sending. Whatever went out went out; the rest does not. */
    @Transactional
    public CampaignResponse cancel(String reference, String reason) {
        Campaign campaign = requireOwn(reference);
        if (Campaign.SENT.equals(campaign.getState()) || Campaign.CANCELLED.equals(campaign.getState())) {
            throw new HodiException("That campaign is already " + campaign.getState().toLowerCase() + ".", HttpStatus.CONFLICT);
        }
        String before = campaign.getState();
        campaign.setState(Campaign.CANCELLED);
        campaign.setCancelReason(reason == null || reason.isBlank() ? null : reason.trim());
        campaign.setFinishedAt(OffsetDateTime.now());
        campaign.setUpdatedBy(AuthContext.username());
        campaigns.save(campaign);
        audit.record(AppConstant.ACTION_UPDATE, "Campaign", campaign.getId(), before, Campaign.CANCELLED + (reason == null ? "" : ": " + reason));
        return toResponse(campaign);
    }

    /** The author reads it as a recipient would: sent to them alone, now, whatever their consent says. */
    @Transactional
    public void sendTest(String reference) {
        Campaign campaign = requireOwn(reference);
        sender.sendTestTo(campaign, AuthContext.requireUserId());
    }

    // ── reading ───────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public PagedResponse<CampaignResponse> list(CampaignListRequest request) {
        UserPrincipal caller = AuthContext.require();
        Specification<Campaign> spec = SearchSpecs.allOf(
                SearchSpecs.notArchived(),
                SearchSpecs.eq("state", request.getState() == null || request.getState().isBlank() ? null : request.getState().trim().toUpperCase()),
                request.getSearch() == null || request.getSearch().isBlank() ? null
                        : (root, q, cb) -> cb.like(cb.lower(root.get("title")), "%" + request.getSearch().trim().toLowerCase() + "%"),
                ownScope(caller));
        return PagedResponse.from(campaigns.findAll(spec, request.toPageable(Sort.by(Sort.Direction.DESC, "createdAt"))), this::toResponse);
    }

    @Transactional(readOnly = true)
    public CampaignResponse find(String reference) {
        return toResponse(requireOwn(reference));
    }

    @Transactional(readOnly = true)
    public List<SendRow> sendsOf(String reference) {
        Campaign campaign = requireOwn(reference);
        return sends.findByCampaignIdOrderByIdAsc(campaign.getId()).stream()
                .map(s -> new SendRow(com.hodi.security.hashid.HashIdUtil.encodeId(s.getUserId()), s.getState(), s.getChannels(), s.getSentAt(), s.getError()))
                .toList();
    }

    // ── internals ─────────────────────────────────────────────────────────────

    private static Specification<Campaign> ownScope(UserPrincipal caller) {
        if (caller.isPlatformStaff()) return null;
        if (caller.getTenantId() != null) return (root, q, cb) -> cb.equal(root.get("tenantId"), caller.getTenantId());
        if (caller.getInstitutionId() != null) return (root, q, cb) -> cb.equal(root.get("institutionId"), caller.getInstitutionId());
        return (root, q, cb) -> cb.disjunction();
    }

    private Campaign requireOwn(String reference) {
        Campaign campaign = campaigns.findByReference(reference == null ? "" : reference.trim())
                .orElseThrow(() -> new ResourceNotFoundException("Campaign", reference));
        UserPrincipal caller = AuthContext.require();
        boolean own = caller.isPlatformStaff()
                || (caller.getTenantId() != null && caller.getTenantId().equals(campaign.getTenantId()))
                || (caller.getInstitutionId() != null && caller.getInstitutionId().equals(campaign.getInstitutionId()));
        if (!own) throw new ResourceNotFoundException("Campaign", reference);
        return campaign;
    }

    /** The platform always; an organisation only when the platform has opened the door. */
    private void requireMayRun(UserPrincipal caller) {
        if (caller.isPlatformStaff()) return;
        if (!configs.getBoolean(ConfigKey.NOTIFY_ORGANISATION_WORDING_ENABLED)) {
            throw new HodiException("The platform has not opened campaigns to organisations.", HttpStatus.FORBIDDEN);
        }
    }

    private CampaignResponse toResponse(Campaign c) {
        UserPrincipal caller = AuthContext.current().orElse(null);
        boolean manage = AuthContext.hasAuthority("CAMPAIGNS_MANAGE");
        boolean approve = AuthContext.hasAuthority("CAMPAIGNS_APPROVE") && caller != null && !caller.getUsername().equals(c.getCreatedBy());
        String owner = c.getTenantId() != null ? "This organisation" : c.getInstitutionId() != null ? "The bank" : "The platform";
        return new CampaignResponse(c.getReference(), c.getTitle(), owner, c.getAudience(), c.getCounty(), c.getRegisteredSince(),
                Arrays.asList(c.getChannels().split(",")), c.getSubject(), c.getBody(), c.getCtaLabel(), c.getCtaPath(), c.getState(),
                c.getScheduledFor(), c.getAudienceCount(), c.getSentCount(), c.getFailedCount(), c.getSkippedCount(),
                c.getStartedAt(), c.getFinishedAt(), c.getApprovedBy(), c.getApprovedAt(), c.getCancelReason(), c.getCreatedBy(), c.getCreatedAt(),
                manage && c.isEditable(), manage && c.isEditable(), approve && Campaign.SUBMITTED.equals(c.getState()),
                manage && !Campaign.SENT.equals(c.getState()) && !Campaign.CANCELLED.equals(c.getState()),
                manage);
    }

    private String nextReference() {
        for (int attempt = 0; attempt < 5; attempt++) {
            String reference = RrnGenerator.generate("CM");
            if (!campaigns.existsByReference(reference)) return reference;
        }
        throw new HodiException("Could not allocate a reference. Try again.", HttpStatus.INTERNAL_SERVER_ERROR);
    }
}
