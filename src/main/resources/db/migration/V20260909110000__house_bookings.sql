/*
 * A house is booked and paid for the way a unit is.
 *
 * With units living in properties, a booking is on a property — and nothing about a booking needs the home
 * to be part of a development. So the last two things that assumed one go: a payment's development becomes
 * optional, and a HOUSE row may carry a sale state (held, reserved, sold) beside its listing state, so the
 * booking has somewhere to write what it knows without a second table.
 */

ALTER TABLE payments ALTER COLUMN development_id DROP NOT NULL;

-- A unit still needs its label, its state, its project and its category. A house may now have a sale state.
ALTER TABLE properties DROP CONSTRAINT ck_property_unit_shape;
ALTER TABLE properties ADD CONSTRAINT ck_property_unit_shape CHECK (
    (listing_kind = 'UNIT') = (unit_label IS NOT NULL)
    AND (listing_kind <> 'UNIT' OR (sale_state IS NOT NULL AND development_id IS NOT NULL AND unit_type_id IS NOT NULL)));

COMMENT ON COLUMN properties.sale_state IS
    'Where the sale of this home stands: AVAILABLE, HELD, RESERVED, SOLD. Always set on a UNIT; set on a '
    'HOUSE once it has been booked. Written by the booking, never by hand.';
