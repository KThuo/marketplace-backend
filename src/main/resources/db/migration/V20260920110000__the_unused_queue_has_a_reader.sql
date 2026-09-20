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
