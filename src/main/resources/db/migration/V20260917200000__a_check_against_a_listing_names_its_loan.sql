-- ─────────────────────────────────────────────────────────────────────────────────────────────────
-- A check run against a listing records what that listing needed borrowed.
--
-- The row kept the household's *maximum* loan and nothing about the home they were asking about, so a
-- check reopened from the history could say "you could borrow up to sixteen million" beside a house
-- costing six — which reads as an offer of sixteen and answers a question nobody asked.
-- ─────────────────────────────────────────────────────────────────────────────────────────────────
ALTER TABLE affordability_checks ADD COLUMN loan_required NUMERIC(15, 2);

COMMENT ON COLUMN affordability_checks.loan_required IS
    'Price of the listing less the deposit, when this check was about a listing. Null otherwise.';
