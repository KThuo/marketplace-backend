-- A credit that landed in an account nobody has registered is not unused money waiting to be placed: it is
-- money nobody can place, because the account it sits in is not ours yet. It used to sit in the unused
-- queue with a reason, where a slip validation could find it and a queue worker could try to apply it to a
-- booking whose organisation it may not belong to. It now waits in its own state until the account is set
-- up, and is retried — by hand from the statements screen, or on its own when the account goes live.
ALTER TABLE coop_statements DROP CONSTRAINT IF EXISTS ck_coop_statement_state;
ALTER TABLE coop_statements ADD CONSTRAINT ck_coop_statement_state
    CHECK (state IN ('MAPPED', 'UNMAPPED', 'IGNORED', 'NO_ACCOUNT'));

UPDATE coop_statements
   SET state = 'NO_ACCOUNT',
       unmapped_reason = 'The account ' || coalesce(account_identifier, '(none)')
           || ' is not registered here. Register it under Payment accounts and the credit is retried.',
       updated_at = now()
 WHERE state = 'UNMAPPED'
   AND payment_account_id IS NULL
   AND status <> 5;
