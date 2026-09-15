-- ─────────────────────────────────────────────────────────────────────────────────────────────────
-- A tenant-owned project markets itself, unless somebody said otherwise.
--
-- selling_tenant_id is who markets the development, and a null one blocks two different things with
-- two different messages: submitting the project ("Say which organisation is marketing this
-- development") and putting a unit type on the marketplace ("...before listing a unit type"). Neither
-- message says they are the same missing field, so a project could be created, have its units
-- generated, and then refuse to go anywhere for a reason nobody connected.
--
-- DevelopmentService now defaults it on create, which fixes projects made from here on. This is for
-- the ones already drafted — they would otherwise stay stuck on a question whose answer, for a
-- seller's own project, was always going to be "us".
--
-- Only where the project has an owning tenant. A bank-owned development genuinely has to name a
-- seller, because there the answer is somebody else, and guessing one would be attaching a project to
-- an organisation that never agreed to market it.
--
-- Only where it is not yet live. A live project has been through approval with whatever was on it at
-- the time, and changing who markets it afterwards is a decision rather than a backfill.
-- ─────────────────────────────────────────────────────────────────────────────────────────────────

UPDATE developments d
SET selling_tenant_id   = d.tenant_id,
    selling_tenant_name = t.name,
    updated_at          = now(),
    updated_by          = 'system'
FROM tenants t
WHERE t.id = d.tenant_id
  AND d.selling_tenant_id IS NULL
  AND d.tenant_id IS NOT NULL
  AND d.status <> 5
  AND d.listing_state IN ('DRAFT', 'PENDING');
