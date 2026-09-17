-- ─────────────────────────────────────────────────────────────────────────────────────────────────
-- A floor plan is not a card's photograph, and the albums that were told otherwise are repaired.
--
-- When kinds arrived, "the cover is the first photograph" was applied to the upload and not to the
-- promotion that runs when a cover is deleted — that still took the next row of any kind. So deleting
-- the last photograph of an album handed the cover to a floor plan, the cached key on the parent (and,
-- through it, on every listing reading that album) was rewritten to point at the plan, and the album
-- was left holding the primary flag on a row that should never carry it.
--
-- It then refused the fix. uk_media_primary permits one primary per owner, and the next photograph
-- uploaded claimed the flag without making room for it, so the insert violated the index: a 500, and
-- the photograph lost. An owner in this state could not be photographed again.
--
-- The services no longer do either thing. This repairs what they already did, in every environment
-- that ran them, because the damage is in cached columns that nothing recomputes until the next upload
-- — and the upload was exactly what was failing.
-- ─────────────────────────────────────────────────────────────────────────────────────────────────

-- ① The flag belongs to a photograph or to nothing.
UPDATE media_assets   SET is_primary = false WHERE is_primary AND media_kind <> 'PHOTO';
UPDATE property_media SET is_primary = false WHERE is_primary AND media_kind <> 'PHOTO';

-- ② Every cover cache re-derived from the album, exactly as coverKeyFor now reads it: the flagged
--    photograph if there is one, else the first photograph, else nothing. Only rows whose cached key is
--    not a live photograph of their own album are touched, so a correct cache is left alone.
UPDATE developments d SET primary_image_key = (
        SELECT ma.storage_key FROM media_assets ma
        WHERE ma.owner_type = 'DEVELOPMENT' AND ma.owner_id = d.id
          AND ma.media_kind = 'PHOTO' AND ma.status <> 5
        ORDER BY ma.is_primary DESC, ma.sort_order, ma.id LIMIT 1)
WHERE d.primary_image_key IS NOT NULL
  AND NOT EXISTS (
        SELECT 1 FROM media_assets ma
        WHERE ma.owner_type = 'DEVELOPMENT' AND ma.owner_id = d.id
          AND ma.media_kind = 'PHOTO' AND ma.status <> 5
          AND ma.storage_key = d.primary_image_key);

UPDATE development_unit_types ut SET primary_image_key = (
        SELECT ma.storage_key FROM media_assets ma
        WHERE ma.owner_type = 'UNIT_TYPE' AND ma.owner_id = ut.id
          AND ma.media_kind = 'PHOTO' AND ma.status <> 5
        ORDER BY ma.is_primary DESC, ma.sort_order, ma.id LIMIT 1)
WHERE ut.primary_image_key IS NOT NULL
  AND NOT EXISTS (
        SELECT 1 FROM media_assets ma
        WHERE ma.owner_type = 'UNIT_TYPE' AND ma.owner_id = ut.id
          AND ma.media_kind = 'PHOTO' AND ma.status <> 5
          AND ma.storage_key = ut.primary_image_key);

UPDATE listing_progress_updates pu SET image_key = (
        SELECT ma.storage_key FROM media_assets ma
        WHERE ma.owner_type = 'PROGRESS_UPDATE' AND ma.owner_id = pu.id
          AND ma.media_kind = 'PHOTO' AND ma.status <> 5
        ORDER BY ma.is_primary DESC, ma.sort_order, ma.id LIMIT 1)
WHERE pu.image_key IS NOT NULL
  AND NOT EXISTS (
        SELECT 1 FROM media_assets ma
        WHERE ma.owner_type = 'PROGRESS_UPDATE' AND ma.owner_id = pu.id
          AND ma.media_kind = 'PHOTO' AND ma.status <> 5
          AND ma.storage_key = pu.image_key);

-- ③ A listing generated from a development does not own its pictures: its cover is its typology's, and
--    the card and each of its units are repointed together. A house keeps its own property_media cover.
UPDATE properties p SET primary_image_key = (
        SELECT ma.storage_key FROM media_assets ma
        WHERE ma.owner_type = 'UNIT_TYPE' AND ma.owner_id = p.unit_type_id
          AND ma.media_kind = 'PHOTO' AND ma.status <> 5
        ORDER BY ma.is_primary DESC, ma.sort_order, ma.id LIMIT 1)
WHERE p.unit_type_id IS NOT NULL
  AND p.primary_image_key IS NOT NULL
  AND NOT EXISTS (
        SELECT 1 FROM media_assets ma
        WHERE ma.owner_type = 'UNIT_TYPE' AND ma.owner_id = p.unit_type_id
          AND ma.media_kind = 'PHOTO' AND ma.status <> 5
          AND ma.storage_key = p.primary_image_key);

UPDATE properties p SET primary_image_key = (
        SELECT pm.storage_key FROM property_media pm
        WHERE pm.property_id = p.id AND pm.media_kind = 'PHOTO' AND pm.status <> 5
        ORDER BY pm.is_primary DESC, pm.sort_order, pm.id LIMIT 1)
WHERE p.unit_type_id IS NULL
  AND p.primary_image_key IS NOT NULL
  AND NOT EXISTS (
        SELECT 1 FROM property_media pm
        WHERE pm.property_id = p.id AND pm.media_kind = 'PHOTO' AND pm.status <> 5
          AND pm.storage_key = p.primary_image_key);
