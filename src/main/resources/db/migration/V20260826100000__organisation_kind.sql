-- ═════════════════════════════════════════════════════════════════════════════════════════════════
-- Not every organisation is a seller.
--
-- M9 gave each approved agent their own `tenants` row, which is what let them reuse `TenantScope` with no
-- new visibility mode. It also put them in the platform's "Seller organisations" list, beside developers
-- and SACCOs, offering actions that do not apply to a one-person agency — add staff, propose a partnership,
-- run a KYC pack. The page meant something narrower than the table held.
--
-- M10's vendors will land in the same table for the same good reason, so the distinction is drawn once,
-- here, rather than twice by accident.
--
-- Filtered rather than hidden: the platform can still list every kind by asking for one, and the kind is on
-- the row so a list can say what it is showing. A default that quietly excludes rows a table contains is
-- how somebody later concludes an organisation was deleted.
-- ═════════════════════════════════════════════════════════════════════════════════════════════════

ALTER TABLE tenants ADD COLUMN organisation_kind VARCHAR(16) NOT NULL DEFAULT 'SELLER';

ALTER TABLE tenants ADD CONSTRAINT ck_tenant_organisation_kind
    CHECK (organisation_kind IN ('SELLER', 'AGENT', 'VENDOR'));

-- The agent organisations M9 already created. Identified by the agent that points at them rather than by
-- seller_type, which is 'AGENCY' for an agency that onboarded as a seller too.
UPDATE tenants
SET organisation_kind = 'AGENT'
WHERE id IN (SELECT tenant_id FROM agent_profiles WHERE tenant_id IS NOT NULL);

CREATE INDEX idx_tenant_kind ON tenants (organisation_kind);
