/*
 * The property row is the unit.
 *
 * Until now a development's homes lived in development_units, and only the *kind* of home — the typology —
 * had a row in properties. So a unit had money (bookings, payments) but no listing, and a house had a listing
 * but no money; a lead could say "a 2-bed at Highrise" but never "B-14"; and the sale of a unit was written in
 * one table while the sale of a house was written in another.
 *
 * This folds development_units into properties. A unit is a property of kind UNIT, grouped under its
 * development (the project) and its typology (the category). Sold is one row updated. Completed is one row
 * updated. Every table that pointed at a unit now points at a property, and the old table is dropped.
 *
 * tenant_id and price become nullable — for a UNIT only. A bank's project may have no selling organisation
 * yet, and a unit's price is its typology's until it has one of its own. institution_id is added so the owner
 * is still on the row.
 */

-- ── 1. What kind of row this is ─────────────────────────────────────────────────────────────────────

ALTER TABLE properties ADD COLUMN listing_kind VARCHAR(12) NOT NULL DEFAULT 'HOUSE';
ALTER TABLE properties ADD CONSTRAINT ck_property_kind CHECK (listing_kind IN ('HOUSE', 'TYPOLOGY', 'UNIT'));
UPDATE properties SET listing_kind = 'TYPOLOGY' WHERE unit_type_id IS NOT NULL;

COMMENT ON COLUMN properties.listing_kind IS
    'HOUSE: an ordinary listing. TYPOLOGY: the card for one kind of home in a development, one row per kind. '
    'UNIT: one home in a development — the row that is booked, paid for and sold.';

-- ── 2. What a unit carries ──────────────────────────────────────────────────────────────────────────

ALTER TABLE properties
    ADD COLUMN institution_id      BIGINT REFERENCES lending_institutions (id),
    ADD COLUMN phase_id            BIGINT REFERENCES development_phases (id),
    ADD COLUMN unit_label          VARCHAR(32),
    ADD COLUMN block               VARCHAR(32),
    ADD COLUMN floor_no            SMALLINT,
    ADD COLUMN door_no             VARCHAR(16),
    ADD COLUMN pay_reference       VARCHAR(8),
    ADD COLUMN sale_state          VARCHAR(16),
    ADD COLUMN completed_on        DATE,
    ADD COLUMN handed_over_on      DATE,
    ADD COLUMN buyer_user_id       BIGINT REFERENCES users (id),
    ADD COLUMN buyer_name          VARCHAR(160),
    ADD COLUMN buyer_phone         VARCHAR(32),
    ADD COLUMN buyer_email         VARCHAR(128),
    ADD COLUMN purchase_request_id BIGINT REFERENCES purchase_requests (id),
    ADD COLUMN reserved_at         TIMESTAMPTZ,
    ADD COLUMN reserved_until      TIMESTAMPTZ,
    ADD COLUMN sold_price          NUMERIC(15, 2),
    ADD COLUMN balconies           SMALLINT,
    ADD COLUMN balcony_area_sqm    NUMERIC(10, 2),
    ADD COLUMN aspect              VARCHAR(64),
    ADD COLUMN notes               TEXT,
    -- Carried only for the length of this migration, to rewrite the foreign keys below.
    ADD COLUMN legacy_unit_id      BIGINT;

ALTER TABLE properties ALTER COLUMN tenant_id DROP NOT NULL;
ALTER TABLE properties ALTER COLUMN price DROP NOT NULL;
ALTER TABLE properties ADD CONSTRAINT ck_property_owner
    CHECK (listing_kind = 'UNIT' OR tenant_id IS NOT NULL);
ALTER TABLE properties ADD CONSTRAINT ck_property_price_present
    CHECK (listing_kind = 'UNIT' OR price IS NOT NULL);

-- A unit has a label, a state and a typology; nothing else does.
ALTER TABLE properties ADD CONSTRAINT ck_property_unit_shape CHECK (
    (listing_kind = 'UNIT') = (unit_label IS NOT NULL AND sale_state IS NOT NULL)
    AND (listing_kind <> 'UNIT' OR (development_id IS NOT NULL AND unit_type_id IS NOT NULL)));
ALTER TABLE properties ADD CONSTRAINT ck_property_sale_state CHECK (sale_state IS NULL OR sale_state IN
    ('AVAILABLE', 'HELD', 'RESERVED', 'SOLD', 'NOT_FOR_SALE', 'RETAINED'));
ALTER TABLE properties ADD CONSTRAINT ck_property_unit_sold CHECK (sale_state IS DISTINCT FROM 'SOLD'
    OR (sold_at IS NOT NULL AND sold_price IS NOT NULL AND buyer_name IS NOT NULL));
