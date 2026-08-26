# Reports, and the two date controls it needs — implementation plan

**Requested:** on the reports page — filters including a search filter, pagination, and a column chooser
that decides what gets downloaded; downloads in Excel, CSV and PDF. Separately: a calendar component and a
date-range component to replace the native date inputs, the range carrying the usual presets.

**Touches:** `hodimp-b` — `ReportCatalogue`, `ReportService`, `ReportController`, `pom.xml`.
`hodimp-f` — `ReportsView`, `services/reports.ts`, and two new components.

---

## 1. What is there now

| | Today |
|---|---|
| Report catalogue | 8 reports, each declaring a view, a date column, an ordered label→column map, which columns are numeric, and whether it is platform-only |
| Running one | `GET /reports/{code}?from&to&limit` — returns up to **500 rows**, with a `truncated` flag. No offset, no total |
| Filtering | The date window, and nothing else |
| Search | None |
| Columns | Every declared column, always |
| Export | CSV only, up to 20 000 rows, already hardened against formula injection |
| Dates on screen | Two native `<input type="date">` |

**The property that makes the SQL defensible, and that this work must not break.** Every fragment of the
query — the view, the date column, the column list, the ordering — comes from `ReportCatalogue`, never from
the request. The request supplies a code that is *matched* against the catalogue, two dates and a limit. A
report engine that accepted a table name, a column name or an `ORDER BY` from a caller would be an injection
surface wearing a business-intelligence hat. Everything below therefore takes **identifiers from the
catalogue and values as bound parameters** — a request may say *which* declared column to filter on, never
what the SQL says.

---

## 2. Backend

### 2.1 Filters that the catalogue declares

Each report gains a list of filters, because "the necessary filters" are not the same for a listings report
and a commission report — one wants state, county and kind; the other wants settlement state and seller.

```java
public record Filter(String label, String column, FilterKind kind, String optionsQuery) {}
enum FilterKind { ENUM, TEXT, BOOLEAN, NUMBER_RANGE }
```

- `ENUM` — the UI renders a select. Its options come from `SELECT DISTINCT` on that declared column,
  scoped the same way the report is, so a seller sees only the values present in their own data.
- The filter's `column` is validated against the report's own declared columns on every request. A filter
  naming a column the report does not declare is rejected, not interpolated.
- Values arrive as bound parameters.

Endpoint: `GET /reports/{code}/filters` for the option lists, so the page can populate its selects without
the report having to be run first.

### 2.2 Search

One free-text box, matched against the report's **declared text columns** — those not in `numeric` and not
a date. Built as `(col1 || ' ' || col2 || …) ILIKE ?` with the term bound and wrapped in `%`. Column names
come from the catalogue; the term never touches the SQL string.

Deliberately not a per-column search: a reports page is somewhere you go to find *a row you half remember*,
and eight search boxes is a worse answer to that than one.

### 2.3 Pagination

`page` and `size` on the run endpoint, `size` capped at 200. The response gains `total` — which needs a
second `COUNT(*)` over the same predicate, so the page can say "1–50 of 812" rather than "50 rows, and
there may be more". `truncated` then goes away: a page that knows its total does not need to hedge.

The existing `MAX_ROWS = 500` cap becomes the *export* concern only.

### 2.4 Column selection

The run and export endpoints accept `columns` — a comma-separated list of column keys. Each is checked
against the report's declared columns; unknown keys are dropped rather than erroring, because a stale
bookmark should give you a report rather than a 400. An empty or absent list means every column.

This is what makes the chooser meaningful: the user unticks four columns and the **file** they download has
the columns they chose, not the full set with the extras hidden client-side.

### 2.5 Three formats

CSV exists. Two to add, and both need a dependency:

| Format | Library | Why this one |
|---|---|---|
| `.xlsx` | `org.apache.poi:poi-ooxml` | The mature, universally used choice. Heavier than the alternatives (~12 MB with transitive deps), which for a server-side export is a cost worth paying for not having to explain a niche library later. `org.dhatim:fastexcel` is the lighter streaming option if the size matters more than the familiarity. |
| `.pdf` | `com.github.librepdf:openpdf` | LGPL + MPL, so it can be used in a commercial product without the copyleft reach. Has `PdfPTable`, which is most of a tabular report. **iText 7 is deliberately not proposed: it is AGPL**, and linking it here would put this codebase under that licence or a paid one. Apache PDFBox is the other safe choice but has no table API, so the same output is several hundred more lines. |

