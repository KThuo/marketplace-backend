package com.hodi.modules.developments;

import com.hodi.common.AppConstant;
import com.hodi.common.exception.HodiException;
import com.hodi.common.exception.ResourceNotFoundException;
import com.hodi.modules.audit.AuditService;
import com.hodi.modules.tenants.Tenant;
import com.hodi.modules.tenants.TenantRepository;
import com.hodi.security.hashid.HashIdUtil;
import com.hodi.security.principal.AuthContext;
import com.hodi.security.principal.UserPrincipal;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * Rights an owner grants another organisation on their development.
 *
 * <p>The case: a bank finances a developer's project, owns the record because the exposure is theirs, and lets
 * the developer post the progress — they are the ones on site with the photographs. Without this the bank would
 * have to choose between tracking a project nobody updates and handing over its own file.
 *
 * <p>Every method here changes who can see or write somebody else's data, which is why granting has its own
 * permission rather than riding on update, why revoking demands a reason, and why both are audited.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DevelopmentCollaboratorService {

    private final DevelopmentCollaboratorRepository repository;
    private final DevelopmentRepository developments;
    private final TenantRepository tenants;
    private final DevelopmentVisibility visibility;
    private final AuditService audit;

    public record GrantRequest(
            @NotBlank String tenantHashId,
            /** PROGRESS_WRITE, UNITS_WRITE or FULL. Blank grants progress only. */
            @Size(max = 24) String rights,
            String note) {}

    public record RevokeRequest(@NotBlank String reason) {}

    public record CollaboratorResponse(
            String id,
            String tenantName,
            String rights,
            OffsetDateTime grantedAt,
            String grantedByName,
            OffsetDateTime revokedAt,
            String revokedReason,
            boolean live,
            String note) {}

    @Transactional(readOnly = true)
    public List<CollaboratorResponse> list(String developmentHashId) {
        Development development = requireVisible(developmentHashId);
        return repository.findForDevelopment(development.getId()).stream().map(this::toResponse).toList();
    }

    /**
     * Grants an organisation rights on this development.
     *
     * <p>Only the owner may grant, not a collaborator — otherwise a granted developer could grant onward, and
     * the bank would have no way to see who had been let in. {@code assertMayManage} is ownership, which is
     * exactly the right test here.
     */
    @Transactional
    public CollaboratorResponse grant(String developmentHashId, GrantRequest request) {
        UserPrincipal caller = AuthContext.require();
        Development development = requireVisible(developmentHashId);
        visibility.assertMayManage(development, caller);

        Tenant tenant = tenants.findById(HashIdUtil.decodeId(request.tenantHashId()))
                .orElseThrow(() -> new ResourceNotFoundException("Organisation", request.tenantHashId()));

        if (tenant.getId().equals(development.getTenantId())) {
            throw new HodiException(
                    "That organisation already owns this development — it needs no grant.",
                    HttpStatus.BAD_REQUEST);
        }
        if (repository.findLiveGrant(development.getId(), tenant.getId()).isPresent()) {
            throw new HodiException(
                    tenant.getName() + " already has rights here. Withdraw them first to change what they "
                            + "may do.", HttpStatus.CONFLICT);
        }

        String rights = request.rights() == null || request.rights().isBlank()
                ? AppConstant.COLLAB_PROGRESS_WRITE
                : request.rights().trim().toUpperCase();
        assertKnownRights(rights);

        DevelopmentCollaborator grant = repository.save(DevelopmentCollaborator.builder()
                .developmentId(development.getId())
                .tenantId(tenant.getId())
                .tenantName(tenant.getName())
                .rights(rights)
                .grantedByUserId(caller.getUserId())
                .grantedByName(caller.getFullName())
                .grantedAt(OffsetDateTime.now())
                .note(request.note() == null || request.note().isBlank() ? null : request.note().trim())
                .createdBy(AuthContext.username())
                .build());

        audit.record(AppConstant.ACTION_CREATE, "DevelopmentCollaborator", grant.getId(), null,
                "granted " + rights + " on " + development.getReference() + " to " + tenant.getName());
        log.info("{} granted {} on development {}", tenant.getName(), rights,
                development.getReference());
        return toResponse(grant);
    }

    /**
     * Withdraws a grant.
     *
     * <p>A timestamp and a reason rather than a delete: "who could post progress last March" is a question an
     * audit asks, and a deleted row cannot answer it. The visibility specification reads live grants only, so
     * the effect is immediate on the next query with nothing to clean up.
     */
    @Transactional
    public void revoke(String developmentHashId, String collaboratorHashId, RevokeRequest request) {
        UserPrincipal caller = AuthContext.require();
        Development development = requireVisible(developmentHashId);
        visibility.assertMayManage(development, caller);

        DevelopmentCollaborator grant = repository.findById(HashIdUtil.decodeId(collaboratorHashId))
                .orElseThrow(() -> new ResourceNotFoundException("Grant", collaboratorHashId));
        if (!grant.getDevelopmentId().equals(development.getId())) {
            throw new ResourceNotFoundException("Grant", collaboratorHashId);
        }
        if (grant.getRevokedAt() != null) {
            throw new HodiException("Those rights have already been withdrawn.", HttpStatus.CONFLICT);
        }

        grant.setRevokedAt(OffsetDateTime.now());
        grant.setRevokedByUserId(caller.getUserId());
        grant.setRevokedReason(request.reason().trim());
        grant.setUpdatedBy(AuthContext.username());
        repository.save(grant);

        audit.record(AppConstant.ACTION_REVOKE, "DevelopmentCollaborator", grant.getId(), null,
                "withdrew " + grant.getRights() + " on " + development.getReference()
                        + " from " + grant.getTenantName() + ": " + request.reason().trim());
        log.info("Rights on development {} withdrawn from {}", development.getReference(),
                grant.getTenantName());
    }

    private void assertKnownRights(String rights) {
        boolean known = AppConstant.COLLAB_PROGRESS_WRITE.equals(rights)
                || AppConstant.COLLAB_UNITS_WRITE.equals(rights)
                || AppConstant.COLLAB_FULL.equals(rights);
        if (!known) {
            throw new HodiException(
                    "Rights must be PROGRESS_WRITE, UNITS_WRITE or FULL.", HttpStatus.BAD_REQUEST);
        }
    }

    private Development requireVisible(String developmentHashId) {
        Development development = developments.findById(HashIdUtil.decodeId(developmentHashId))
                .orElseThrow(() -> new ResourceNotFoundException("Development", developmentHashId));
        if (!visibility.mayRead(development, AuthContext.require())) {
            throw new ResourceNotFoundException("Development", developmentHashId);
        }
        return development;
    }

    private CollaboratorResponse toResponse(DevelopmentCollaborator c) {
        return new CollaboratorResponse(
                HashIdUtil.encodeId(c.getId()),
                c.getTenantName(),
                c.getRights(),
                c.getGrantedAt(),
                c.getGrantedByName(),
                c.getRevokedAt(),
                c.getRevokedReason(),
                c.isLive(),
                c.getNote());
    }
}
