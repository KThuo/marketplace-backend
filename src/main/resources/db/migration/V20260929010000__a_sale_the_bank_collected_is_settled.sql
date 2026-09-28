-- A sale the bank collected is settled: the owner gets the proceeds, the agent their fee, the bank keeps or
-- moves its own — each a transfer through the disbursement engine, tied to the booking it settles.
--
-- Nothing here is a balance. What a settlement pays is computed from the booking's money in and its
-- commission lines when it is proposed, and the transfers that result are the record.

ALTER TABLE disbursements
    ADD COLUMN booking_id      BIGINT REFERENCES unit_bookings (id),
    -- PROCEEDS (to the owner) | AGENT_FEE | BANK_FEE (only when the bank moves its fee rather than keeping it)
    ADD COLUMN settlement_kind VARCHAR(16),
    ADD CONSTRAINT ck_disbursement_settlement CHECK (
        (booking_id IS NULL AND settlement_kind IS NULL)
        OR (booking_id IS NOT NULL AND settlement_kind IN ('PROCEEDS', 'AGENT_FEE', 'BANK_FEE')));
CREATE INDEX idx_disbursement_booking ON disbursements (booking_id) WHERE booking_id IS NOT NULL;

-- Settled when the proceeds have reached the owner: set by the transfer's own confirmation, never by hand.
ALTER TABLE unit_bookings
    ADD COLUMN settled_at TIMESTAMPTZ;
CREATE INDEX idx_booking_awaiting_settlement ON unit_bookings (development_id)
    WHERE state = 'COMPLETED' AND settled_at IS NULL AND status <> 5;
