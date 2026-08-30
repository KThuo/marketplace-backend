-- ═════════════════════════════════════════════════════════════════════════════════════════════════
-- Views for the charts, and the one column every single one of them must carry
--
-- <h2>tenant_id is not optional here</h2>
--
-- `TenantScope.sqlPredicate` builds `<column> IN (...)` and the reporting paths splice it onto a query. A view
-- without a `tenant_id` cannot be scoped, and an aggregate that cannot be scoped is where one seller reads
-- another's sales figures — worse than a row-level leak, because a total does not look like somebody else's
-- data until you work out what it is made of.
--
-- So every view below carries it, including the ones where it seems redundant. A chart whose scope column is
-- missing is a chart that either shows everything or has to be special-cased, and the special case is the one
-- somebody forgets.
--
-- <h2>Months, not raw timestamps</h2>
--
-- Every time series is bucketed with `date_trunc('month', ...)` in the view rather than in the service. The
-- alternative is a service that formats dates and a chart that groups them, which is two places for the
-- boundary of a month to disagree — and the day they disagree is the day a figure moves between columns for
-- no reason anybody can explain.
--
-- <h2>Why views rather than SQL in Java</h2>
--
-- The same reason ReportCatalogue works the way it does: no identifier in these queries comes from a request.
-- A catalogue naming a view and some columns can be checked by reading it; SQL assembled from parameters has
-- to be reasoned about.
-- ═════════════════════════════════════════════════════════════════════════════════════════════════

/*
 * Listings published each month, by what became of them.
 *
 * Published rather than created, because a draft is not activity — a seller who drafted forty listings in
 * January and published none had a quiet January.
 */
CREATE VIEW v_chart_listings_by_month AS
SELECT p.tenant_id,
       date_trunc('month', p.published_at)::date AS bucket,
       p.listing_state                           AS series,
       count(*)                                  AS value
  FROM properties p
 WHERE p.status <> 5
   AND p.published_at IS NOT NULL
 GROUP BY 1, 2, 3;

/* What is on the marketplace right now, by kind. The composition question, not the trend one. */
CREATE VIEW v_chart_listings_by_type AS
SELECT p.tenant_id,
       NULL::date            AS bucket,
       p.property_type       AS series,
       count(*)              AS value
  FROM properties p
 WHERE p.status <> 5
   AND p.listing_state = 'LIVE'
 GROUP BY 1, 3;

/* Enquiries, viewings and offers each month — the top of the funnel, split by which kind it is. */
CREATE VIEW v_chart_leads_by_month AS
SELECT l.tenant_id,
       date_trunc('month', l.created_at)::date AS bucket,
       l.lead_type                             AS series,
       count(*)                                AS value
  FROM v_report_leads l
 WHERE l.created_at IS NOT NULL
 GROUP BY 1, 2, 3;

/*
 * Unit inventory across a seller's developments.
 *
 * The four states as one stacked bar per project, which is the figure a developer opens a dashboard to see:
 * how much of each scheme is left.
 *
 * institution_id is folded in as a second scope column — a bank owns developments without being a tenant, and
 * a view that only understood tenants would show a lender nothing at all.
 */
CREATE VIEW v_chart_unit_inventory AS
SELECT d.tenant_id,
       d.institution_id,
       NULL::date         AS bucket,
       s.series,
       s.value
  FROM developments d
  CROSS JOIN LATERAL (VALUES
        ('Available', d.units_available),
        ('Reserved',  d.units_reserved),
        ('Sold',      d.units_sold)
  ) AS s(series, value)
 WHERE d.status <> 5
   AND d.units_total > 0;

/*
 * Money received each month against bookings.
 *
 * Sums the payments rather than the schedule: what was promised and what arrived are different questions, and
 * this is the one a finance screen asks. Reversals are negative rows, so a plain sum is already net of them.
 */
CREATE VIEW v_chart_payments_by_month AS
SELECT p.tenant_id,
       p.institution_id,
       date_trunc('month', p.paid_on)::date AS bucket,
       'Received'                           AS series,
       sum(p.amount)                        AS value
  FROM booking_payments p
 WHERE p.status <> 5
 GROUP BY 1, 2, 3;

/*
 * Where every live booking stands.
 *
 * Reserved against agreed is the conversion a sales manager watches; lapsed and cancelled kept apart because
 * a clock and a decision are different failures and the remedies differ.
 */
CREATE VIEW v_chart_bookings_by_state AS
SELECT b.tenant_id,
       b.institution_id,
       NULL::date  AS bucket,
       b.state     AS series,
       count(*)    AS value
  FROM unit_bookings b
 WHERE b.status <> 5
 GROUP BY 1, 2, 4;

COMMENT ON VIEW v_chart_listings_by_month IS
    'Every chart view carries tenant_id because TenantScope.sqlPredicate splices onto exactly that column. '
    'An aggregate that cannot be scoped is where one seller reads another''s figures.';
