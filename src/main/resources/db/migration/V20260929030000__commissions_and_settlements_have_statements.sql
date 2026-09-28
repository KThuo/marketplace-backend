-- Three statements: every commission line (whose, on what, paid how), every sale the bank collected and
-- how its settlement stands, and every buyer an agent brought. Views, not tables: the first copy of the
-- truth, shaped differently, with the owner columns the report engine scopes on.

-- ── commission: now a line per payee, against a booking ──────────────────────────────────────────
DROP VIEW IF EXISTS v_report_commission;
CREATE VIEW v_report_commission AS
SELECT c.reference,
       CASE c.payee_kind WHEN 'AGENT' THEN 'Agent' ELSE 'The bank' END AS earned_by,
       c.agent_name,
       c.booking_ref,
       c.development_name,
       c.property_ref,
       c.property_title,
       c.tenant_name,
       c.sale_price,
       c.rate_percent,
       c.amount,
       c.currency,
       CASE c.paid_by WHEN 'BANK' THEN 'The bank' ELSE 'The seller' END AS borne_by,
       c.state,
       c.sold_at,
       c.invoiced_at,
       c.paid_at,
       db.reference                                          AS paid_by_transfer,
       c.tenant_id,
       d.institution_id,
       c.development_id
  FROM commission_records c
  LEFT JOIN developments d ON d.id = c.development_id
  LEFT JOIN disbursements db ON db.id = c.disbursement_id
 WHERE c.status <> 5;

-- ── sale settlements: one row per completed sale the bank collected ──────────────────────────────
--
-- Gross is what was received on the booking; the fees are its live commission lines; net is what the owner
-- gets, with the agent's fee coming off only where the seller bears it. The same arithmetic as
-- SettlementService.figures, so the statement and the screen agree.
CREATE VIEW v_report_sale_settlements AS
SELECT b.reference                                           AS booking_ref,
       d.name                                                AS development_name,
       coalesce(p.unit_label, p.title)                       AS home,
       b.buyer_name,
       coalesce(d.institution_name, d.tenant_name)           AS owner_name,
       b.currency,
       b.price_agreed,
       bal.paid                                              AS gross,
       coalesce(bank.amount, 0)                              AS bank_fee,
       coalesce(agent.amount, 0)                             AS agent_fee,
       agent.agent_name,
       CASE agent.paid_by WHEN 'BANK' THEN 'The bank' WHEN 'SELLER' THEN 'The seller' END AS agent_fee_borne_by,
       greatest(bal.paid - coalesce(bank.amount, 0)
                - CASE WHEN agent.paid_by = 'BANK' THEN 0 ELSE coalesce(agent.amount, 0) END, 0) AS net_to_owner,
       CASE WHEN b.settled_at IS NOT NULL THEN 'Settled'
            WHEN EXISTS (SELECT 1 FROM disbursements x WHERE x.booking_id = b.id AND x.status <> 5
                            AND x.state NOT IN ('SUCCEEDED', 'FAILED', 'REFUSED')) THEN 'On its way'
            ELSE 'Awaiting settlement' END                   AS state,
       b.completed_at,
       b.settled_at,
       proceeds.reference                                    AS proceeds_transfer,
       b.tenant_id,
       b.institution_id,
       b.development_id
  FROM unit_bookings b
  JOIN developments d ON d.id = b.development_id AND d.collection_mode = 'BANK'
  JOIN properties p ON p.id = b.property_id
  LEFT JOIN v_booking_balances bal ON bal.booking_id = b.id
  LEFT JOIN commission_records bank ON bank.booking_id = b.id AND bank.payee_kind = 'PLATFORM'
       AND bank.status <> 5 AND bank.state <> 'WAIVED'
  LEFT JOIN commission_records agent ON agent.booking_id = b.id AND agent.payee_kind = 'AGENT'
       AND agent.status <> 5 AND agent.state <> 'WAIVED'
  LEFT JOIN LATERAL (
        SELECT x.reference FROM disbursements x
         WHERE x.booking_id = b.id AND x.settlement_kind = 'PROCEEDS' AND x.status <> 5
         ORDER BY CASE x.state WHEN 'SUCCEEDED' THEN 0 ELSE 1 END, x.id DESC LIMIT 1
       ) proceeds ON true
 WHERE b.state = 'COMPLETED' AND b.status <> 5;

-- ── agent sales: every buyer an agent brought, whatever became of the sale ───────────────────────
CREATE VIEW v_report_agent_sales AS
SELECT a.full_name                                           AS agent_name,
       a.reference                                           AS agent_ref,
       b.reference                                           AS booking_ref,
       d.name                                                AS development_name,
       coalesce(p.unit_label, p.title)                       AS home,
       b.buyer_name,
       CASE b.state WHEN 'COMPLETED' THEN 'Sold' WHEN 'AGREED' THEN 'Agreed' WHEN 'RESERVED' THEN 'Reserved'
                    WHEN 'CANCELLED' THEN 'Cancelled' WHEN 'LAPSED' THEN 'Lapsed' ELSE b.state END AS state,
       b.currency,
       b.price_agreed,
       coalesce(bal.paid, 0)                                 AS paid,
       line.amount                                           AS commission,
       line.state                                            AS commission_state,
       b.created_at                                          AS booked_at,
       b.completed_at,
       b.tenant_id,
       b.institution_id,
       b.development_id
  FROM unit_bookings b
  JOIN agent_profiles a ON a.id = b.introduced_by_agent_id
  JOIN properties p ON p.id = b.property_id
  LEFT JOIN developments d ON d.id = b.development_id
  LEFT JOIN v_booking_balances bal ON bal.booking_id = b.id
  LEFT JOIN commission_records line ON line.booking_id = b.id AND line.payee_kind = 'AGENT' AND line.status <> 5
 WHERE b.status <> 5;
