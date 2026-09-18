-- ─────────────────────────────────────────────────────────────────────────────────────────────────
-- There is no gateway in between, and there is one bank.
--
-- The payment tables were built on the assumption that this platform sat behind a payments gateway,
-- as one of its business clients: a statements table named after it, a provider column naming its
-- catalogue, a notification secret for the header it signs its callbacks with, and channels for four
-- banks because that is what the gateway fronts.
--
-- None of that is the architecture. The marketplace reaches Co-operative Bank directly and is the end
-- system Co-op calls back. Co-op is also the only institution with the financial ability to transact
-- here, so three of the four banks in the catalogue could never settle anything — they were a list
-- somebody could attach an account to the wrong bank from.
--
-- Names that assert a false architecture are a trap for whoever reads them next, so they are corrected
-- here rather than left with a comment.
-- ─────────────────────────────────────────────────────────────────────────────────────────────────

-- ① The statements table is Co-op's, not a gateway's.
ALTER TABLE pesi_statements RENAME TO coop_statements;
ALTER INDEX IF EXISTS uk_pesi_ref_no RENAME TO uk_coop_statement_ref;

-- ② The provider columns say what they hold: a channel code, and the product behind an account.
ALTER TABLE payment_types    RENAME COLUMN pesi_provider_type TO provider_type;
ALTER TABLE payment_accounts RENAME COLUMN pesi_type          TO provider_code;
ALTER INDEX IF EXISTS uk_payment_type_pesi RENAME TO uk_payment_type_provider;

-- ③ Channel codes lose the gateway's prefixes. Done before the other banks go, so a row that is about
--    to be archived is renamed consistently rather than left as the one exception.
UPDATE payment_types    SET provider_type = 'COOP_STK_PUSH'    WHERE provider_type = 'COOP_BANK_STK_PUSH';
UPDATE payment_types    SET provider_type = 'COOP_IPN_ACCOUNT' WHERE provider_type = 'COOP_BANK_IPN_ACCOUNT';
UPDATE payment_types    SET provider_type = 'COOP_BILLER'      WHERE provider_type = 'COOP_BILLER_B2B';
UPDATE payment_accounts SET provider_code = 'COOP_STK_PUSH'    WHERE provider_code = 'COOP_BANK_STK_PUSH';
UPDATE payment_accounts SET provider_code = 'COOP_IPN_ACCOUNT' WHERE provider_code = 'COOP_BANK_IPN_ACCOUNT';
UPDATE payment_accounts SET provider_code = 'COOP_BILLER'      WHERE provider_code = 'COOP_BILLER_B2B';

-- ④ The banks that cannot settle anything here are archived, not deleted.
--
--    Archived because a payment row may already point at one: a channel that vanishes takes the
--    provenance of every payment recorded through it with it. Status 5 is this schema's "deleted", and
--    nothing offers it — which is the whole of what was wanted.
UPDATE payment_types
   SET status = 5, status_flag = 'Deleted'
 WHERE provider_name IS NOT NULL
   AND provider_name <> 'Co-operative Bank';

-- ⑤ The gateway's own settings go, and Co-op's arrive.
--
--    The notification secret was for a header the gateway signs its callbacks with. Co-op authenticates
--    with HTTP Basic instead, and while either half is blank the endpoint refuses every request rather
--    than accepting anonymous notifications — the values below are deliberately empty, so a deployment
--    that has not been configured yet takes no money it cannot attribute.
DELETE FROM configurations WHERE config_key IN ('pesi.ipn.secret', 'pesi.providers');

INSERT INTO configurations (config_key, config_value, value_type, category, label, description,
                            is_secret, is_overridable, status, status_flag, created_by)
VALUES
    ('coop.ipn.username', '', 'STRING', 'INTEGRATION', 'Co-op notification username',
     'The username Co-op sends on inbound payment notifications, as HTTP Basic. While this or the '
     'password is blank, notifications are refused rather than accepted unauthenticated.',
     false, false, 1, 'Active', 'migration'),
    ('coop.ipn.password', '', 'STRING', 'INTEGRATION', 'Co-op notification password',
     'The password paired with the notification username. Stored encrypted.',
     true, false, 1, 'Active', 'migration'),
    ('coop.consumer.key', '', 'STRING', 'INTEGRATION', 'Co-op consumer key',
     'The OAuth2 client id issued by Co-op, exchanged for the bearer token every outbound call carries.',
     false, false, 1, 'Active', 'migration'),
    ('coop.consumer.secret', '', 'STRING', 'INTEGRATION', 'Co-op consumer secret',
     'The OAuth2 client secret issued by Co-op. Stored encrypted.',
     true, false, 1, 'Active', 'migration'),
    ('coop.environment', 'SANDBOX', 'STRING', 'INTEGRATION', 'Co-op environment',
     'SANDBOX or PRODUCTION. Decides which of a payment type''s configured hosts is used.',
     false, false, 1, 'Active', 'migration'),
    ('payment.providers', 'Co-operative Bank', 'STRING', 'INTEGRATION', 'Payment providers offered',
     'Comma-separated provider names whose channels appear in the payment catalogue and can be given an '
     'account. Cash and cheque are always offered. Empty means every provider.',
     false, false, 1, 'Active', 'migration')
ON CONFLICT (config_key) DO NOTHING;

-- ⑥ Where the calls go belongs to the payment type, not to the code.
--
--    A host or a path compiled into the application has to be redeployed when the bank moves one, opens
--    a second environment, or issues a new version of an endpoint — all three of which banks do. The
--    descriptor says which fields a type needs and what to call them; the values sit on the type, and
--    the secrets among them are encrypted exactly as a secret configuration value is.
ALTER TABLE payment_types ADD COLUMN required_config_fields jsonb;
ALTER TABLE payment_types ADD COLUMN config                 jsonb;

COMMENT ON COLUMN payment_types.required_config_fields IS
    'Form descriptor: the fields this channel needs — key, label, type, required — rendered by the screen '
    'and read by the adapter. Adding a channel is a row, not a deploy.';
COMMENT ON COLUMN payment_types.config IS
    'The values for those fields, including the endpoints. Fields whose descriptor says "password" are '
    'stored encrypted.';

-- The Co-op channels describe what each needs. Hosts and paths, because they are Co-op's to change.
UPDATE payment_types SET required_config_fields = jsonb_build_object(
    'fields', jsonb_build_array(
        jsonb_build_object('key', 'sandboxBaseUrl', 'label', 'Sandbox host',
                           'type', 'text', 'required', true,  'fullWidth', true),
        jsonb_build_object('key', 'productionBaseUrl', 'label', 'Production host',
                           'type', 'text', 'required', false, 'fullWidth', true),
        jsonb_build_object('key', 'tokenPath', 'label', 'Token path',
                           'type', 'text', 'required', true,  'fullWidth', true),
        jsonb_build_object('key', 'requestPath', 'label', 'Request path',
                           'type', 'text', 'required', true,  'fullWidth', true),
        jsonb_build_object('key', 'statusPath', 'label', 'Status query path',
                           'type', 'text', 'required', false, 'fullWidth', true),
        jsonb_build_object('key', 'callbackUrl', 'label', 'Callback URL Co-op should call',
                           'type', 'text', 'required', false, 'fullWidth', true)))
 WHERE provider_name = 'Co-operative Bank';
