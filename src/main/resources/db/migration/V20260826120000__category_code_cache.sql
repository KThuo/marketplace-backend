-- ═════════════════════════════════════════════════════════════════════════════════════════════════
-- The category code, cached on the rows that display it.
--
-- M10 shipped with `category_name` denormalised onto vendors and catalogue items but not the code — so the
-- response mapper looked the code up by matching the name against the whole category list, once per row.
-- The category list in turn counts its vendors per category. Listing twenty vendors therefore ran twenty
-- category loads and a hundred and sixty count queries, and the platform's vendor screen timed out at thirty
-- seconds with two vendors in the table.
--
-- The name was already cached for exactly this reason. Caching the code beside it is the same decision
-- applied to the same problem — a label cache, refreshed when a row's category changes.
-- ═════════════════════════════════════════════════════════════════════════════════════════════════

ALTER TABLE vendor_profiles ADD COLUMN category_code VARCHAR(32);
ALTER TABLE catalogue_items  ADD COLUMN category_code VARCHAR(32);

UPDATE vendor_profiles v
SET category_code = c.code
FROM vendor_categories c
WHERE c.id = v.category_id;

UPDATE catalogue_items i
SET category_code = c.code
FROM vendor_categories c
WHERE c.id = i.category_id;
