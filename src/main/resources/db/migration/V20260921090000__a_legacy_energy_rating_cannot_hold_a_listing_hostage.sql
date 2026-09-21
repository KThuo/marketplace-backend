-- ═════════════════════════════════════════════════════════════════════════════════════════════════
-- A legacy energy rating cannot hold a listing hostage
--
-- `ck_property_energy_rating` was added NOT VALID so that legacy values — "100", "EDGE", whatever had
-- been typed into eight free characters — could stand and be shown as they were, rather than being
-- invented into a band the property may not hold. The reasoning said the constraint "governs what is
-- written from now on without claiming the rows behind it were ever checked".
--
-- <h2>That is not what NOT VALID does</h2>
--
-- NOT VALID skips the one-time scan of existing rows. The check still runs on every row version
-- written afterwards, and an UPDATE writes a whole new row version — including the columns it did not
-- touch. So a property holding an unreadable rating could not be updated at all: booking it, holding
-- it, publishing it, marking it sold, editing anything about it all failed with a message about a field
-- nobody had touched. The sibling migration for payments reasoned this through and exempted the voided
-- state for exactly this reason; this one did not.
--
-- <h2>The trade</h2>
--
-- The unreadable value goes. A rating that reads "100" tells a buyer nothing — the scale is A to G, and
-- no letter can be honestly derived from it — while a property that cannot be sold costs the seller the
-- sale. What is cleared is named in a notice first, so it is a recorded decision rather than a silent
-- loss, and the seller can retype the band on the listing.
--
-- The constraint is then VALIDATEd: every row is proven to satisfy it, and no later update can trip
-- over data left behind. The scan takes an ACCESS EXCLUSIVE lock only briefly (SHARE UPDATE EXCLUSIVE
-- for VALIDATE), and this table is small.
-- ═════════════════════════════════════════════════════════════════════════════════════════════════

-- Anything that plainly means a band becomes one: " b " is a B. Repeated from the original migration
-- because this file has to stand on its own against whatever the database actually holds.
UPDATE properties SET energy_rating = upper(trim(energy_rating))
    WHERE energy_rating IS NOT NULL
      AND upper(trim(energy_rating)) ~ '^[A-G]$'
      AND energy_rating <> upper(trim(energy_rating));

-- Say what is about to be cleared, by listing, so it is recoverable from the deploy log if anybody asks.
DO $$
DECLARE
    unreadable INTEGER;
    sample     TEXT;
BEGIN
    SELECT count(*), string_agg(reference || ' = "' || energy_rating || '"', ', ' ORDER BY id)
      INTO unreadable, sample
      FROM properties
     WHERE energy_rating IS NOT NULL
       AND energy_rating !~ '^[A-G]$';
    IF unreadable > 0 THEN
        RAISE NOTICE 'ck_property_energy_rating: clearing % unreadable energy rating(s), which blocked '
                     'every update to their listings: %', unreadable, sample;
    END IF;
END $$;

UPDATE properties SET energy_rating = NULL
    WHERE energy_rating IS NOT NULL
      AND energy_rating !~ '^[A-G]$';

-- Proven, not merely promised: no row survives that a later update would be refused for.
ALTER TABLE properties VALIDATE CONSTRAINT ck_property_energy_rating;

COMMENT ON CONSTRAINT ck_property_energy_rating ON properties IS
    'An energy rating is a band from A to G. Validated: every row satisfies it, so no update to a '
    'listing can be refused for a value left behind by an older form.';