ALTER TABLE properties ADD CONSTRAINT ck_property_unit_held CHECK (sale_state IS DISTINCT FROM 'HELD'
    OR reserved_until IS NOT NULL);
ALTER TABLE properties ADD CONSTRAINT ck_property_unit_handover CHECK (
    construction_status IS DISTINCT FROM 'HANDED_OVER' OR listing_kind <> 'UNIT' OR handed_over_on IS NOT NULL);
ALTER TABLE properties ADD CONSTRAINT ck_property_unit_counts CHECK (
    (balconies IS NULL OR balconies >= 0) AND (balcony_area_sqm IS NULL OR balcony_area_sqm >= 0));

-- The typology uniqueness now applies to typology rows only: many unit rows share a unit_type_id.
DROP INDEX uk_property_unit_type;
CREATE UNIQUE INDEX uk_property_unit_type ON properties (unit_type_id)
    WHERE unit_type_id IS NOT NULL AND listing_kind = 'TYPOLOGY' AND status <> 5;
CREATE UNIQUE INDEX uk_property_unit_label ON properties (development_id, unit_label)
    WHERE listing_kind = 'UNIT' AND status <> 5;
CREATE UNIQUE INDEX uk_property_pay_reference ON properties (pay_reference) WHERE pay_reference IS NOT NULL;
CREATE INDEX idx_property_inventory ON properties (development_id, unit_type_id, sale_state)
    WHERE listing_kind = 'UNIT' AND status <> 5;
CREATE INDEX idx_property_unit_phase ON properties (phase_id) WHERE phase_id IS NOT NULL;
CREATE INDEX idx_property_unit_buyer ON properties (buyer_user_id) WHERE buyer_user_id IS NOT NULL;
CREATE INDEX idx_property_reservation_expiry ON properties (reserved_until)
    WHERE sale_state IN ('HELD', 'RESERVED') AND reserved_until IS NOT NULL;
CREATE INDEX idx_properties_kind ON properties (listing_kind) WHERE status <> 5;

-- ── 3. Every existing unit becomes a row ────────────────────────────────────────────────────────────

INSERT INTO properties (
    listing_kind, legacy_unit_id, tenant_id, tenant_name, institution_id, reference, title, description,
    property_type, listing_type, tenure, price, currency,
    bedrooms, bathrooms, parking_spaces, floor_area_sqm, balconies, balcony_area_sqm, aspect,
    county, town, estate, address_line, latitude, longitude,
    listing_state, published_at, sold_at,
    development_id, unit_type_id, development_name, development_reference, unit_type_reference,
    construction_status, phase_id, unit_label, block, floor_no, door_no, pay_reference, sale_state,
    completed_on, handed_over_on, buyer_user_id, buyer_name, buyer_phone, buyer_email, purchase_request_id,
    reserved_at, reserved_until, sold_price, notes,
    status, status_flag, deactivation_reason, created_at, updated_at, created_by, updated_by)
SELECT 'UNIT', u.id,
       coalesce(d.selling_tenant_id, d.tenant_id),
       (SELECT tn.name FROM tenants tn WHERE tn.id = coalesce(d.selling_tenant_id, d.tenant_id)),
       d.institution_id,
       u.reference,
       d.name || ' · ' || u.unit_label,
       u.description,
       t.property_type, 'SALE', NULL, u.list_price, u.currency,
       u.bedrooms, u.bathrooms, u.parking_spaces, u.floor_area_sqm, u.balconies, u.balcony_area_sqm, u.aspect,
       d.county, d.town, d.estate, d.address_line, d.latitude, d.longitude,
       CASE WHEN u.sale_state = 'SOLD' THEN 'SOLD'
            WHEN d.listing_state = 'LIVE' THEN 'LIVE'
            WHEN d.listing_state = 'WITHDRAWN' THEN 'WITHDRAWN'
            ELSE 'DRAFT' END,
       d.published_at, u.sold_at,
       u.development_id, u.unit_type_id, d.name, d.reference, t.reference,
       u.construction_status, u.phase_id, u.unit_label, u.block, u.floor_no, u.door_no, u.pay_reference,
       u.sale_state, u.completed_on, u.handed_over_on, u.buyer_user_id, u.buyer_name, u.buyer_phone,
       u.buyer_email, u.purchase_request_id, u.reserved_at, u.reserved_until, u.sold_price, u.notes,
       u.status, u.status_flag, u.deactivation_reason, u.created_at, u.updated_at, u.created_by, u.updated_by
  FROM development_units u
  JOIN developments d ON d.id = u.development_id
  JOIN development_unit_types t ON t.id = u.unit_type_id;

-- ── 4. Everything that pointed at a unit now points at its property ─────────────────────────────────

