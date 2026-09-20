-- ═════════════════════════════════════════════════════════════════════════════════════════════════
-- A prompt carries the trace of the request that made it
--
-- What went wrong with a payment request is written to the server log in full — the firewall's support
-- ID, the bank's HTML page, the exception. The customer is told only that it did not go through and to
-- try again: the detail is revealing and none of it is theirs to act on. What links the two is this id,
-- the same one the request log carries, so an investigation starts from the transaction and lands on
-- every line written about it.
-- ═════════════════════════════════════════════════════════════════════════════════════════════════

ALTER TABLE payment_intents ADD COLUMN trace_id VARCHAR(32);

COMMENT ON COLUMN payment_intents.trace_id IS
    'The request log''s trace id for the request that made this prompt. The customer sees this and a '
    'plain sentence; the log holds the rest.';
