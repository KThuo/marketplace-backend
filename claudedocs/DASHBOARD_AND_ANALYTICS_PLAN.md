# Dashboard and analytics: from a row of counters to a view of the business

**The complaint:** the dashboard is a row of access-management counters — organisations, staff, partnerships,
live sessions — and the analytics page is six charts from a catalogue with no window, no comparison and no
drill-down. Neither says how the business is doing.
**Reference:** `../../new-hodi` — `com.hodi.analytics` (`DashboardService`, `DashboardQueries`,
`AnalyticsService`, `AnalyticsWindow`, `AnalyticsScope`, the money and portfolio queries) and on the client
`pages/dashboard/DashboardPage.vue`, `pages/analytics/AnalyticsPage.vue`, `components/analytics/KpiTile.vue`.
**Touches:** `modules/dashboard`, `modules/analytics`, a new `security/OwnerScopeSql`; on the client the
dashboard and analytics pages, a KPI tile, and the chart service.

## 1. What is worth borrowing, and what is not

new-hodi runs a rental business: its dashboard is invoiced, collected, spent and arrears; its analytics is a
window of months read against the window before, cut by charge, by channel, by tenure and by age of debt.
This platform sells homes off-plan and finances their construction. The *shapes* carry across exactly; the
*figures* do not, and the translation is the whole of the work.

