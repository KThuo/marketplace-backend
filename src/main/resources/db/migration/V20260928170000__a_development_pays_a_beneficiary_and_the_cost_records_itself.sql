-- A disbursement can now be a development paying one of its beneficiaries, from the owner's own account.
--
-- The engine is the same one the bank releases sale proceeds with: the bank names the account, a second person
-- approves, the send is claimed once, the answer is chased and never re-sent. What is added is the context a
-- statement needs — which development, which phase and category, which beneficiary and what kind, the invoice
-- — and whose money it is, so the right organisation's checker decides.
ALTER TABLE disbursements
    ADD COLUMN development_id       BIGINT,
    ADD COLUMN phase_id             BIGINT,
    ADD COLUMN cost_category_id     BIGINT,
    ADD COLUMN beneficiary_id       BIGINT,
    -- The kind of payee as it was when paid: a type renamed later does not rewrite a statement.
    ADD COLUMN beneficiary_type     VARCHAR(120),
    -- Whose money left. Null on the bank's own payouts, which is what every row before this was.
    ADD COLUMN owner_tenant_id      BIGINT,
    ADD COLUMN owner_institution_id BIGINT,
    ADD COLUMN invoice_reference    VARCHAR(64),
    -- Who proposed and approved it: the owning organisation's makers and checkers, or the bank's.
    ADD COLUMN managed_by           VARCHAR(8)   NOT NULL DEFAULT 'BANK',
    -- The invoice or certificate behind it, in the vault. Copied onto the cost once the money has gone.
    ADD COLUMN document_id          BIGINT;

ALTER TABLE disbursements DROP CONSTRAINT ck_disbursement_payee;
ALTER TABLE disbursements ADD CONSTRAINT ck_disbursement_payee
    CHECK (payee_kind IN ('SELLER_ORGANISATION', 'OTHER', 'BENEFICIARY'));
ALTER TABLE disbursements DROP CONSTRAINT ck_disbursement_payee_ref;
-- A seller organisation is named by its row; anybody else only by name; a beneficiary by its row, and only
-- ever from a development.
ALTER TABLE disbursements ADD CONSTRAINT ck_disbursement_payee_ref CHECK (
    (payee_kind = 'SELLER_ORGANISATION' AND tenant_id IS NOT NULL)
    OR (payee_kind = 'OTHER' AND tenant_id IS NULL)
    OR (payee_kind = 'BENEFICIARY' AND tenant_id IS NULL AND beneficiary_id IS NOT NULL AND development_id IS NOT NULL));
ALTER TABLE disbursements ADD CONSTRAINT ck_disbursement_managed_by CHECK (managed_by IN ('OWNER', 'BANK'));

CREATE INDEX idx_disbursement_development ON disbursements (development_id) WHERE development_id IS NOT NULL;
CREATE INDEX idx_disbursement_beneficiary ON disbursements (beneficiary_id) WHERE beneficiary_id IS NOT NULL;
CREATE INDEX idx_disbursement_owner_tenant ON disbursements (owner_tenant_id) WHERE owner_tenant_id IS NOT NULL;

-- A cost line knows who was paid and how the line got here: typed in, or written by a payment that succeeded.
ALTER TABLE development_expenditures
    ADD COLUMN beneficiary_id  BIGINT,
    ADD COLUMN disbursement_id BIGINT,
    ADD COLUMN entry_kind      VARCHAR(16) NOT NULL DEFAULT 'MANUAL';
ALTER TABLE development_expenditures ADD CONSTRAINT ck_expenditure_entry_kind
    CHECK (entry_kind IN ('MANUAL', 'DISBURSEMENT'));
-- One payment writes one cost, however many times the bank's answer is read.
CREATE UNIQUE INDEX uk_expenditure_disbursement ON development_expenditures (disbursement_id)
    WHERE disbursement_id IS NOT NULL;
CREATE INDEX idx_expenditure_beneficiary ON development_expenditures (beneficiary_id) WHERE beneficiary_id IS NOT NULL;
