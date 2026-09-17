-- ─────────────────────────────────────────────────────────────────────────────────────────────────
-- The calculator asks for take-home pay, so the column says take-home.
--
-- It asked for gross — "before tax, from your main job" — and then spent every shilling of it as
-- though tax had not been taken. On a Kenyan salary PAYE, the housing levy and SHIF are not a
-- rounding error, so every figure the household was shown was overstated by the amount already gone
-- from their pay before it reached them.
--
-- Modelling payroll from a gross figure was the alternative: bands, reliefs and levies that change
-- with each Finance Act, maintained forever, and wrong in a way nobody would check. Asking for the
-- figure that reaches the account needs no model and cannot drift.
--
-- Existing rows are renamed rather than converted. They hold what the person typed against a form
-- that said gross, and there is no honest factor to multiply that by — what is knowable is that the
-- column now means take-home, and older rows are a check somebody ran on a different question.
-- ─────────────────────────────────────────────────────────────────────────────────────────────────
ALTER TABLE affordability_checks RENAME COLUMN gross_monthly_income TO monthly_take_home;

COMMENT ON COLUMN affordability_checks.monthly_take_home IS
    'Pay after tax and deductions, as the applicant stated it. Rows written before 2026-09-17 hold a '
    'gross figure, because that is what the form asked for then.';

COMMENT ON COLUMN mortgage_products.min_monthly_income IS
    'The least a household may take home each month and still be considered — not a gross floor.';
