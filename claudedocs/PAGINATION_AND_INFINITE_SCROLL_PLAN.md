# Pagination: ten a page, a narrower size picker, and infinite scroll for the card lists

**Four changes across 34 files.** Counted per list, not per file, because several files hold more than one.

## 1. What the audit found

| Asked for | Found |
|---|---|
| Default page size 10 | 20 on most tables, 25 on two, 50 on two |
| Pagination outside the table, everywhere | **Already true of all 20 tables.** The one case reported — `/app/enquiries` — is not a table |
| Size picker as wide as its largest number | 170px wide for a two-character number |
| Card lists: no pager, infinite scroll with a count | 12 lists paginated |

**The reported case was a card list, not a table.** `/app/enquiries` renders `<button class="row">` per
enquiry inside `<section class="list surface-card">`, with the pager inside that card — so it looked like a
table with its footer inside one. Every actual `<DataTable>` already had its pager as a sibling of the table's
card, so requirement two needed no work; the enquiries case is resolved by requirement four instead, which
removes its pager altogether.

## 2. Ten a page

The twenty table views. Only the query that drives the pager was touched — a page also carries calls like
`institutionApi.list({ size: 200 })` to fill a dropdown, and those are "fetch everything", not a page size.

## 3. The size picker

`.sizeselect { width: 78px }` had been in `AppPagination` all along and never applied. A parent's scoped class
lands on the child component's root element, where `.select.sm` inside `AppSelect` — one class more specific —
was setting `min-width: 170px`, and `min-width` beats `width` regardless. So the control was 170px to hold
"10".

`AppSelect` now reads its small variant's three widths from custom properties, which cross the component
boundary without a specificity contest, and the picker asks for 72px: enough for "100", the widest option, and
fixed so the control does not resize when the number in it changes.

## 4. Infinite scroll for the twelve card lists

Numbered pages suit a table, where somebody is hunting one row and will come back to page four to find it.
They suit a wall of cards much less — choosing between page two and page three of a set of cards is a decision
nobody wants to make, and the answer is nearly always "keep going".

`InfiniteMore` is the shared foot: the sentinel, the observer, the count, and a button. All twelve lists had
an identical shape — one `load()`, one `loading`, one `page`, one `rows.value = result.content` — so the
change is the same five edits in each, and `load(more)` decides whether a batch replaces or appends.

Three details worth keeping:

- **The button is not a fallback nobody sees.** `IntersectionObserver` is absent in some environments and
  disabled in others, and a list advanced only by a scroll the browser will not report has no way through.
- **A batch that does not fill the screen has to ask again.** An observer fires on a *transition* into view;
  ten rows arriving on a tall screen leave the foot where it already was, and nothing crosses the boundary.
  So the intersection state is kept and a finished load reconsiders — with a floor, since a parent that
  answers without the count moving would otherwise spin.
- **A load that is not appending resets the cursor.** This was a bug found before it shipped: acting on a row
  with sixty on screen re-read the third batch and rendered those twenty as the whole list, the first forty
  gone with nothing to say they had been. Every non-appending caller means "this list from the top".

## 5. Verified in the browser

Against a network-stubbed session, 57 user types and 47 enquiries.

- A table opens with **10 rows**, "Showing 1–10 of 57", pager outside the card, size picker **72px** showing "10".
- Enquiries opens with one batch and reads "20 of 47 enquiries"; the button takes it to 40; a scroll to the
  bottom chains the rest and it settles on "All 47 enquiries shown" with the button gone.
- A filter change collapses the accumulation back to one batch.

### The bug the browser caught

The foot is rendered behind `v-if="total > 0"`, so at `onMounted` it did not exist and `observe()` was called
on nothing. **The button still worked**, which is exactly what made it look finished: scrolling did nothing
and the only symptom was a list that stopped growing at whatever the button had reached. `MarketplaceSearch`
already guarded this with a watcher on its sentinel; not following that pattern closely enough is what caused
it.

## 6. Two things deliberately left

- **The foot sits inside the list card**, where the pager used to be, rather than outside it. It is the list's
  own count line rather than a pager, and on the enquiries screen the list is one column of a two-column grid
  — a foot outside the card would sit under the conversation panel as well.
- **A filter change does not scroll back to the top.** The pane clamps to the shorter content on its own, and
  a scroll the reader did not ask for is its own kind of annoyance. If the observer then pulls a second batch
  because the foot is in view, that is the design working.
