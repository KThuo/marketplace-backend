package com.hodi.modules.operations;

import com.hodi.common.AppConstant;
import com.hodi.common.PagedResponse;
import com.hodi.common.dto.PagedDataRequest;
import com.hodi.common.exception.HodiException;
import com.hodi.common.exception.ResourceNotFoundException;
import com.hodi.common.util.RrnGenerator;
import com.hodi.common.util.SearchSpecs;
import com.hodi.modules.audit.AuditService;
import com.hodi.modules.profiles.UserProfile;
import com.hodi.modules.profiles.UserProfileRepository;
import com.hodi.modules.usergroups.UserGroup;
import com.hodi.modules.usergroups.UserGroupRepository;
import com.hodi.modules.users.User;
import com.hodi.modules.users.UserRepository;
import com.hodi.security.TenantScope;
import com.hodi.security.hashid.HashIdUtil;
import com.hodi.security.principal.AuthContext;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Where a new lead goes (M12).
 *
 * <h2>Why routing needed to exist</h2>
 *
 * <p>Until now an enquiry arrived unassigned and stayed unassigned until somebody replied, at which point it
 * attached itself to whoever happened to open it first. That works for a two-person seller and fails for
 * everybody else: the enquiries nobody opens are exactly the ones nobody is accountable for.
 *
 * <h2>First match wins</h2>
 *
 * <p>Rules are ordered by an explicit priority, and the first one whose criteria fit takes the lead.
 * Deliberately not "most specific wins": that is a scoring system, and a scoring system is one nobody can
 * predict the behaviour of by reading the list.
 *
 * <h2>Routing never fails a lead</h2>
 *
 * <p>{@link #routeFor} returns an empty optional when nothing matches, and every caller treats that as
 * "leave it unassigned" rather than as an error. A buyer's enquiry must not fail to send because an
 * administrator wrote a rule pointing at somebody who has since left.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AssignmentService {

    private final AssignmentRuleRepository rules;
    private final UserRepository users;
    private final UserProfileRepository profiles;
    private final UserGroupRepository userGroups;
    private final AuditService audit;

    // ── DTOs ──────────────────────────────────────────────────────────────────

    public record RuleResponse(
            String reference,
            String name,
            String workType,
            String scope,
            String county,
            String propertyType,
            String assigneeName,
            String assigneeGroupName,
            boolean roundRobin,
            String description,
            int priority,
            Integer status,
            String statusFlag) {}

    public record SaveRuleRequest(
            @NotBlank(message = "Give the rule a name") @Size(max = 160) String name,
            @NotBlank(message = "What does it route?") String workType,
            @Size(max = 64) String county,
            @Size(max = 32) String propertyType,
            /**
             * A <em>profile</em> hash id, or a group's. Exactly one.
             *
             * <p>A profile rather than a user, deliberately: a profile is "this person in this
             * organisation", which is exactly what a routing rule points at. Taking a user id would have
             * meant checking their organisation by looking through every profile they hold and accepting
             * any match — and the users list returns profile ids anyway, so a caller sending what the list
             * gave them would have been resolving the wrong person entirely.
             */
            String assigneeProfileHashId,
            String assigneeGroupHashId,
            Integer priority) {}

    @Getter
    @Setter
    public static class RuleListRequest extends PagedDataRequest {
        private String workType;
    }

    /** Who a lead goes to, and why. */
    public record Route(Long userId, String userName, String ruleReference, String ruleName) {}

    // ── the routing itself ────────────────────────────────────────────────────

    /**
     * The first rule that fits, resolved to a person.
     *
     * @return empty when nothing matches, or when what matched points at nobody usable
     */
    @Transactional
    public Optional<Route> routeFor(String workType, Long tenantId,
                                    String county, String propertyType) {
        for (AssignmentRule rule : rules.findCandidates(workType, tenantId)) {
            if (!rule.matches(county, propertyType, tenantId)) continue;
            Optional<User> assignee = resolve(rule);
            if (assignee.isEmpty()) {
                // A rule pointing at somebody who has left should not swallow the lead — try the next one.
                log.debug("Rule {} matched but resolved to nobody", rule.getReference());
                continue;
            }
            User user = assignee.get();
            if (rule.isRoundRobin()) {
                rule.setLastAssignedUserId(user.getId());
                rules.save(rule);
            }
            return Optional.of(new Route(user.getId(), user.fullName(),
                    rule.getReference(), rule.getName()));
        }
        return Optional.empty();
    }

    /**
     * Round-robin, remembered rather than random.
     *
     * <p>Random distributes badly over the small numbers this deals with — five people and twenty leads a
     * week — and nobody can tell by looking whether it is working. Remembering where it got to is
     * predictable and visibly fair.
     */
    private Optional<User> resolve(AssignmentRule rule) {
        if (!rule.isRoundRobin()) {
            return users.findById(rule.getAssigneeUserId())
                    .filter(u -> AppConstant.isLive(u.getStatus()) && u.isEnabled());
        }
        // Membership lives on the profile, not the user: one person can hold several, and only the profile
        // in that group should put them in the rotation.
        List<User> members = profiles.findLiveByGroup(rule.getAssigneeGroupId()).stream()
                .map(UserProfile::getUserId)
                .distinct()
                .map(users::findById)
                .flatMap(Optional::stream)
                .filter(u -> AppConstant.isLive(u.getStatus()) && u.isEnabled())
                .sorted(Comparator.comparing(User::getId))
                .toList();
        if (members.isEmpty()) return Optional.empty();
        if (rule.getLastAssignedUserId() == null) return Optional.of(members.getFirst());

        int last = -1;
        for (int i = 0; i < members.size(); i++) {
            if (members.get(i).getId().equals(rule.getLastAssignedUserId())) {
                last = i;
                break;
            }
        }
        // Somebody who has left drops out of the list; starting again from the top is the honest answer.
        return Optional.of(members.get((last + 1) % members.size()));
    }

    // ── maintaining the rules ─────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public PagedResponse<RuleResponse> list(RuleListRequest request) {
        Long ownTenant = TenantScope.ownTenantId();
        Specification<AssignmentRule> spec = SearchSpecs.allOf(
                SearchSpecs.notArchived(),
                SearchSpecs.fuzzy("searchText", request.getSearch()),
                SearchSpecs.statusIn(request.effectiveStatuses()),
                SearchSpecs.eq("workType", blankToNull(request.getWorkType())),
                /*
                 * A seller sees their own rules and the platform's, because the platform's are what route
                 * their leads when they have written none — a list that hid them would leave somebody
                 * wondering why leads are being assigned at all.
                 */
                ownTenant == null ? null : (root, query, cb) -> cb.or(
                        cb.isNull(root.get("tenantId")),
                        cb.equal(root.get("tenantId"), ownTenant)));
        var page = rules.findAll(spec,
                request.toPageable(Sort.by(Sort.Direction.ASC, "priority")));
        return PagedResponse.from(page, this::toResponse);
    }

    @Transactional
    public RuleResponse create(SaveRuleRequest request) {
        AssignmentRule rule = AssignmentRule.builder()
                .reference(RrnGenerator.generate("AR"))
                .tenantId(TenantScope.ownTenantId())
                .createdBy(AuthContext.username())
                .build();
        apply(rule, request);
        AssignmentRule saved = rules.save(rule);
        audit.record(AppConstant.ACTION_CREATE, "AssignmentRule", saved.getId(), null, snapshot(saved));
        return toResponse(saved);
    }

    @Transactional
    public RuleResponse update(String reference, SaveRuleRequest request) {
        AssignmentRule rule = requireOwn(reference);
        String before = snapshot(rule);
        apply(rule, request);
        rule.setStatus(AppConstant.STATUS_EDITED);
        rule.setStatusFlag(AppConstant.FLAG_EDITED);
        rule.setUpdatedBy(AuthContext.username());
        AssignmentRule saved = rules.save(rule);
        audit.record(AppConstant.ACTION_UPDATE, "AssignmentRule", saved.getId(), before, snapshot(saved));
        return toResponse(saved);
    }

    @Transactional
    public RuleResponse setActive(String reference, boolean active) {
        AssignmentRule rule = requireOwn(reference);
        String before = snapshot(rule);
        rule.setStatus(active ? AppConstant.STATUS_ACTIVE : AppConstant.STATUS_INACTIVE);
        rule.setStatusFlag(active ? AppConstant.FLAG_ACTIVE : AppConstant.FLAG_INACTIVE);
        rule.setUpdatedBy(AuthContext.username());
        AssignmentRule saved = rules.save(rule);
        audit.record(active ? AppConstant.ACTION_ACTIVATE : AppConstant.ACTION_DEACTIVATE,
                "AssignmentRule", saved.getId(), before, snapshot(saved));
        return toResponse(saved);
    }

    @Transactional
    public void archive(String reference) {
        AssignmentRule rule = requireOwn(reference);
        rule.setStatus(AppConstant.STATUS_DELETED);
        rule.setStatusFlag(AppConstant.FLAG_DELETED);
        rule.setUpdatedBy(AuthContext.username());
        rules.save(rule);
        audit.record(AppConstant.ACTION_DELETE, "AssignmentRule", rule.getId(), null, snapshot(rule));
    }

    // ── internals ─────────────────────────────────────────────────────────────

    private void apply(AssignmentRule rule, SaveRuleRequest request) {
        rule.setName(request.name().trim());
        rule.setWorkType(request.workType().trim().toUpperCase(Locale.ROOT));
        rule.setCounty(blankToNull(request.county()));
        rule.setPropertyType(request.propertyType() == null || request.propertyType().isBlank()
                ? null : request.propertyType().trim().toUpperCase(Locale.ROOT));
        if (request.priority() != null) rule.setPriority(request.priority());

        boolean hasUser = request.assigneeProfileHashId() != null
                && !request.assigneeProfileHashId().isBlank();
        boolean hasGroup = request.assigneeGroupHashId() != null
                && !request.assigneeGroupHashId().isBlank();
        if (hasUser == hasGroup) {
            throw new HodiException(
                    "Send it to one person, or share it round a group — not both and not neither.",
                    HttpStatus.BAD_REQUEST);
        }

        if (hasUser) {
            UserProfile profile = profiles.findById(HashIdUtil.decodeId(request.assigneeProfileHashId()))
                    .orElseThrow(() -> new ResourceNotFoundException(
                            "Staff member", request.assigneeProfileHashId()));
            assertSameOrganisation(profile.getTenantId());
            User assignee = users.findById(profile.getUserId())
                    .orElseThrow(() -> new ResourceNotFoundException("User", profile.getUserId()));
            rule.setAssigneeUserId(assignee.getId());
            rule.setAssigneeName(assignee.fullName());
            rule.setAssigneeGroupId(null);
            rule.setAssigneeGroupName(null);
        } else {
            UserGroup group = userGroups.findById(HashIdUtil.decodeId(request.assigneeGroupHashId()))
                    .orElseThrow(() -> new ResourceNotFoundException(
                            "User group", request.assigneeGroupHashId()));
            assertSameOrganisation(group.getTenantId());
            rule.setAssigneeGroupId(group.getId());
            rule.setAssigneeGroupName(group.getName());
            rule.setAssigneeUserId(null);
            rule.setAssigneeName(null);
            rule.setLastAssignedUserId(null);
        }
    }

    /**
     * A rule may only point inside the organisation that owns it.
     *
     * <p>Otherwise a seller could route their own leads to somebody else's staff, which is a data leak
     * dressed as a workflow setting.
     */
    private void assertSameOrganisation(Long targetTenantId) {
        Long own = TenantScope.ownTenantId();
        if (own == null) return;   // platform staff write platform rules
        if (targetTenantId == null || !targetTenantId.equals(own)) {
            throw new HodiException("That person is not in your organisation.", HttpStatus.FORBIDDEN);
        }
    }

    private AssignmentRule requireOwn(String reference) {
        AssignmentRule rule = rules.findByReference(reference)
                .orElseThrow(() -> new ResourceNotFoundException("Rule", reference));
        Long own = TenantScope.ownTenantId();
        if (own == null) return rule;   // platform staff maintain every rule
        if (!own.equals(rule.getTenantId())) {
            // Including the platform's own rules: a seller reads them and does not edit them.
            throw new HodiException("That rule is not yours to change.", HttpStatus.FORBIDDEN);
        }
        return rule;
    }

    private RuleResponse toResponse(AssignmentRule r) {
        return new RuleResponse(r.getReference(), r.getName(), r.getWorkType(),
                r.getTenantId() == null ? "Platform" : "This organisation",
                r.getCounty(), r.getPropertyType(), r.getAssigneeName(), r.getAssigneeGroupName(),
                r.isRoundRobin(), r.describe(), r.getPriority(), r.getStatus(), r.getStatusFlag());
    }

    private static String snapshot(AssignmentRule r) {
        return "{\"reference\":\"%s\",\"name\":\"%s\",\"workType\":\"%s\",\"priority\":%d}".formatted(
                r.getReference(), r.getName(), r.getWorkType(), r.getPriority());
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
