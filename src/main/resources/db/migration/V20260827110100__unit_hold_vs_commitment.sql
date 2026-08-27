-- ═════════════════════════════════════════════════════════════════════════════════════════════════
-- A held unit has an expiry; a committed one does not
--
-- `ck_unit_reserved` required `reserved_until` for a unit in either HELD or RESERVED, and that was right when
-- the only kind of reservation was a time-boxed hold taken over the phone.
--
-- Bookings introduce the other kind. An AGREED booking is a commitment: it does not expire, and its
-- `expires_at` is deliberately cleared so that nothing — not a person, not the sweep — reads it as still
-- counting down. The unit it holds therefore has no expiry either, and the old constraint refused to let it
-- be saved at all.
--
-- <h2>The two words already existed</h2>
--
-- AppConstant's own note on these states says HELD and RESERVED "are both holds and differ in commitment".
-- That is exactly the distinction wanted, and it was going unused: both booking states were being mapped onto
-- RESERVED. Now
--
--   * HELD     — a hold with a deadline. A phone reservation, or a booking still in RESERVED.
--   * RESERVED — committed. A booking that has been agreed. No deadline.
--
-- So the constraint narrows to HELD, which is the only state where a missing expiry would be a real gap: a
-- hold with no end is a unit nobody can ever sell.
--
-- Nothing to backfill. Every existing row in either state was written by the hold path, which always set
-- `reserved_until` — the new constraint is strictly weaker than the old one, so every row already satisfies it.
-- ═════════════════════════════════════════════════════════════════════════════════════════════════

ALTER TABLE development_units DROP CONSTRAINT ck_unit_reserved;

ALTER TABLE development_units ADD CONSTRAINT ck_unit_held
    CHECK (sale_state <> 'HELD' OR reserved_until IS NOT NULL);

COMMENT ON COLUMN development_units.reserved_until IS
    'When a hold ends. Required while HELD and null once RESERVED, because a commitment has no deadline and '
    'a column still counting down would be read as though it did.';
