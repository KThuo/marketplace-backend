package com.hodi.modules.notifications;

import com.hodi.common.AppConstant;
import com.hodi.common.exception.HodiException;
import com.hodi.common.exception.ResourceNotFoundException;
import com.hodi.modules.audit.AuditService;
import com.hodi.security.principal.AuthContext;
import com.hodi.security.principal.UserPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The reminder rules: the platform's defaults, an organisation's own days, and the "said once" record
 * (notifications plan §3.5).
 */
@Service
@RequiredArgsConstructor
public class ReminderRuleService {

    private final ReminderRuleRepository rules;
    private final ReminderRuleOverrideRepository overrides;
    private final ReminderSentRepository sent;
    private final AuditService audit;

    // ── DTOs ──────────────────────────────────────────────────────────────────

    /** What the sweep asks: whether, how many days, and how often again. */
    public record Effective(String code, boolean enabled, int days, Integer repeatEveryDays) {}

    public record RuleRow(String code, String description, boolean enabled, int days, Integer repeatEveryDays,
                          OffsetDateTime updatedAt, String updatedBy,
                          /** The organisation's own answer, when read as one. */
                          OverrideRow override, Boolean effectiveEnabled, Integer effectiveDays, Integer effectiveRepeat) {}

    public record OverrideRow(Boolean enabled, Integer days, Integer repeatEveryDays, OffsetDateTime updatedAt, String updatedBy) {}

    public record SaveRuleRequest(Boolean enabled, Integer days, Integer repeatEveryDays, Boolean clearRepeat) {}

    /** Null fields clear the override for that field — "as the platform says". */
    public record SaveOverrideRequest(Boolean enabled, Integer days, Integer repeatEveryDays) {}

    // ── resolving, for the sweep ──────────────────────────────────────────────

    @Transactional(readOnly = true)
    public Effective resolve(String code, Long tenantId, Long institutionId) {
        ReminderRule rule = rules.findById(code).orElse(null);
        if (rule == null) return new Effective(code, false, 0, null);
        ReminderRuleOverride override = tenantId == null && institutionId == null ? null
                : overrides.findFor(code, tenantId, institutionId).orElse(null);
        return apply(rule, override);
    }

    static Effective apply(ReminderRule rule, ReminderRuleOverride override) {
        boolean enabled = rule.isEnabled();
        int days = rule.getDays();
        Integer repeat = rule.getRepeatEveryDays();
        if (override != null) {
            if (override.getEnabled() != null) enabled = enabled && override.getEnabled();
            if (override.getDays() != null) days = override.getDays();
            if (override.getRepeatEveryDays() != null && repeat != null) repeat = override.getRepeatEveryDays();
        }
        return new Effective(rule.getCode(), enabled, days, repeat);
    }

    /**
     * Whether to say it now, and the record that it was said.
     *
     * <p>True the first time; afterwards only when the rule repeats and the last time was at least that many
     * days ago. Writes the row in the same call, so a sweep that is interrupted after this point has said it
     * on the record even if the gateway lost the message — the log is where that shows.
     */
    @Transactional
    public boolean claim(Effective rule, String subjectType, Long subjectId, String subjectKey, OffsetDateTime asOf) {
        String key = subjectKey == null ? "" : subjectKey;
        ReminderSent row = sent.findByRuleCodeAndSubjectTypeAndSubjectIdAndSubjectKey(rule.code(), subjectType, subjectId, key).orElse(null);
        if (row == null) {
            sent.save(ReminderSent.builder().ruleCode(rule.code()).subjectType(subjectType).subjectId(subjectId).subjectKey(key).sentAt(asOf).build());
            return true;
        }
        if (rule.repeatEveryDays() == null || rule.repeatEveryDays() <= 0) return false;
        if (row.getSentAt().plusDays(rule.repeatEveryDays()).isAfter(asOf)) return false;
        row.setSentAt(asOf);
        row.setTimes(row.getTimes() + 1);
        sent.save(row);
        return true;
    }

