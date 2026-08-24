package com.hodi.modules.usertypes;

import com.hodi.common.AppConstant;
import com.hodi.common.PagedResponse;
import com.hodi.common.dto.PagedDataRequest;
import com.hodi.common.exception.DuplicateResourceException;
import com.hodi.common.exception.HodiException;
import com.hodi.common.exception.ResourceNotFoundException;
import com.hodi.common.util.SearchSpecs;
import com.hodi.modules.audit.AuditService;
import com.hodi.modules.usergroups.UserGroupRepository;
import com.hodi.modules.profiles.UserProfileRepository;
import com.hodi.modules.usertypes.dto.UserTypeDtos.CreateUserTypeRequest;
import com.hodi.modules.usertypes.dto.UserTypeDtos.UpdateUserTypeRequest;
import com.hodi.modules.usertypes.dto.UserTypeDtos.UserTypeResponse;
import com.hodi.security.hashid.HashIdUtil;
import com.hodi.security.principal.AuthContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * The global user-type catalogue. Platform-only throughout — no organisation may add a class of user
 * (plan section 4.2).
 *
 * <p>The interesting rule here is what <em>cannot</em> be edited. A type's {@code code} is matched as an exact
 * token inside every module's {@code allowed_user_types} CSV, and its {@code actorClass} decides which
 * organisation column its holders carry and how {@code PrincipalFactory} resolves their visible tenants.
 * Both are therefore immutable after creation: renaming a code would silently strip module access from
 * everybody holding it, and moving a type between actor classes would reclassify live users. Names,
 * descriptions and ordering are free to change, because nothing depends on them.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserTypeService {

    private final UserTypeRepository repository;
    private final UserProfileRepository profiles;
    private final UserGroupRepository userGroups;
    private final AuditService audit;

    @Transactional(readOnly = true)
    public PagedResponse<UserTypeResponse> list(PagedDataRequest request) {
        Specification<UserType> spec = SearchSpecs.allOf(
                SearchSpecs.notArchived(),
                SearchSpecs.fuzzy("searchText", request.getSearch()),
                SearchSpecs.statusIn(request.effectiveStatuses()));
        var page = repository.findAll(spec,
                request.toPageable(Sort.by(Sort.Direction.ASC, "sortOrder", "name")));
        return PagedResponse.from(page, this::toResponse);
    }

    /**
     * Every live type, for the pickers.
     *
     * <p>Readable by anyone who may see users or groups, not only by a super admin: a seller owner filling in
     * the user form needs the list to render it, and the list is a catalogue of labels rather than anything
     * sensitive. Which of them they may actually assign is narrowed on the client by actor class and on the
     * server by {@code UserService}.
     */
    @Transactional(readOnly = true)
    public List<UserTypeResponse> options() {
        return repository.findByStatusNotOrderBySortOrderAsc(AppConstant.STATUS_DELETED).stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional
    public UserTypeResponse create(CreateUserTypeRequest request) {
        String code = request.code().trim().toUpperCase();
        if (repository.existsByCodeIgnoreCase(code)) {
            throw new DuplicateResourceException("A user type with that code already exists");
        }
        UserType saved = repository.save(UserType.builder()
                .code(code)
                .name(request.name().trim())
                .description(request.description())
                .actorClass(request.actorClass().trim().toUpperCase())
                .sortOrder(request.sortOrder() == null ? 0 : request.sortOrder())
                .status(AppConstant.STATUS_ACTIVE)
                .statusFlag(AppConstant.FLAG_ACTIVE)
                .createdBy(AuthContext.username())
                .build());
        audit.record(AppConstant.ACTION_CREATE, "UserType", saved.getId(), null, saved);
        return toResponse(saved);
    }

    @Transactional
    public UserTypeResponse update(String hashId, UpdateUserTypeRequest request) {
        UserType type = require(hashId);
        String before = snapshot(type);

        // Deliberately not touching code or actorClass — see the class comment. The request cannot even
        // express them, so this is belt and braces against a future field being added carelessly.
        type.setName(request.name().trim());
        type.setDescription(request.description());
        if (request.sortOrder() != null) type.setSortOrder(request.sortOrder());
        type.setStatus(AppConstant.STATUS_EDITED);
        type.setStatusFlag(AppConstant.FLAG_EDITED);
        type.setUpdatedBy(AuthContext.username());

        UserType saved = repository.save(type);
        // The label cache on every profile holding this type. One writer, here — the rule the denormalisation
        // convention exists for. Previously the copy sat on `users` and nothing re-stamped it, so renaming a
        // type left every holder displaying the old name until they were next edited.
        profiles.renameUserTypeLabel(saved.getId(), saved.getName());
        audit.record(AppConstant.ACTION_UPDATE, "UserType", saved.getId(), before, snapshot(saved));
        return toResponse(saved);
    }

    /**
     * Takes a type out of use.
     *
     * <p>Refused while anybody still holds it. A deactivated type whose holders remain would leave those
     * people authenticated with a user type that no module admits — technically a safe state, since
     * {@code AppModule.allows} would deny everything, but one that presents as "my permissions vanished" with
     * nothing on their own account to explain it.
     */
    @Transactional
    public void deactivate(String hashId, String reason) {
        UserType type = require(hashId);
        long holders = countHolders(type);
        if (holders > 0) {
            throw new HodiException(
                    "%d user%s still %s this type. Move them to another type first."
                            .formatted(holders, holders == 1 ? "" : "s", holders == 1 ? "has" : "have"),
                    HttpStatus.CONFLICT);
        }
        String before = snapshot(type);
        type.setStatus(AppConstant.STATUS_INACTIVE);
        type.setStatusFlag(AppConstant.FLAG_INACTIVE);
        type.setDeactivationReason(reason);
        type.setUpdatedBy(AuthContext.username());
        repository.save(type);
        audit.record(AppConstant.ACTION_DEACTIVATE, "UserType", type.getId(), before, snapshot(type));
    }

    @Transactional
    public void activate(String hashId) {
        UserType type = require(hashId);
        String before = snapshot(type);
        type.setStatus(AppConstant.STATUS_ACTIVE);
        type.setStatusFlag(AppConstant.FLAG_ACTIVE);
        type.setDeactivationReason(null);
        type.setUpdatedBy(AuthContext.username());
        repository.save(type);
        audit.record(AppConstant.ACTION_ACTIVATE, "UserType", type.getId(), before, snapshot(type));
    }

    /** Soft archive to {@code status = 5}. Never a hard delete — the code is referenced from CSVs and rows. */
    @Transactional
    public void archive(String hashId) {
        UserType type = require(hashId);
        long holders = countHolders(type);
        if (holders > 0) {
            throw new HodiException("That type is still in use and cannot be deleted.",
                    HttpStatus.CONFLICT);
        }
        String before = snapshot(type);
        type.setStatus(AppConstant.STATUS_DELETED);
        type.setStatusFlag(AppConstant.FLAG_DELETED);
        type.setUpdatedBy(AuthContext.username());
        repository.save(type);
        audit.record(AppConstant.ACTION_DELETE, "UserType", type.getId(), before, snapshot(type));
    }

    // ── internals ─────────────────────────────────────────────────────────────

    private UserType require(String hashId) {
        Long id = HashIdUtil.decodeId(hashId);
        return repository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("User type", hashId));
    }

    private long countHolders(UserType type) {
        return profiles.countLiveByUserTypeCode(type.getCode());
    }

    private UserTypeResponse toResponse(UserType type) {
        return new UserTypeResponse(
                HashIdUtil.encodeId(type.getId()),
                type.getCode(),
                type.getName(),
                type.getDescription(),
                type.getActorClass(),
                type.getSortOrder(),
                countHolders(type),
                userGroups.countLiveByUserTypeCode(type.getCode()),
                type.getStatus(),
                type.getStatusFlag(),
                type.getCreatedAt(),
                type.getCreatedBy());
    }

    /** A compact before/after for the audit diff — the fields somebody would ask about. */
    private String snapshot(UserType type) {
        return "{\"code\":\"%s\",\"name\":\"%s\",\"actorClass\":\"%s\",\"sortOrder\":%d,\"status\":%d}"
                .formatted(type.getCode(), type.getName(), type.getActorClass(),
                        type.getSortOrder(), type.getStatus());
    }
}
