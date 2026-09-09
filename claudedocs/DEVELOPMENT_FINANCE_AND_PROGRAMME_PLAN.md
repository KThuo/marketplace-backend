# Developments: where the money went, and when it will be finished

**The complaint:** opening a development says almost nothing about how the project is consuming funds or
whether it will finish when it said it would. The detail page shows a budget, a facility amount, one
percentage and a list of phases with dates. It cannot answer the questions the people running and financing
a build actually ask.
**Scope of this document:** what the schema and screens already record, what is missing, and three ways to
close the gap — with a recommendation and the decisions that are the user's to make before anything is built.
**Touches (if the recommendation is taken):** `modules/developments` (a new ledger, two views, a finance
service), `modules/analytics` (development-scoped charts), `modules/reports` (one report), the enums and
seeder; on the client the development detail page gains a finance and programme section, a ledger, and three
charts.

## 1. The questions the screen has to answer

Written down first, because the gap analysis is measured against them. These are what a developer's finance
lead and a bank's credit officer ask of a financed project, in roughly the order they ask them.

| # | Question | Answerable today? |
|---|---|---|
| Q1 | How much of the budget has been **spent**, how much is **committed** but not yet paid, and how much is **left**? | Partly — per phase, as a typed number, with no history |
| Q2 | Are we spending **faster or slower than planned** at this point in the build? | No — `planned_spend` exists per phase but nothing compares the two or plots them |
| Q3 | How much of the **facility has been drawn**, and how much is undrawn? | No — only the facility's size is recorded |
| Q4 | **Where did the money go** — land, construction, fees, finance costs, marketing? | No — spend is one number per phase |
| Q5 | **When will it finish**, and how far has that moved from what was promised? | Partly — phases carry planned, revised and actual dates and a slippage figure, but nothing rolls them up |
| Q6 | Which phases are **late right now**, and by how much? | Computed per phase, shown only as a footnote |
| Q7 | How has **percent complete moved over time**? | Recorded on stakeholder progress posts, never charted |
| Q8 | What has the project **earned**: units contracted, deposits collected, receivables outstanding and overdue? | The data exists (bookings, payments, `v_booking_balances`) but is not rolled up to the development |
| Q9 | Is the project **self-funding** — collections against spend — or leaning on the facility? | No |
| Q10 | Who recorded a spend figure, when, against what evidence? | No — a typed column, overwritten |

## 2. What is already here

### 2.1 Data

**Development** (`developments`): `budget_amount`, `facility_reference`, `facility_amount`, `started_on`,
`projected_completion_on`, `actual_completion_on`, `percent_complete` with `percent_basis`,
`construction_status`, the unit counters, `from_price`/`to_price`. All hand-typed except the counters and the
percentage, which `DevelopmentInventoryService` derives from phases and units.

**Phase** (`development_phases`): three completion dates (planned, revised, actual — the slippage design is
already right), `budget_amount`, `planned_spend`, `committed_amount`, `spent_amount`, `weight_pct`,
`percent_complete`, `milestone_code`, `planned_unit_count`. The money columns are typed into `PhaseEditor`
and overwritten in place. The migration comment on `planned_spend` says why it exists: "the planned-versus-
actual spend curve a lender judges a project by needs two series". The series were provisioned; the curve was
never drawn.

**Progress updates** (`progress_updates`): dated posts that may carry `percent_complete` and a
`milestone_code`, scoped to a development, phase or unit, with a `STAKEHOLDERS` audience for the detailed
ones. This is the only time series of completion the platform holds.

**Sales side**: `unit_bookings` (price agreed, schedule), `payments` (now with balance snapshots and channel),
and `v_booking_balances` (scheduled, paid, balance, overdue, next due per booking). Everything needed for Q8
is in the database; nothing sums it per development.

**Analytics**: `v_chart_unit_inventory`, `v_chart_bookings_by_state`, `v_chart_payments_by_month` — all
organisation-wide, none scoped to one development. `ChartService.draw(key)` takes no subject.

### 2.2 Screens

`DevelopmentDetailView` → "Programme and finance": started, expected, percent with its basis, budget,
facility, and a phase list showing percent and dates with a slippage note. No spend, no committed, no
facility utilisation, no sales, no charts. `PhaseEditor` collects the four money figures, so people *can*
type them and then never see them again except by reopening the editor.

### 2.3 Permissions — a gap worth naming before building more

Budget and facility are on `DevelopmentResponse`, which anyone with `DEVELOPMENTS_VIEW` who can see the
development receives — including a **collaborator** granted progress rights. A contractor posting site
photographs on a bank's project can read the bank's facility size. Adding a spend ledger and drawdowns makes
this worse, not better, so money gets its own permission before it gets more data (§4.4).

## 3. The gap, in one table