    // ── the platform's rules ──────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<RuleRow> all() {
        return rules.findAllByOrderBySortOrderAscCodeAsc().stream().map(r -> toRow(r, null)).toList();
    }

    @Transactional
    public RuleRow save(String code, SaveRuleRequest request) {
        ReminderRule rule = require(code);
        String before = snapshot(rule.isEnabled(), rule.getDays(), rule.getRepeatEveryDays());
        if (request.enabled() != null) rule.setEnabled(request.enabled());
        if (request.days() != null) rule.setDays(days(request.days()));
        if (Boolean.TRUE.equals(request.clearRepeat())) rule.setRepeatEveryDays(null);
        else if (request.repeatEveryDays() != null) rule.setRepeatEveryDays(days(request.repeatEveryDays()));
        rule.setUpdatedAt(OffsetDateTime.now());
        rule.setUpdatedBy(AuthContext.username());
        rules.save(rule);
        audit.record(AppConstant.ACTION_UPDATE, "ReminderRule", 0L, before,
                snapshot(rule.isEnabled(), rule.getDays(), rule.getRepeatEveryDays()));
        return toRow(rule, null);
    }

    // ── an organisation's overrides ───────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<RuleRow> mine() {
        UserPrincipal caller = requireOrganisation();
        Map<String, ReminderRuleOverride> own = new HashMap<>();
        for (ReminderRuleOverride o : overrides.findAllFor(caller.getTenantId(), caller.getInstitutionId())) own.put(o.getRuleCode(), o);
        return rules.findAllByOrderBySortOrderAscCodeAsc().stream()
                .filter(r -> concerns(r.getCode(), caller))
                .map(r -> toRow(r, own.get(r.getCode()))).toList();
    }

    @Transactional
    public RuleRow saveMine(String code, SaveOverrideRequest request) {
        UserPrincipal caller = requireOrganisation();
        ReminderRule rule = require(code);
        if (!concerns(rule.getCode(), caller)) {
            throw new HodiException("That reminder does not concern your organisation.", HttpStatus.FORBIDDEN);
        }
        ReminderRuleOverride row = overrides.findFor(code, caller.getTenantId(), caller.getInstitutionId())
                .orElseGet(() -> ReminderRuleOverride.builder().ruleCode(code)
                        .tenantId(caller.getTenantId()).institutionId(caller.getTenantId() == null ? caller.getInstitutionId() : null).build());
        String before = snapshot(row.getEnabled(), row.getDays(), row.getRepeatEveryDays());
        row.setEnabled(request.enabled());
        row.setDays(request.days() == null ? null : days(request.days()));
        row.setRepeatEveryDays(request.repeatEveryDays() == null ? null : days(request.repeatEveryDays()));
        row.setUpdatedAt(OffsetDateTime.now());
        row.setUpdatedBy(AuthContext.username());
        if (row.getEnabled() == null && row.getDays() == null && row.getRepeatEveryDays() == null) {
            if (row.getId() != null) overrides.delete(row);
            audit.record(AppConstant.ACTION_UPDATE, "ReminderRuleOverride", row.getId() == null ? 0L : row.getId(), before, "cleared");
            return toRow(rule, null);
        }
        overrides.save(row);
        audit.record(AppConstant.ACTION_UPDATE, "ReminderRuleOverride", row.getId(), before,
                snapshot(row.getEnabled(), row.getDays(), row.getRepeatEveryDays()));
        return toRow(rule, row);
    }

    /** The valuer's lapse is the platform's panel; everything else concerns the organisation whose thing it is. */
    static boolean concerns(String code, UserPrincipal caller) {
        return !"VALUER_LAPSE".equals(code);
    }

    // ── internals ─────────────────────────────────────────────────────────────

    private ReminderRule require(String code) {
        return rules.findById(code == null ? "" : code.trim().toUpperCase())
                .orElseThrow(() -> new ResourceNotFoundException("Reminder", code));
    }

    private static UserPrincipal requireOrganisation() {
        UserPrincipal caller = AuthContext.require();
        if (caller.getTenantId() == null && caller.getInstitutionId() == null) {
            throw new HodiException("Only an organisation has overrides; the platform edits the rules themselves.", HttpStatus.FORBIDDEN);
        }
        return caller;
    }

    private static int days(int value) {
        if (value < 0 || value > 365) throw new HodiException("Days are between 0 and 365.", HttpStatus.BAD_REQUEST);
        return value;
    }

    private static String snapshot(Boolean enabled, Integer days, Integer repeat) {
        return "enabled=" + enabled + " days=" + days + " repeat=" + repeat;
    }

    private static RuleRow toRow(ReminderRule r, ReminderRuleOverride o) {
        Effective effective = o == null ? null : apply(r, o);
        return new RuleRow(r.getCode(), r.getDescription(), r.isEnabled(), r.getDays(), r.getRepeatEveryDays(), r.getUpdatedAt(), r.getUpdatedBy(),
                o == null ? null : new OverrideRow(o.getEnabled(), o.getDays(), o.getRepeatEveryDays(), o.getUpdatedAt(), o.getUpdatedBy()),
                effective == null ? null : effective.enabled(), effective == null ? null : effective.days(),
                effective == null ? null : effective.repeatEveryDays());
    }
}
