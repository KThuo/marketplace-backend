-- A development's two statements: money in and money out, each read from its own source.
--
-- Separate views over separate tables, deliberately. Money in is buyers' payments against the development's
-- bookings; money out is what the development paid, through Hodi or recorded by hand. Nothing here joins the
-- two, so a debit can never be read as a credit — and slip validation, which reads coop_statements, never
-- sees a row from either. Both carry tenant_id, institution_id and development_id, which is what lets the
-- report engine scope them by owner exactly as it scopes the development finance report.

-- ── money in ─────────────────────────────────────────────────────────────────────────────────────
CREATE VIEW v_report_development_money_in AS
SELECT p.id,
       p.paid_on,
       p.created_at,
       p.reference,
       p.quoted_reference                                    AS receipt,
       p.external_reference                                  AS bank_reference,
       b.reference                                           AS booking_reference,
       p.unit_label,
       p.buyer_name,
       p.payer_name,
       p.amount,
       p.currency,
       p.method,
       p.payment_type_name,
       -- How the money was placed against the booking. A statement behind it means the bank told us; nothing
       -- behind it means a person wrote it down.
       CASE WHEN p.statement_id IS NOT NULL THEN 'Bank notification' ELSE 'By hand' END AS how_placed,
       CASE WHEN p.status = 4 THEN 'Voided' ELSE 'Received' END AS state,
       p.created_by                                          AS recorded_by,
       p.void_reason,
       p.development_id,
       d.name                                                AS development_name,
       p.tenant_id,
       p.institution_id,
       coalesce(d.institution_name, d.tenant_name)           AS owner_name
  FROM payments p
  JOIN developments d ON d.id = p.development_id
  LEFT JOIN unit_bookings b ON b.id = p.booking_id
 WHERE p.status <> 5 AND p.development_id IS NOT NULL;

-- ── money out ────────────────────────────────────────────────────────────────────────────────────
--
-- Two kinds of row. A payment made through Hodi appears in every state, from proposed to paid or refused,
-- because a checker's queue and a statement should agree about what is in flight. A cost recorded by hand
-- appears once, as spent, and says so — it is a person's word, not the bank's. A cost a payment wrote for
-- itself is the same money as that payment and is not listed twice.
CREATE VIEW v_report_development_money_out AS
SELECT 'DB' || x.id                                          AS id,
       x.happened_on,
       x.reference,
       x.bank_reference,
       x.payee,
       x.beneficiary_type,
       x.purpose,
       ph.name                                               AS phase_name,
       c.name                                                AS category_name,
       x.invoice_reference,
       x.paid_from,
       x.maker,
       x.checker,
       x.state,
       'Paid through Hodi'                                   AS route,
       x.amount,
       x.currency,
       x.development_id,
       d.name                                                AS development_name,
       x.tenant_id,
       x.institution_id,
       coalesce(d.institution_name, d.tenant_name)           AS owner_name
  FROM (
        SELECT s.id,
               coalesce(s.settled_at, s.created_at)         AS happened_on,
               s.reference, s.bank_reference,
               s.payee_name                                 AS payee,
               s.beneficiary_type, s.purpose,
               s.phase_id, s.cost_category_id, s.invoice_reference,
               -- The last four digits: enough to tell two accounts apart, not enough to be an account.
               '••' || right(a.account_no, 4)               AS paid_from,
               s.made_by                                    AS maker,
               s.checked_by                                 AS checker,
               CASE s.state
                    WHEN 'AWAITING_APPROVAL' THEN 'Awaiting approval'
                    WHEN 'APPROVED'          THEN 'Approved, about to send'
                    WHEN 'SENDING'           THEN 'Sending'
                    WHEN 'SENT'              THEN 'Sent, awaiting the bank'
                    WHEN 'SUCCEEDED'         THEN 'Paid'
                    WHEN 'FAILED'            THEN 'Not paid'
                    WHEN 'REFUSED'           THEN 'Refused'
                    ELSE s.state END                        AS state,
               s.amount, s.currency, s.development_id,
               s.owner_tenant_id                            AS tenant_id,
               s.owner_institution_id                       AS institution_id
          FROM disbursements s
          LEFT JOIN payment_accounts a ON a.id = s.source_account_id
         WHERE s.status <> 5 AND s.development_id IS NOT NULL
       ) x
  JOIN developments d ON d.id = x.development_id
  LEFT JOIN development_phases ph ON ph.id = x.phase_id
  LEFT JOIN development_cost_categories c ON c.id = x.cost_category_id

UNION ALL

SELECT 'EX' || e.id,
       e.incurred_on::timestamptz,
       e.reference,
       e.reference_no,
       e.payee,
       bt.name,
       e.notes,
       ph.name,
       c.name,
       e.reference_no,
       NULL,
       e.created_by,
       NULL,
       CASE WHEN e.status = 4 THEN 'Voided' ELSE 'Recorded' END,
       'Manual entry',
       e.amount,
       e.currency,
       e.development_id,
       d.name,
       e.tenant_id,
       e.institution_id,
       coalesce(d.institution_name, d.tenant_name)
  FROM development_expenditures e
  JOIN developments d ON d.id = e.development_id
  LEFT JOIN development_phases ph ON ph.id = e.phase_id
  LEFT JOIN development_cost_categories c ON c.id = e.category_id
  LEFT JOIN beneficiaries bn ON bn.id = e.beneficiary_id
  LEFT JOIN beneficiary_types bt ON bt.id = bn.type_id
 WHERE e.status <> 5 AND e.entry_kind = 'MANUAL' AND e.kind = 'SPENT';
