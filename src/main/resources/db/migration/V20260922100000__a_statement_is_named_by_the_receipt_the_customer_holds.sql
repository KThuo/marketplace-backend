-- The bank reference of a Co-op credit is the M-Pesa receipt — the ten characters the payer has on their
-- phone — and the bank's own transaction id is kept beside it, in ft. Until now ref_no held the bank's
-- TransactionId and the receipt reached only "what they quoted", so a slip validated on the number the
-- customer actually held found nothing.

ALTER TABLE coop_statements ADD COLUMN ft VARCHAR(64);
COMMENT ON COLUMN coop_statements.ft IS
    'The bank''s own transaction id for the posting (Co-op TransactionId). The customer-facing reference is ref_no.';
CREATE INDEX idx_coop_statement_ft ON coop_statements (ft) WHERE ft IS NOT NULL;

-- Rows recorded under the bank's id keep it as ref_no — a payment already quotes it — and it is copied to
-- ft so the same search finds them either way. Recognised by the CBS shape, e.g. CB0089060_25092025_23.
UPDATE coop_statements SET ft = ref_no WHERE ft IS NULL AND ref_no ~ '^CB[0-9]+_[0-9]+_[0-9]+$';

-- ft is searchable, not shown: the search box finds a statement by it.
-- pg_trgm's operator class, wherever the extension lives (see V20260920140000 for why).
DO $$
DECLARE ext_schema text;
BEGIN
    SELECT n.nspname INTO ext_schema
      FROM pg_extension e JOIN pg_namespace n ON n.oid = e.extnamespace
     WHERE e.extname = 'pg_trgm';
    IF ext_schema IS NULL THEN
        EXECUTE 'CREATE EXTENSION pg_trgm';
        ext_schema := current_schema();
    END IF;
    EXECUTE format('SET LOCAL search_path TO %I, %I', current_schema(), ext_schema);
END $$;
DROP INDEX IF EXISTS idx_coop_statement_search;
ALTER TABLE coop_statements DROP COLUMN search_text;
ALTER TABLE coop_statements ADD COLUMN search_text TEXT GENERATED ALWAYS AS (
    lower(coalesce(ref_no, '') || ' ' || coalesce(ft, '') || ' ' || coalesce(our_reference, '') || ' ' ||
          coalesce(trace_id, '') || ' ' || coalesce(reference, '') || ' ' || coalesce(customer_name, '') || ' ' ||
          coalesce(phone_no, '') || ' ' || coalesce(account_identifier, ''))
) STORED;
CREATE INDEX idx_coop_statement_search ON coop_statements USING gin (search_text gin_trgm_ops);
