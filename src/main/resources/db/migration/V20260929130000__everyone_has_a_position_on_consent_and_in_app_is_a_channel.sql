-- Everyone has a position on consent, and in-app is a channel.
--
-- Only buyer registration wrote consent rows. A seller's owner onboarded by the platform, a platform
-- administrator, a valuer, an agent — none had a row, so every notice to them was silently dropped: the
-- sender asks for granted channels, finds none, and the correct handling of a refusal is silence. This
-- backfills the missing rows the way the first backfill did, and every onboarding path writes them from now.
--
-- In-app joins email and SMS as a channel: a page the person opens rather than a message pushed at them,
-- so it is granted by default for every purpose, and like the other two cannot be refused for the
-- transactional one.

ALTER TABLE consent_preferences DROP CONSTRAINT ck_consent_channel;
ALTER TABLE consent_preferences
    ADD CONSTRAINT ck_consent_channel CHECK (channel IN ('EMAIL', 'SMS', 'IN_APP'));

INSERT INTO consent_preferences (user_id, channel, purpose, granted, source, created_by, updated_by)
SELECT u.id, c.channel, p.purpose,
       p.purpose = 'TRANSACTIONAL' OR c.channel = 'IN_APP',
       'BACKFILL', 'flyway', 'flyway'
FROM users u
         CROSS JOIN (VALUES ('EMAIL'), ('SMS'), ('IN_APP')) AS c (channel)
         CROSS JOIN (VALUES ('TRANSACTIONAL'), ('PROPERTY_ALERTS'), ('PROMOTIONAL')) AS p (purpose)
WHERE u.status <> 5
ON CONFLICT (user_id, channel, purpose) DO NOTHING;
