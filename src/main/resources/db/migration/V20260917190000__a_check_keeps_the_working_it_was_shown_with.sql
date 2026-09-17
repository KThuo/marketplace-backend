-- ─────────────────────────────────────────────────────────────────────────────────────────────────
-- A saved affordability check keeps its own explanation.
--
-- Reopening one from the history showed the figures and nothing else, because the derivation was built
-- at the moment of the calculation and never stored. Re-deriving it on read is the tempting fix and the
-- wrong one: it would answer with today's product — a rate that has moved, a ceiling the bank has since
-- tightened — and contradict the figures stored on the very same row.
--
-- A check is a record of what a household was told on a day. The explanation is part of what they were
-- told, so it is kept beside the numbers it explains.
-- ─────────────────────────────────────────────────────────────────────────────────────────────────
ALTER TABLE affordability_checks ADD COLUMN provider_steps jsonb;

COMMENT ON COLUMN affordability_checks.provider_steps IS
    'The working as it was shown when this check was run. Never re-derived.';
