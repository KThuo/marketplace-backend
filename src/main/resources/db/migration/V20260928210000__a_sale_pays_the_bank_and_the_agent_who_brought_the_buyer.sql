-- A sale can pay two people: the bank, for selling on the owner's behalf, and an agent, for bringing the
-- buyer. Both are lines against the booking, raised when it completes, at the rate the development says.
--
-- Until now commission_records held one thing: the platform's cut of a completed *house* sale, keyed on the
-- property. A development's units raised nothing, and nobody's name was on the buyer. This makes a
-- commission a line per payee against a booking, and gives a development its own schedule.

-- ── the schedule, per development ──────────────────────────────────────────────────────────────
--
-- Null means "the platform default": whatever commission.rate.percent (and the new agent key) say at the
-- moment a sale completes. Zero is a rate and raises nothing. Who bears the agent's fee when the bank settles
-- a sale is the same kind of term: the seller's proceeds, as is usual, or the bank's own fee.
ALTER TABLE developments
    ADD COLUMN bank_commission_percent  NUMERIC(6, 3),
    ADD COLUMN agent_commission_percent NUMERIC(6, 3),
    ADD COLUMN agent_commission_paid_by VARCHAR(8),
    ADD CONSTRAINT ck_development_commission_rates CHECK (
        (bank_commission_percent  IS NULL OR bank_commission_percent  BETWEEN 0 AND 100) AND
        (agent_commission_percent IS NULL OR agent_commission_percent BETWEEN 0 AND 100)),
    ADD CONSTRAINT ck_development_agent_paid_by CHECK (
        agent_commission_paid_by IS NULL OR agent_commission_paid_by IN ('SELLER', 'BANK'));

-- ── who brought the buyer ──────────────────────────────────────────────────────────────────────
--
-- On the booking, because that is the sale. Set while the booking is live; frozen once it completes, when a
-- commission has been raised against it.
ALTER TABLE unit_bookings
    ADD COLUMN introduced_by_agent_id BIGINT REFERENCES agent_profiles (id);
CREATE INDEX idx_booking_introducer ON unit_bookings (introduced_by_agent_id)
    WHERE introduced_by_agent_id IS NOT NULL;

-- ── a commission is a line per payee, against a booking ────────────────────────────────────────
ALTER TABLE commission_records
    ADD COLUMN booking_id       BIGINT REFERENCES unit_bookings (id),
    ADD COLUMN booking_ref      VARCHAR(16),
    ADD COLUMN development_id   BIGINT REFERENCES developments (id),
    ADD COLUMN development_name VARCHAR(255),
    -- PLATFORM | AGENT
    ADD COLUMN payee_kind       VARCHAR(16) NOT NULL DEFAULT 'PLATFORM',
    ADD COLUMN agent_profile_id BIGINT REFERENCES agent_profiles (id),
    ADD COLUMN agent_name       VARCHAR(160),
    -- SELLER | BANK: whose money this comes out of when the bank settles the sale.
    ADD COLUMN paid_by          VARCHAR(8)  NOT NULL DEFAULT 'SELLER',
    -- The transfer that paid it, when the bank paid it rather than a person marking it paid.
    ADD COLUMN disbursement_id  BIGINT REFERENCES disbursements (id),
    ADD CONSTRAINT ck_commission_payee   CHECK (payee_kind IN ('PLATFORM', 'AGENT')),
    ADD CONSTRAINT ck_commission_agent   CHECK (payee_kind <> 'AGENT' OR agent_profile_id IS NOT NULL),
    ADD CONSTRAINT ck_commission_paid_by CHECK (paid_by IN ('SELLER', 'BANK'));

-- The rows already here are the platform's, on house sales, borne by the seller — which the defaults say.
-- Each is joined to the completed booking that sold the house, where there is exactly one to join to.
UPDATE commission_records c
   SET booking_id  = b.id,
       booking_ref = b.reference
  FROM unit_bookings b
 WHERE c.booking_id IS NULL
   AND b.property_id = c.property_id
   AND b.state = 'COMPLETED'
   AND b.status <> 5
   AND (SELECT count(*) FROM unit_bookings x
         WHERE x.property_id = c.property_id AND x.state = 'COMPLETED' AND x.status <> 5) = 1;

-- One line per payee per sale. The old key — one row per (property, sold-at) — would have refused the
-- agent's line beside the bank's; a legacy row without a booking keeps its (property, sold-at) uniqueness.
DROP INDEX uk_commission_per_sale;
CREATE UNIQUE INDEX uk_commission_per_sale ON commission_records (booking_id, payee_kind)
    WHERE booking_id IS NOT NULL AND status <> 5;
CREATE UNIQUE INDEX uk_commission_legacy_sale ON commission_records (property_id, sold_at)
    WHERE booking_id IS NULL;
CREATE INDEX idx_commission_agent ON commission_records (agent_profile_id, sold_at DESC)
    WHERE agent_profile_id IS NOT NULL;
CREATE INDEX idx_commission_development ON commission_records (development_id, sold_at DESC)
    WHERE development_id IS NOT NULL;

-- The search column now knows the agent, the development and the booking.
DROP INDEX idx_commission_search_trgm;
ALTER TABLE commission_records DROP COLUMN search_text;
ALTER TABLE commission_records ADD COLUMN search_text text
    GENERATED ALWAYS AS (
        lower(coalesce(reference, '') || ' ' || coalesce(property_ref, '') || ' ' ||
              coalesce(property_title, '') || ' ' || coalesce(tenant_name, '') || ' ' ||
              coalesce(booking_ref, '') || ' ' || coalesce(development_name, '') || ' ' ||
              coalesce(agent_name, '') || ' ' || coalesce(payee_kind, '') || ' ' ||
              coalesce(invoice_ref, '') || ' ' || coalesce(state, ''))
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
CREATE INDEX idx_commission_search_trgm ON commission_records USING gin (search_text gin_trgm_ops);
