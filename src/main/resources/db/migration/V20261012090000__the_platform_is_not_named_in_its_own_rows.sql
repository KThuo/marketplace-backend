-- ─────────────────────────────────────────────────────────────────────────────────────────────────
-- The platform's name is a placeholder until the client chooses one, and lives in company.name only.
--
-- Two places wrote it into data: the money-out report view labelled every payment "Paid through Hodi",
-- and the cost a payment records for itself said the same in its notes. Both now say "the platform".
--
-- The view is rewritten from its own definition rather than restated here, so this migration changes the
-- one literal and nothing else about it. The literal is text before and after, so the column keeps its type.
-- ─────────────────────────────────────────────────────────────────────────────────────────────────

DO $$
DECLARE
    def text;
BEGIN
    def := pg_get_viewdef('v_report_development_money_out'::regclass, true);
    IF position('Paid through Hodi' IN def) > 0 THEN
        EXECUTE 'CREATE OR REPLACE VIEW v_report_development_money_out AS '
                || replace(def, '''Paid through Hodi''', '''Paid through the platform''');
    END IF;
END $$;

UPDATE development_expenditures
   SET notes = 'Paid through the platform' || substring(notes FROM length('Paid through Hodi') + 1)
 WHERE notes LIKE 'Paid through Hodi%';
