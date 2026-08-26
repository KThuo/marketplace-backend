-- ═════════════════════════════════════════════════════════════════════════════════════════════════
-- Phase 5 / M15 — MIS and reporting (plan §4: "reporting views only")
--
-- Views rather than tables, and that is the whole design decision. A reporting table is a second copy of
-- the truth that has to be kept in step with the first; a view is the first, shaped differently. Nothing
-- here can drift, because there is nothing here to drift.
--
-- <h2>Every view carries its scoping column</h2>
--
-- `tenant_id` is on all of them, even where it took a join to get it, because `TenantScope.sqlPredicate`
-- splices a predicate on exactly that column. A reporting view without one would be a view somebody has to
-- remember to scope — and reporting is precisely where "somebody forgot" turns into one organisation
-- reading another's figures.
--
-- <h2>Why the platform still reads the transactional database</h2>
--
-- Plan §4 says a read replica or warehouse, "not the transactional DB". That remains right and remains
-- Phase 7's job: these views are the shape the reports want, and pointing them at a replica later is a
-- connection-string change rather than a rewrite. Naming them now is what makes that true.
-- ═════════════════════════════════════════════════════════════════════════════════════════════════

-- What is on the marketplace, and what became of it.
CREATE VIEW v_report_listings AS
SELECT p.id                AS property_id,
       p.reference,
       p.title,
       p.tenant_id,
       p.tenant_name,
       p.property_type,
       p.listing_type,
       p.county,
       p.town,
       p.price,
       p.currency,
       p.listing_state,
       p.promotion_boost > 0 AS promoted,
       p.created_at        AS drafted_at,
       p.published_at,
       p.sold_at,
       p.withdrawn_at,
       -- How long it took to sell, in days. Null while it has not.
       CASE WHEN p.sold_at IS NOT NULL AND p.published_at IS NOT NULL
            THEN EXTRACT(day FROM (p.sold_at - p.published_at))::int END AS days_to_sell
FROM properties p
WHERE p.status <> 5;

-- Every lead, from three tables, in one shape. What a funnel is built on.
CREATE VIEW v_report_leads AS
SELECT 'ENQUIRY'::varchar(24) AS lead_type,
       e.reference,
       e.tenant_id,
       e.tenant_name,
       e.property_reference,
       e.property_title,
       e.state,
       e.assigned_to_name     AS handled_by,
       e.created_at
FROM enquiry_tickets e WHERE e.status <> 5
UNION ALL
SELECT 'SITE_VISIT', v.reference, v.tenant_id, v.tenant_name, v.property_reference, v.property_title,
       v.state, NULL, v.created_at
FROM site_visits v WHERE v.status <> 5
UNION ALL
SELECT 'OFFER', o.reference, o.tenant_id, o.tenant_name, o.property_reference, o.property_title,
       o.state, NULL, o.created_at
FROM purchase_requests o WHERE o.status <> 5;

-- What the platform earned, and where it stands.
CREATE VIEW v_report_commission AS
SELECT c.reference,
       c.tenant_id,
       c.tenant_name,
       c.property_ref,
       c.property_title,
       c.sale_price,
       c.rate_percent,
       c.amount,
       c.currency,
       c.state,
       c.sold_at,
       c.invoiced_at,
       c.paid_at
FROM commission_records c
WHERE c.status <> 5;

-- What placement was bought, and whether it ran.
CREATE VIEW v_report_promotions AS
SELECT lp.reference,
       lp.tenant_id,
       lp.tenant_name,
       lp.property_ref,
       lp.property_title,
       lp.package_name,
       lp.placement,
       lp.price,
       lp.currency,
       lp.duration_days,
       lp.state,
       lp.starts_at,
       lp.ends_at,
       lp.created_at
FROM listing_promotions lp
WHERE lp.status <> 5;

-- Valuation turnaround: the question a panel manager is asked every month.
CREATE VIEW v_report_valuations AS
SELECT vr.reference,
       vr.tenant_id,
       vr.property_reference,
       vr.property_title,
       vr.valuer_name,
       vr.state,
       vr.created_at    AS requested_at,
       vr.assigned_at,
       vr.completed_at,
       CASE WHEN vr.completed_at IS NOT NULL
            THEN EXTRACT(day FROM (vr.completed_at - vr.created_at))::int END AS days_to_complete
FROM valuation_requests vr
WHERE vr.status <> 5;

-- What went under the hammer, and for how much.
CREATE VIEW v_report_auctions AS
SELECT al.reference,
       al.tenant_id,
       al.tenant_name,
       al.title,
       al.county,
       al.auctioneer_name,
       al.guide_price,
       al.reserve_price,
       al.sold_price,
       al.currency,
       al.state,
       al.auction_date,
       al.sold_at,
       -- What it fetched against the guide, as a percentage. The number an auctioneer is judged on.
       CASE WHEN al.sold_price IS NOT NULL AND al.guide_price > 0
            THEN round((al.sold_price / al.guide_price) * 100, 1) END AS percent_of_guide
FROM auction_lots al
WHERE al.status <> 5;

-- Where every organisation stands with Compliance.
CREATE VIEW v_report_compliance AS
SELECT t.id            AS tenant_id,
       t.name          AS tenant_name,
       t.organisation_kind,
       t.seller_type,
       t.onboarding_status,
       t.created_at    AS onboarded_at,
       (SELECT count(*) FROM user_profiles up
         WHERE up.tenant_id = t.id AND up.kyc_status = 'APPROVED' AND up.status <> 5) AS cleared_people,
       (SELECT count(*) FROM user_profiles up
         WHERE up.tenant_id = t.id AND up.kyc_status IN ('PENDING', 'SUBMITTED')
           AND up.status <> 5) AS waiting_people,
       (SELECT count(*) FROM properties p
         WHERE p.tenant_id = t.id AND p.listing_state = 'LIVE' AND p.status <> 5) AS live_listings
FROM tenants t
WHERE t.status <> 5;

-- What buyers said, by subject.
CREATE VIEW v_report_ratings AS
SELECT r.reference,
       r.subject_tenant_id AS tenant_id,
       r.subject_type,
       r.subject_ref,
       r.subject_label,
       r.score,
       r.verified,
       r.state,
       r.report_count,
       r.created_at
FROM ratings r
WHERE r.status <> 5;
