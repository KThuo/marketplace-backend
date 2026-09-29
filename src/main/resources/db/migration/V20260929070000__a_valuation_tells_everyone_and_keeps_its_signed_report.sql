-- A valuation tells everyone concerned, keeps its signed report in the vault, and books its inspection.
--
-- Three small additions to the same flow. The valuer's inspection appointment is a time on the job, and a
-- projected diary entry follows it. The signed PDF is a vault document referenced from the report, so the
-- bank reads the figure it lends against behind an ACL rather than off the media path. And the two things
-- a daily sweep says — "this job is overdue", "this valuer's cover lapses soon" — are said once, which needs
-- a column each to remember that they were.

ALTER TABLE valuation_requests
    ADD COLUMN inspection_at      TIMESTAMPTZ,
    -- The day the overdue notice went out; null until it has.
    ADD COLUMN overdue_noticed_on DATE;

ALTER TABLE valuation_reports
    ADD COLUMN document_id BIGINT REFERENCES vault_documents (id);

ALTER TABLE valuer_profiles
    -- The expiry date the last lapse warning was about. A renewal moves the date, and the warning fires again
    -- for the new one when its turn comes.
    ADD COLUMN lapse_warned_for DATE;