-- The views over bookings name unit_id, so they go first and come back in §5 over property_id.
DROP VIEW v_chart_dev_funding;
DROP VIEW v_development_finance;
DROP VIEW v_booking_balances;

-- Bookings: on a property, which may be a house as well as a unit — so the development becomes optional.
ALTER TABLE unit_bookings ADD COLUMN property_id BIGINT REFERENCES properties (id);
UPDATE unit_bookings b SET property_id = p.id FROM properties p WHERE p.legacy_unit_id = b.unit_id;
ALTER TABLE unit_bookings ALTER COLUMN property_id SET NOT NULL;
ALTER TABLE unit_bookings ALTER COLUMN development_id DROP NOT NULL;
DROP INDEX uk_booking_live_unit;
DROP INDEX idx_booking_unit;
ALTER TABLE unit_bookings DROP COLUMN unit_id;
-- The constraint the feature rests on: one live booking per home.
CREATE UNIQUE INDEX uk_booking_live_property ON unit_bookings (property_id)
    WHERE state IN ('RESERVED', 'AGREED') AND status <> 5;
CREATE INDEX idx_booking_property ON unit_bookings (property_id, booked_on DESC) WHERE status <> 5;

-- Payments.
ALTER TABLE payments ADD COLUMN property_id BIGINT REFERENCES properties (id);
UPDATE payments pm SET property_id = p.id FROM properties p WHERE p.legacy_unit_id = pm.unit_id;
ALTER TABLE payments DROP COLUMN unit_id;
CREATE INDEX idx_payment_property ON payments (property_id, paid_on DESC) WHERE property_id IS NOT NULL;

-- Unit features and progress posts keep their column name — both already have a property_id meaning the
-- listing they belong to, or are unit-specific by nature — and point at properties instead.
ALTER TABLE unit_features DROP CONSTRAINT unit_features_unit_id_fkey;
UPDATE unit_features f SET unit_id = p.id FROM properties p WHERE p.legacy_unit_id = f.unit_id;
ALTER TABLE unit_features ADD CONSTRAINT unit_features_unit_id_fkey FOREIGN KEY (unit_id) REFERENCES properties (id);

ALTER TABLE listing_progress_updates DROP CONSTRAINT listing_progress_updates_unit_id_fkey;
UPDATE listing_progress_updates l SET unit_id = p.id FROM properties p WHERE p.legacy_unit_id = l.unit_id;
ALTER TABLE listing_progress_updates ADD CONSTRAINT listing_progress_updates_unit_id_fkey
    FOREIGN KEY (unit_id) REFERENCES properties (id);

-- Media has no foreign key; its owner id is rewritten.
UPDATE media_assets m SET owner_id = p.id FROM properties p
 WHERE m.owner_type = 'DEVELOPMENT_UNIT' AND p.legacy_unit_id = m.owner_id;

-- ── 5. The views that named unit_id, back over property_id ──────────────────────────────────────────

CREATE VIEW v_booking_balances AS
SELECT b.id                                             AS booking_id,
       b.reference,
       b.development_id,
       b.property_id,
       b.tenant_id,
       b.institution_id,
       b.state,
       b.currency,
       b.price_agreed,
       b.deposit_due,
       COALESCE(s.scheduled, 0)                         AS scheduled,
       COALESCE(p.paid, 0)                              AS paid,
       COALESCE(b.price_agreed, COALESCE(s.scheduled, 0)) - COALESCE(p.paid, 0) AS balance,
       s.next_due_on,
       GREATEST(COALESCE(s.due_to_date, 0) - COALESCE(p.paid, 0), 0) AS overdue
  FROM unit_bookings b
  LEFT JOIN (
        SELECT i.booking_id,
               SUM(i.amount)                                                     AS scheduled,
               SUM(CASE WHEN i.due_on <= CURRENT_DATE THEN i.amount ELSE 0 END)   AS due_to_date,
               MIN(CASE WHEN i.due_on > CURRENT_DATE THEN i.due_on END)           AS next_due_on
          FROM booking_instalments i
         WHERE i.status <> 5
           AND i.plan_no = (SELECT MAX(i2.plan_no) FROM booking_instalments i2
                             WHERE i2.booking_id = i.booking_id AND i2.status <> 5)
         GROUP BY i.booking_id
  ) s ON s.booking_id = b.id
  LEFT JOIN (
        -- Received only. A voided payment is history, not money.
        SELECT pay.booking_id, SUM(pay.amount) AS paid
          FROM payments pay
         WHERE pay.status = 1
         GROUP BY pay.booking_id
  ) p ON p.booking_id = b.id
 WHERE b.status <> 5;

