-- ─────────────────────────────────────────────────────────────────────────────────────────────────
-- A saved search can say which side of the market it is about.
--
-- The marketplace gains a sale/letting filter in the migration before this one, and a saved search that
-- could not carry it would alert somebody looking for a place to rent about every house that went up for
-- sale. An alert that sends the wrong thing is worse than one that does not exist: the second is noticed,
-- the first is unsubscribed from.
--
-- Its own file rather than an addition to the previous one, because that migration has already run
-- somewhere — and Flyway checksums what it has applied, so editing it turns the next boot into a
-- validation failure rather than a deploy.
--
-- Null means both, which is what every alert saved before now meant and still means.
-- ─────────────────────────────────────────────────────────────────────────────────────────────────

ALTER TABLE search_alerts ADD COLUMN listing_type VARCHAR(16);

ALTER TABLE search_alerts ADD CONSTRAINT ck_search_alert_listing_type CHECK (
    listing_type IS NULL OR listing_type IN ('SALE', 'RENT'));
