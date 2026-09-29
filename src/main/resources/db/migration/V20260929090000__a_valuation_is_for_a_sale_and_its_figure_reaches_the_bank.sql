-- A valuation is for a sale, and its figure reaches the bank.
--
-- A job can now say which booking or offer it was raised against, so the sale's page shows the figure and
-- the valuation's page shows the sale. And affordability and the mortgage panel look up the latest
-- completed valuation on a property to lend against the lesser of the price and the valuer's figure —
-- which needs the lookup to be cheap.

ALTER TABLE valuation_requests
    ADD COLUMN booking_id BIGINT REFERENCES unit_bookings (id),
    ADD COLUMN offer_id   BIGINT REFERENCES purchase_requests (id);

CREATE INDEX idx_valuation_requests_property_completed
    ON valuation_requests (property_id, completed_at DESC)
    WHERE state = 'COMPLETED' AND status <> 5;
