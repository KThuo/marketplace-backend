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
import com.hodi.modules.institutions.LendingInstitution;
import com.hodi.modules.institutions.LendingInstitutionRepository;
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
import com.hodi.security.TenantScope;
import com.hodi.security.hashid.HashIdUtil;
import com.hodi.security.password.PasswordService;
import com.hodi.security.principal.AuthContext;
import com.hodi.security.principal.UserPrincipal;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.OffsetDateTime;

/**
 * Staff administration: platform staff, seller staff and lender staff.
 *
 * <p>Buyers are not created here — they register themselves (see
 * {@code com.hodi.modules.buyers.BuyerRegistrationService}). They are visible through this module's list so
 * support can find somebody, but there is no path here that mints one, because a staff-created buyer with a
 * temporary password is an account nobody has proved they own.
 *
 * <h2>Organisation affiliation is derived, not submitted</h2>
 *
 * <p>The single most important rule in this class. A seller owner creating staff gets their own organisation,
 * full stop — the request's {@code tenantId} is ignored rather than validated, because a field that is
 * sometimes honoured is a field somebody will eventually get honoured. Only platform staff may name an
 * organisation, and what they may name is constrained by the user type's actor class: a {@code SELLER} type
 * needs a tenant and a {@code LENDER} type needs an institution, and neither can be given the other's.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserService {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String TEMP_ALPHABET =
            "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789";

    private final UserRepository repository;
    private final UserTypeRepository userTypes;
    private final UserGroupRepository userGroups;
    private final TenantRepository tenants;
    private final LendingInstitutionRepository institutions;
    private final PasswordService passwords;
    private final RefreshTokenService refreshTokens;
    private final StorageService storage;
    private final AuditService audit;

    // ── reads ─────────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public PagedResponse<UserResponse> list(UserListRequest request) {
        Specification<User> spec = SearchSpecs.allOf(
                SearchSpecs.notArchived(),
                SearchSpecs.fuzzy("searchText", request.getSearch()),
                SearchSpecs.statusIn(request.effectiveStatuses()),
                SearchSpecs.eq("actorClass", blankToNull(request.getActorClass())),
                SearchSpecs.eq("userTypeCode", blankToNull(request.getUserTypeCode())),
                SearchSpecs.eq("tenantId", HashIdUtil.decodeId(request.getTenantId())),
                SearchSpecs.eq("institutionId", HashIdUtil.decodeId(request.getInstitutionId())),
                SearchSpecs.eq("userGroupId", HashIdUtil.decodeId(request.getUserGroupId())),
                lockedFilter(request.getLocked()),
                visibleTo(AuthContext.require()));
        var page = repository.findAll(spec,
                request.toPageable(Sort.by(Sort.Direction.ASC, "firstName", "lastName")));
        return PagedResponse.from(page, this::toResponse);
    }

    /**
     * Which users the caller may see.
     *
     * <p>Not a plain {@code TenantScope.restrict("tenantId")}, because the {@code users} table holds three
     * populations that a tenant predicate alone gets wrong in both directions. A lender's staff carry no
     * tenant at all, so restricting on {@code tenant_id} would hide a lender admin's own colleagues from
     * them; and buyers carry no organisation either, so the same predicate would hide every buyer from
     * support. So:
     *
     * <ul>
     *   <li>platform staff — everyone;
     *   <li>seller staff — their own organisation's staff, and nobody else's;
     *   <li>lender staff — their own institution's staff. Deliberately <em>not</em> the staff of the sellers
     *       they are partnered with: a partnership grants sight of a portfolio, not of another
     *       organisation's people.
     * </ul>
     *
     * <p>That last point is the one worth being explicit about, because {@code TenantScope} would have said
     * otherwise. Visible-tenant scope is about business data; staff records are not business data.
     */
    private Specification<User> visibleTo(UserPrincipal caller) {
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

    private Specification<User> lockedFilter(Boolean locked) {
        if (locked == null) return null;
        return (root, query, cb) -> locked
                ? cb.isTrue(root.get("locked"))
                : cb.isFalse(root.get("locked"));
    }

    @Transactional(readOnly = true)
    public UserResponse find(String hashId) {
        return toResponse(requireVisible(hashId));
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
        UserType type = userTypes.findById(HashIdUtil.decodeId(request.userTypeId()))
                .orElseThrow(() -> new ResourceNotFoundException("User type", request.userTypeId()));

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
        UserGroup group = resolveGroup(request.userGroupId(), type, affiliation);

        String temporary = temporaryPassword();
        User user = User.builder()
                .firstName(request.firstName().trim())
                .lastName(request.lastName().trim())
                .email(email)
                .username(username)
                .phone(blankToNull(request.phone()))
                .userTypeId(type.getId())
                .userTypeCode(type.getCode())
                .userTypeName(type.getName())
                .actorClass(type.getActorClass())
                .tenantId(affiliation.tenantId())
                .tenantName(affiliation.tenantName())
                .institutionId(affiliation.institutionId())
                .institutionName(affiliation.institutionName())
                .userGroupId(group == null ? null : group.getId())
                .userGroupName(group == null ? null : group.getName())
                .mustChangePassword(true)
                .enabled(true)
                .status(AppConstant.STATUS_ACTIVE)
                .statusFlag(AppConstant.FLAG_ACTIVE)
                .createdBy(AuthContext.username())
                .build();
        // Through PasswordService so the temporary credential satisfies the same policy as a chosen one, and
        // so its expiry is stamped. A temporary password exempt from policy is a permanent one in practice.
        passwords.applyTo(user, temporary);
        // applyTo clears the flag, because it is the method a user calls to satisfy it. Re-set it: this
        // password was not chosen by its holder.
        user.setMustChangePassword(true);

        User saved = repository.save(user);
        audit.record(AppConstant.ACTION_CREATE, "User", saved.getId(), null, snapshot(saved));
        log.info("Created {} user {} in {}", saved.getActorClass(), saved.getUsername(),
                affiliation.label());
        return new TemporaryPasswordResponse(saved.getUsername(), temporary);
    }

    @Transactional
    public UserResponse update(String hashId, UpdateUserRequest request) {
        User user = requireManageable(hashId);
        String before = snapshot(user);

        String email = request.email().trim().toLowerCase();
        if (!email.equals(user.getEmail()) && repository.existsByEmail(email)) {
            throw new DuplicateResourceException("An account with that email address already exists");
        }

        user.setFirstName(request.firstName().trim());
        user.setLastName(request.lastName().trim());
        user.setEmail(email);
        user.setPhone(blankToNull(request.phone()));

        if (request.userGroupId() != null) {
            UserType type = userTypes.findById(user.getUserTypeId())
                    .orElseThrow(() -> new ResourceNotFoundException("User type", user.getUserTypeId()));
            UserGroup group = resolveGroup(request.userGroupId(), type,
                    new Affiliation(user.getTenantId(), user.getTenantName(),
                            user.getInstitutionId(), user.getInstitutionName()));
            if (group != null) {
                assertNotLastOwner(user, group.getId());
                user.setUserGroupId(group.getId());
                user.setUserGroupName(group.getName());
            }
        }

        user.setStatus(AppConstant.STATUS_EDITED);
        user.setStatusFlag(AppConstant.FLAG_EDITED);
        user.setUpdatedBy(AuthContext.username());

        User saved = repository.save(user);
        audit.record(AppConstant.ACTION_UPDATE, "User", saved.getId(), before, snapshot(saved));
        return toResponse(saved);
    }

    @Transactional
    public void deactivate(String hashId, String reason) {
        User user = requireManageable(hashId);
        assertNotSelf(user, "deactivate");
        assertNotLastOwner(user, null);

        String before = snapshot(user);
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
        audit.record(AppConstant.ACTION_DEACTIVATE, "User", user.getId(), before, snapshot(user));
    }

    @Transactional
    public void activate(String hashId) {
        User user = requireManageable(hashId);
        String before = snapshot(user);
        user.setEnabled(true);
        user.setLocked(false);
        user.setLockedUntil(null);
        user.setFailedAttempts(0);
        user.setStatus(AppConstant.STATUS_ACTIVE);
        user.setStatusFlag(AppConstant.FLAG_ACTIVE);
        user.setDeactivationReason(null);
        user.setUpdatedBy(AuthContext.username());
        repository.save(user);
        audit.record(AppConstant.ACTION_ACTIVATE, "User", user.getId(), before, snapshot(user));
    }

    @Transactional
    public void archive(String hashId) {
        User user = requireManageable(hashId);
        assertNotSelf(user, "delete");
        assertNotLastOwner(user, null);

        String before = snapshot(user);
        user.setEnabled(false);
        user.setStatus(AppConstant.STATUS_DELETED);
        user.setStatusFlag(AppConstant.FLAG_DELETED);
        user.setUpdatedBy(AuthContext.username());
        repository.save(user);
        refreshTokens.revokeAllForUser(user.getId());
        audit.record(AppConstant.ACTION_DELETE, "User", user.getId(), before, snapshot(user));
    }

    /** Issues a fresh temporary password, shown once. Also clears any lockout — that is the point. */
    @Transactional
    public TemporaryPasswordResponse resetPassword(String hashId) {
        User user = requireManageable(hashId);
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
        return new TemporaryPasswordResponse(user.getUsername(), temporary);
    }

    @Transactional
    public int revokeSessions(String hashId) {
        User user = requireManageable(hashId);
        int revoked = refreshTokens.revokeAllForUser(user.getId());
        user.setSessionsValidFrom(OffsetDateTime.now());
        repository.save(user);
        audit.record(AppConstant.AUDIT_SESSION_REVOKED, "User", user.getId(), null,
                "revoked " + revoked + " session(s)");
        return revoked;
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
     * {@code LENDER} type placed in a tenant would produce a user whose visible-tenant set resolves through a
     * partnership lookup on an institution they do not have, which is a broken account rather than a
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
            if (caller.getInstitutionId() != null) {
                requireActorClass(type, AppConstant.ACTOR_LENDER,
                        "You can only add staff of your own institution's kind.");
                return new Affiliation(null, null,
                        caller.getInstitutionId(), caller.getInstitutionName());
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
            case AppConstant.ACTOR_LENDER -> {
                Long institutionId = HashIdUtil.decodeId(institutionHashId);
                if (institutionId == null) {
                    throw new HodiException("Choose the lending institution this user belongs to.",
                            HttpStatus.BAD_REQUEST);
                }
                LendingInstitution institution = institutions.findById(institutionId)
                        .orElseThrow(() -> new ResourceNotFoundException(
                                "Institution", institutionHashId));
                yield new Affiliation(null, null, institution.getId(), institution.getName());
            }
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
     * user's affiliation rather than the caller's.
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

    private User requireVisible(String hashId) {
        User user = repository.findById(HashIdUtil.decodeId(hashId))
                .orElseThrow(() -> new ResourceNotFoundException("User", hashId));
        UserPrincipal caller = AuthContext.require();
        if (caller.isPlatformStaff()) return user;

        boolean mine = (caller.getTenantId() != null
                        && caller.getTenantId().equals(user.getTenantId()))
                || (caller.getInstitutionId() != null
                        && caller.getInstitutionId().equals(user.getInstitutionId()));
        if (!mine) {
            // Not-found rather than forbidden: confirming the account exists leaks that somebody works for
            // another organisation.
            throw new ResourceNotFoundException("User", hashId);
        }
        return user;
    }

    /** Visible, and not a buyer — buyers are read-only through this module. */
    private User requireManageable(String hashId) {
        User user = requireVisible(hashId);
        if (user.isBuyerActor() && !AuthContext.require().isPlatformStaff()) {
            throw new HodiException("Buyer accounts are managed by their owner.", HttpStatus.FORBIDDEN);
        }
        return user;
    }

    private void assertNotSelf(User user, String verb) {
        if (user.getId().equals(AuthContext.userId())) {
            throw new HodiException("You cannot " + verb + " your own account.", HttpStatus.CONFLICT);
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
     * @param movingToGroupId the group the user is being moved to, or null when they are being removed
     *                        outright. Passing the target lets the guard allow a sideways move between two
     *                        owner-carrying groups, which is a legitimate thing to want.
     */
    private void assertNotLastOwner(User user, Long movingToGroupId) {
        if (user.getUserGroupId() == null) return;

        UserGroup current = userGroups.findById(user.getUserGroupId()).orElse(null);
        if (current == null || !current.isSystem()) return;
        if (movingToGroupId != null && movingToGroupId.equals(current.getId())) return;

        long remaining = repository.countLiveMembers(current.getId());
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

    private UserResponse toResponse(User user) {
        return new UserResponse(
                HashIdUtil.encodeId(user.getId()),
                user.getFirstName(),
                user.getLastName(),
                user.fullName(),
                user.getEmail(),
                user.getUsername(),
                user.getPhone(),
                storage.urlFor(user.getAvatarKey()),
                HashIdUtil.encodeId(user.getUserTypeId()),
                user.getUserTypeCode(),
                user.getUserTypeName(),
                user.getActorClass(),
                HashIdUtil.encodeId(user.getUserGroupId()),
                user.getUserGroupName(),
                HashIdUtil.encodeId(user.getTenantId()),
                user.getTenantName(),
                HashIdUtil.encodeId(user.getInstitutionId()),
                user.getInstitutionName(),
                organisationLabel(user),
                user.isTotpEnabled(),
                user.isSmsOtpEnabled(),
                user.isCurrentlyLocked(),
                user.getLockedUntil(),
                user.isMustChangePassword(),
                user.getEmailVerifiedAt() != null,
                user.getPhoneVerifiedAt() != null,
                user.getLastLogin(),
                user.getStatus(),
                user.getStatusFlag(),
                user.getDeactivationReason(),
                user.getCreatedAt(),
                user.getCreatedBy());
    }

    /** The scope column: which organisation this person belongs to, in one string. */
    private static String organisationLabel(User user) {
        if (user.getTenantName() != null) return user.getTenantName();
        if (user.getInstitutionName() != null) return user.getInstitutionName();
        return user.isBuyerActor() ? "Buyer" : "Platform";
    }

    private static String snapshot(User user) {
        return ("{\"username\":\"%s\",\"email\":\"%s\",\"userType\":\"%s\",\"group\":\"%s\","
                + "\"tenantId\":%s,\"institutionId\":%s,\"enabled\":%s,\"status\":%d}")
                .formatted(user.getUsername(), user.getEmail(), user.getUserTypeCode(),
                        user.getUserGroupName() == null ? "" : user.getUserGroupName(),
                        String.valueOf(user.getTenantId()), String.valueOf(user.getInstitutionId()),
                        user.isEnabled(), user.getStatus());
    }
}
