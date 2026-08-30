-- ═════════════════════════════════════════════════════════════════════════════════════════════════
-- A listing that stands for many identical homes
--
-- An estate of a hundred and twenty-five bungalows is not a hundred and twenty-five things to scroll past. It
-- is four: thirty studios, forty one-beds, thirty two-beds, twenty-five three-beds — each a row saying what it
-- is, what it starts at and how many are left. The individual homes are a drill-down, not a search result.
--
-- The grouping already exists in the model: a development has typologies, a typology has units, and a typology
-- gets one `properties` row so enquiries, offers, viewings and commissions keep working through the ten tables
-- that already reference it. What was missing is what a card needs to render that row without a join per
-- listing.
--
-- <h2>Cached, like the counts beside them</h2>
--
-- `development_name`, `units_available` and `units_total` are already mirrored onto the listing by
-- DevelopmentInventoryService. These two references join them, for the same reason: the marketplace search is
-- the hottest read in the application, and a card that needs the project's reference to build a link should
-- not make the page join to `developments` to find it.
--
-- The cost of a cache is that it can go stale, and the mitigation is the same one those columns already have:
-- one writer. DevelopmentInventoryService is the only thing that sets any of them.
--
-- <h2>Why the reference and not the id</h2>
--
-- A link is built from a reference — `/development/DV260827DEMO` — because that is what the public routes take
-- and what somebody can write down. Storing the id would mean encoding it on every render to get back to the
-- thing the URL needs.
-- ═════════════════════════════════════════════════════════════════════════════════════════════════

ALTER TABLE properties
    ADD COLUMN development_reference VARCHAR(16),
    ADD COLUMN unit_type_reference   VARCHAR(16);

COMMENT ON COLUMN properties.development_reference IS
    'The project this listing belongs to, cached for the link on its card. Maintained by '
    'DevelopmentInventoryService along with development_name and the unit counts.';
COMMENT ON COLUMN properties.unit_type_reference IS
    'The typology this listing stands for, cached so the card can offer its units without a join.';

/*
 * Backfilled from what is already linked.
 *
 * Every listing with a unit_type_id was created by putting a typology on the market, so its references are
 * derivable now — and a listing whose card cannot build its own link would be a broken link rather than a
 * missing one.
 */
UPDATE properties p
   SET development_reference = d.reference,
       unit_type_reference   = t.reference
  FROM development_unit_types t
  JOIN developments d ON d.id = t.development_id
 WHERE p.unit_type_id = t.id;
