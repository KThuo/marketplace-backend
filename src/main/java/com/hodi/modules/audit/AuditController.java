package com.hodi.modules.audit;

import com.hodi.common.ApiResponse;
import com.hodi.common.PagedResponse;
import com.hodi.common.dto.PagedDataRequest;
import com.hodi.common.util.SearchSpecs;
import com.hodi.security.TenantScope;
import com.hodi.security.hashid.HashIdUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;

/**
 * The audit trail, read-only.
 *
 * <p>Scoped through {@code TenantScope} like every other tenant-bearing list: platform staff see everything,
 * a seller sees their own organisation's trail, and the bank sees the trail of the organisations they are
 * partnered with. That last one follows from the scope model rather than being a separate decision — the bank
 * working a seller's portfolio can see what happened to it.
 *
 * <p>Rows with a null {@code tenant_id} are platform actions, and a restricted caller does not see them:
 * {@code TenantScope.restrict} produces an {@code IN} predicate, which null never satisfies. That is the
 * behaviour we want, and it is worth naming because it is easy to read as an oversight.
 */
@RestController
@RequestMapping("/api/v1/audit")
@RequiredArgsConstructor
public class AuditController {

    private final AuditLogRepository repository;

    public record AuditResponse(
            String id, String actionId, String actorUsername, String actorUserType, String actorClass,
            String operation, String entity, String entityId, String outcome,
            String changedFields, String ipAddress, OffsetDateTime createdAt) {}

    @GetMapping("/list")
    @PreAuthorize("hasAuthority('AUDIT_VIEW')")
    @Transactional(readOnly = true)
    public ApiResponse<PagedResponse<AuditResponse>> list(@ModelAttribute PagedDataRequest request,
                                                          @RequestParam(required = false) String entity,
                                                          @RequestParam(required = false) String operation) {
        Specification<AuditLog> spec = SearchSpecs.allOf(
                SearchSpecs.fuzzy("searchText", request.getSearch()),
                SearchSpecs.eq("entity", entity == null || entity.isBlank() ? null : entity),
                SearchSpecs.eq("operation", operation == null || operation.isBlank() ? null : operation),
                SearchSpecs.betweenDays("createdAt", request.getFrom(), request.getTo()),
                TenantScope.restrict("tenantId"));
        var page = repository.findAll(spec,
                request.toPageable(Sort.by(Sort.Direction.DESC, "createdAt")));
        return ApiResponse.success(PagedResponse.from(page, AuditController::toResponse));
    }

    /**
     * What this person did, whoever they are.
     *
     * <p>No {@code AUDIT_VIEW}. That permission governs reading *other people's* trail, which is a
     * privilege; reading your own is not one, and gating it would mean the only people who could see their
     * own history are the auditors who least need the feature. A buyer, a seller's agent and a bank clerk
     * all get theirs.
     *
     * <p>Filtered on {@code actorUserId} rather than on the username, because a username can be changed in
     * the first session and the trail would then split across two names. The id is what does not move.
     *
     * <p>Deliberately not tenant-scoped either: somebody who has left an organisation still did what they
     * did, and a trail that emptied when their profile moved would be the opposite of a record.
     */
    @GetMapping("/mine")
    @PreAuthorize("isAuthenticated()")
    @Transactional(readOnly = true)
    public ApiResponse<PagedResponse<AuditResponse>> mine(@ModelAttribute PagedDataRequest request,
                                                          @RequestParam(required = false) String entity,
                                                          @RequestParam(required = false) String operation) {
        Long me = com.hodi.security.principal.AuthContext.require().getUserId();
        Specification<AuditLog> spec = SearchSpecs.allOf(
                SearchSpecs.fuzzy("searchText", request.getSearch()),
                SearchSpecs.eq("actorUserId", me),
                SearchSpecs.eq("entity", entity == null || entity.isBlank() ? null : entity),
                SearchSpecs.eq("operation", operation == null || operation.isBlank() ? null : operation),
                SearchSpecs.betweenDays("createdAt", request.getFrom(), request.getTo()));
        var page = repository.findAll(spec,
                request.toPageable(Sort.by(Sort.Direction.DESC, "createdAt")));
        return ApiResponse.success(PagedResponse.from(page, AuditController::toResponse));
    }

    private static AuditResponse toResponse(AuditLog row) {
        return new AuditResponse(
                HashIdUtil.encodeId(row.getId()),
                row.getActionId(),
                row.getActorUsername(),
                row.getActorUserType(),
                row.getActorClass(),
                row.getOperation(),
                row.getEntity(),
                // Encoded like every other id on the wire. It is only meaningful together with `entity`,
                // which is why the two travel as a pair.
                HashIdUtil.encodeId(row.getEntityId()),
                row.getOutcome(),
                row.getChangedFields(),
                row.getIpAddress(),
                row.getCreatedAt());
    }
}
