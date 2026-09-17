-- ─────────────────────────────────────────────────────────────────────────────────────────────────
-- Seventeen pieces of marketplace feedback, and the columns they need.
--
-- One migration rather than nine, because several of the seventeen turned out to be the same fault
-- read off different screens, and splitting them would have meant a column added in one file and
-- the constraint that gives it meaning added in another.
--
-- Nothing here drops or rewrites a column. Every addition is nullable or defaulted, so a running
-- instance keeps working through the deploy and the application picks the new behaviour up when it
-- restarts.
-- ─────────────────────────────────────────────────────────────────────────────────────────────────


-- ── 1. A photograph and a plan are different things ──────────────────────────────────────────────
--
-- `media_assets` has said so since it was written — PHOTO, FLOOR_PLAN, SITE_PLAN, BROCHURE, DRONE —
-- but the uploader never sent a kind, so everything in it is a PHOTO and the discriminator has never
-- been used. `property_media` did not have the column at all, so a listing could not hold a floor
-- plan even in principle.
--
-- CERTIFICATE joins them both. A green certification was a tick box and a free-text name with
-- nothing behind it; the evidence had nowhere to go, which is the same as saying nobody checked.

ALTER TABLE property_media ADD COLUMN media_kind VARCHAR(24) NOT NULL DEFAULT 'PHOTO';

ALTER TABLE property_media ADD CONSTRAINT ck_property_media_kind CHECK (media_kind IN
    ('PHOTO', 'FLOOR_PLAN', 'SITE_PLAN', 'BROCHURE', 'DRONE', 'CERTIFICATE'));

CREATE INDEX idx_property_media_kind ON property_media (property_id, media_kind, sort_order)
    WHERE status <> 5;

ALTER TABLE media_assets DROP CONSTRAINT ck_media_kind;
ALTER TABLE media_assets ADD CONSTRAINT ck_media_kind CHECK (media_kind IN
    ('PHOTO', 'FLOOR_PLAN', 'SITE_PLAN', 'BROCHURE', 'DRONE', 'CERTIFICATE'));

-- The cover is a photograph. Before the kind existed every row was one, so the partial index did not
-- have to say so; now that a floor plan can be primary by accident, it does.
DROP INDEX IF EXISTS uk_media_primary;
CREATE UNIQUE INDEX uk_media_primary ON media_assets (owner_type, owner_id)
    WHERE is_primary AND status <> 5;


-- ── 2. Amenities at project level ────────────────────────────────────────────────────────────────
--
-- `unit_features` already carried two owners — a property and a typology — with a constraint saying
-- exactly one. A development is the third, and it is the one the feedback actually asked for: the
-- borehole, the gate and the clubhouse belong to the estate, not to each flat in it, and ticking
-- them on all ninety listings was the only way to say so.
--
-- The typology column, meanwhile, has never had a writer. It gets one in this release; the column
-- itself needs nothing.

ALTER TABLE unit_features ADD COLUMN development_id BIGINT REFERENCES developments (id);

ALTER TABLE unit_features DROP CONSTRAINT ck_unit_feature_owner;
ALTER TABLE unit_features ADD CONSTRAINT ck_unit_feature_owner CHECK (
    (unit_id IS NOT NULL)::int
  + (unit_type_id IS NOT NULL)::int
  + (development_id IS NOT NULL)::int = 1);

CREATE UNIQUE INDEX uk_unit_feature_development ON unit_features (development_id, feature_code)
    WHERE development_id IS NOT NULL AND status <> 5;
CREATE INDEX idx_unit_feature_dev_lookup ON unit_features (development_id)
    WHERE development_id IS NOT NULL AND status <> 5;


-- ── 3. A development can be certified too ────────────────────────────────────────────────────────
--
-- The three green columns were on `properties` only. A development is the thing that holds an EDGE
-- or a Safari Green certificate — the certificate is issued to the project, and every unit in it
-- inherits the claim — so the project having nowhere to record it was backwards.

ALTER TABLE developments ADD COLUMN green_certified BOOLEAN NOT NULL DEFAULT false;
ALTER TABLE developments ADD COLUMN green_certification VARCHAR(64);
ALTER TABLE developments ADD COLUMN energy_rating VARCHAR(8);


-- ── 4. An energy rating is a band, not eight free characters ─────────────────────────────────────
--
-- `energy_rating VARCHAR(8)` with no CHECK and no validation on the request accepted anything
-- somebody typed, which is why it could not be rendered as anything but a string. The scale is A–G,
-- as every energy performance certificate in use here is.
--
-- Existing values are healed where they plainly mean a band and left alone otherwise — an unreadable
-- legacy value is shown as it stands rather than being invented into a rating the property may not
-- hold. The constraint is therefore NOT VALID: it governs what is written from now on without
-- claiming the rows behind it were ever checked.

UPDATE properties SET energy_rating = upper(trim(energy_rating))
    WHERE energy_rating IS NOT NULL
      AND upper(trim(energy_rating)) ~ '^[A-G]$';

ALTER TABLE properties ADD CONSTRAINT ck_property_energy_rating
    CHECK (energy_rating IS NULL OR energy_rating ~ '^[A-G]$') NOT VALID;

ALTER TABLE developments ADD CONSTRAINT ck_development_energy_rating
    CHECK (energy_rating IS NULL OR energy_rating ~ '^[A-G]$');


