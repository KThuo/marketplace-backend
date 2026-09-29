-- The first booking terms said "within any time of the booking closing" when a window was unlimited. The
-- figures now carry their own preposition ("at any time", "within 90 days"), and the wording is rewritten
-- to read either way. Version 1 is corrected in place: nothing has been agreed under it yet.

UPDATE booking_terms_templates
SET body = replace(replace(body,
        'A lapsed booking can be revived within {{reviveWindow}} of lapsing, as long as nobody else has booked the home.',
        'A lapsed booking can be revived {{reviveWindow}} after it lapses, as long as nobody else has booked the home.'),
        'You may ask for a refund of what you have paid within {{refundWindow}} of the booking closing.',
        'You may ask for a refund of what you have paid {{refundWindow}} after the booking closes.')
WHERE version = 1;
