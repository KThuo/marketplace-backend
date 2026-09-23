-- A message on a lead carries a kind and, where the kind is a move in a negotiation, the figure. Words stay
-- words; the offer, a counter, the acceptance are events drawn differently and read as the money's story.
ALTER TABLE lead_messages ADD COLUMN kind VARCHAR(24) NOT NULL DEFAULT 'MESSAGE';
ALTER TABLE lead_messages ADD COLUMN amount NUMERIC(15, 2);

-- The offer remembers the money: what was first offered, the seller's outstanding counter, what was agreed.
ALTER TABLE purchase_requests ADD COLUMN original_amount NUMERIC(15, 2);
ALTER TABLE purchase_requests ADD COLUMN counter_amount NUMERIC(15, 2);
ALTER TABLE purchase_requests ADD COLUMN counter_by VARCHAR(16);
ALTER TABLE purchase_requests ADD COLUMN agreed_amount NUMERIC(15, 2);
UPDATE purchase_requests SET original_amount = offer_amount WHERE original_amount IS NULL;
UPDATE purchase_requests SET agreed_amount = offer_amount WHERE state = 'ACCEPTED' AND agreed_amount IS NULL;
ALTER TABLE purchase_requests ALTER COLUMN original_amount SET NOT NULL;

-- What was already said gets its kind from the state it moved the offer to.
UPDATE lead_messages m SET kind = 'OFFER', amount = p.original_amount
  FROM purchase_requests p
 WHERE m.lead_type = 'PURCHASE_REQUEST' AND m.lead_id = p.id AND m.state_after = 'SUBMITTED'
   AND m.id = (SELECT min(id) FROM lead_messages f WHERE f.lead_type = m.lead_type AND f.lead_id = m.lead_id);
UPDATE lead_messages m SET kind = 'ACCEPTED', amount = p.agreed_amount
  FROM purchase_requests p
 WHERE m.lead_type = 'PURCHASE_REQUEST' AND m.lead_id = p.id AND m.state_after = 'ACCEPTED' AND m.author_side <> 'BUYER';
UPDATE lead_messages SET kind = 'DECLINED'  WHERE lead_type = 'PURCHASE_REQUEST' AND state_after = 'DECLINED' AND author_side <> 'BUYER';
UPDATE lead_messages SET kind = 'WITHDRAWN' WHERE lead_type = 'PURCHASE_REQUEST' AND state_after = 'WITHDRAWN' AND author_side = 'BUYER';
UPDATE lead_messages SET kind = 'REVIEW'    WHERE lead_type = 'PURCHASE_REQUEST' AND state_after = 'UNDER_REVIEW' AND author_side <> 'BUYER';