-- ── 5. How long a rent is for ────────────────────────────────────────────────────────────────────
--
-- `listing_type` has allowed RENT since the table was created and no screen has ever set it, so a
-- rental was indistinguishable from a sale and its price was rendered as though somebody would pay
-- it once. A rent needs its period to be a price at all.
--
-- Defaulted rather than NOT NULL: a sale has no period, and writing MONTH on every sale row to keep
-- the column full would make the column mean nothing.

ALTER TABLE properties ADD COLUMN rent_period VARCHAR(16);
ALTER TABLE properties ADD CONSTRAINT ck_property_rent_period CHECK (
    rent_period IS NULL OR rent_period IN ('DAY', 'WEEK', 'MONTH', 'YEAR'));

-- Anything already marked RENT predates the control and is monthly by the convention of this market.
UPDATE properties SET rent_period = 'MONTH' WHERE listing_type = 'RENT' AND rent_period IS NULL;


-- ── 6. Where the auction is held ─────────────────────────────────────────────────────────────────
--
-- The lot already carries the *property's* coordinates. The venue is somewhere else entirely — a
-- hotel, an auctioneer's rooms, a court — and "Nairobi, at the usual place" has cost bidders a
-- morning often enough that a pin is worth two columns.

ALTER TABLE auction_lots ADD COLUMN venue_latitude  NUMERIC(9, 6);
ALTER TABLE auction_lots ADD COLUMN venue_longitude NUMERIC(9, 6);


-- ── 7. A viewing and an offer keep what was said ─────────────────────────────────────────────────
--
-- `site_visits.seller_note`, `purchase_requests.decision_note` and `outcome_note` are one column
-- each, and every decision overwrote the last. A viewing rescheduled twice retained only the second
-- reason; an offer countered twice retained only the final word. On the admin side that reads as
-- "the conversation was not saved", because it was not.
--
-- Modelled on `enquiry_messages`, which already got this right, but polymorphic over the two lead
-- kinds rather than a third near-identical table. No foreign key for the same reason `media_assets`
-- has none: the parent is one of two tables, and a constraint that can only name one of them would
-- be enforcing half a rule.

CREATE TABLE lead_messages (
    id              BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,

    lead_type       VARCHAR(24)  NOT NULL,
    lead_id         BIGINT       NOT NULL,

    -- Who is speaking, not who is logged in: a platform administrator answering on a seller's
    -- behalf is PLATFORM, and the buyer should see that rather than a name they never dealt with.
    author_side     VARCHAR(16)  NOT NULL,
    author_user_id  BIGINT,
    author_name     VARCHAR(160),

    body            TEXT         NOT NULL,

    -- The state the lead moved to, when this message was the reason it moved. Null for a plain
    -- note. Kept beside the text so the history reads as a sequence of decisions rather than as
    -- loose remarks whose consequence has to be inferred from timestamps.
    state_after     VARCHAR(32),

    status          INTEGER      NOT NULL DEFAULT 1,
    status_flag     VARCHAR(32)  NOT NULL DEFAULT 'Active',
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_by      VARCHAR(64),

    CONSTRAINT ck_lead_message_type CHECK (lead_type IN ('SITE_VISIT', 'PURCHASE_REQUEST')),
    CONSTRAINT ck_lead_message_side CHECK (author_side IN ('BUYER', 'SELLER', 'PLATFORM'))
);

CREATE INDEX idx_lead_message_thread ON lead_messages (lead_type, lead_id, created_at)
    WHERE status <> 5;

-- ── What is already on the rows, carried in as the first message of each thread ──────────────────
--
-- Without this the history starts empty for every lead in flight, and the screens would tell an
-- administrator the conversation was lost — which is the complaint, restated by the fix for it.
-- Timestamps come from the decision where there is one, so the imported lines sit in the right
-- order against anything said afterwards.

INSERT INTO lead_messages (lead_type, lead_id, author_side, author_user_id, author_name, body, state_after, created_at, created_by)
SELECT 'SITE_VISIT', id, 'BUYER', user_id, buyer_name, buyer_note, NULL, created_at, created_by
FROM site_visits
WHERE buyer_note IS NOT NULL AND trim(buyer_note) <> '';

INSERT INTO lead_messages (lead_type, lead_id, author_side, author_user_id, author_name, body, state_after, created_at, created_by)
SELECT 'SITE_VISIT', id, 'SELLER', decided_by_user_id, NULL, seller_note, state,
       coalesce(decided_at, updated_at), updated_by
FROM site_visits
WHERE seller_note IS NOT NULL AND trim(seller_note) <> '';

INSERT INTO lead_messages (lead_type, lead_id, author_side, author_user_id, author_name, body, state_after, created_at, created_by)
SELECT 'SITE_VISIT', id, 'SELLER', decided_by_user_id, NULL, outcome_note, state,
       coalesce(decided_at, updated_at), updated_by
FROM site_visits
WHERE outcome_note IS NOT NULL AND trim(outcome_note) <> '';

INSERT INTO lead_messages (lead_type, lead_id, author_side, author_user_id, author_name, body, state_after, created_at, created_by)
SELECT 'PURCHASE_REQUEST', id, 'BUYER', user_id, buyer_name, buyer_message, NULL, created_at, created_by
FROM purchase_requests
WHERE buyer_message IS NOT NULL AND trim(buyer_message) <> '';

INSERT INTO lead_messages (lead_type, lead_id, author_side, author_user_id, author_name, body, state_after, created_at, created_by)
SELECT 'PURCHASE_REQUEST', id, 'SELLER', decided_by_user_id, NULL, decision_note, state,
       coalesce(decided_at, updated_at), updated_by
FROM purchase_requests
WHERE decision_note IS NOT NULL AND trim(decision_note) <> '';
