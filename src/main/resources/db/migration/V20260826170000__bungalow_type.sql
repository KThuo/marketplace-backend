-- The taxonomy has to contain what the data contains.
--
-- M13's seeded property types came from the Java enum's six values. The frontend's hardcoded list had a
-- seventh — BUNGALOW — and there are listings using it. A type table that omits a type in use makes the
-- marketplace's own facet show a filter the listing form cannot offer, which is the exact drift the table
-- was introduced to end.
INSERT INTO property_type_configs
    (code, name, description, icon, sort_order,
     has_bedrooms, has_bathrooms, has_floor_area, has_plot_area, has_year_built, lettable, created_by)
SELECT 'BUNGALOW', 'Bungalow', 'A single-storey house on its own plot.', '🏚', 35,
       true, true, true, true, true, true, 'system'
WHERE NOT EXISTS (SELECT 1 FROM property_type_configs WHERE code = 'BUNGALOW');