CREATE VIEW v_development_finance AS
SELECT d.id                                                  AS development_id,
       d.reference,
       d.name                                                AS development_name,
       d.tenant_id,
       d.institution_id,
       coalesce(d.institution_name, d.tenant_name)           AS owner_name,
       d.currency,
       d.construction_status,
       d.percent_complete,
       d.started_on,
       d.projected_completion_on,
       d.actual_completion_on,
       d.units_total,
       d.units_available,
       d.units_reserved,
       d.units_sold,
       d.budget_amount,
       d.facility_amount,
       d.facility_reference,
       coalesce(ph.phase_budget, 0)                          AS phase_budget,
       coalesce(ph.planned_spend, 0)                         AS planned_spend,
       -- What should have been spent by now: the planned spend of every phase already due to finish.
       coalesce(ph.planned_to_date, 0)                       AS planned_to_date,
       coalesce(ex.committed, 0)                             AS committed,
       coalesce(ex.spent, 0)                                 AS spent,
       coalesce(dd.drawn, 0)                                 AS drawn,
       coalesce(bk.contracted, 0)                            AS contracted,
       coalesce(bk.collected, 0)                             AS collected,
       coalesce(bk.receivable, 0)                            AS receivable,
       coalesce(bk.overdue, 0)                               AS overdue,
       coalesce(bk.live_bookings, 0)                         AS live_bookings,
       ph.phases,
       -- The latest date any phase now expects to finish: what happened, else what is now expected, else
       -- what was promised.
       ph.forecast_on,
       coalesce(ph.phases_late, 0)                           AS phases_late,
       ph.worst_slippage_days,
       d.created_at
  FROM developments d
  LEFT JOIN LATERAL (
        SELECT count(*)                                                                  AS phases,
               sum(p.budget_amount)                                                      AS phase_budget,
               sum(p.planned_spend)                                                      AS planned_spend,
               sum(p.planned_spend) FILTER (WHERE p.planned_completion_on <= CURRENT_DATE) AS planned_to_date,
               max(coalesce(p.actual_completion_on, p.revised_completion_on,
                            p.planned_completion_on))                                    AS forecast_on,
               count(*) FILTER (WHERE p.actual_completion_on IS NULL
                                  AND coalesce(p.revised_completion_on, p.planned_completion_on)
                                      < CURRENT_DATE)                                    AS phases_late,
               max(coalesce(p.actual_completion_on, p.revised_completion_on)
                   - p.planned_completion_on)                                            AS worst_slippage_days
          FROM development_phases p
         WHERE p.development_id = d.id AND p.status <> 5
  ) ph ON true
  LEFT JOIN LATERAL (
        SELECT sum(e.amount) FILTER (WHERE e.kind = 'COMMITTED') AS committed,
               sum(e.amount) FILTER (WHERE e.kind = 'SPENT')     AS spent
          FROM development_expenditures e
         WHERE e.development_id = d.id AND e.status = 1
  ) ex ON true
  LEFT JOIN LATERAL (
        SELECT sum(f.amount) AS drawn
          FROM facility_drawdowns f
         WHERE f.development_id = d.id AND f.status = 1
  ) dd ON true
  LEFT JOIN LATERAL (
        SELECT sum(b.price_agreed) FILTER (WHERE b.state IN ('RESERVED', 'AGREED', 'COMPLETED')) AS contracted,
               sum(v.paid)                                                                     AS collected,
               sum(v.balance) FILTER (WHERE b.state IN ('RESERVED', 'AGREED'))                 AS receivable,
               sum(v.overdue) FILTER (WHERE b.state IN ('RESERVED', 'AGREED'))                 AS overdue,
               count(*) FILTER (WHERE b.state IN ('RESERVED', 'AGREED'))                       AS live_bookings
          FROM unit_bookings b
          JOIN v_booking_balances v ON v.booking_id = b.id
         WHERE b.development_id = d.id AND b.status <> 5
  ) bk ON true
 WHERE d.status <> 5;

CREATE VIEW v_chart_dev_funding AS
SELECT f.tenant_id, f.institution_id, f.development_id, NULL::date AS bucket, s.series, s.value
  FROM v_development_finance f
  CROSS JOIN LATERAL (VALUES
        ('Budget',    f.budget_amount),
        ('Spent',     f.spent),
        ('Drawn',     f.drawn),
        ('Collected', f.collected)
  ) AS s(series, value)
 WHERE s.value IS NOT NULL;

-- ── 6. The old table goes ───────────────────────────────────────────────────────────────────────────

DROP TABLE development_units;
ALTER TABLE properties DROP COLUMN legacy_unit_id;

COMMENT ON TABLE properties IS
    'Every home the platform knows about: an ordinary listing (HOUSE), the card for one kind of home in a '
    'development (TYPOLOGY), and each home in a development (UNIT). Bookings, payments, leads and media '
    'all point here, whichever kind the row is.';
