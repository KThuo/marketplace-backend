-- A supplier several developers buy from is registered once, by the bank, and shared with every organisation.
--
-- Such a beneficiary has no owner: tenant_id and institution_id are both null. Everybody may read it and pay
-- it; only the bank may change it, and only the bank's checker approves it. An owner's own beneficiary still
-- has exactly one owner, and never two.
ALTER TABLE beneficiaries DROP CONSTRAINT ck_beneficiary_owner;
ALTER TABLE beneficiaries ADD CONSTRAINT ck_beneficiary_owner CHECK (NOT (tenant_id IS NOT NULL AND institution_id IS NOT NULL));

COMMENT ON COLUMN beneficiaries.tenant_id IS
    'The organisation that pays this beneficiary. Null together with institution_id means the bank registered it for everybody.';
