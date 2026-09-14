-- ─────────────────────────────────────────────────────────────────────────────────────────────────
-- The brand goes back to what it was, and the contacts stop pretending.
--
-- V20260914130000 painted the platform in the bank's green and renamed it. The green was too much —
-- it reached every button, the sidebar, the sign-in panel and three gradients — and the teal on navy
-- it replaced was the better design. So the palette and the name return to it.
--
-- Two of those rows were never really a re-brand, and they are the reason this migration is not a
-- plain revert. company.email was seeded 'hello@coopbank.local' and the outbound mail domain
-- 'coopbank.local' — placeholder addresses that nobody could receive at, seeded because the previous
-- values were placeholders too. A published contact address that does not work is worse than no
-- published address: it looks like a real detail right up until somebody writes to it. They go blank,
-- and ThemeService already omits a blank contact from the payload, so the footer simply stops
-- claiming one until a person sets a real one from Settings.
--
-- Conditional, like the migration it undoes. Each UPDATE only fires where the value is still the one
-- that was seeded — a colour or a name somebody has since chosen is theirs, and this is not entitled
-- to overwrite it just because it disagrees.
-- ─────────────────────────────────────────────────────────────────────────────────────────────────

UPDATE configurations SET config_value = 'Hodi Market Place', updated_at = now(), updated_by = 'system'
WHERE config_key = 'company.name'        AND config_value = 'Co-op Bank Property';

UPDATE configurations SET config_value = '#0B2545', updated_at = now(), updated_by = 'system'
WHERE config_key = 'theme.primary'       AND config_value = '#04351F';

UPDATE configurations SET config_value = '#0B2545', updated_at = now(), updated_by = 'system'
WHERE config_key = 'theme.ink'           AND config_value = '#04351F';

UPDATE configurations SET config_value = '#1B7F79', updated_at = now(), updated_by = 'system'
WHERE config_key = 'theme.accent'        AND config_value = '#00883C';

UPDATE configurations SET config_value = '#4FB3A9', updated_at = now(), updated_by = 'system'
WHERE config_key = 'theme.accent.light'  AND config_value = '#6BBA8E';

UPDATE configurations SET config_value = 'HODI', updated_at = now(), updated_by = 'system'
WHERE config_key = 'notify.sms.sender.id' AND config_value = 'COOPBANK';

-- ── the placeholders that should never have been seeded ───────────────────────────────────────────
UPDATE configurations SET config_value = '', updated_at = now(), updated_by = 'system'
WHERE config_key = 'company.email'
  AND config_value IN ('hello@coopbank.local', 'hello@hodi.local');

UPDATE configurations SET config_value = 'hodi.local', updated_at = now(), updated_by = 'system'
WHERE config_key = 'notify.email.domain' AND config_value = 'coopbank.local';

-- ── the documents an agent signs stop naming the platform ─────────────────────────────────────────
--
-- The name is configuration, so a document that spells it out goes stale the day it changes — and
-- these are documents somebody signs. {{platformName}} is filled in when the terms are shown and when
-- the agreement is generated, and SignatureService hashes the text AFTER substitution, so the hash on
-- a signature is a hash of the words that person actually read.
--
-- Both spellings are replaced, so a database that took the Co-op rename and one that never did end up
-- with the same template. Agreements already generated hold their own rendered copy and are untouched.
UPDATE configurations
SET config_value = replace(replace(config_value,
        'Co-op Bank Property', '{{platformName}}'),
        'Hodi Market Place', '{{platformName}}'),
    updated_at   = now(),
    updated_by   = 'system'
WHERE config_key IN ('agent.terms.text', 'agent.agreement.template')
  AND (config_value LIKE '%Co-op Bank Property%' OR config_value LIKE '%Hodi Market Place%');

-- The branding keys move to their own settings group. The seeder refreshes a key's category on every
-- boot, so this is only here to make the grouping true for anybody reading the table before then.
UPDATE configurations SET category = 'BRANDING', updated_at = now(), updated_by = 'system'
WHERE config_key IN ('company.name', 'company.email', 'company.phone', 'company.address');
