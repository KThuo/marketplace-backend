-- pg_trgm's operator class, wherever the extension lives.
-- An extension exists once per database, in one schema, and on a shared server that schema is whichever
-- application installed it first — public on one machine, another application's schema on the next. Rather
-- than guess, look it up and put it on this transaction's search path; Flyway runs the whole script in one
-- transaction, so every gin_trgm_ops below resolves. Installs it into our own schema if nobody has yet.
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
-- The settings change log becomes searchable, the same way every other list is.
--
-- It was the one paged list in the application with no search: finding "who changed the Pesi key in March"
-- meant paging through everything. The house rule is that a list endpoint searches a generated column backed
-- by a pg_trgm GIN index and never scans in the JVM, so this is the missing half of that rule rather than a
-- new idea.
--
-- The indexed text is what somebody actually searches by: the key, who changed it, why, and the organisation
-- it belonged to. Deliberately NOT the values — a previous or new value can be a masked secret, and putting
-- those in a searchable column would make "find me the row whose value started with sk_live" a query.
-- ═════════════════════════════════════════════════════════════════════════════════════════════════

ALTER TABLE configuration_logs ADD COLUMN search_text text
    GENERATED ALWAYS AS (
        lower(coalesce(config_key, '') || ' ' || coalesce(actor_username, '') || ' ' ||
              coalesce(reason, '') || ' ' || coalesce(tenant_name, '') || ' ' || coalesce(scope, ''))
    ) STORED;

CREATE INDEX idx_config_logs_search_trgm ON configuration_logs USING gin (search_text gin_trgm_ops);

-- The date filter this list already offers had no index behind it, so narrowing to a month still scanned.
CREATE INDEX idx_config_logs_created ON configuration_logs (created_at DESC);
