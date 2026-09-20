-- ═════════════════════════════════════════════════════════════════════════════════════════════════
-- The phone prompt and the account notification are switched on
--
-- Both shipped Inactive in September, because nothing could call Co-op yet and offering a channel with
-- nothing behind it records money against nothing. The client, the credentials and the account
-- configuration have since arrived, and the prompt's own service refuses while the channel is off with
-- "not switched on yet" — a message that sent the operator to a catalogue screen to flip a row every
-- fresh deployment. The operator's decision is which ACCOUNTS collect; the channel being available is
-- the platform's.
--
-- Only rows still at their shipped state move. A channel somebody switched off on purpose (status 4 set
-- after activation) cannot be told apart here, so the choice is made once and can be reversed on the
-- catalogue screen like any other.
-- ═════════════════════════════════════════════════════════════════════════════════════════════════

UPDATE payment_types
   SET status = 1, status_flag = 'Active', updated_by = 'migration', updated_at = now()
 WHERE provider_type IN ('COOP_STK_PUSH', 'COOP_IPN_ACCOUNT')
   AND status = 4;
