-- ═════════════════════════════════════════════════════════════════════════════════════════════════
-- A payment that is not cash or a cheque has a statement behind it
--
-- Cash and a cheque are somebody asserting money arrived. Everything else — a phone prompt, a bank
-- transfer, a paybill — arrives as a notification from the bank, and the notification is the evidence.
-- Until now nothing made a payment carry that evidence: `statement_id` was nullable and nothing checked
-- it, so an operator could key a "Co-op phone prompt" for any amount with no prompt, no notification and
-- no bank behind it, and it moved a buyer's balance.
--
-- The service now refuses to key anything but cash and cheque by hand, and writes a statement for a
-- prompt the status enquiry confirms. This constraint is what makes that a property of the data rather
-- than a habit of the code.
--
-- <h2>Why voided and archived rows are exempt</h2>
--
-- Rows recorded before this rule may carry no statement. NOT VALID leaves them standing, but a NOT VALID
-- constraint is still checked on UPDATE — and a void is an update. Without the exemption a legacy payment
-- keyed as a bank transfer could never be voided, which is the one thing that must always be possible.
-- A new row is inserted as received (status 1) and so must carry its statement; voiding it later keeps the
-- statement on the payment, so nothing is lost by exempting the voided state.
-- ═════════════════════════════════════════════════════════════════════════════════════════════════

ALTER TABLE payments
    ADD CONSTRAINT ck_payment_statement CHECK (
        status IN (4, 5)
        OR method IN ('CASH', 'CHEQUE')
        OR statement_id IS NOT NULL
    ) NOT VALID;

COMMENT ON CONSTRAINT ck_payment_statement ON payments IS
    'A received payment that is not cash or a cheque names the bank statement it was credited from. '
    'NOT VALID: rows from before the rule stand, and are listed at migration time for a person to decide.';

-- Say which existing rows the rule would have refused, so somebody can decide about each one.
DO $$
DECLARE
    offending INTEGER;
    sample    TEXT;
BEGIN
    SELECT count(*), string_agg(reference, ', ' ORDER BY id)
      INTO offending, sample
      FROM payments
     WHERE status = 1
       AND method NOT IN ('CASH', 'CHEQUE')
       AND statement_id IS NULL;
    IF offending > 0 THEN
        RAISE NOTICE 'ck_payment_statement: % received payment(s) predate the rule and carry no statement: %',
            offending, sample;
    END IF;
END $$;

-- A void releases every statement credited as that payment, and this is the lookup it runs.
CREATE INDEX IF NOT EXISTS idx_coop_statement_payment
    ON coop_statements (mapped_payment_id) WHERE mapped_payment_id IS NOT NULL;
