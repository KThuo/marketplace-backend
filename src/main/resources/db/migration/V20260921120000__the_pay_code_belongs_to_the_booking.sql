-- The four-character pay code moves from the unit to the booking.
--
-- Money is received against a booking: every payment, schedule, prompt and balance hangs off unit_bookings,
-- and a unit can be booked, cancelled and booked again by somebody else. A code that names the unit lands a
-- late payment from the first buyer on the second buyer's booking. A code that names the booking lands it
-- on the cancelled booking, where the matcher can say so and a person can decide.
--
-- Unique across every booking ever, not only live ones, for the same reason: a recycled code would be the
-- misdirected payment all over again. The space is 32^4 = 1,048,576 codes.

ALTER TABLE unit_bookings ADD COLUMN pay_reference VARCHAR(8);

-- A live booking inherits its unit's code, so a buyer who has already been told a code keeps it. Unit codes
-- are unique and a unit has at most one live booking, so nothing inherited can collide.
UPDATE unit_bookings b
   SET pay_reference = p.pay_reference
  FROM properties p
 WHERE p.id = b.property_id
   AND b.state IN ('RESERVED', 'AGREED')
   AND b.status <> 5
   AND p.pay_reference IS NOT NULL;

-- Every other booking gets a fresh code: drawn from the same alphabet (no 0/O/1/I), checked against the codes
-- already on bookings and against every unit's code, so a code a buyer may have seen on a letter is not
-- reissued to a stranger's closed booking.
DO $$
DECLARE
    alphabet CONSTANT TEXT := 'ABCDEFGHJKLMNPQRSTUVWXYZ23456789';
    row_id   BIGINT;
    code     TEXT;
BEGIN
    FOR row_id IN SELECT id FROM unit_bookings WHERE pay_reference IS NULL ORDER BY id LOOP
        LOOP
            SELECT string_agg(substr(alphabet, 1 + floor(random() * 32)::int, 1), '') INTO code
              FROM generate_series(1, 4);
            EXIT WHEN NOT EXISTS (SELECT 1 FROM unit_bookings WHERE pay_reference = code)
                  AND NOT EXISTS (SELECT 1 FROM properties WHERE pay_reference = code);
        END LOOP;
        UPDATE unit_bookings SET pay_reference = code WHERE id = row_id;
    END LOOP;
END $$;

ALTER TABLE unit_bookings ALTER COLUMN pay_reference SET NOT NULL;
CREATE UNIQUE INDEX uk_booking_pay_reference ON unit_bookings (pay_reference);
COMMENT ON COLUMN unit_bookings.pay_reference IS
    'The four-character code a buyer quotes when paying for this booking: at the bank, on a transfer, on an STK prompt. Unique across every booking ever.';

-- The receive form's picker searches this text; a payment quoting only the code has to be findable by it.
ALTER TABLE unit_bookings DROP COLUMN search_text;
ALTER TABLE unit_bookings ADD COLUMN search_text TEXT GENERATED ALWAYS AS (
    lower(coalesce(reference, '') || ' ' || coalesce(pay_reference, '') || ' ' || coalesce(buyer_name, '') || ' ' ||
          coalesce(buyer_phone, '') || ' ' || coalesce(buyer_email, ''))
) STORED;
-- pg_trgm's operator class, wherever the extension lives (see V20260920140000 for why).
DO $$
DECLARE ext_schema text;
BEGIN
    SELECT n.nspname INTO ext_schema
      FROM pg_extension e JOIN pg_namespace n ON n.oid = e.extnamespace
     WHERE e.extname = 'pg_trgm';
    IF ext_schema IS NULL THEN
        EXECUTE 'CREATE EXTENSION pg_trgm';
        ext_schema := current_schema();
    END IF;
    EXECUTE format('SET LOCAL search_path TO %I, %I', current_schema(), ext_schema);
END $$;
CREATE INDEX idx_booking_search ON unit_bookings USING gin (search_text gin_trgm_ops);

-- One source of truth. The unit inventory shows the live booking's code instead.
DROP INDEX IF EXISTS uk_property_pay_reference;
ALTER TABLE properties DROP COLUMN pay_reference;
