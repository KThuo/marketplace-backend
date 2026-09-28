-- Two decisions about a development's money, each the bank's to make and each per development, so that a
-- change of model later is a reconfiguration rather than a rebuild.
--
-- collection_mode: who collects a buyer's money.
--   BANK  — only accounts the bank configured collect for it. The bank sells on the owner's behalf and holds
--           the money until every party is satisfied, so it can refund in a dispute.
--   OWNER — the owner may configure the accounts that collect for it.
-- spending_managed_by: who records and pays the development's costs.
--   OWNER — the owning organisation's makers and checkers. For a development a bank owns, the owner is that
--           bank, so this is the right default everywhere.
--   BANK  — the platform's (the bank's) staff, for a project it finances or runs. The owner still reads it.
ALTER TABLE developments ADD COLUMN collection_mode VARCHAR(8) NOT NULL DEFAULT 'BANK';
ALTER TABLE developments ADD COLUMN spending_managed_by VARCHAR(8) NOT NULL DEFAULT 'OWNER';
ALTER TABLE developments ADD CONSTRAINT ck_development_collection_mode
    CHECK (collection_mode IN ('BANK', 'OWNER'));
ALTER TABLE developments ADD CONSTRAINT ck_development_spending_managed_by
    CHECK (spending_managed_by IN ('OWNER', 'BANK'));

-- Existing developments keep behaving as they do today. The platform-wide "Who collects payments" setting has
-- been answering this question for every development at once; each one now carries that answer itself.
UPDATE developments SET collection_mode = 'OWNER'
 WHERE (SELECT upper(config_value) FROM configurations
         WHERE config_key = 'payments.collection.scope' AND status <> 5
         ORDER BY id DESC LIMIT 1) = 'ORGANISATION';

-- Whether the bank configured this account, as opposed to the organisation it belongs to.
--
-- Under collection_mode BANK only a bank-configured account may collect for a development, and whose account
-- it is does not say who set it up: the bank configures accounts *for* owners. Existing rows are left false.
-- That is the safe reading — an owner-configured account never collects under BANK — and it changes nothing
-- today: while the platform setting said PLATFORM, organisation accounts were not offered at all, and where it
-- said ORGANISATION the developments above have just become OWNER.
ALTER TABLE payment_accounts ADD COLUMN configured_by_bank BOOLEAN NOT NULL DEFAULT false;
UPDATE payment_accounts SET configured_by_bank = true WHERE tenant_id IS NULL AND institution_id IS NULL;
