-- pg_trgm's operator class, wherever the extension lives.
-- An extension exists once per database, in one schema, and on a shared server that schema is whichever
-- application installed it first — public on one machine, another application's schema on the next. Rather
-- than guess, look it up and put it on this transaction's search path; Flyway runs the whole script in one
-- transaction, so every gin_trgm_ops below resolves. Installs it into our own schema if nobody has yet.
--
-- This file shipped without this block and failed on a server where pg_trgm sits outside the default
-- search_path: "operator class gin_trgm_ops does not exist for access method gin" — the same failure the
-- seller-applications migration had, fixed the same way.
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

-- ═════════════════════════════════════════════════════════════════════════════════════════════════
-- The unused queue gets a reader
--
-- Since August every Co-op notification has been stored, matched or not, with a sentence saying why it
-- could not be placed. Nothing read those rows. This is the schema side of the screen that does: a
-- search column in the shape every other list here searches on, and an index for the queue's own order.
--
-- What a person searches by is what they are holding: the bank's reference off a slip, the words the
-- payer typed, the payer's name or phone. All of it in one lowered column, matched by trigram, so a
-- partial reference read over the phone still finds the row.
-- ═════════════════════════════════════════════════════════════════════════════════════════════════

ALTER TABLE coop_statements ADD COLUMN search_text TEXT GENERATED ALWAYS AS (
    lower(coalesce(ref_no, '')             || ' ' ||
          coalesce(our_reference, '')      || ' ' ||
          coalesce(trace_id, '')           || ' ' ||
          coalesce(reference, '')          || ' ' ||
          coalesce(customer_name, '')      || ' ' ||
          coalesce(phone_no, '')           || ' ' ||
          coalesce(account_identifier, ''))
) STORED;

CREATE INDEX idx_coop_statement_search ON coop_statements USING gin (search_text gin_trgm_ops);

-- The ledger and the worklist are one list with a state filter, ordered by when the money arrived.
CREATE INDEX idx_coop_statement_state_paid ON coop_statements (state, paid_at DESC) WHERE status <> 5;
