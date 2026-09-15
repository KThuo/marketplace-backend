-- ─────────────────────────────────────────────────────────────────────────────────────────────────
-- Amenities get a glyph, and cover listings rather than only units.
--
-- An amenity list is read by scanning, and a column of identical bullets has to be read word by word.
-- The glyph is what makes "borehole" findable at a glance. A key into the client's icon set rather
-- than an uploaded image: there is nothing to store, nothing to resize, and every estate then shows
-- the same borehole. An unknown or null key renders a neutral fallback, so a key that reaches the
-- database before the client knows it degrades rather than breaks.
--
-- ── Why no new table ─────────────────────────────────────────────────────────────────────────────
--
-- unit_features.unit_id lost its foreign key when units became rows in `properties`, so it already
-- means "the property this is on" — and a house, a typology card and a unit are all properties. The
-- link table therefore serves every kind of listing as it stands, and the only thing missing was a
-- vocabulary wide enough to describe a house rather than a flat in a block.
--
-- The twelve that were there describe the inside of a unit, because that is what they were built for.
-- Property-level amenities — the borehole, the generator, the lift, the gate — had nowhere to live,
-- which is why an ordinary listing showed three booleans and nothing else.
-- ─────────────────────────────────────────────────────────────────────────────────────────────────

ALTER TABLE unit_feature_configs ADD COLUMN icon VARCHAR(40);

-- The twelve already there, given their glyphs.
UPDATE unit_feature_configs SET icon = 'kitchen'    WHERE code = 'OPEN_PLAN_KITCHEN';
UPDATE unit_feature_configs SET icon = 'kitchen'    WHERE code = 'SEPARATE_KITCHEN';
UPDATE unit_feature_configs SET icon = 'store'      WHERE code = 'PANTRY';
UPDATE unit_feature_configs SET icon = 'bath'       WHERE code = 'EN_SUITE_MASTER';
UPDATE unit_feature_configs SET icon = 'bath'       WHERE code = 'ALL_EN_SUITE';
UPDATE unit_feature_configs SET icon = 'store'      WHERE code = 'WALK_IN_CLOSET';
UPDATE unit_feature_configs SET icon = 'dsq'        WHERE code = 'DSQ';
UPDATE unit_feature_configs SET icon = 'terrace'    WHERE code = 'CORNER_UNIT';
UPDATE unit_feature_configs SET icon = 'garden'     WHERE code = 'GARDEN';
UPDATE unit_feature_configs SET icon = 'terrace'    WHERE code = 'ROOF_TERRACE';
UPDATE unit_feature_configs SET icon = 'lighting'   WHERE code = 'DOUBLE_VOLUME';
UPDATE unit_feature_configs SET icon = 'carport'    WHERE code = 'COVERED_PARKING';

-- ── The vocabulary a house needs ─────────────────────────────────────────────────────────────────
--
-- Sorted in the order somebody reading a listing cares about them: water and power first, because in
-- this market they are the questions asked before the bedrooms.
INSERT INTO unit_feature_configs (code, name, description, category, icon, sort_order, created_by) VALUES
    ('BOREHOLE',        'Borehole',            'On-site water borehole.',        'WATER',    'water',       20, 'migration'),
    ('WATER_TANK',      'Water storage',       'Tanks with a reserve.',          'WATER',    'tank',        22, 'migration'),
    ('WATER_HEATER',    'Water heating',       'Instant or tank heating.',       'WATER',    'heater',      24, 'migration'),
    ('BACKUP_GENERATOR','Backup generator',    'Standby power.',                 'POWER',    'generator',   30, 'migration'),
    ('SOLAR',           'Solar',               'Solar water or power.',          'POWER',    'solar',       32, 'migration'),
    ('CCTV',            'CCTV',                'Monitored cameras.',             'SECURITY', 'cctv',        40, 'migration'),
    ('MANNED_SECURITY', 'Manned security',     'Guarded, day and night.',        'SECURITY', 'guard',       42, 'migration'),
    ('PERIMETER_WALL',  'Perimeter wall',      'Walled and gated.',              'SECURITY', 'wall',        44, 'migration'),
    ('CONTROLLED_GATE', 'Controlled access',   'Gate with access control.',      'SECURITY', 'gate',        46, 'migration'),
    ('ELECTRIC_FENCE',  'Electric fence',      'Fenced perimeter.',              'SECURITY', 'alarm',       48, 'migration'),
    ('LIFT',            'Lift',                'Passenger lift.',                'SHARED',   'lift',        50, 'migration'),
    ('SWIMMING_POOL',   'Swimming pool',       'Shared pool.',                   'SHARED',   'pool',        52, 'migration'),
    ('GYM',             'Gym',                 'Shared gym.',                    'SHARED',   'gym',         54, 'migration'),
    ('PLAYGROUND',      'Playground',          'Children''s play area.',         'SHARED',   'playground',  56, 'migration'),
    ('CLUBHOUSE',       'Clubhouse',           'Shared clubhouse.',              'SHARED',   'clubhouse',   58, 'migration'),
    ('VISITOR_PARKING', 'Visitor parking',     'Parking for visitors.',          'PARKING',  'parking',     60, 'migration'),
    ('LIFT_ACCESS',     'Step-free access',    'Reachable without stairs.',      'SHARED',   'accessible',  62, 'migration'),
    ('FIBRE',           'Fibre internet',      'Fibre to the building.',         'INSIDE',   'wifi',        70, 'migration'),
    ('AIR_CONDITIONING','Air conditioning',    'Fitted AC.',                     'INSIDE',   'aircon',      72, 'migration'),
    ('FURNISHED',       'Furnished',           'Comes furnished.',               'INSIDE',   'furnished',   74, 'migration'),
    ('FITTED_WARDROBES','Fitted wardrobes',    'Built-in wardrobes.',            'INSIDE',   'store',       76, 'migration'),
    ('LAUNDRY',         'Laundry',             'Plumbed laundry area.',          'INSIDE',   'laundry',     78, 'migration'),
    ('BALCONY',         'Balcony',             'Private balcony.',               'OUTDOOR',  'balcony',     80, 'migration'),
    ('PETS_ALLOWED',    'Pets allowed',        'Pets permitted.',                'OTHER',    'pets',        90, 'migration'),
    ('WASTE_COLLECTION','Waste collection',    'Refuse collected.',              'OTHER',    'waste',       92, 'migration')
ON CONFLICT (code) DO NOTHING;