| new-hodi | Here |
|---|---|
| Invoiced | **Contracted** — the price agreed on live and completed bookings |
| Collected | **Collected** — payments received, voided excluded |
| Spent (estate expenses) | **Spent** — the development cost ledger (§ finance plan) |
| Arrears | **Receivable** and **overdue** — from `v_booking_balances` |
| Occupancy rate, occupied/vacant/total | **Sales rate** — sold and reserved against total units |
| Payment collections table | The same: the month's receipts, paged |
| Twelve-month calendar of receipts | The same |
| Composition by charge / channel / tenure | **Collections by payment type**, **spend by cost category**, **units by state** |
| Arrears by age, worst tenancies | **Receivables by age of the oldest unpaid instalment**, worst bookings |
| Property comparison table, movers | **Development comparison** — units, contracted, collected, receivable, budget, spent, drawn, percent complete, slippage; movers by collected |
| Operations: maintenance, visits, stays | **Pipeline** — enquiries, viewings and offers, from the lead tables |
| Platform overview (HODI's own revenue) | Not carried: the platform's revenue here is commission, and that module has its own screen. Platform staff get the same dashboard and analytics, unscoped, plus the existing platform cards. |

Three rules from the reference are kept verbatim because they are the reason its screens are trusted:

- **Nothing is stored, generated overnight or cached.** Every figure is a sum over bookings, payments, the
  cost ledger and the units when the page asks, so it cannot disagree with the lists behind it and there is
  no refresh button.
- **Each dashboard card owns its period; the analytics page shares one window.** Overall is all time or a
  year, the summary a month, the calendar a year. On analytics every panel answers the same question about
  the same months, and every KPI is read against the window before, of equal length.
- **Scope becomes SQL in exactly one place.** The failure this prevents has already happened here once:
  `ChartService.scope` splices the caller's *tenant* ids into an `institution_id IN (…)` predicate, which
  is wrong for every lender. A single `OwnerScopeSql` replaces it — platform sees all; a lender sees its own
  institution's rows and its partnered sellers'; a seller sees its own, the developments it markets and the
  ones it has been granted — and the dashboard, the analytics and the charts all call it.

## 2. What is built

### Backend

- `security/OwnerScopeSql` — the predicate above, over a table's `tenant_id`, `institution_id` and optional
  `development_id` columns. `ChartService` switches to it.
- `modules/analytics/AnalyticsWindow` — the reference's window, ported: inclusive months, `previous()` of
  equal length, sixty-month cap, defaults to the last twelve.
- `modules/analytics/AnalyticsQueries` — JDBC, every query scoped through `OwnerScopeSql`, every value
  bound. Totals over a window, monthly trend, the three compositions, receivable ageing and the worst
  bookings, the development comparison, the pipeline.
- `modules/analytics/AnalyticsService` + controller at `/api/v1/analytics/{summary|trend|composition|
  receivables|developments|pipeline}`, each taking the window and an optional development.
- `modules/dashboard/DashboardService` — keeps the audience cards it has (they are the only place the
  stranded-lender and unverified-buyer facts are said in words) and gains `overall`, `monthly` and
  `calendar`, each its own endpoint under `/api/v1/dashboard`.

### Frontend

- `DashboardView` — the cards row stays; below it Overall (contracted · collected · spent · receivable, with
  a year select), Monthly Summary (Sales performance with its rate and thresholds; Cash flow with in, out and
  net; the Collections table with a pager), and the Calendar. A development filter narrows all three.
- `AnalyticsView` — a from/to month window and a development filter pinned at the top; a KPI strip of tiles
  each with its move against the previous window and a sparkline of the window's months; the trend chart;
  then tabs: Money, Receivables, Developments, Pipeline. The catalogue charts stay at the bottom as "More
  charts".
- `components/analytics/KpiTile.vue` — the reference's tile, without its UI library: label, formatted value,
  change chip that knows whether up is good, hand-drawn SVG sparkline.

## 3. Out of scope

- A stored monthly report table and a recompute job (the reference's `property_reports`). Nothing here is
  large enough to need one; the day it is, the queries in `AnalyticsQueries` are what the job would run.
- Exports of the analytics panels. The report centre exports rows; a development finance report is added
  with the finance work and covers the comparison table's columns.
- A platform revenue overview. Commission has its own module and screen.

## 4. Delivered (8 September 2026)

**Backend** — `modules/analytics/AnalyticsWindow` (inclusive months, `previous()` of equal length, sixty-month
cap, defaults to the last twelve; bad input is a 400). `AnalyticsViews` (the records). `AnalyticsQueries`
(JDBC; every query starts from `OwnerScopeSql`, every window edge, development filter and page size bound):
totals over a window or a year or all time, today's positions (receivable, overdue, unit tallies, projects
late / over budget, budgets, facilities), monthly trend with quiet months present, collections by payment
type, spend by cost category, units by state, receivable ageing by the oldest unpaid instalment, the worst
bookings, the development comparison from `v_development_finance` with collections in the window, the
pipeline (tenant-scoped: leads sit on listings), the month's receipts paged, and the twelve-month calendar.
`AnalyticsService`/`AnalyticsController` at `/api/v1/analytics/{summary,trend,composition,receivables,
developments,pipeline}` behind `DASHBOARD_VIEW`; a development filter is checked through
`DevelopmentVisibility` and is not-found when the caller may not see it. `DashboardService` gains
`overall`, `monthly` and `calendar` (`/api/v1/dashboard/{overall,monthly,calendar}`) and three project cards
(late, over budget, buyers behind) for holders of `DEVELOPMENTS_FINANCE_VIEW`. `ChartService` already reads
`OwnerScopeSql`. Tests: `AnalyticsWindowTest`, `AnalyticsIT` (window arithmetic against the ledger, the
comparison, another seller's exclusion, the lender's scope, the dashboard's month and calendar).

**Frontend** — `AnalyticsView` rebuilt: from/to month window and development filter; a KPI strip read
against the previous window with sparklines; a position strip; the trend chart; tabs Money (three donuts and
the budget/facility tiles), Receivables (ageing bars, who owes most), Developments (comparison table with
totals, movers), Pipeline; the catalogue charts at the bottom as "More charts". `DashboardView` keeps the
cards and gains Overall (year select), Monthly summary (sales performance with rate bar, cash flow in/out/net,
paged receipts) and Calendar (bar chart plus twelve month tiles), all narrowed by one development filter.
`components/analytics/KpiTile.vue` (tone, change chip that knows whether up is good, hand-drawn sparkline),
`DevelopmentFilter.vue`. `services/analytics.ts` gains `analyticsApi` and `dashboardFiguresApi`.

Not carried from §2: a separate `trend` fetch on the page (the summary already carries the window's months,
so the trend chart is drawn from it; the endpoint exists for a client that wants the points alone).
