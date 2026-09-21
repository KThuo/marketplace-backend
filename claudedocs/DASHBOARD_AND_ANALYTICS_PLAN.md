# A dashboard that says what needs doing, and analytics that answer the questions a bank asks

**Date:** 21 September 2026 · **Branch:** `feature/coop-bank` (backend and frontend)

## 1. What exists

The two pages are already real, and nothing here throws them away.

**Dashboard** (`/app`, `DASHBOARD_VIEW`): a greeting; a row of audience cards assembled server-side
(platform: seller organisations, suspended, staff, live sessions, plus projects late / over budget / buyers
behind when non-zero; seller: your team; buyer: account state); then three figure panels each with its own
period — *Overall* (all time or a year: contracted, collected, spent, drawn, receivable, sales rate),
*Monthly summary* (the month's totals, cash flow, and a paged table of its receipts), *Calendar* (twelve months
of collected and spent) — and the chart catalogue underneath.

**Analytics** (`/app/analytics`, `DASHBOARD_VIEW`): one window (twelve months by default) and a development
filter; five headline figures each read against the previous window; a where-things-stand strip; a
contracted-vs-collected trend; four tabs — *Money* (collections by type, spend by category, units by state),
*Receivables* (ageing, who owes most), *Developments* (side by side, who moved), *Pipeline* (enquiries,
viewings by outcome, offers by state); and the catalogue of nine declared charts.

Everything is summed live through `AnalyticsQueries`, scoped by `OwnerScopeSql`, nothing cached. That
architecture is right and stays.

## 2. What is missing

1. **Nothing says what needs doing.** The dashboard is figures with filters. The modules built this month all
   produce work for a person — bank credits nobody has placed, approvals waiting, disbursements awaiting
   release, prompts the bank never answered, offers awaiting a decision, viewings to confirm, holds about to
   lapse, buyers behind — and none of it is on the page a person lands on.
2. **The new modules are invisible.** Statements, disbursements, payment prompts and offer conversion have
   no figure anywhere.
3. **Analytics stops at money in and money out.** It cannot answer: how well do we collect against what is
   due; how many enquiries become sales and how long that takes; how fast are units selling and how much
   stock is left; how much of the bank's money arrives matched and how quickly; what left the bank.
4. **No hierarchy.** Every dashboard panel has the same weight, and the month's receipts table duplicates
   the payments page on the one screen every session opens.

## 3. The design

### 3.1 Dashboard — "today"

Server-assembled per audience as now; every block is present only when the caller holds the permission
that would let them act on it. The buyer's dashboard is out of scope: buyers land on the marketplace.

```
┌──────────────────────────────────────────────────────────────────────────────┐
│ Good morning, Eric.            [development ▾]                               │
│ KES 12.4M collected in September   ▲ 18% on August                           │
├────────────────────────────────┬─────────────────────────────────────────────┤
│ Needs you                      │ This month against last                     │
│ ● 4 bank credits unplaced      │ Collected · Contracted · Receivable ·       │
│   KES 1.2M, oldest 6 days ago  │ Overdue · Spent · Drawn   (tiles, deltas)   │
│ ● 2 approvals await you        ├─────────────────────────────────────────────┤
│ ● 1 transfer awaiting release  │ Twelve months: contracted vs collected      │
│ ● 3 offers awaiting a decision │ (line chart)                                │
│ ● 7 buyers behind, KES 3.1M    │                                             │
│ ● 2 holds lapse this week      ├──────────────────────┬──────────────────────┤
│ ● 1 prompt unanswered          │ Recent receipts (6)  │ Inventory (donut)    │
│                                │                      │ Funnel this month    │
└────────────────────────────────┴──────────────────────┴──────────────────────┘
```

- **Hero**: the one number this audience runs on — money collected this month — with the delta on last
  month. Left-aligned display type; no gradient, no decoration.
- **Needs you**: a list, not tiles. Each line a count, a sentence, the money where money is the point, and
  a link to the screen that clears it. Items, each gated:
  unplaced bank credits (`STATEMENTS_VIEW`), approvals awaiting *my* decision (`APPROVALS_VIEW`),
  disbursements awaiting release or out past their deadline (`DISBURSEMENTS_VIEW`), prompts unanswered past
  their deadline (`PAYMENTS_VIEW`), offers awaiting a decision (`PURCHASE_REQUESTS_DECIDE`), viewings to
  confirm (`SITE_VISITS_DECIDE`), buyers with an instalment overdue (`BOOKINGS_VIEW`), holds lapsing within
  seven days (`BOOKINGS_VIEW`), listings awaiting approval and seller applications pending (platform),
  projects late / over budget (`DEVELOPMENTS_FINANCE_VIEW`, as today). An empty list says so in one line.
- **This month against last**: six `KpiTile`s with `Figure` deltas — the strip the analytics summary already
  computes, for one month.
- **Twelve months**: the existing trend query, drawn once.
- **Recent receipts**: the six latest, linking to receipts; the paged table goes.
- **Inventory** donut and **funnel this month** (enquiries → viewings → offers → bookings, counts only).
- **What moves to analytics**: *Overall* and *Calendar* become the analytics *Calendar* tab; *Monthly
  summary*'s receipts table is the payments page. The development filter stays on the dashboard.

### 3.2 Analytics — questions, not panels

Window and development filter as now. Tabs become questions:

