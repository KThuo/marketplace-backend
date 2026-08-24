package com.hodi.modules.usergroups;

import com.hodi.common.AppConstant;
import com.hodi.common.PagedResponse;
import com.hodi.common.dto.PagedDataRequest;
import com.hodi.common.exception.DuplicateResourceException;
import com.hodi.common.exception.HodiException;
import com.hodi.common.exception.ResourceNotFoundException;
import com.hodi.common.util.SearchSpecs;
import com.hodi.modules.appmodules.AppModule;
import com.hodi.modules.appmodules.AppModuleRepository;
import com.hodi.modules.audit.AuditService;
import com.hodi.modules.permissions.Permission;
import com.hodi.modules.permissions.PermissionRepository;
import com.hodi.modules.tenantmodules.TenantModuleRepository;
import com.hodi.modules.usergroups.dto.UserGroupDtos.CloneTemplateRequest;
import com.hodi.modules.usergroups.dto.UserGroupDtos.CreateUserGroupRequest;
import com.hodi.modules.usergroups.dto.UserGroupDtos.UpdateUserGroupRequest;
import com.hodi.modules.usergroups.dto.UserGroupDtos.UserGroupResponse;
import com.hodi.modules.profiles.UserProfileRepository;
import com.hodi.modules.usertypes.UserType;
import com.hodi.modules.usertypes.UserTypeRepository;
import com.hodi.security.hashid.HashIdUtil;
import com.hodi.security.principal.AuthContext;
import com.hodi.security.principal.UserPrincipal;
import jakarta.persistence.criteria.Predicate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * User groups — roles as arbitrary permission bundles (access axis 2).
 *
 * <p><strong>No hardcoded bundles anywhere.</strong> An organisation composes a group from any subset of the
 * catalogue, of any size. The seeded templates are ordinary rows, and cloning one produces a row that is
 * independent from that moment — editing the template afterwards does not reach into the clones, which is
 * what makes offering templates safe rather than a hidden coupling.
 *
 * <p><strong>What this service is really guarding</strong> is that an organisation cannot grant itself more
 * than it has. Every write goes through {@link #resolvePermissions}, which refuses platform-only codes and
 * codes whose module either is not enabled for the organisation or does not admit the group's user type. The
 * picker filters the same three ways, but the picker runs in a browser — this is the copy that counts.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserGroupService {

    private final UserGroupRepository repository;
    private final PermissionRepository permissions;
    private final UserTypeRepository userTypes;
    private final AppModuleRepository appModules;
    private final TenantModuleRepository tenantModules;
    private final UserProfileRepository profiles;
    private final AuditService audit;

    // ── reads ─────────────────────────────────────────────────────────────────

    /**
     * The groups the caller may see.
     *
     * <p>Not {@code TenantScope.restrict}, and that is deliberate rather than an oversight: a group's
     * visibility is not the same shape as a data row's. An organisation sees <em>its own groups plus the
     * global templates</em>, and templates belong to nobody — a tenant-id predicate would hide exactly the
     * rows every organisation is supposed to see. Platform staff see everything.
     */
    @Transactional(readOnly = true)
    public PagedResponse<UserGroupResponse> list(PagedDataRequest request) {
        UserPrincipal caller = AuthContext.require();
        Specification<UserGroup> spec = SearchSpecs.allOf(
                SearchSpecs.notArchived(),
                SearchSpecs.fuzzy("searchText", request.getSearch()),
                SearchSpecs.statusIn(request.effectiveStatuses()),
                visibleTo(caller));
        var page = repository.findAll(spec,
                request.toPageable(Sort.by(Sort.Direction.ASC, "userTypeCode", "name")));
        return PagedResponse.from(page, this::toResponse);
    }

    private Specification<UserGroup> visibleTo(UserPrincipal caller) {
        if (caller.isPlatformStaff()) return null;
        return (root, query, cb) -> {
            List<Predicate> ors = new ArrayList<>();
            // The global templates and platform roles: owned by nobody.
            ors.add(cb.and(cb.isNull(root.get("tenantId")), cb.isNull(root.get("institutionId"))));
            if (caller.getTenantId() != null) {
                ors.add(cb.equal(root.get("tenantId"), caller.getTenantId()));
            }
            if (caller.getInstitutionId() != null) {
                ors.add(cb.equal(root.get("institutionId"), caller.getInstitutionId()));
            }
            return cb.or(ors.toArray(new Predicate[0]));
        };
    }

    @Transactional(readOnly = true)
    public UserGroupResponse find(String hashId) {
        return toResponse(requireVisible(hashId));
    }

    /** The cloneable global templates. */
    @Transactional(readOnly = true)
    public List<UserGroupResponse> templates() {
        return repository.findTemplates().stream().map(this::toResponse).toList();
    }

    /** Groups assignable to a user of one type — own organisation's, plus templates. */
    @Transactional(readOnly = true)
    public List<UserGroupResponse> assignable(String userTypeCode) {
        UserPrincipal caller = AuthContext.require();
        return repository.findAssignable(userTypeCode, caller.getTenantId(), caller.getInstitutionId())
                .stream()
                .map(this::toResponse)
                .toList();
    }

    // ── writes ────────────────────────────────────────────────────────────────

    @Transactional
    public UserGroupResponse create(CreateUserGroupRequest request) {
        UserPrincipal caller = AuthContext.require();
        UserType type = userTypes.findById(HashIdUtil.decodeId(request.userTypeId()))
                .orElseThrow(() -> new ResourceNotFoundException("User type", request.userTypeId()));

        assertNameFree(request.name().trim(), caller, null);

        UserGroup group = UserGroup.builder()
                .name(request.name().trim())
                .description(request.description())
                .userTypeId(type.getId())
                .userTypeCode(type.getCode())
                .userTypeName(type.getName())
                // Ownership is derived from the caller, never taken from the request. A field that accepted a
                // tenant id would be a field somebody could point at another organisation.
                .tenantId(caller.getTenantId())
                .institutionId(caller.getInstitutionId())
                .template(false)
                .system(false)
                .status(AppConstant.STATUS_ACTIVE)
                .statusFlag(AppConstant.FLAG_ACTIVE)
                .createdBy(AuthContext.username())
                .build();
        group.setPermissions(resolvePermissions(request.permissions(), type.getCode(), caller));

        UserGroup saved = repository.save(group);
        audit.record(AppConstant.ACTION_CREATE, "UserGroup", saved.getId(), null, snapshot(saved));
        return toResponse(saved);
    }

    @Transactional
    public UserGroupResponse update(String hashId, UpdateUserGroupRequest request) {
        UserGroup group = requireOwned(hashId);
        UserPrincipal caller = AuthContext.require();
        String before = snapshot(group);

        assertNameFree(request.name().trim(), caller, group.getId());

        group.setName(request.name().trim());
        group.setDescription(request.description());

        /*
         * The user type is not editable, and this is not laziness.
         *
         * A group's type decides which modules admit it, which is what its permission set was picked against.
         * Changing it would leave a bundle assembled for one class of user attached to another — every
         * permission whose module does not admit the new type would go silently inert, and the group would
         * look intact while granting a fraction of what it says. Rebuilding it as a new group is honest about
         * the fact that it is a different role.
         */

        if (request.permissions() != null) {
            if (group.isSystem()) {
                // A system group always carries everything its side may hold. Letting somebody trim it is
                // how an organisation locks itself out of its own account.
                throw new HodiException(
                        "The owner group's permissions cannot be edited — it always carries everything.",
                        HttpStatus.CONFLICT);
            }
            group.setPermissions(resolvePermissions(request.permissions(), group.getUserTypeCode(), caller));
        }
        group.setStatus(AppConstant.STATUS_EDITED);
        group.setStatusFlag(AppConstant.FLAG_EDITED);
        group.setUpdatedBy(AuthContext.username());

        UserGroup saved = repository.save(group);
        // One writer for the denormalised label, living in the owning service — the rule from the
        // denormalisation convention. An ad-hoc UPDATE elsewhere is how the copy starts disagreeing.
        profiles.renameGroupLabel(saved.getId(), saved.getName());
        audit.record(AppConstant.ACTION_UPDATE, "UserGroup", saved.getId(), before, snapshot(saved));
        return toResponse(saved);
    }

    /**
     * Clones a global template into the layer the caller owns.
     *
     * <p>The clone is independent from this moment: it copies the template's permissions rather than
     * referencing it, so later edits to the template do not reach it. That is the property that makes
     * templates useful — an organisation can start from a sensible role and then diverge without the platform
     * quietly changing their access underneath them.
     *
     * <p><strong>Platform staff clone into the global layer.</strong> This used to refuse them outright — "no
     * organisation to clone into. Edit the template instead" — which was wrong twice over: editing the shared
     * template to make one platform role would change what every organisation cloning it afterwards receives,
     * and it left platform staff with no way at all to assign a Support Administrator, since a template cannot
     * be held by a user. The copy they get is a global, non-template group: theirs to assign, and no longer
     * the shape everybody else starts from.
     */
    @Transactional
    public UserGroupResponse cloneTemplate(String templateHashId, CloneTemplateRequest request) {
        UserPrincipal caller = AuthContext.require();
        UserGroup template = repository.findById(HashIdUtil.decodeId(templateHashId))
                .orElseThrow(() -> new ResourceNotFoundException("Template", templateHashId));
        if (!template.isTemplate() || !template.isGlobal()) {
            throw new HodiException("That is not a template.", HttpStatus.BAD_REQUEST);
        }

        /*
         * A platform clone has to be renamed, because it lands in the same layer as the template it came
         * from and the name is unique there. "Support Administrator" would collide with the template of that
         * name; "Support Administrator (platform)" says what it is.
         */
        String fallback = caller.isPlatformStaff()
                ? template.getName() + " (platform)"
                : template.getName();
        String name = request.name() == null || request.name().isBlank()
                ? fallback
                : request.name().trim();
        assertNameFree(name, caller, null);

        UserGroup clone = UserGroup.builder()
                .name(name)
                .description(template.getDescription())
                .userTypeId(template.getUserTypeId())
                .userTypeCode(template.getUserTypeCode())
                .userTypeName(template.getUserTypeName())
                .tenantId(caller.getTenantId())
                .institutionId(caller.getInstitutionId())
                .template(false)
                .system(false)
                .status(AppConstant.STATUS_ACTIVE)
                .statusFlag(AppConstant.FLAG_ACTIVE)
                .createdBy(AuthContext.username())
                .build();
        /*
         * Filtered through the same resolver as a hand-built group rather than copied wholesale. A template is
         * seeded against the full catalogue, so a tenant whose modules are a subset of it would otherwise
         * receive permissions their organisation cannot use — and, if a template ever carried a platform-only
         * code by mistake, cloning would be the way it escaped.
         */
        clone.setPermissions(resolvePermissions(
                template.getPermissions().stream().map(Permission::getActionCode).toList(),
                template.getUserTypeCode(), caller));

        UserGroup saved = repository.save(clone);
        audit.record(AppConstant.ACTION_CLONE, "UserGroup", saved.getId(),
                "template=" + template.getName(), snapshot(saved));
        return toResponse(saved);
    }

    @Transactional
    public void deactivate(String hashId, String reason) {
        UserGroup group = requireOwned(hashId);
        assertNotSystem(group, "deactivated");
        assertNoLiveMembers(group);

        String before = snapshot(group);
        group.setStatus(AppConstant.STATUS_INACTIVE);
        group.setStatusFlag(AppConstant.FLAG_INACTIVE);
        group.setDeactivationReason(reason);
        group.setUpdatedBy(AuthContext.username());
        repository.save(group);
        audit.record(AppConstant.ACTION_DEACTIVATE, "UserGroup", group.getId(), before, snapshot(group));
    }

    @Transactional
    public void activate(String hashId) {
        UserGroup group = requireOwned(hashId);
        String before = snapshot(group);
        group.setStatus(AppConstant.STATUS_ACTIVE);
        group.setStatusFlag(AppConstant.FLAG_ACTIVE);
        group.setDeactivationReason(null);
        group.setUpdatedBy(AuthContext.username());
        repository.save(group);
        audit.record(AppConstant.ACTION_ACTIVATE, "UserGroup", group.getId(), before, snapshot(group));
    }

    @Transactional
    public void archive(String hashId) {
        UserGroup group = requireOwned(hashId);
        assertNotSystem(group, "deleted");
        assertNoLiveMembers(group);

        String before = snapshot(group);
        group.setStatus(AppConstant.STATUS_DELETED);
        group.setStatusFlag(AppConstant.FLAG_DELETED);
        group.setUpdatedBy(AuthContext.username());
        repository.save(group);
        audit.record(AppConstant.ACTION_DELETE, "UserGroup", group.getId(), before, snapshot(group));
    }

    // ── the guard that matters ────────────────────────────────────────────────

    /**
     * Turns submitted action codes into permissions, refusing everything the caller may not grant.
     *
     * <p>Three refusals, in the order they matter:
     *
     * <ol>
     *   <li><strong>Platform-only codes</strong>, unless the caller is platform staff. This is the privilege
     *       boundary: without it, a seller owner could post {@code TENANTS_CREATE} into one of their own
     *       groups and onboard organisations.
     *   <li><strong>Modules the group's user type does not admit.</strong> Not a security hole so much as a
     *       correctness one — the resolver would drop those authorities at login, leaving a role that reads as
     *       granted and behaves as empty.
     *   <li><strong>Modules not enabled for the caller's organisation.</strong> Same reasoning.
     * </ol>
     *
     * <p>Unknown codes are refused rather than skipped. Silently dropping one would mean the group saved
     * successfully and did less than the person who saved it believes.
     */
    private Set<Permission> resolvePermissions(List<String> actionCodes, String userTypeCode,
                                               UserPrincipal caller) {
        if (actionCodes == null || actionCodes.isEmpty()) return new LinkedHashSet<>();

        Set<String> requested = actionCodes.stream()
                .filter(c -> c != null && !c.isBlank())
                .map(c -> c.trim().toUpperCase())
                .collect(Collectors.toCollection(LinkedHashSet::new));

        List<Permission> found = permissions.findByActionCodeIn(requested);
        if (found.size() != requested.size()) {
            Set<String> known = found.stream().map(Permission::getActionCode).collect(Collectors.toSet());
            List<String> unknown = requested.stream().filter(c -> !known.contains(c)).toList();
            throw new HodiException("Unknown permission: " + String.join(", ", unknown),
                    HttpStatus.BAD_REQUEST);
        }

        Map<String, AppModule> modules = appModules.findAll().stream()
                .collect(Collectors.toMap(AppModule::getCode, Function.identity(), (a, b) -> a));
        Set<String> tenantEnabled = caller.getTenantId() == null
                ? null
                : Set.copyOf(tenantModules.findEnabledModuleCodes(caller.getTenantId()));

        Set<Permission> granted = new LinkedHashSet<>();
        List<String> refused = new ArrayList<>();
        for (Permission permission : found) {
            if (permission.isPlatformOnly() && !caller.isPlatformStaff()) {
                refused.add(permission.getActionCode());
                continue;
            }
            AppModule module = modules.get(permission.getModuleCode());
            if (module == null || !AppConstant.isLive(module.getStatus())) {
                refused.add(permission.getActionCode());
                continue;
            }
            if (tenantEnabled != null && !tenantEnabled.contains(module.getCode())) {
                refused.add(permission.getActionCode());
                continue;
            }
            if (userTypeCode != null && !module.allows(userTypeCode)) {
                refused.add(permission.getActionCode());
                continue;
            }
            granted.add(permission);
        }

        if (!refused.isEmpty()) {
            log.warn("Refused {} permission(s) for a group of type {}: {}",
                    refused.size(), userTypeCode, refused);
            throw new HodiException(
                    "These permissions are not available for this kind of user: "
                            + String.join(", ", refused),
                    HttpStatus.FORBIDDEN);
        }
        return granted;
    }

    // ── internals ─────────────────────────────────────────────────────────────

    /** Readable by the caller: their own organisation's, or a global template. */
    private UserGroup requireVisible(String hashId) {
        UserGroup group = repository.findById(HashIdUtil.decodeId(hashId))
                .orElseThrow(() -> new ResourceNotFoundException("User group", hashId));
        UserPrincipal caller = AuthContext.require();
        if (caller.isPlatformStaff()) return group;
        if (group.isGlobal()) return group;
        boolean mine = (group.getTenantId() != null
                        && group.getTenantId().equals(caller.getTenantId()))
                || (group.getInstitutionId() != null
                        && group.getInstitutionId().equals(caller.getInstitutionId()));
        if (!mine) {
            // Not-found rather than forbidden: confirming the group exists tells the caller something about
            // another organisation's configuration.
            throw new ResourceNotFoundException("User group", hashId);
        }
        return group;
    }

    /**
     * Writable by the caller.
     *
     * <p>Stricter than {@link #requireVisible} in exactly one way: a global template is readable by everyone
     * and editable only by the platform. An organisation that wants a different template clones it.
     */
    private UserGroup requireOwned(String hashId) {
        UserGroup group = requireVisible(hashId);
        UserPrincipal caller = AuthContext.require();
        if (caller.isPlatformStaff()) return group;
        if (group.isGlobal()) {
            throw new HodiException(
                    "That is a shared template. Clone it to make a version you can change.",
                    HttpStatus.FORBIDDEN);
        }
        return group;
    }

    private void assertNotSystem(UserGroup group, String verb) {
        if (group.isSystem()) {
            throw new HodiException(
                    "The owner group cannot be " + verb + " — it is what keeps access to this account.",
                    HttpStatus.CONFLICT);
        }
    }

    /**
     * A group with people in it cannot be taken out of use.
     *
     * <p>Otherwise those users keep the group id on their row and resolve to no permissions at all — they can
     * sign in and see nothing, with nothing on their own account to explain it. Moving them first makes the
     * consequence visible to whoever is causing it.
     */
    private void assertNoLiveMembers(UserGroup group) {
        long members = profiles.countLiveMembers(group.getId());
        if (members > 0) {
            throw new HodiException(
                    "%d user%s still in this group. Move them to another group first."
                            .formatted(members, members == 1 ? " is" : "s are"),
                    HttpStatus.CONFLICT);
        }
    }

    /** Names are unique within an owner, not globally — two sellers may both have a "Sales" group. */
    private void assertNameFree(String name, UserPrincipal caller, Long excludeId) {
        var existing = caller.getTenantId() != null
                ? repository.findByNameAndTenantId(name, caller.getTenantId())
                : caller.getInstitutionId() != null
                        ? repository.findByNameAndInstitutionId(name, caller.getInstitutionId())
                        : repository.findGlobalByName(name);
        if (existing.isPresent() && !existing.get().getId().equals(excludeId)) {
            throw new DuplicateResourceException("You already have a group called \"" + name + "\"");
        }
    }

    private UserGroupResponse toResponse(UserGroup group) {
        List<String> codes = group.getPermissions().stream()
                .map(Permission::getActionCode)
                .sorted()
                .toList();
        return new UserGroupResponse(
                HashIdUtil.encodeId(group.getId()),
                group.getName(),
                group.getDescription(),
                HashIdUtil.encodeId(group.getUserTypeId()),
                group.getUserTypeCode(),
                group.getUserTypeName(),
                HashIdUtil.encodeId(group.getTenantId()),
                null,
                HashIdUtil.encodeId(group.getInstitutionId()),
                null,
                group.isTemplate(),
                group.isSystem(),
                ownerLabel(group),
                codes.size(),
                profiles.countLiveMembers(group.getId()),
                codes,
                group.getStatus(),
                group.getStatusFlag(),
                group.getCreatedAt(),
                group.getCreatedBy());
    }

    /** What the list's scope column shows, so somebody can tell a template from their own group at a glance. */
    private static String ownerLabel(UserGroup group) {
        if (group.isTemplate()) return "Shared template";
        if (group.isGlobal()) return "Platform";
        return group.getTenantId() != null ? "This organisation" : "This institution";
    }

    private static String snapshot(UserGroup group) {
        return "{\"name\":\"%s\",\"userType\":\"%s\",\"permissions\":%d,\"status\":%d}"
                .formatted(group.getName(), group.getUserTypeCode(),
                        group.getPermissions().size(), group.getStatus());
    }
}
