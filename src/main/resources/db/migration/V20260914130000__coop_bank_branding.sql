-- ─────────────────────────────────────────────────────────────────────────────────────────────────
-- The brand, for databases that already exist.
--
-- SeederService.seedConfigurations never overwrites a config VALUE once the row is there — only the
-- label, description and flags around it. That is the right rule: somebody has tuned these from the
-- Settings screen, and a deploy that reset a hardened session timeout because the enum's default
-- changed would be a bad surprise. The consequence is that editing a default in ConfigKey only ever
-- reaches a fresh database, so the brand has to arrive here for every other one.
--
-- Each UPDATE is conditional on the value still being the Hodi default. A row somebody has already
-- changed is theirs — if they have set a logo, a colour or a platform name of their own, this leaves
-- it alone rather than replacing it with a guess. That is the same "only where the row is untouched"
-- rule V20260826110000__vendors.sql used on allowed_user_types, and for the same reason.
--
-- The greens are the bank's family rather than values from a brand guide, which nobody has given us.
-- They are all editable from Settings, so correcting them is a screen rather than a release.
--
-- Not here: the logo and favicon URLs. They default to empty, which renders the inline SVG lockup,
-- and pointing them at files that do not exist yet would replace a working mark with a broken image.
-- Those are set from Settings once the assets are uploaded.
-- ─────────────────────────────────────────────────────────────────────────────────────────────────

UPDATE configurations SET config_value = 'Co-op Bank Property', updated_at = now(), updated_by = 'system'
WHERE config_key = 'company.name'            AND config_value = 'Hodi Market Place';

UPDATE configurations SET config_value = 'hello@coopbank.local', updated_at = now(), updated_by = 'system'
WHERE config_key = 'company.email'           AND config_value = 'hello@hodi.local';

UPDATE configurations SET config_value = 'COOPBANK', updated_at = now(), updated_by = 'system'
WHERE config_key = 'notify.sms.sender.id'    AND config_value = 'HODI';

UPDATE configurations SET config_value = 'coopbank.local', updated_at = now(), updated_by = 'system'
WHERE config_key = 'notify.email.domain'     AND config_value = 'hodi.local';

UPDATE configurations SET config_value = '#04351F', updated_at = now(), updated_by = 'system'
WHERE config_key = 'theme.primary'           AND config_value = '#0B2545';

UPDATE configurations SET config_value = '#04351F', updated_at = now(), updated_by = 'system'
WHERE config_key = 'theme.ink'               AND config_value = '#0B2545';

UPDATE configurations SET config_value = '#00883C', updated_at = now(), updated_by = 'system'
WHERE config_key = 'theme.accent'            AND config_value = '#1B7F79';

UPDATE configurations SET config_value = '#6BBA8E', updated_at = now(), updated_by = 'system'
WHERE config_key = 'theme.accent.light'      AND config_value = '#4FB3A9';

-- The two documents an agent signs. Replaced by substring rather than wholesale, so a platform that
-- has edited its own terms keeps every other word of them and only the platform's name moves.
UPDATE configurations
SET config_value = replace(config_value, 'Hodi Market Place', 'Co-op Bank Property'),
    updated_at   = now(),
    updated_by   = 'system'
WHERE config_key IN ('agent.terms.text', 'agent.agreement.template')
  AND config_value LIKE '%Hodi Market Place%';