| Tab | What it answers | New queries |
|---|---|---|
| **Money** (keep) | What came in, went out, what it was made of | — |
| **Collections** (new) | How well we collect what is due | due vs collected by month (from `booking_instalments`); on-time vs late share; median days late; channel mix by month (stacked); prompts sent / paid / failed / unanswered by month with success rate |
| **Receivables** (keep + one) | Who owes, how old, what is expected | expected in 30 / 60 / 90 days from the schedule |
| **Sales funnel** (replaces Pipeline) | How many enquiries become sales, and how long | counts per stage in the window; conversion between stages; median days enquiry → viewing → offer → booking; offers accepted / declined / withdrawn / converted |
| **Inventory** (new) | How fast units sell and how much is left | units sold per month; absorption rate; months of stock at current pace; availability by development and type; price per m² by development |
| **Bank** (new, platform only) | How the bank's money behaves | statements by month split matched automatically / by hand / set aside / unplaced; median hours arrival → placed; disbursements by month and state; net flow in vs out |
| **Developments** (keep) | Side by side | — |
| **Calendar** (moved) | Twelve months of receipts and spend, any year | — |

Every table on the page gets **Download CSV**, built client-side from the loaded rows: no server change, no
new permission, and the reports module keeps the heavy exports.

### 3.3 What does not change
- Scope: every new sum goes through `OwnerScopeSql`; no request input is spliced. Bank-only tabs are refused
  server-side, not hidden client-side.
- Live sums, no cache, no nightly job. The tables involved are small for years yet; if a query slows, it
  gets a view like the chart views, not a cache.
- The chart component, palette and text alternatives. New charts use `ChartData` through `AppChart`.
- The audience-card mechanism: "Needs you" is assembled the same way, in `DashboardService`.

## 4. Steps

| # | Step | Backend | Frontend | Size |
|---|---|---|---|---|
| 0 | Demo activity for the development database: twelve buyers, nine months of bookings paying on time, late and not at all, every non-cash payment with its bank statement and prompt, unplaced and set-aside credits, failed and unanswered prompts, offers in every state (two converted), viewings, enquiries, transfers out, project spend and drawdowns | `DemoActivitySeeder` behind `hodi.seed.demo=true` (`SEED_DEMO`), refuses a non-local datasource, runs once | — | M |
| 1 | Dashboard "today": hero, Needs you, month strip, twelve-month chart, recent receipts, inventory, funnel | `DashboardService.attention()` + `hero()` (one endpoint, gated items); funnel-this-month and inventory from existing queries | `DashboardView` rebuilt; Overall/Monthly/Calendar panels removed | L |
| 2 | Analytics: Collections and Sales funnel tabs | `AnalyticsQueries`: due-vs-collected, lateness, channel mix by month, prompt outcomes, funnel stages and durations, offer outcomes | two tabs, CSV download on every table | L |
| 3 | Analytics: Inventory and Bank tabs | absorption, stock, price per m², statements by outcome and time-to-place, disbursements by month | two tabs | M |
| 4 | Analytics: Calendar tab (Overall + Calendar moved), expected receivables | reuse | one tab, one strip | S |

Each step: integration tests on the new queries with fixtures (bookings, payments, statements, prompts,
offers), full suite green, `vue-tsc` and build green, commit, jar and `dist.zip` rebuilt.

## 5. Two things to know before starting

- **The dev database was thin** — one booking, two payments — so step 0 seeds nine months of realistic
  activity (agreed 21 September). Run the backend once with `SEED_DEMO=true`; it writes once and refuses
  any datasource that is not on the machine.
- **The Monthly receipts table leaves the dashboard** (agreed 21 September): it duplicates the payments page.

## 6. Progress

| Step | State | Notes |
|---|---|---|
| 0 | **Done, 21 September** | `DemoActivitySeeder` (backend `c1186b1`): 12 buyers, 28 bookings, 69 payments (KES 110M) each with statement and prompt where not cash, 4 unplaced and 2 set-aside credits, 8 stray prompts, 14 offers (2 converted), 16 viewings, 20 enquiries, 5 transfers, 17 spend lines, 5 drawdowns. Run once with `SEED_DEMO=true`. |
| 1 | **Done, 21 September** | `GET /dashboard/today` assembles the page per caller: `MonthFigures` against the month before, `Positions`, a gated `Attention` list from `DashboardAttentionQueries` (unplaced credits, buyers behind, transfers awaiting release and unanswered, approvals not the caller's own, prompts unanswered, holds lapsing in 7 days, offers, viewings, enquiries, listings pending, seller applications, KYC packs, projects late / over budget), twelve months of trend, six recent receipts, units by state, the month's funnel. `DashboardTodayIT` (3) checks every line against the list behind its link and that lines are gated. Frontend `DashboardView` rebuilt; Overall, Monthly, Calendar and the receipts table removed. |
| 2 | **Done, 21 September** | `AnalyticsFlowQueries`: due vs collected by month from the current plan's instalments; lateness by reading each payment against the instalment its running total first reaches (3 days' grace; median days late); channel mix by month; prompts by month and outcome. Funnel: stage counts with conversion from the stage before, median days enquiry→viewing, viewing→offer, offer→booking, enquiry→booking tied by person and home, offers by outcome and converted, viewings by outcome. `GET /analytics/collections`, `GET /analytics/funnel`. `AnalyticsFlowIT` (3) checks each figure against its SQL and that the parts add up. Frontend: Collections and Sales funnel tabs replace Pipeline; `utils/csv.ts`; every table downloads as CSV. |
| 3 | **Done, 21 September** | `AnalyticsStockQueries`: units sold and bookings by month, the stock by development and kind with price per m², hold outcomes; statements by month split placed-by-matcher / by hand / set aside / unplaced, median minutes from arrival to placing, transfers by month and outcome, money in against confirmed money out. `GET /analytics/inventory`, `GET /analytics/bank` (refused to anyone but the platform inside the service). `AnalyticsStockIT` (3). Frontend: Inventory tab for everyone, Bank tab for platform staff, CSV on every table and chart. |