| Need | Have | Missing |
|---|---|---|
| Spend over time (Q1, Q2, Q10) | One typed `spent_amount` per phase | A dated ledger: who, when, how much, against what |
| Spend by category (Q4) | — | A category on each ledger line |
| Facility utilisation (Q3, Q9) | `facility_amount` | Dated drawdowns |
| Planned-vs-actual curve (Q2) | `planned_spend` per phase | A time series to compare it against, and the chart |
| Schedule summary (Q5, Q6) | Dates and `slippageDays` per phase | A roll-up: target date, forecast, days remaining, phases late, overall slippage |
| Completion over time (Q7) | Percent on progress posts | A chart |
| Sales roll-up (Q8, Q9) | Bookings and payments | A per-development view: contracted, collected, receivable, overdue, expected by month |
| A place to see all of it | Five `<dt>` rows and a phase list | A finance and programme section with figures, a ledger and charts |
| Who may see money | `DEVELOPMENTS_VIEW` | A finance permission, and a collaborator grant that excludes it |

## 4. Three ways to close it

### Option A — read-only roll-up over what is typed today

No schema change. A `DevelopmentFinanceService` sums the phase columns into budget / planned to date /
committed / spent / remaining, computes facility-versus-spent, rolls the dates up into target, forecast,
days remaining and phases late, and joins `v_booking_balances` for the sales side. One new section on the
detail page and a per-phase money table.

*Answers* Q1, Q3 (approximately), Q5, Q6, Q8. *Cannot answer* Q2 as a curve, Q4, Q7 as a chart, Q9
honestly, Q10 at all — because spend remains a snapshot somebody overwrites.

*Cost:* about two days. *Verdict:* worth doing first whichever option is chosen, because every figure in it
is real today. Not sufficient on its own: "how the project has been consuming funds" is a question about
time, and a snapshot has none.

### Option B — a cost ledger and drawdowns, with the phase figures derived from them (recommended)

Two new tables and the phase money columns become **derived**, the way the unit counters already are.

**`development_expenditures`** — one row per cost event.
`development_id`, `phase_id` (nullable: a project-level cost), `category` (`LAND`, `CONSTRUCTION`,
`PROFESSIONAL_FEES`, `FINANCE_COSTS`, `STATUTORY`, `MARKETING`, `CONTINGENCY`, `OTHER`), `kind`
(`COMMITTED` — a contract or certificate signed; `SPENT` — money out), `amount`, `currency`, `incurred_on`,
`payee`, `reference` (invoice or certificate number), `notes`, optional `document_id` into the vault, the
usual status pair and audit columns. Never edited: a wrong line is voided with a reason, exactly as a payment
is, because a cost report a lender has read must not quietly change.

**`facility_drawdowns`** — one row per disbursement against the facility: `development_id`, `amount`,
`drawn_on`, `reference`, `notes`, voidable the same way. Drawn and undrawn are then sums, not guesses.

**Derivation.** `phases.committed_amount` and `phases.spent_amount` stop being typed. `DevelopmentInventoryService`
(the existing single writer of counted columns) recounts them from the ledger whenever a line is written or
voided, and `budget_amount` and `planned_spend` stay as the plan the actuals are measured against. The
migration writes one `SPENT` and one `COMMITTED` opening line per phase from the figures already typed,
dated at the phase's actual start or the development's, with a reference of `OPENING`, so nothing anybody
entered is lost and the ledger's total equals the old column on day one.

**Views.**
- `v_development_finance` — one row per development: budget, planned spend to date (phases whose planned
  completion has passed, plus a pro-rata of the one in progress), committed, spent, remaining, facility,
  drawn, undrawn, contracted sales value, collected, receivable, overdue, and `tenant_id` /
  `institution_id` so `TenantScope.sqlPredicate` and the development visibility rule can both scope it.
- `v_development_spend_by_month` — cumulative planned versus actual, bucketed by month, the two series the
  phase table was designed to hold.
- `v_development_collections_by_month` — receipts against bookings on this development, so Q9 is the two
  lines on one chart.

**Charts.** Three, on the detail page, scoped to one development: spend curve (planned vs actual),
funding position (spent, drawn, collected as three bars), completion over time (from stakeholder progress
posts). `ChartService.draw` gains an optional subject — a development id — checked through
`DevelopmentVisibility`, so the existing `AppChart` component renders them unchanged.

**Report.** `DEVELOPMENT_FINANCE` in `ReportCatalogue` over `v_development_finance`: one row per project
for a lender's whole book.

**Screen.** The "Programme and finance" section becomes: a figure row (budget · spent · committed ·
remaining · facility drawn/undrawn · collected · receivable), a schedule row (target · forecast · days
remaining · phases late · slippage), the three charts, the phase table with its money columns and variance,
then two tabs beneath — Expenditure (the ledger, with Record a cost and Void) and Drawdowns.

*Answers* Q1–Q10. *Cost:* five to seven days on top of Option A. *Verdict:* this is the one. It is the
design the phase table already anticipates, it reuses the void-not-edit rule the payments module just
established, and it turns "how has this project been consuming funds" from a snapshot into a series.

