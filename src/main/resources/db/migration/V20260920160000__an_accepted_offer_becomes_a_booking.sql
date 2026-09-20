-- ═════════════════════════════════════════════════════════════════════════════════════════════════
-- An accepted offer becomes a booking
--
-- The offer used to end at "accepted": the seller was told to be in touch, and the booking — the thing
-- that reserves the home and receives the money — had to be made again by hand from another screen,
-- retyping what the offer already held. The offer now records the booking it became, once.
-- ═════════════════════════════════════════════════════════════════════════════════════════════════

ALTER TABLE purchase_requests ADD COLUMN booking_id BIGINT REFERENCES unit_bookings (id);

-- One booking per offer, and one offer per booking: converting twice is how a home is reserved twice.
CREATE UNIQUE INDEX uk_purchase_request_booking ON purchase_requests (booking_id) WHERE booking_id IS NOT NULL;

COMMENT ON COLUMN purchase_requests.booking_id IS
    'The booking this accepted offer was converted into. Null until the seller converts it.';
