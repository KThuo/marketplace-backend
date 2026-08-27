-- ═════════════════════════════════════════════════════════════════════════════════════════════════
-- The short code a buyer quotes when they pay for a unit
--
-- Payments are collected through Pesi into a till, and the platform is one Pesi business with many tills —
-- each till assigned to whoever collects on it, the bank or a developer. An inbound notification carries the
-- till it landed in and whatever reference the payer typed, and this column is what that reference is
-- matched against.
--
-- <h2>Why the unit and not the booking</h2>
--
-- A buyer pays for a flat, not for a contract. The code goes on the letter, the sales agent quotes it over
-- the phone, and it stays the same if the reservation lapses and the unit is sold to somebody else — which
-- is also why it is `UNIQUE` across every unit ever rather than only the live ones: a payment arriving three
-- weeks after a cancellation must land on the unit it names, not on whoever inherited the code.
--
-- <h2>Four characters, and what that costs</h2>
--
-- Four characters from the ambiguity-free 32-letter alphabet already used by RrnGenerator — no O, 0, I or 1,
-- because this gets read off a letter and typed into a phone — is 32^4 = 1,048,576 codes. Uniqueness is the
-- constraint's job and the generator retries on a clash, so exhaustion is not the risk.
--
-- The risk is a *typo landing on another real unit*. Four characters carry no redundancy, so a single
-- mistyped letter produces another well-formed code, and the chance it happens to be a live one is roughly
-- (units in the system) / 1,048,576 — about one in two hundred at five thousand units, one in twenty at
-- fifty thousand. That is small and it is not nothing, and it is money.
--
-- Two mitigations, and the second is the one that matters:
--
--   * A payment is auto-matched on this code ALONE only when something corroborates it — the amount equals
--     an amount outstanding on that unit, or the paying phone number is the buyer's. A bare reference match
--     with nothing agreeing goes to the unmapped queue for a person, which is the whole point of having one.
--   * If the volumes above ever look uncomfortable, one of the four characters becomes a checksum over the
--     other three. That makes every single-character typo and every transposition *detectable* rather than
--     silently valid, at the cost of dropping to 32,768 codes. It is a generator change and a validation
--     rule, not a migration, so the decision can wait for real numbers.
-- ═════════════════════════════════════════════════════════════════════════════════════════════════

ALTER TABLE development_units ADD COLUMN pay_reference VARCHAR(8);

COMMENT ON COLUMN development_units.pay_reference IS
    'Short code a buyer quotes when paying for this unit. Four characters from the ambiguity-free '
    'alphabet, unique across every unit ever — a late payment must land on the unit it names.';

-- Unique across all units, live or not, and across every development: a till does not know which project a
-- payer had in mind.
CREATE UNIQUE INDEX uk_unit_pay_reference ON development_units (pay_reference)
    WHERE pay_reference IS NOT NULL;
