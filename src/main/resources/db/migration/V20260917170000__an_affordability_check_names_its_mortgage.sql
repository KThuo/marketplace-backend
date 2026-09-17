-- ─────────────────────────────────────────────────────────────────────────────────────────────────
-- A check is computed against a mortgage, so it has to say which one.
--
-- Every figure a household is shown — the rate, the ceiling, the term, the deposit rule — now comes
-- from the product they chose. A stored check that does not name it is a set of numbers nobody can
-- reproduce a month later, which is the opposite of what keeping the row is for.
--
-- The reference and the name are copied rather than joined, for the same reason the listing reference
-- on this table is: a product whose rate moves afterwards must not silently rewrite what somebody was
-- told on the day.
-- ─────────────────────────────────────────────────────────────────────────────────────────────────
ALTER TABLE affordability_checks ADD COLUMN product_reference VARCHAR(16);
ALTER TABLE affordability_checks ADD COLUMN product_name      VARCHAR(160);

COMMENT ON COLUMN affordability_checks.product_reference IS
    'The mortgage these figures were computed against, as at the moment of the check.';
