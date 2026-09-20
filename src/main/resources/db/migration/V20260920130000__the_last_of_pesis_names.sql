-- ═════════════════════════════════════════════════════════════════════════════════════════════════
-- The last of pesi's names
--
-- The gateway left the codebase in September; its tables and Java types were renamed then. What stayed
-- were the constraint and index names on the two tables that came from it — `ck_pesi_state` on
-- coop_statements, `uk_pesi_method_account` on payment_accounts — which is how a failed insert still
-- reports an intermediary that does not exist. Renamed here, by whatever names the live database
-- actually holds, because migrations that were rewritten in place left different deployments with
-- slightly different sets.
--
-- The setting for holding a phone prompt's request open goes with them: the ask returns as soon as the
-- bank accepts it now, and a setting nothing reads is a plausible wrong answer for the next person.
-- ═════════════════════════════════════════════════════════════════════════════════════════════════

DO $$
DECLARE
    r        record;
    renamed  text;
BEGIN
    FOR r IN
        SELECT c.conname AS name, t.relname AS tbl
          FROM pg_constraint c
          JOIN pg_class t ON t.oid = c.conrelid
         WHERE c.conname LIKE '%pesi%'
    LOOP
        renamed := replace(replace(r.name, 'pesi_statement', 'coop_statement'), 'pesi_method', 'payment_account');
        IF renamed LIKE '%pesi%' THEN
            renamed := replace(renamed, 'pesi', CASE r.tbl
                WHEN 'coop_statements'  THEN 'coop_statement'
                WHEN 'payment_accounts' THEN 'payment_account'
                ELSE 'coop' END);
        END IF;
        IF renamed <> r.name AND NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = renamed) THEN
            EXECUTE format('ALTER TABLE %I RENAME CONSTRAINT %I TO %I', r.tbl, r.name, renamed);
        END IF;
    END LOOP;

    FOR r IN
        SELECT indexname AS name, tablename AS tbl
          FROM pg_indexes
         WHERE indexname LIKE '%pesi%'
    LOOP
        renamed := replace(replace(r.name, 'pesi_statement', 'coop_statement'), 'pesi_method', 'payment_account');
        IF renamed LIKE '%pesi%' THEN
            renamed := replace(renamed, 'pesi', CASE r.tbl
                WHEN 'coop_statements'  THEN 'coop_statement'
                WHEN 'payment_accounts' THEN 'payment_account'
                ELSE 'coop' END);
        END IF;
        IF renamed <> r.name AND NOT EXISTS (SELECT 1 FROM pg_class WHERE relname = renamed) THEN
            EXECUTE format('ALTER INDEX %I RENAME TO %I', r.name, renamed);
        END IF;
    END LOOP;
END $$;

-- Read by nothing since the ask became an acknowledgement.
DELETE FROM configurations WHERE config_key = 'coop.stk.wait.seconds';
