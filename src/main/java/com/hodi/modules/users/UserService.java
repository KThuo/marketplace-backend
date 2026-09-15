package com.hodi.modules.users;

import com.hodi.common.AppConstant;
import com.hodi.common.PagedResponse;
import com.hodi.common.exception.DuplicateResourceException;
import com.hodi.common.exception.HodiException;
import com.hodi.common.exception.ResourceNotFoundException;
import com.hodi.common.util.SearchSpecs;
import com.hodi.infra.storage.StorageService;
import com.hodi.modules.audit.AuditService;
import com.hodi.modules.auth.RefreshTokenService;
import com.hodi.modules.banks.Bank;
import com.hodi.modules.banks.BankRepository;
import com.hodi.modules.profiles.UserProfile;
import com.hodi.modules.profiles.UserProfileRepository;
import com.hodi.modules.profiles.UserProfileService;
import com.hodi.modules.tenants.Tenant;
import com.hodi.modules.tenants.TenantRepository;
import com.hodi.modules.usergroups.UserGroup;
import com.hodi.modules.usergroups.UserGroupRepository;
import com.hodi.modules.users.dto.UserDtos.CreateUserRequest;
import com.hodi.modules.users.dto.UserDtos.TemporaryPasswordResponse;
import com.hodi.modules.users.dto.UserDtos.UpdateUserRequest;
import com.hodi.modules.users.dto.UserDtos.UserListRequest;
import com.hodi.modules.users.dto.UserDtos.UserResponse;
import com.hodi.modules.usertypes.UserType;
import com.hodi.modules.usertypes.UserTypeRepository;
import com.hodi.security.hashid.HashIdUtil;
import com.hodi.security.password.PasswordService;
import com.hodi.security.principal.AuthContext;
import com.hodi.security.principal.UserPrincipal;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.Predicate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Staff administration: platform staff, seller staff and the bank's staff.
 *
 * <p>Buyers are not created here — they register themselves (see
 * {@code com.hodi.modules.buyers.BuyerRegistrationService}). They are visible through this module's list so
 * support can find somebody, but there is no path here that mints one, because a staff-created buyer with a
 * temporary password is an account nobody has proved they own.
 *
 * <h2>The list is a list of profiles</h2>
 *
 * <p>Since a person can hold more than one profile (BRD FR073), "the users of this organisation" is a
 * question about profiles: it is the profile that carries the user type, the group and the organisation.
 * Somebody who is both a buyer and a seller's owner appears twice, which is correct — they are two actors
 * with one credential, and an administrator of one organisation should see the one that concerns them.
 *
 * <p>The actions on a row nevertheless act on the <strong>account</strong>: deactivating, resetting a
 * password and signing somebody out are all things you do to a credential, and a person locked out of one
 * profile but not another would be a state nobody asked for. Removing somebody from an organisation without
 * touching their credential is a different operation, and belongs with the phase that gives organisations
 * their own membership screens.
 *
 * <h2>Organisation affiliation is derived, not submitted</h2>
 *
 * <p>The single most important rule in this class. A seller owner creating staff gets their own organisation,
 * full stop — the request's {@code tenantId} is ignored rather than validated, because a field that is
 * sometimes honoured is a field somebody will eventually get honoured. Only platform staff may name an
 * organisation, and what they may name is constrained by the user type's actor class: a {@code SELLER} type
 * needs a tenant and a platform type needs none, and neither can be given the other's.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserService {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String TEMP_ALPHABET =
            "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789";

    private final UserRepository repository;
    private final UserProfileRepository profiles;
    private final UserProfileService userProfiles;
    private final UserTypeRepository userTypes;
    private final UserGroupRepository userGroups;
    private final TenantRepository tenants;
    private final BankRepository institutions;
    private final PasswordService passwords;
    private final RefreshTokenService refreshTokens;
    private final StorageService storage;
    private final AuditService audit;
    private final com.hodi.modules.approvals.ApprovalService approvals;

    // ── reads ─────────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public PagedResponse<UserResponse> list(UserListRequest request) {
        Specification<UserProfile> spec = SearchSpecs.allOf(
                SearchSpecs.notArchived(),
                search(request.getSearch()),
                SearchSpecs.statusIn(request.effectiveStatuses()),
                SearchSpecs.eq("profileType", blankToNull(request.getActorClass())),
                SearchSpecs.eq("userTypeCode", blankToNull(request.getUserTypeCode())),
                SearchSpecs.eq("tenantId", HashIdUtil.decodeId(request.getTenantId())),
                SearchSpecs.eq("institutionId", HashIdUtil.decodeId(request.getInstitutionId())),
                SearchSpecs.eq("userGroupId", HashIdUtil.decodeId(request.getUserGroupId())),
                lockedFilter(request.getLocked()),
                visibleTo(AuthContext.require()));

        var page = profiles.findAll(spec, request.toPageable(
                Sort.by(Sort.Direction.ASC, "userTypeName", "id")));

        // One query for the people on this page rather than one per row: the list is paged, so this is at
        // most a page's worth of ids, and the alternative is the N+1 the label caches exist to avoid.
        List<Long> userIds = page.getContent().stream().map(UserProfile::getUserId).distinct().toList();
        var people = repository.findByIdIn(userIds).stream()
                .collect(java.util.stream.Collectors.toMap(User::getId, u -> u));

        return PagedResponse.from(page, profile -> toResponse(people.get(profile.getUserId()), profile));
    }

    /**
     * Free-text search across the person and the profile.
     *
     * <p>Two generated columns, because the searchable facts live on two rows: the name and address on the
     * person, the role and organisation on the profile. Each typed word has to match one or the other — ANDed
     * across words, ORed across the two columns — so "wanjiru acacia" finds Wanjiru at Acacia Ridge, which
     * neither column could answer alone.
     */
    private Specification<UserProfile> search(String term) {
        if (term == null || term.isBlank()) return null;
        List<String> tokens = new ArrayList<>();
        for (String token : term.trim().toLowerCase().split("\\s+")) {
            if (!token.isEmpty()) tokens.add(token);
        }
        if (tokens.isEmpty()) return null;

        return (root, query, cb) -> {
            Join<UserProfile, User> user = root.join("user", jakarta.persistence.criteria.JoinType.INNER);
            List<Predicate> all = new ArrayList<>(tokens.size());
            for (String token : tokens) {
                String like = "%" + token + "%";
                // The columns are lower-cased by the database, so the term is lowered rather than the
                // column — wrapping a column in lower() would make its trigram index unusable.
                all.add(cb.or(cb.like(root.get("searchText"), like),
                        cb.like(user.get("searchText"), like)));
            }
            return cb.and(all.toArray(new Predicate[0]));
        };
    }

    /**
     * Which profiles the caller may see.
     *
     * <p>Not {@code TenantScope.restrict("tenantId")}, because this list holds four populations that a tenant
     * predicate alone gets wrong in both directions. A bank's staff carry no tenant, so restricting on
     * {@code tenant_id} would hide the bank admin's own colleagues from them; buyers carry no organisation
     * either, so the same predicate would hide every buyer from support. So:
     *
     * <ul>
     *   <li>platform staff — everyone;
     *   <li>seller staff — their own organisation's people, and nobody else's;
     *   <li>the bank's staff — their own institution's people. Deliberately <em>not</em> the staff of the sellers
     *       they are partnered with: a partnership grants sight of a portfolio, not of another
     *       organisation's people.
     * </ul>
     *
     * <p>That last point is the one worth being explicit about, because {@code TenantScope} would have said
     * otherwise. Visible-tenant scope is about business data; staff records are not business data.
     */
    private Specification<UserProfile> visibleTo(UserPrincipal caller) {
        if (caller.isPlatformStaff()) return null;
        if (caller.getTenantId() != null) {
            return (root, query, cb) -> cb.equal(root.get("tenantId"), caller.getTenantId());
        }
        if (caller.getInstitutionId() != null) {
            return (root, query, cb) -> cb.equal(root.get("institutionId"), caller.getInstitutionId());
        }
        // A caller with no organisation who is not platform staff is a buyer. Buyers do not administer users;
        // the permission gate already refused them, and this is the belt to that braces.
        return (root, query, cb) -> cb.disjunction();
    }

    /** Locked is a property of the credential, so this one filters through the join. */
    private Specification<UserProfile> lockedFilter(Boolean locked) {
        if (locked == null) return null;
        return (root, query, cb) -> {
            Join<UserProfile, User> user = root.join("user", jakarta.persistence.criteria.JoinType.INNER);
            return locked ? cb.isTrue(user.get("locked")) : cb.isFalse(user.get("locked"));
        };
    }

    @Transactional(readOnly = true)
    public UserResponse find(String hashId) {
        UserProfile profile = requireVisible(hashId);
        return toResponse(account(profile), profile);
    }

    // ── writes ────────────────────────────────────────────────────────────────

    /**
     * Creates a staff account with a temporary password.
     *
     * <p>The password is returned once, in the response, and never stored in readable form or emailed from
     * here. {@code must_change_password} is set, and {@code PasswordChangeRequiredFilter} enforces it
     * server-side — so the temporary credential can do exactly one thing: become a real one.
     */
    @Transactional
    public TemporaryPasswordResponse create(CreateUserRequest request) {
        UserPrincipal caller = AuthContext.require();

        /*
         * The group decides the type, not the caller.
         *
         * A user group belongs to exactly one user type — that is enforced when the group is created — so
         * asking for both was asking one question twice. The old form did, and every path that accepted the
         * pair then had to check they agreed; this way there is nothing to disagree.
         */
        UserGroup named = userGroups.findById(HashIdUtil.decodeId(request.userGroupId()))
                .orElseThrow(() -> new ResourceNotFoundException("User group", request.userGroupId()));
        UserType type = userTypes.findByCode(named.getUserTypeCode())
                .orElseThrow(() -> new HodiException(
                        "\"%s\" names a kind of user that no longer exists.".formatted(named.getName()),
                        HttpStatus.CONFLICT));

        if (AppConstant.ACTOR_BUYER.equals(type.getActorClass())) {
            throw new HodiException(
                    "Buyers register themselves — a staff-created buyer account is one nobody has proved "
                            + "they own.", HttpStatus.BAD_REQUEST);
        }
        assertMayAssignType(caller, type);

        String email = request.email().trim().toLowerCase();
        if (repository.existsByEmail(email)) {
            throw new DuplicateResourceException("An account with that email address already exists");
        }
        String username = resolveUsername(request.username(), email);

        Affiliation affiliation = resolveAffiliation(caller, type, request.tenantId(),
                request.institutionId());
        // Re-resolved through the same guard every other path uses: the group has to be one this affiliation
        // may hold, live, and not a shared template. Looking it up above only told us which type it names.
        UserGroup group = resolveGroup(request.userGroupId(), type, affiliation);

        String temporary = temporaryPassword();
        User user = User.builder()
                .firstName(request.firstName().trim())
                .lastName(request.lastName().trim())
                .email(email)
                .username(username)
                .phone(blankToNull(request.phone()))
                .mustChangePassword(true)
                /*
                 * Created, and unable to sign in until the bank says so.
                 *
                 * STATUS_NEW rather than STATUS_INACTIVE, because those are different facts: inactive is an
                 * account somebody switched off, and this one has never been in service. Conflating them
                 * would make "never approved" and "deactivated" indistinguishable on the list and in the
                 * trail, and would let `activate` treat one as the other.
                 */
                .enabled(false)
                .status(AppConstant.STATUS_NEW)
                .statusFlag(AppConstant.FLAG_NEW)
                .createdBy(AuthContext.username())
                .build();
        // Through PasswordService so the temporary credential satisfies the same policy as a chosen one, and
        // so its expiry is stamped. A temporary password exempt from policy is a permanent one in practice.
        passwords.applyTo(user, temporary);
        // applyTo clears the flag, because it is the method a user calls to satisfy it. Re-set it: this
        // password was not chosen by its holder.
        user.setMustChangePassword(true);

        User saved = repository.save(user);
        UserProfile profile = userProfiles.provisionFirst(saved.getId(), type, group,
                affiliation.tenantId(), affiliation.tenantName(),
                affiliation.institutionId(), affiliation.institutionName());
        submitForApproval(saved, profile,
                "Created by " + AuthContext.username() + " as " + describe(profile) + ".");
        audit.record(AppConstant.ACTION_CREATE, "User", saved.getId(), null, snapshot(saved, profile));
        log.info("Created {} user {} in {} — waiting for approval", type.getActorClass(),
                saved.getUsername(), affiliation.label());
        return new TemporaryPasswordResponse(saved.getUsername(), temporary, true);
    }

    /**
     * Puts an account in front of the bank.
     *
     * <p>Scoped to the new person's own organisation rather than the caller's, so a seller's owner sees their
     * own request waiting — and so the bank, who sees everything, reads it against the organisation it
     * concerns. {@code submitOrRestate} rather than {@code submit}: a maker fixing what a send-back asked for
     * should not be told their edit is already waiting for a decision.
     */
    private void submitForApproval(User user, UserProfile profile, String note) {
        approvals.submitOrRestate(AppConstant.APPROVAL_ENTITY_USER, user.getId(),
                AppConstant.APPROVAL_ACTION_CREATE, profile.getTenantId(),
                profile.getInstitutionId(), user.fullName() + " — " + describe(profile), note);
    }

    /** What the checker reads before opening anything: the role, and where it applies. */
    private static String describe(UserProfile profile) {
        String group = profile.getUserGroupName() == null
                ? profile.getUserTypeName() : profile.getUserGroupName();
        return group + " at " + profile.organisationLabel();
    }

    /**
     * Created, and not yet let in.
     *
     * <p>Both halves, because either alone is a different state: a disabled account that was once active is
     * deactivated, and {@code STATUS_NEW} on an enabled row would be a row mid-save. Only the pair means
     * "the bank has not decided".
     */
    private static boolean awaitingApproval(User user) {
        return !user.isEnabled() && user.getStatus() != null
                && user.getStatus() == AppConstant.STATUS_NEW;
    }

    @Transactional
    public UserResponse update(String hashId, UpdateUserRequest request) {
        UserProfile profile = requireManageable(hashId);
        User user = account(profile);
        String before = snapshot(user, profile);

        String email = request.email().trim().toLowerCase();
        if (!email.equals(user.getEmail()) && repository.existsByEmail(email)) {
            throw new DuplicateResourceException("An account with that email address already exists");
        }

        user.setFirstName(request.firstName().trim());
        user.setLastName(request.lastName().trim());
        user.setEmail(email);
        user.setPhone(blankToNull(request.phone()));

        /*
         * Changing the group can change the kind of user, and has to be allowed to.
         *
         * The form no longer asks for a user type, so moving somebody from "Sales Agents" to "Mortgage
         * Officers" is the only way to change what they are — and if this method insisted the new group match
         * the type already on the profile, that move would be impossible and the type would be frozen at
         * creation. So the type is re-read from the group and re-stamped, through the same guards a create
         * goes through: an actor class that matches the organisation, and no minting of platform staff by
         * somebody who is not.
         */
        if (request.userGroupId() != null) {
            UserGroup named = userGroups.findById(HashIdUtil.decodeId(request.userGroupId()))
                    .orElseThrow(() -> new ResourceNotFoundException("User group",
                            request.userGroupId()));
            UserType type = userTypes.findByCode(named.getUserTypeCode())
                    .orElseThrow(() -> new HodiException(
                            "\"%s\" names a kind of user that no longer exists.".formatted(named.getName()),
                            HttpStatus.CONFLICT));
            UserPrincipal caller = AuthContext.require();
            assertMayAssignType(caller, type);
            if (!type.getActorClass().equals(profile.getProfileType())) {
                // A seller's staff member cannot become the bank's staff by way of a group: the organisation on
                // the profile would then be the wrong kind for the actor class, and TenantScope would resolve
                // visibility through a column that is null.
                throw new HodiException(
                        ("\"%s\" is a group for %s users. Moving somebody between organisations of "
                                + "different kinds is not a change of group.")
                                .formatted(named.getName(), type.getActorClass()),
                        HttpStatus.BAD_REQUEST);
            }

            UserGroup group = resolveGroup(request.userGroupId(), type,
                    new Affiliation(profile.getTenantId(), profile.getTenantName(),
                            profile.getInstitutionId(), profile.getInstitutionName()));
            if (group != null) {
                assertNotLastOwner(profile, group.getId());
                profile.setUserGroupId(group.getId());
                profile.setUserGroupName(group.getName());
                profile.setUserTypeId(type.getId());
                profile.setUserTypeCode(type.getCode());
                profile.setUserTypeName(type.getName());
                profile.setStatus(AppConstant.STATUS_EDITED);
                profile.setStatusFlag(AppConstant.FLAG_EDITED);
                profile.setUpdatedBy(AuthContext.username());
                profiles.save(profile);
            }
        }

        /*
         * A waiting account stays waiting, and goes back in front of the bank.
         *
         * Stamping STATUS_EDITED here would knock it off STATUS_NEW and make it indistinguishable from an
         * approved account that somebody had edited — and it is that pair, disabled plus NEW, that the
         * login guard and every button on the row read.
         *
         * The resubmission is what makes a send-back work: the checker says what is wrong, the maker fixes
         * it and saves, and the request is raised again carrying the newer description. An edit made while
         * the request is still pending restates it for the same reason, which is the rule already settled
         * for developments — the checker should read the latest version of what they are being asked about.
         */
        boolean waiting = awaitingApproval(user);
        if (!waiting) {
            user.setStatus(AppConstant.STATUS_EDITED);
            user.setStatusFlag(AppConstant.FLAG_EDITED);
        }
        user.setUpdatedBy(AuthContext.username());

        User saved = repository.save(user);
        if (waiting) {
            submitForApproval(saved, profile,
                    "Changed by " + AuthContext.username() + " — now " + describe(profile) + ".");
        }
        audit.record(AppConstant.ACTION_UPDATE, "User", saved.getId(), before,
                snapshot(saved, profile));
        return toResponse(saved, profile);
    }

    @Transactional
    public void deactivate(String hashId, String reason) {
        UserProfile profile = requireManageable(hashId);
        User user = account(profile);
        assertNotSelf(user, "deactivate");
        assertNotAwaitingApproval(user, "Deactivating");
        assertNotLastOwner(profile, null);

        String before = snapshot(user, profile);
        user.setEnabled(false);
        user.setStatus(AppConstant.STATUS_INACTIVE);
        user.setStatusFlag(AppConstant.FLAG_INACTIVE);
        user.setDeactivationReason(reason);
        user.setUpdatedBy(AuthContext.username());
        repository.save(user);

        /*
         * Their sessions end now, not at the end of their idle window.
         *
         * JwtAuthenticationFilter reloads the user per request and would refuse a disabled account anyway, so
         * this is belt and braces — but it is the belt that matters if that filter is ever relaxed, and it
         * also clears the refresh rows so a deactivated account cannot rotate its way through the grace
         * period.
         */
        refreshTokens.revokeAllForUser(user.getId());
        audit.record(AppConstant.ACTION_DEACTIVATE, "User", user.getId(), before,
                snapshot(user, profile));
    }

    @Transactional
    public void activate(String hashId) {
        UserProfile profile = requireManageable(hashId);
        User user = account(profile);
        /*
         * The guard that makes the gate real.
         *
         * USERS_ACTIVATE is a far commoner permission than USERS_APPROVE — every organisation grants it to
         * whoever tidies up their staff list — and without this line it switches `enabled` on directly,
         * which is the entire decision the bank was supposed to be making.
         */
        assertNotAwaitingApproval(user, "Activating");
        String before = snapshot(user, profile);
        user.setEnabled(true);
        user.setLocked(false);
        user.setLockedUntil(null);
        user.setFailedAttempts(0);
        user.setStatus(AppConstant.STATUS_ACTIVE);
        user.setStatusFlag(AppConstant.FLAG_ACTIVE);
        user.setDeactivationReason(null);
        user.setUpdatedBy(AuthContext.username());
        repository.save(user);
        audit.record(AppConstant.ACTION_ACTIVATE, "User", user.getId(), before,
                snapshot(user, profile));
    }

    @Transactional
    public void archive(String hashId) {
        UserProfile profile = requireManageable(hashId);
        User user = account(profile);
        assertNotSelf(user, "delete");
        assertNotAwaitingApproval(user, "Deleting");
        assertNotLastOwner(profile, null);

        String before = snapshot(user, profile);
        user.setEnabled(false);
        user.setStatus(AppConstant.STATUS_DELETED);
        user.setStatusFlag(AppConstant.FLAG_DELETED);
        user.setUpdatedBy(AuthContext.username());
        repository.save(user);
        refreshTokens.revokeAllForUser(user.getId());
        audit.record(AppConstant.ACTION_DELETE, "User", user.getId(), before,
                snapshot(user, profile));
    }

    /** Issues a fresh temporary password, shown once. Also clears any lockout — that is the point. */
    @Transactional
    public TemporaryPasswordResponse resetPassword(String hashId) {
        UserProfile profile = requireManageable(hashId);
        User user = account(profile);
        String temporary = temporaryPassword();
        passwords.applyTo(user, temporary);
        user.setMustChangePassword(true);
        user.setLocked(false);
        user.setLockedUntil(null);
        user.setFailedAttempts(0);
        // Access tokens too, not just the refresh rows — an administrator resetting somebody's password is
        // usually responding to a lost or compromised credential, and leaving the current token alive for
        // another window addresses neither.
        user.setSessionsValidFrom(OffsetDateTime.now());
        user.setUpdatedBy(AuthContext.username());
        repository.save(user);
        refreshTokens.revokeAllForUser(user.getId());
        audit.record(AppConstant.AUDIT_PASSWORD_CHANGE, "User", user.getId(), null,
                "temporary password issued by " + AuthContext.username());
        return new TemporaryPasswordResponse(user.getUsername(), temporary, awaitingApproval(user));
    }

    @Transactional
    public int revokeSessions(String hashId) {
        UserProfile profile = requireManageable(hashId);
        User user = account(profile);
        int revoked = refreshTokens.revokeAllForUser(user.getId());
        user.setSessionsValidFrom(OffsetDateTime.now());
        repository.save(user);
        audit.record(AppConstant.AUDIT_SESSION_REVOKED, "User", user.getId(), null,
                "revoked " + revoked + " session(s)");
        return revoked;
    }

    // ── what the bank's decision does ─────────────────────────────────────────

    /**
     * Approved: the account can sign in.
     *
     * <p>Called by {@link com.hodi.modules.users.UserApprovalHandler} inside the deciding transaction, so a
     * failure here rolls the decision back rather than leaving a queue row reading "approved" beside an
     * account that never opened.
     *
     * <p>{@code must_change_password} is deliberately untouched. The temporary credential the maker handed
     * over is still temporary, and approval is permission to use it once — not permission to keep it.
     *
     * <p>No caller guards: every one of them ran before the decision was recorded. Tolerant of an account
     * that is no longer waiting, because a stale queue row should not blow up a decision — it should do
     * nothing, which is what an already-enabled account needs.
     */
    @Transactional
    public void applyApproval(Long userId, String approvedBy) {
        User user = repository.findById(userId).orElse(null);
        if (user == null || !awaitingApproval(user)) return;
        String before = snapshot(user);
        user.setEnabled(true);
        user.setStatus(AppConstant.STATUS_ACTIVE);
        user.setStatusFlag(AppConstant.FLAG_ACTIVE);
        user.setUpdatedBy(AuthContext.username());
        repository.save(user);
        audit.record(AppConstant.ACTION_ACTIVATE, "User", user.getId(), before, snapshot(user));
        log.info("User {} approved by {}", user.getUsername(), approvedBy);
    }

    /**
     * Refused. Rejection archives the account; a send-back leaves it waiting.
     *
     * <p>The difference is the whole reason the two decisions are separate words. A send-back is "fix this
     * and come back", so the account has to survive for there to be anything to fix — the maker edits it and
     * the edit resubmits. A rejection is "this person should not exist here", and an account left disabled
     * after that is a row somebody with {@code USERS_ACTIVATE} could later switch on without a second look.
     *
     * <p>The username and email stay taken, which is deliberate: releasing them would let the same maker
     * recreate the rejected account unchanged and put it straight back in the queue.
     */
    @Transactional
    public void applyRefusal(Long userId, String decision, String reason) {
        User user = repository.findById(userId).orElse(null);
        if (user == null || !awaitingApproval(user)) return;
        if (AppConstant.APPROVAL_SENT_BACK.equals(decision)) {
            log.info("User {} sent back: {}", user.getUsername(), reason);
            return;
        }
        String before = snapshot(user);
        user.setStatus(AppConstant.STATUS_DELETED);
        user.setStatusFlag(AppConstant.FLAG_DELETED);
        user.setDeactivationReason(reason);
        user.setUpdatedBy(AuthContext.username());
        repository.save(user);
        audit.record(AppConstant.ACTION_DELETE, "User", user.getId(), before, snapshot(user));
        log.info("User {} rejected: {}", user.getUsername(), reason);
    }

    /** What a person's account looks like on its own — the profile is not what a decision changes. */
    private static String snapshot(User user) {
        return "{\"username\":\"%s\",\"enabled\":%s,\"status\":%d}"
                .formatted(user.getUsername(), user.isEnabled(), user.getStatus());
    }

    // ── affiliation and authority ─────────────────────────────────────────────

    private record Affiliation(Long tenantId, String tenantName,
                               Long institutionId, String institutionName) {
        String label() {
            if (tenantName != null) return tenantName;
            if (institutionName != null) return institutionName;
            return "the platform";
        }
    }

    /**
     * Works out which organisation a new user belongs to.
     *
     * <p>For an organisation's own administrator the answer is fixed and the request has no say. For platform
     * staff the answer comes from the request but must agree with the user type's actor class — a
     * A type placed in the wrong organisation would produce a profile whose visible-tenant set resolves through
     * a partnership lookup on an institution it does not have, which is a broken account rather than a
     * dangerous one, but broken in a way nothing downstream would explain.
     */
    private Affiliation resolveAffiliation(UserPrincipal caller, UserType type,
                                           String tenantHashId, String institutionHashId) {
        if (!caller.isPlatformStaff()) {
            if (caller.getTenantId() != null) {
                requireActorClass(type, AppConstant.ACTOR_SELLER,
                        "You can only add staff of your own organisation's kind.");
                return new Affiliation(caller.getTenantId(), caller.getTenantName(), null, null);
            }
            throw new HodiException("Your account is not attached to an organisation.",
                    HttpStatus.FORBIDDEN);
        }

        return switch (type.getActorClass()) {
            case AppConstant.ACTOR_PLATFORM -> new Affiliation(null, null, null, null);
            case AppConstant.ACTOR_SELLER -> {
                Long tenantId = HashIdUtil.decodeId(tenantHashId);
                if (tenantId == null) {
                    throw new HodiException("Choose the seller organisation this user belongs to.",
                            HttpStatus.BAD_REQUEST);
                }
                Tenant tenant = tenants.findById(tenantId)
                        .orElseThrow(() -> new ResourceNotFoundException("Organisation", tenantHashId));
                yield new Affiliation(tenant.getId(), tenant.getName(), null, null);
            }
            /*
             * No branch for an institution-bound actor. Every user type is PLATFORM, SELLER, BUYER, VALUER,
             * AGENT or VENDOR now — the three that once belonged to a bank became platform staff, because
             * the bank runs the platform rather than being let into it.
             */
            default -> throw new HodiException("That kind of user cannot be created here.",
                    HttpStatus.BAD_REQUEST);
        };
    }

    private void requireActorClass(UserType type, String expected, String message) {
        if (!expected.equals(type.getActorClass())) {
            throw new HodiException(message, HttpStatus.FORBIDDEN);
        }
    }

    /** Only the platform may mint platform staff. Anything else here would be an escalation path. */
    private void assertMayAssignType(UserPrincipal caller, UserType type) {
        if (AppConstant.ACTOR_PLATFORM.equals(type.getActorClass()) && !caller.isPlatformStaff()) {
            throw new HodiException("You cannot create platform staff.", HttpStatus.FORBIDDEN);
        }
    }

    /**
     * Resolves the group, checking it belongs to the same user type and to an owner the user can hold.
     *
     * <p>A group from another organisation would be a cross-organisation reference that
     * {@code EffectivePermissionResolver} would happily honour — it looks the group up by id and never asks
     * whose it is. This is the check that stops that, and it is why the group id is validated against the
     * profile's affiliation rather than the caller's.
     */
    private UserGroup resolveGroup(String groupHashId, UserType type, Affiliation affiliation) {
        Long groupId = HashIdUtil.decodeId(groupHashId);
        if (groupId == null) return null;

        UserGroup group = userGroups.findById(groupId)
                .orElseThrow(() -> new ResourceNotFoundException("User group", groupHashId));

        if (!group.getUserTypeCode().equals(type.getCode())) {
            throw new HodiException(
                    "\"%s\" is a group for %s, not %s."
                            .formatted(group.getName(), group.getUserTypeName(), type.getName()),
                    HttpStatus.BAD_REQUEST);
        }
        if (!AppConstant.isLive(group.getStatus())) {
            throw new HodiException("That group is not active.", HttpStatus.CONFLICT);
        }

        boolean ownedByThisUser =
                (group.getTenantId() != null && group.getTenantId().equals(affiliation.tenantId()))
                || (group.getInstitutionId() != null
                        && group.getInstitutionId().equals(affiliation.institutionId()))
                || group.isGlobal();
        if (!ownedByThisUser) {
            throw new HodiException("That group belongs to another organisation.", HttpStatus.FORBIDDEN);
        }
        /*
         * A global template assigned directly to a user is refused. A template is a shape to clone, and a
         * user pointing at one would mean the platform editing the template silently changed that person's
         * access — the exact coupling cloning exists to avoid. Platform roles (global, not template) are fine.
         */
        if (group.isTemplate()) {
            throw new HodiException(
                    "\"%s\" is a shared template. Clone it first, then assign the copy."
                            .formatted(group.getName()),
                    HttpStatus.BAD_REQUEST);
        }
        return group;
    }

    // ── guards ────────────────────────────────────────────────────────────────

    /** The account behind a profile. A profile without one is a referential impossibility, not a 404. */
    private User account(UserProfile profile) {
        return repository.findById(profile.getUserId())
                .orElseThrow(() -> new IllegalStateException(
                        "Profile " + profile.getId() + " has no user row"));
    }

    private UserProfile requireVisible(String hashId) {
        UserProfile profile = profiles.findById(HashIdUtil.decodeId(hashId))
                .orElseThrow(() -> new ResourceNotFoundException("User", hashId));
        UserPrincipal caller = AuthContext.require();
        if (caller.isPlatformStaff()) return profile;

        boolean mine = (caller.getTenantId() != null
                        && caller.getTenantId().equals(profile.getTenantId()))
                || (caller.getInstitutionId() != null
                        && caller.getInstitutionId().equals(profile.getInstitutionId()));
        if (!mine) {
            // Not-found rather than forbidden: confirming the account exists leaks that somebody works for
            // another organisation.
            throw new ResourceNotFoundException("User", hashId);
        }
        return profile;
    }

    /** Visible, and not a buyer — buyers are read-only through this module. */
    private UserProfile requireManageable(String hashId) {
        UserProfile profile = requireVisible(hashId);
        if (profile.isBuyerActor() && !AuthContext.require().isPlatformStaff()) {
            throw new HodiException("Buyer accounts are managed by their owner.", HttpStatus.FORBIDDEN);
        }
        return profile;
    }

    private void assertNotSelf(User user, String verb) {
        if (user.getId().equals(AuthContext.userId())) {
            throw new HodiException("You cannot " + verb + " your own account.", HttpStatus.CONFLICT);
        }
    }

    /**
     * Nothing goes round the bank's decision.
     *
     * <p>Three endpoints could otherwise: {@code activate} would enable the account outright, and
     * {@code deactivate} and {@code archive} would dispose of it while leaving a pending request pointing at
     * it — which a checker could then approve into existence.
     *
     * <p>So disposal is the bank's too: they reject it. A maker who created an account by mistake asks for a
     * rejection, and that is right rather than inconvenient — a maker who can make their own mistake vanish
     * unseen is not working under Maker/Checker. What they <em>can</em> do alone is fix it: editing a waiting
     * account resubmits it.
     */
    private static void assertNotAwaitingApproval(User user, String verb) {
        if (awaitingApproval(user)) {
            throw new HodiException(
                    verb + " is not yours to do while this account is waiting for the bank to approve it. "
                            + "Edit it to resubmit, or ask the bank to reject it.", HttpStatus.CONFLICT);
        }
    }

    /**
     * The lock-out guard: an organisation must always retain one live member of its owner group.
     *
     * <p>Without it, deactivating the last owner — or moving them to a lesser group — leaves an organisation
     * that nobody can administer, and no amount of permissions elsewhere gets it back. The refusal is
     * deliberately at the point of the change rather than a warning, because the person making it is usually
     * tidying up and would not read the warning.
     *
     * @param movingToGroupId the group the profile is being moved to, or null when it is being removed
     *                        outright. Passing the target lets the guard allow a sideways move between two
     *                        owner-carrying groups, which is a legitimate thing to want.
     */
    private void assertNotLastOwner(UserProfile profile, Long movingToGroupId) {
        if (profile.getUserGroupId() == null) return;

        UserGroup current = userGroups.findById(profile.getUserGroupId()).orElse(null);
        if (current == null || !current.isSystem()) return;
        if (movingToGroupId != null && movingToGroupId.equals(current.getId())) return;

        long remaining = profiles.countLiveMembers(current.getId());
        if (remaining <= 1) {
            // Parenthesised, because `.formatted()` binds to the literal immediately before it: without
            // the brackets it applied to the second half of the concatenation, which has no %s in it, and
            // the message reached the user with a literal "%s" where the group name should have been.
            throw new HodiException(
                    ("This is the last active member of \"%s\". Add another owner first, or this "
                            + "organisation would have nobody able to administer it.")
                            .formatted(current.getName()),
                    HttpStatus.CONFLICT);
        }
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    /**
     * A username, derived from the email's local part when none was given.
     *
     * <p>Uniqueness is global — one namespace for all four populations — so a collision is resolved by
     * appending digits rather than refused. Somebody adding staff should not have to invent a username
     * because another organisation already employs a "jane".
     */
    private String resolveUsername(String requested, String email) {
        String base = (requested == null || requested.isBlank())
                ? email.substring(0, email.indexOf('@')).replaceAll("[^a-zA-Z0-9._-]", "")
                : requested.trim();
        if (base.isEmpty()) base = "user";
        if (!repository.existsByUsernameIgnoreCase(base)) return base;
        if (requested != null && !requested.isBlank()) {
            // An explicitly requested username is refused rather than silently altered: somebody who typed
            // it wants that one, and handing them "jane2" without saying so is worse than saying no.
            throw new DuplicateResourceException("That username is taken");
        }
        for (int suffix = 2; suffix < 1000; suffix++) {
            String candidate = base + suffix;
            if (!repository.existsByUsernameIgnoreCase(candidate)) return candidate;
        }
        throw new HodiException("Could not derive a free username — please supply one.",
                HttpStatus.CONFLICT);
    }

    /**
     * A readable temporary password that still satisfies policy.
     *
     * <p>The alphabet excludes the characters people misread aloud ({@code 0/O}, {@code 1/l/I}), because this
     * gets read down a phone. The symbol and digit are appended rather than left to chance so the value
     * cannot fail the very policy check that is about to run on it.
     */
    private static String temporaryPassword() {
        StringBuilder sb = new StringBuilder(14);
        for (int i = 0; i < 10; i++) {
            sb.append(TEMP_ALPHABET.charAt(RANDOM.nextInt(TEMP_ALPHABET.length())));
        }
        sb.append('#');
        sb.append(RANDOM.nextInt(10));
        // Guarantee an uppercase, in case ten draws happened to miss.
        sb.setCharAt(0, Character.toUpperCase(sb.charAt(0)));
        return sb.toString();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private UserResponse toResponse(User user, UserProfile profile) {
        return new UserResponse(
                HashIdUtil.encodeId(profile.getId()),
                HashIdUtil.encodeId(user.getId()),
                user.getFirstName(),
                user.getLastName(),
                user.fullName(),
                user.getEmail(),
                user.getUsername(),
                user.getPhone(),
                storage.urlFor(user.getAvatarKey()),
                HashIdUtil.encodeId(profile.getUserTypeId()),
                profile.getUserTypeCode(),
                profile.getUserTypeName(),
                profile.getProfileType(),
                HashIdUtil.encodeId(profile.getUserGroupId()),
                profile.getUserGroupName(),
                HashIdUtil.encodeId(profile.getTenantId()),
                profile.getTenantName(),
                HashIdUtil.encodeId(profile.getInstitutionId()),
                profile.getInstitutionName(),
                profile.organisationLabel(),
                user.isTotpEnabled(),
                user.isSmsOtpEnabled(),
                user.isCurrentlyLocked(),
                user.getLockedUntil(),
                user.isMustChangePassword(),
                user.getEmailVerifiedAt() != null,
                user.getPhoneVerifiedAt() != null,
                user.getLastLogin(),
                // The account's status, not the profile's: it is what decides whether this person can sign
                // in, and it is what every action on the row changes.
                user.getStatus(),
                user.getStatusFlag(),
                user.getDeactivationReason(),
                awaitingApproval(user),
                user.getCreatedAt(),
                user.getCreatedBy());
    }

    private static String snapshot(User user, UserProfile profile) {
        return ("{\"username\":\"%s\",\"email\":\"%s\",\"userType\":\"%s\",\"group\":\"%s\","
                + "\"tenantId\":%s,\"institutionId\":%s,\"enabled\":%s,\"status\":%d,"
                + "\"profileId\":%s}")
                .formatted(user.getUsername(), user.getEmail(), profile.getUserTypeCode(),
                        profile.getUserGroupName() == null ? "" : profile.getUserGroupName(),
                        String.valueOf(profile.getTenantId()),
                        String.valueOf(profile.getInstitutionId()),
                        user.isEnabled(), user.getStatus(), String.valueOf(profile.getId()));
    }
}