### Option C — full project cost control

Budget lines per phase per category with approvals, purchase orders and payment certificates, valuer-signed
progress certificates gating each drawdown, retention, variations. This is what a quantity surveyor's
package does and what a bank's project-finance desk may eventually want. *Verdict:* out of scope now. The
ledger in Option B is the foundation it would sit on, and nothing in B has to be undone to get there.

## 5. Recommended sequence

1. **Permission first.** `DEVELOPMENTS_FINANCE_VIEW` and `DEVELOPMENTS_FINANCE_RECORD` in the
   `DEVELOPMENTS` module; budget, facility and everything in §4 move behind the first, and a collaborator
   grant does not confer it. Small, and it has to precede the data.
2. **Option A's roll-up**, served from a `DevelopmentFinanceService` and shown on the detail page — real
   figures within two days, and the screen the later work lands on.
3. **The ledger and drawdowns** (Option B), with the migration that turns typed figures into opening lines
   and makes the phase columns derived.
4. **Views, charts and the report.**
5. **Dashboard cards** for lender staff — projects late, projects over budget — last, because they read the
   views and nothing else.

## 6. Decisions that are the user's to make

These change what gets built, so they are asked rather than assumed:

1. **Who records spend?** The owner's finance staff, the developer as a collaborator, or both? If the
   developer, `development_collaborators` needs a `FINANCE_WRITE` grant beside the existing progress and
   units grants.
2. **Is "funds consumed" the developer's expenditure, the lender's disbursements, or both?** Option B models
   both because they answer different questions (Q1 versus Q3); if only one matters, the other table can wait.
3. **Do the typed phase figures retire?** Recommended yes — one writer, derived from the ledger — with the
   opening-line migration so nothing is lost. The alternative is keeping them as an override, which is two
   answers to one question.
4. **Categories.** The eight proposed in §4 are a starting list; the platform can hold them as configuration
   the way it holds progress milestones, so a developer's own vocabulary is not refused.
5. **Evidence.** Should a cost line or a drawdown carry a document — invoice, certificate, disbursement
   advice — into the existing vault? Cheap to add now, awkward to add later.

## 7. Out of scope, and why

- Approvals on cost lines (Maker/Checker) — Option C territory.
- Foreign-currency costs — every figure here is in the development's currency.
- Interest accrual on the facility — a finance-cost line records what was actually charged; computing it is
  the bank's system's job.

## 8. Delivered (8 September 2026)

Decisions taken: (1) anyone holding the record permission who can see the development records spend — no
new collaborator grant kind; (2) both the developer's expenditure and the lender's drawdowns are modelled;
(3) the typed phase committed/spent figures retired, turned into opening lines; (4) categories are platform
configuration, add/rename/suspend; (5) evidence goes into the vault.

**Backend** — migration `V20260908150000__development_finance.sql` (`development_cost_categories`,
`development_expenditures`, `facility_drawdowns`, `v_development_finance`, `v_chart_dev_spend`,
`v_chart_dev_funding`, `v_chart_dev_completion`; `development_id` added to the three existing development
charts). Permissions `DEVELOPMENTS_FINANCE_VIEW`, `DEVELOPMENTS_FINANCE_RECORD`, `COST_CATEGORIES_MANAGE`.
`security/OwnerScopeSql` is the one owner predicate for JDBC reads; `ChartService` and owner-scoped reports
use it. `DevelopmentFinanceService`/`Controller` under `/api/v1/developments/{id}/finance/…`;
`CostCategoryService`/`Controller` under `/api/v1/cost-categories`. `DevelopmentInventoryService.
recountPhaseMoney` is the only writer of a phase's committed/spent. `DevelopmentService` hides and refuses
to overwrite budget/facility for callers without the finance permission. `DocumentService.readTrusted` reads
evidence for a caller the finance service has already checked. Report `DEVELOPMENT_FINANCE`. Charts
`dev-spend`, `dev-funding`, `dev-completion` (subject-only); `/api/v1/charts/{key}?developmentId=`.
`DevelopmentFinanceIT` covers recount-on-void, the summary against the view, recording rules, evidence
through the development, cross-organisation not-found, and the lender's scope.

**Frontend** — `DevelopmentFinancePanel` on the development page (figures, schedule sentence, three charts,
phase-by-phase table, Costs and Drawdowns tabs with record, void and evidence), `RecordCostModal`,
`RecordDrawdownModal`, `CostCategoriesView` at `/cost-categories` (Platform nav), `services/
developmentFinance.ts`, `chartApi.find(key, developmentId)`. `PhaseEditor` no longer takes committed or
spent.

Not done from §5: the lender dashboard cards (step 5) — folded into the dashboard and analytics work in
`DASHBOARD_AND_ANALYTICS_PLAN.md`.
