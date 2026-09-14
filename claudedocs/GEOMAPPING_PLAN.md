# Geomapping — picking a location, and showing it

Branch: `feature/coop-bank`, both repos.

---

## 1. What is being asked

Google Maps on listings and developments: a way to **set** where a property is, and **map previews**
wherever one is shown.

## 2. What is already here

More than expected, and it is worth being precise about the gap.

| | State |
|---|---|
| `latitude` / `longitude` | On `properties` **and** `developments`, `numeric(9,6)`, already in the public payloads |
| `MAPS_GOOGLE_KEY` | Configured, served on `/public/theme` as `mapsApiKey`, deliberately not secret |
| Listing detail (public) | **Has a map** — an Embed API iframe plus an "Open in Google Maps" link |
| Development detail (public) | **No map at all** |
| Unit detail (public) | No map |
| Setting a location | Two `<input type="number" step="0.000001">` boxes, on both edit forms |

So the data model needs nothing. The two real gaps are:

1. **Nobody can realistically set a location.** A seller is asked for latitude and longitude as six-decimal
   numbers. That is a field for somebody who already has coordinates, which a person listing a house does
   not. This is what "geomapping" means here.
2. **Developments and units show no map**, though they carry the coordinates to draw one.

## 3. Approach — take the axis pattern

Axis already solved the picking half, and the reasoning in it is worth inheriting rather than
rediscovering:

- `utils/googleMaps.ts` — loads the JS API **once**, because Google's loader throws if the script is
  included twice, and clears its cached promise on failure so one bad load does not wedge every later call.
- `GoogleAddressPicker.vue` — a search box, a small map and a pin, degrading to a plain text field when
  maps are off or unreachable.
- It builds on **`PlaceAutocompleteElement`**, not the older `Autocomplete` class. Google stopped offering
  the legacy widget to new API keys in March 2025, so anything written against `Autocomplete` today simply
  will not work on a key created now.
- `types/google-maps.d.ts` — a hand-written sliver of the SDK's surface, so using maps needs no dependency.

Hodi differs in one way that changes the component: axis stores **one address string** plus coordinates;
hodi already has structured location fields (`county`, `town`, `estate`, `addressLine`) that other things
depend on — search facets, the listing card, `search_text`. So the picker here must **not** own the address.

**It sets coordinates only.** The address fields stay exactly as they are, hand-typed, and the map is the
way to drop the pin. Searching is a convenience for finding the right spot, not a replacement for the
fields underneath.

## 4. Frontend

1. **`utils/googleMaps.ts`** and **`types/google-maps.d.ts`** — ported from axis, unchanged in substance.
2. **`LocationPicker.vue`** — search box, map, draggable pin, "use my location", and a clear button.
   Emits `latitude` / `longitude` only. When no key is configured it renders the two number inputs that
   are there today, so nothing is lost in an unconfigured environment.
3. **`MapPreview.vue`** — read-only. Uses the **Embed API iframe**, not the JS SDK: it needs no script
   load, no quota beyond the embed, and it is what `PropertyDetail` already proved. Falls back to an
   "Open in Google Maps" link, which needs no key at all.
4. Wire the picker into `PropertyEditView` and `DevelopmentEditView`, replacing the number pairs.
5. Wire the preview into the public `DevelopmentDetail` and `UnitDetail`, and refactor `PropertyDetail`
   to use it rather than its own inline copy.

## 5. Why a draggable pin as well as a search

A search resolves to the centroid of whatever Google thinks the place is. For an estate on the edge of a
town that is often several hundred metres out, and for an off-plan development on land with no address it
can be nothing useful at all. The pin is the seller's own statement of where the thing is — which is
exactly what the existing caption on the listing page already claims it is:

> *the pin is the seller's, at estate precision rather than the door.*

Drag makes that true. Without it the caption describes a geocode.

## 6. Out of scope

- Reverse geocoding the pin back into `county` / `town` / `estate`. Those are the seller's own words and
  feed search facets; overwriting them from a Google place name would churn the facet vocabulary.
- Map search on the marketplace ("draw an area"). That is a listings-query feature, not a geomapping one.
- Clustering, heat maps, drive-time. Nothing asks for them.

## 7. Status

**Done**, both repos. 232 backend tests pass; frontend typechecks and builds.

Backend change was one record: `PublicUnitDetail` gained `town`, `county`, `latitude`, `longitude`,
read from the development rather than the unit row — a unit copies its project's coordinates when it is
generated, so a developer who later moves the pin updates one row and not two hundred.

Verified against the running system: a pin set on the demo project comes back on
`/public/developments/DV260827DEMO`, and the same coordinates appear in the unit block of
`/public/properties/UN260827BYSX`, so the inheritance is real rather than assumed. All three pages
serve.

**What cannot be verified here**: the embedded map itself. `maps.google.key` is blank in this
environment, so what renders today is the degradation — the "Open in Google Maps" link, no iframe. To
see the maps, set the key in Settings → it is a Google Maps **browser** key, restricted by HTTP
referrer rather than kept secret, which is why it is served to the client on `/public/theme`.
