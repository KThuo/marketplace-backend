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

CREATE INDEX idx_config_logs_search_trgm ON configuration_logs USING gin (search_text public.gin_trgm_ops);

-- The date filter this list already offers had no index behind it, so narrowing to a month still scanned.
CREATE INDEX idx_config_logs_created ON configuration_logs (created_at DESC);