One endpoint, format as a path segment: `GET /reports/{code}/export.{csv|xlsx|pdf}`, sharing the query,
the scoping, the column selection and the audit log line the CSV path already writes.

The CSV formula-injection guard applies to XLSX too — a cell beginning `=`, `+`, `-` or `@` is a formula
when Excel opens it, and that is not a CSV-specific problem. PDF does not execute cells and needs no such
guard.

PDF specifics worth deciding once: landscape A4, the report name and the window in a header, page numbers
in a footer, and the column set trimmed to what fits — a 14-column report cannot be a readable portrait
page, and silently cutting columns off the edge would be worse than saying so.

---

## 3. Frontend — the two date controls

Naive UI is a dependency but the platform uses it for **exactly two things**: the config provider and the
message host. Every control on screen — the select, the modal, the table, the pagination, the toolbar — is
hand-built to the design system. `n-date-picker` would be the first Naive *component* on the platform, and
it would look like it came from somewhere else, which is the same complaint that started the checkbox work.
So both of these are ours.

### 3.1 `AppDatePicker`

A text input plus a calendar panel. Deliberately keeps a real input: typing `2026-08-26` is faster than
clicking through a calendar, and a picker that only accepts clicks is slower for anyone who knows the date.

- Month grid, week starting Monday, weekday headers, the current day marked, the selected day filled with
  `--brand` and `--brand-contrast`
- Keyboard: arrows move by day, `PageUp`/`PageDown` by month, `Enter` selects, `Escape` closes, and focus
  returns to the input — the same contract `AppModal` and `AppSelect` already keep
- `role="dialog"` on the panel, `aria-label` on every day button reading the full date, `aria-current="date"`
  on today, `aria-selected` on the chosen day
- `min` / `max` support, because a range needs to stop its own two ends crossing over

### 3.2 `AppDateRange`

The presets, which are the point of it:

**Today · Yesterday · Last 7 days · Last 30 days · This month · Last month · Last 3 months · This year ·
Last year · All time · Custom**

Chosen against the presets the request named, with two additions and one substitution worth stating:
"Last 30 days" is there because "last month" is ambiguous between *the previous calendar month* and *the
last thirty days* — so both exist, named so they cannot be confused. "All time" is there because clearing
a date window is a thing people want and an empty pair of inputs does not say it.

A preset resolves to two concrete dates and shows them, rather than staying an abstraction — so the reader
can see that "Last 3 months" means 27 May to 26 August before they trust a figure. Picking Custom opens two
`AppDatePicker` panels side by side.

---

## 4. Frontend — the reports page

Rebuilt around the existing shared components rather than new ones: `TableToolbar` for the search box,
`DataTable` for the rows, `AppPagination` for the pager, `AppSelect` for each declared filter.

The column chooser is a popover of checkboxes — the checkbox work from earlier is what makes a list of
fourteen of them tolerable — with "All" and "None", and a count in the trigger so the state is legible
without opening it. Its selection drives both the table and the download, because a chooser that only
changed the screen would be a display toggle wearing a download control's clothes.

The download control becomes a split: the format is a choice, not three buttons.

---

## 5. Sequence, and what is blocked

| Step | Blocked on |
|---|---|
| 1. `AppDatePicker` + `AppDateRange` | nothing — starting here |
| 2. Backend: filters, search, pagination, column selection | nothing |
| 3. Reports page rebuilt on those | steps 1–2 |
| 4. XLSX + PDF export | **your sign-off on the two dependencies in §2.5** |

Step 4 is the only one that waits. Everything else proceeds; CSV keeps working throughout, so the page is
never in a state where downloading is broken.

## 6. Verification

- `scripts/contrast-audit.py` clean, `npm run build` clean, `mvn -o compile` clean at each step
- The calendar and range driven by **keyboard only** — arrows, PageUp/PageDown, Enter, Escape — since a date
  picker that needs a mouse is the most common way this class of component fails
- The reports page walked at full screen and at 320px, with the accessibility checks from the WCAG audit:
  every control named, one `h1`, no skipped heading levels, nothing clipped
- Each format downloaded and opened: the CSV in a spreadsheet, the XLSX in Excel, the PDF in a viewer —
  including a report whose data contains a comma, a quote and a leading `=`
- Pagination checked against a report with more rows than one page, and the totals confirmed to describe
  **the whole result** rather than the visible page, which is the classic error here
