-- ═════════════════════════════════════════════════════════════════════════════════════════════════
-- Who the rating is about, as an organisation.
--
-- M7 gave sellers, agents and vendors `RATINGS_VIEW` and `RATINGS_REPLY` — and no way to answer the
-- question those permissions exist for: *which of these are about me?* Every subject type resolves to an
-- organisation eventually (a listing's seller, an agent's own tenant, a vendor's own tenant), but resolving
-- it at read time means five repositories and a switch on every row.
--
-- So the answer is written down when the rating is. It is a denormalised owner reference, the same
-- decision as `tenant_name` on a listing, and it makes "reviews about us" one indexed predicate.
-- ═════════════════════════════════════════════════════════════════════════════════════════════════

ALTER TABLE ratings ADD COLUMN subject_tenant_id BIGINT;

CREATE INDEX idx_rating_subject_tenant ON ratings (subject_tenant_id, created_at DESC)
    WHERE subject_tenant_id IS NOT NULL;

-- Backfill: every subject type, resolved once.
UPDATE ratings r SET subject_tenant_id = p.tenant_id
FROM properties p WHERE r.subject_type = 'PROPERTY' AND p.id = r.subject_id;

UPDATE ratings r SET subject_tenant_id = r.subject_id
WHERE r.subject_type = 'SELLER';

UPDATE ratings r SET subject_tenant_id = a.tenant_id
FROM agent_profiles a WHERE r.subject_type = 'AGENT' AND a.id = r.subject_id;

UPDATE ratings r SET subject_tenant_id = v.tenant_id
FROM vendor_profiles v WHERE r.subject_type = 'VENDOR' AND v.id = r.subject_id;

UPDATE ratings r SET subject_tenant_id = i.tenant_id
FROM catalogue_items i WHERE r.subject_type = 'CATALOGUE_ITEM' AND i.id = r.subject_id;
