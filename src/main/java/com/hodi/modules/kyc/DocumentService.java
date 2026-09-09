package com.hodi.modules.kyc;

import com.hodi.common.AppConstant;
import com.hodi.common.RefGenerator;
import com.hodi.common.exception.HodiException;
import com.hodi.common.exception.ResourceNotFoundException;
import com.hodi.infra.vault.VaultStorage;
import com.hodi.modules.audit.AuditService;
import com.hodi.security.principal.AuthContext;
import com.hodi.security.principal.UserPrincipal;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDate;
import java.util.List;

/**
 * The vault's front door (plan §3.9).
 *
 * <h2>Every read is checked and every read is recorded</h2>
 *
 * <p>{@link #read} is the only way to bytes in this application. It resolves the ACL, refuses if no grant
 * applies, and writes an audit row <em>whether or not it refused</em> — a refused read is the more
 * interesting of the two, and a trail that only logs successes cannot show somebody trying.
 *
 * <p>The audit write runs in its own transaction, so a refusal that throws still leaves the record. That is
 * the same arrangement {@code AuditService} uses everywhere, and it is load-bearing here.
 *
 * <h2>Three kinds of grant</h2>
 *
 * <ol>
 *   <li>The organisation the document is about, through an ACL row naming the tenant.</li>
 *   <li>A named person, through a row naming the user — one document, without the module.</li>
 *   <li>Anybody holding a permission code, which is how Compliance sees what it is judging.</li>
 * </ol>
 *
 * <p>Platform staff are <strong>not</strong> exempt. A super administrator with no grant is refused like
 * anybody else — the vault is the one place in this codebase where "the platform sees everything" does not
 * hold, because the documents in it are the reason the rule exists.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentService {

    private static final String REFERENCE_PREFIX = "DC";

    private final VaultDocumentRepository documents;
    private final VaultDocumentAclRepository acls;
    private final VaultStorage vault;
    private final AuditService audit;

    public record Fetched(byte[] bytes, String contentType, String fileName) {}

    // ── writing ───────────────────────────────────────────────────────────────

    /**
     * Stores a document and grants the obvious parties sight of it.
     *
     * @param tenantId       the organisation it is about, or null for a document about a person
     * @param userId         the person it is about, or null
     * @param readPermission a permission code whose holders may read it — how Compliance gets in
     */
    @Transactional
    public VaultDocument store(MultipartFile file, String folder, String documentCode,
                               String documentName, Long tenantId, Long userId,
                               LocalDate issuedOn, LocalDate expiresOn, String readPermission) {
        UserPrincipal caller = AuthContext.require();
        VaultStorage.Stored stored = vault.store(file, folder);

        VaultDocument document = documents.save(VaultDocument.builder()
                .reference(nextReference())
                .tenantId(tenantId)
                .userId(userId)
                .documentCode(documentCode)
                .documentName(documentName)
                .storageKey(stored.key())
                .contentType(stored.contentType())
                .sizeBytes(stored.sizeBytes())
                .originalName(stored.fileName())
                .checksumSha256(stored.checksumSha256())
                .encryption(stored.encryption())
                .issuedOn(issuedOn)
                .expiresOn(expiresOn)
                .uploadedByUserId(caller.getUserId())
                .uploadedByName(caller.getFullName())
                .createdBy(caller.getUsername())
                .updatedBy(caller.getUsername())
                .build());

        // The grants, written with the document rather than left to whoever remembers.
        if (tenantId != null) grant(document.getId(), null, tenantId, null, caller.getUsername());
        if (userId != null) grant(document.getId(), userId, null, null, caller.getUsername());
        if (readPermission != null) grant(document.getId(), null, null, readPermission, caller.getUsername());

        audit.record(AppConstant.AUDIT_DOCUMENT_UPLOAD, "VaultDocument", document.getId(), null,
                document.getReference() + " " + documentCode + " (" + stored.encryption() + ", sha256 "
                        + stored.checksumSha256().substring(0, 12) + "…)");
        return document;
    }

    @Transactional
    public VaultDocumentAcl grant(Long documentId, Long userId, Long tenantId, String permission,
                                  String by) {
        return acls.save(VaultDocumentAcl.builder()
                .documentId(documentId)
                .userId(userId)
                .tenantId(tenantId)
                .permission(permission)
                .grantedBy(by)
                .build());
    }

    // ── reading ───────────────────────────────────────────────────────────────

    /**
     * The bytes, if this caller may have them.
     *
     * <p>Both paths write an audit row before anything is returned — the refusal one especially, since a
     * refused read is the more interesting of the two.
     */
    @Transactional
    public Fetched read(String reference) {
        VaultDocument document = documents.findByReference(reference == null ? "" : reference.trim())
                .orElseThrow(() -> new ResourceNotFoundException("Document", reference));

        UserPrincipal caller = AuthContext.require();
        if (!mayRead(document, caller)) {
            recordRead(document, caller, false);
            // Not a 404: the caller reached a document that exists and was told they may not have it. A 404
            // here would be the kinder lie, and it would also hide a real access attempt from the person
            // reading the trail.
            throw new HodiException("You do not have access to that document.", HttpStatus.FORBIDDEN);
        }

        recordRead(document, caller, true);
        byte[] bytes = vault.read(document.getStorageKey());
        return new Fetched(bytes, document.getContentType(), document.getOriginalName());
    }

    /**
     * The bytes, for a caller that has already decided this reader may have them.
     *
     * <p>The vault's ACL knows three kinds of grant — a tenant, a person, a permission — and none of them can
     * say "anyone who may see this development". A development's finance evidence is read by exactly that
     * set: the owner, the bank that financed it, the developer building it. So {@code DevelopmentFinanceService}
     * checks the development and then comes here, and this records the read exactly as {@link #read} does.
     * Nothing is skipped but the ACL, and only because the caller has applied a rule the ACL cannot express.
     *
     * @param basis what established the right, for the audit line
     */
    @Transactional
    public Fetched readTrusted(VaultDocument document, String basis) {
        UserPrincipal caller = AuthContext.require();
        audit.record(AppConstant.AUDIT_DOCUMENT_READ, "VaultDocument", document.getId(), null,
                document.getReference() + " " + document.getDocumentCode() + " read by " + caller.getUsername()
                        + " via " + basis, AppConstant.OUTCOME_SUCCESS);
        byte[] bytes = vault.read(document.getStorageKey());
        return new Fetched(bytes, document.getContentType(), document.getOriginalName());
    }

    /**
     * Whether this caller has a live grant.
     *
     * <p>Deliberately not "or the caller is platform staff". Everywhere else in this codebase the platform
     * sees everything, and this is the exception the vault exists to be.
     */
    public boolean mayRead(VaultDocument document, UserPrincipal caller) {
        for (VaultDocumentAcl acl : acls.findByDocumentId(document.getId())) {
            if (!acl.isLive()) continue;
            if (acl.getUserId() != null && acl.getUserId().equals(caller.getUserId())) return true;
            if (acl.getTenantId() != null && acl.getTenantId().equals(caller.getTenantId())) return true;
            if (acl.getPermission() != null && AuthContext.hasAuthority(acl.getPermission())) return true;
        }
        return false;
    }

    /**
     * Records the attempt.
     *
     * <p>The row survives the exception the refusal is about to throw, because {@link AuditService} already
     * writes every entry in its own transaction — that is its documented guarantee and the reason this
     * method carries no transaction annotation of its own. One here would be a self-invocation that never
     * reached the proxy, which is worse than none: it would read as a promise nothing keeps.
     *
     * <p>A trail that recorded only successful reads would not be an access log.
     */
    private void recordRead(VaultDocument document, UserPrincipal caller, boolean allowed) {
        audit.record(AppConstant.AUDIT_DOCUMENT_READ, "VaultDocument", document.getId(), null,
                document.getReference() + " " + document.getDocumentCode()
                        + (allowed ? " read by " : " REFUSED to ") + caller.getUsername(),
                allowed ? AppConstant.OUTCOME_SUCCESS : AppConstant.OUTCOME_UNAUTHORIZED);
    }

    // ── housekeeping ──────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<VaultDocument> forTenant(Long tenantId) {
        return documents.findForTenant(tenantId);
    }

    @Transactional
    public void archive(String reference) {
        VaultDocument document = documents.findByReference(reference == null ? "" : reference.trim())
                .orElseThrow(() -> new ResourceNotFoundException("Document", reference));
        document.setStatus(AppConstant.STATUS_DELETED);
        document.setStatusFlag(AppConstant.FLAG_DELETED);
        document.setUpdatedBy(AuthContext.username());
        documents.save(document);
        // The object is left in place. Archiving a row is not a deletion request, and a document Compliance
        // judged a submission on has to remain retrievable for as long as the decision does.
    }

    private String nextReference() {
        for (int attempt = 0; attempt < 5; attempt++) {
            String reference = RefGenerator.getInstance().generate(REFERENCE_PREFIX);
            if (!documents.existsByReference(reference)) return reference;
        }
        throw new HodiException("Could not allocate a document reference. Try again.",
                HttpStatus.INTERNAL_SERVER_ERROR);
    }
}
