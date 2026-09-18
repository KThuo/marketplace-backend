-- ─────────────────────────────────────────────────────────────────────────────────────────────────
-- A pending approval records what the edit actually changed.
--
-- The row carried a label and a note — "PR260915CB7H — Two bedroom at Highrise", "Edited while live" —
-- and neither answers the only question the checker has: what changed? Approving on that is approving
-- the fact that somebody edited something, which is a rubber stamp with extra steps rather than a
-- second pair of eyes.
--
-- The snapshots are stored rather than re-derived when the queue is opened. The entity holds the new
-- values — that is what "edited" means — so re-deriving would compare the new value with itself and
-- report that nothing changed. And a second edit before anybody decides must restate the difference
-- from the last approved state, which only a stored "before" can do.
-- ─────────────────────────────────────────────────────────────────────────────────────────────────
ALTER TABLE approval_workflows ADD COLUMN before_payload jsonb;
ALTER TABLE approval_workflows ADD COLUMN after_payload  jsonb;
ALTER TABLE approval_workflows ADD COLUMN field_labels   jsonb;

COMMENT ON COLUMN approval_workflows.before_payload IS
    'The entity as it stood when the edit began. Null on older rows and on requests that carry no diff.';
COMMENT ON COLUMN approval_workflows.after_payload IS
    'The entity as submitted. The difference against before_payload is what the checker is shown.';
COMMENT ON COLUMN approval_workflows.field_labels IS
    'Field key to the words the submitting screen uses, so the queue names things as the maker saw them.';
