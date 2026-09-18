-- ═════════════════════════════════════════════════════════════════════════════════════════════════
-- Payments with Co-operative Bank, in one script.
--
-- This replaces ten migrations written across one day, as the shape of the integration was worked out
-- against the bank's own collections: the move off a gateway, the channel and account descriptors, the
-- intent that remembers a request, the kinds that say what a method is for, and the names.
--
-- ── Why the intermediate steps are still here ────────────────────────────────────────────────────
--
-- They are concatenated in order rather than rewritten into a tidy end state. A hand-written
-- consolidation is a second implementation of ten scripts, and the way it fails is silent: one clause
-- missed, and a fresh deployment gets a schema subtly unlike every existing one — discovered months
-- later by a query that works everywhere except in production.
--
-- Concatenation cannot diverge, because it is the same statements in the same order. The cost is that
-- this file adds a column and later drops it, or sets a value and later replaces it. That cost is paid
-- once, on an empty database, in milliseconds.
--
-- Verified by running the whole migration history into an empty database and comparing the resulting
-- payment_types, payment_accounts, payment_intents and coop_statements — columns, constraints, indexes
-- and every seeded row — against a database built by the ten originals. They match.
-- ═════════════════════════════════════════════════════════════════════════════════════════════════


-- ┌─────────────────────────────────────────────────────────────────────────────────────────────
-- │ was V20260918100000__the_marketplace_banks_with_coop_directly.sql
-- └─────────────────────────────────────────────────────────────────────────────────────────────
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

-- ┌─────────────────────────────────────────────────────────────────────────────────────────────
-- │ was V20260918120000__an_account_asks_what_its_channel_needs.sql
-- └─────────────────────────────────────────────────────────────────────────────────────────────
-- ─────────────────────────────────────────────────────────────────────────────────────────────────
-- An account asks what its channel needs, and nothing else.
--
-- `payment_accounts` has four fixed columns — pay_bill_no, account_no, account_name, short_code — and
-- the service writes all four whatever the channel is. Which two the form shows is decided by a pair
-- of booleans the application invented: requires_short_code, and category = 'VALIDATE'.
--
-- Those booleans are a guess at what a channel needs, and the guess is wrong in both directions. A
-- Co-op phone prompt takes no paybill and no short code; it takes an operator code, a consumer key
-- and a consumer secret. A Co-op biller takes nine fields, per biller, and there is no column for any
-- of them. account_name is a field no Co-op channel has at all.
--
-- V20260918100000 already established the answer one level up: a channel carries a JSON descriptor of
-- its own configuration, and "adding a channel is a row, not a deploy". This applies the same rule to
-- the account — which, on this platform, IS the configured method.
--
-- One flat list of fields, the same grammar required_config_fields already uses. An earlier draft of
-- this migration grouped them under super / company / business / accounts, which is how a gateway with
-- companies under it and businesses under those has to scope them. There is one organisation
-- configuring one channel here, so every group rendered on the one form in the one order and the
-- grouping decided nothing — it was a vocabulary carried across from a system this platform no longer
-- talks to, and it cost eight overloads in ChannelConfig to read.
-- ─────────────────────────────────────────────────────────────────────────────────────────────────

-- ① What an account of each channel needs, and the values it holds.
--
--    Two columns rather than one, exactly as payment_types has them: the descriptor is the platform's
--    and changes with a migration, the values are the organisation's and change with a form.
ALTER TABLE payment_types    ADD COLUMN account_config_fields jsonb;
ALTER TABLE payment_accounts ADD COLUMN config                jsonb;

COMMENT ON COLUMN payment_types.account_config_fields IS
    'Form descriptor for one account of this channel: fields, plus accountKey naming the fields that '
    'compose account_no, and accountsLabel for what one account is called. Same shape as '
    'required_config_fields — one descriptor grammar for both columns.';
COMMENT ON COLUMN payment_accounts.config IS
    'The values for those fields, by key. Every field whose descriptor says "password" is stored '
    'through EncryptionUtil, and a read answers a mask rather than the value.';
-- account_no holds the code the channel resolves by, and the platform never holds the account itself.
--
-- Money is going to a bank, so for everything but cash and cheque there is a real account behind the
-- channel — but the bank already knows which. One organisation holds several codes, one per channel, all
-- resolving at Co-op to the same account: HODI on one payment type, QUANTUMNEX on another. Storing the
-- account number here would add a copy of something we are not the record of, to no end.
--
-- So the code is the handle, and it is what the list shows: somebody can see which account a channel
-- lands in without the platform holding the number. pesi's live rows are the same shape —
-- COOP_BANK_STK_PUSH holds "HODI" and "NOTIFY", COOP_BILLER_B2B "210001918breezeestate".
--
-- An inbound notification carries whichever of the two Co-op chooses to send, so resolution matches the
-- payload against this column for that channel and does not care which it was given.
COMMENT ON COLUMN payment_accounts.account_no IS
    'The code this channel resolves by, composed from the fields the descriptor names in accountKey — '
    'an operator code, a user id, an institution code and service name composed. What an inbound '
    'notification is matched on. The bank account behind it is the bank''s record, not this one.';

-- ② The phone prompt.
--
--    Operator code, not an account number and certainly not a paybill: Co-op issues a short word
--    naming the operator, and pesi's live rows for this channel hold "HODI" and "NOTIFY".
UPDATE payment_types SET account_config_fields = jsonb_build_object(
    'accountKey', jsonb_build_array('accountNumber'),
    'fields', jsonb_build_array(
        jsonb_build_object('key', 'consumerKey', 'label', 'Co-op consumer key',
                           'type', 'text', 'required', true),
        jsonb_build_object('key', 'consumerSecret', 'label', 'Co-op consumer secret',
                           'type', 'password', 'required', true),
        jsonb_build_object('key', 'accountNumber', 'label', 'Operator code',
                           'type', 'text', 'required', true)))
 WHERE provider_type = 'COOP_STK_PUSH';

-- ③ The account credits arrive in.
--
--    The bank account number is the match key here, and the callback credentials are this account's
--    own — Co-op authenticates to us per account, so they cannot be a platform setting.
--    Here the code Co-op quotes is the account number itself, because a credit arrives naming where it
--    landed. Same column, same rule; it is the code for this channel and nothing further is stored.
UPDATE payment_types SET account_config_fields = jsonb_build_object(
    'accountKey', jsonb_build_array('accountNumber'),
    'fields', jsonb_build_array(
        jsonb_build_object('key', 'whitelistedIps', 'label', 'Whitelisted IPs',
                           'type', 'text', 'required', false, 'fullWidth', true),
        jsonb_build_object('key', 'accountNumber', 'label', 'Account number credits arrive in',
                           'type', 'text', 'required', true),
        jsonb_build_object('key', 'callbackUrl', 'label', 'Notification URL',
                           'type', 'text', 'required', false, 'fullWidth', true),
        jsonb_build_object('key', 'callbackUsername', 'label', 'Auth username',
                           'type', 'text', 'required', false),
        jsonb_build_object('key', 'callbackPassword', 'label', 'Auth password',
                           'type', 'password', 'required', false)))
 WHERE provider_type = 'COOP_IPN_ACCOUNT';

-- ④ The biller, which is the case that decided the shape.
--
--    Nine fields, all of them the biller's own, and a match key composed of two of them: a biller
--    advice carries an institution code and a service name in its header and no id, so the only way
--    to know which biller it is for is to compose the pair. pesi's rows read "210001918breezeestate"
--    for exactly this reason.
--
--    connectionID and connectionPassword are what Co-op authenticates to us with; validationUrl and
--    adviceUrl are where we call the end system back. Per biller, both directions.
UPDATE payment_types SET account_config_fields = jsonb_build_object(
    'accountsLabel', 'Biller',
    'accountKey', jsonb_build_array('institutionCode', 'serviceName'),
    'fields', jsonb_build_array(
        jsonb_build_object('key', 'institutionCode', 'label', 'Institution code (assigned by Co-op)',
                           'type', 'text', 'required', true),
        jsonb_build_object('key', 'serviceName', 'label', 'Service name (assigned by Co-op)',
                           'type', 'text', 'required', true),
        jsonb_build_object('key', 'institutionName', 'label', 'Institution name',
                           'type', 'text', 'required', true, 'fullWidth', true),
        jsonb_build_object('key', 'connectionID', 'label', 'Co-op connection ID',
                           'type', 'text', 'required', true),
        jsonb_build_object('key', 'connectionPassword', 'label', 'Co-op connection password',
                           'type', 'password', 'required', true),
        jsonb_build_object('key', 'callbackUsername', 'label', 'Auth username (we call the end system)',
                           'type', 'text', 'required', true),
        jsonb_build_object('key', 'callbackPassword', 'label', 'Auth password',
                           'type', 'password', 'required', true),
        jsonb_build_object('key', 'validationUrl', 'label', 'Validation URL',
                           'type', 'text', 'required', true, 'fullWidth', true),
        jsonb_build_object('key', 'adviceUrl', 'label', 'Advice URL',
                           'type', 'text', 'required', true, 'fullWidth', true)))
 WHERE provider_type = 'COOP_BILLER';

-- ⑤ Cash and cheque need nothing, and saying so is not the same as saying nothing.
--
--    An empty descriptor renders a form with no fields, which is correct. A null one is "not written
--    yet", which the screen is entitled to treat as a channel it cannot configure.
UPDATE payment_types
   SET account_config_fields = jsonb_build_object('fields', jsonb_build_array())
 WHERE category IN ('CASH', 'CHEQUE');

-- ⑥ One code per channel, which is what an inbound notification can distinguish.
--
--    Per channel and not platform-wide: one organisation may hold the same code on two channels, and an
--    operator code issued by Co-op says nothing about a till number issued by somebody else. What must
--    not collide is two accounts on one channel answering to one code — every notification on it would
--    then be unattributable.
--
--    Partial, on live rows only: an archived account keeps its code so the provenance of payments made
--    through it survives.
CREATE UNIQUE INDEX uk_payment_account_code
    ON payment_accounts (account_no, payment_type_id)
 WHERE status <> 5 AND account_no IS NOT NULL;

-- ⑦ Where the money is collected.
--
--    The ownership columns already exist — tenant_id, institution_id, neither set meaning the
--    platform's own — and PaymentScope already decides who may see which. Only the switch is new.
--
--    PLATFORM is the default because it is what happens today. ORGANISATION falls back to the
--    platform's account for an organisation that has not configured one, so turning it on cannot
--    leave anybody unable to take money.
INSERT INTO configurations (config_key, config_value, value_type, category, label, description,
                            is_secret, is_overridable, status, status_flag, created_by)
VALUES ('payments.collection.scope', 'PLATFORM', 'STRING', 'PAYMENTS',
        'Who collects payments',
        'PLATFORM: every payment is collected to the platform''s own account. ORGANISATION: an '
        'organisation with a configured account of its own collects to it, and one without falls back '
        'to the platform''s.',
        false, false, 1, 'Active', 'migration')
ON CONFLICT (config_key) DO NOTHING;

-- ⑧ One host, and one endpoint per channel.
--
--    The channel descriptor written by V20260918100000 gave every payment type a sandbox host, a
--    production host, a token path, a request path and a status path, and a platform setting picked
--    which host was live. That is one host too many in two places: the bank has one base address per
--    environment, and an address that reads sandbox *is* the sandbox — a separate switch saying so is a
--    second source of truth that can disagree with it, and the failure is paying the wrong bank.
--
--    So the host moves to one platform setting, shared by every channel, and a payment type keeps only
--    the endpoint that is its own. Each type has exactly one: pesi models a status query as its own
--    transaction type rather than a second path on another, and that is the distinction worth keeping.
--
--    Nothing is configured yet — every config column and every coop setting is blank — so this is a
--    reshape with no values to carry across.
UPDATE payment_types SET required_config_fields = jsonb_build_object(
    'fields', jsonb_build_array(
        jsonb_build_object('key', 'endpoint', 'label', 'Endpoint path',
                           'type', 'text', 'required', true, 'fullWidth', true),
        jsonb_build_object('key', 'callbackUrl', 'label', 'Callback URL Co-op should call',
                           'type', 'text', 'required', false, 'fullWidth', true)))
 WHERE provider_name = 'Co-operative Bank';

UPDATE payment_types SET config = NULL WHERE provider_name = 'Co-operative Bank';

DELETE FROM configurations WHERE config_key = 'coop.environment';

INSERT INTO configurations (config_key, config_value, value_type, category, label, description,
                            is_secret, is_overridable, status, status_flag, created_by)
VALUES
    ('coop.base.url', '', 'STRING', 'INTEGRATION', 'Co-op host',
     'The base address every Co-op call is made against. Whether this deployment talks to the sandbox '
     'or to production is decided by what is in here and nothing else. Blank means no outbound call is '
     'attempted.',
     false, false, 1, 'Active', 'migration'),
    ('coop.token.path', '', 'STRING', 'INTEGRATION', 'Co-op token path',
     'The OAuth2 endpoint on that host, exchanged for the bearer token every call carries. One for the '
     'bank rather than one per channel.',
     false, false, 1, 'Active', 'migration')
ON CONFLICT (config_key) DO NOTHING;

-- ┌─────────────────────────────────────────────────────────────────────────────────────────────
-- │ was V20260918140000__an_account_need_not_be_named.sql
-- └─────────────────────────────────────────────────────────────────────────────────────────────
-- ─────────────────────────────────────────────────────────────────────────────────────────────────
-- An account carries what its channel asks for, which is not always a name and not always unique.
--
-- V20260918120000 gave each channel a descriptor of what one of its accounts needs, and the three
-- Co-op channels answered: an operator code and two credentials; an account number and callback
-- credentials; a biller's nine. None of them has an "account name" — that field belonged to the four
-- fixed columns, not to any bank — and two of the old rules now refuse rows that are perfectly correct.
-- ─────────────────────────────────────────────────────────────────────────────────────────────────

-- ① A name is the bank's record, not this one's.
--
--    The old rule demanded account_no AND account_name on every non-manual account, because the form
--    asked for both of everybody. A Co-op phone prompt has neither to give: its code is an operator
--    word, and there is no name field anywhere in its onboarding. Demanding one produces exactly what
--    the descriptor work set out to remove — a value somebody typed to get past a field.
--
--    What stays demanded is the code. Without it no inbound notification on that channel can be
--    attributed to anybody, which is a worse failure than a missing label.
ALTER TABLE payment_accounts DROP CONSTRAINT ck_payment_account_fields;
ALTER TABLE payment_accounts ADD CONSTRAINT ck_payment_account_fields CHECK (
    (category IN ('CASH', 'CHEQUE') AND account_no IS NULL AND account_name IS NULL)
    OR (category NOT IN ('CASH', 'CHEQUE')
        AND account_no IS NOT NULL AND length(btrim(account_no)) > 0));

COMMENT ON COLUMN payment_accounts.account_name IS
    'The name on the account, where the channel asks for one. Optional: no Co-op channel has such a '
    'field, and the bank is the record of whose account it is.';

-- ② One code per channel, not one code per platform.
--
--    uk_payment_account_no made account_no unique across every channel at once. That was right while
--    the column held a till number issued once by one gateway. It is wrong now: one organisation holds
--    several codes that resolve at Co-op to the same account — an operator code on the prompt, an
--    institution code and service name composed on the biller — and the platform-wide index refuses
--    the second one with a message about a duplicate that is not a duplicate.
--
--    uk_payment_account_code, added by V20260918120000, already says the rule that matters: two
--    accounts on ONE channel must not answer to one code, or every notification on it is
--    unattributable. This drops the broader index in favour of it.
--
--    The platform-wide rule is not simply abandoned — PaymentAccountService still applies it to every
--    channel with no descriptor, which is every channel predating this work. The database cannot make
--    that distinction (it would have to join payment_types), so the service makes it and says so.
DROP INDEX IF EXISTS uk_payment_account_no;

COMMENT ON COLUMN payment_accounts.account_no IS
    'The code this channel resolves by, composed from the fields the descriptor names in accountKey. '
    'Unique per channel, not per platform: one organisation holds several, all resolving at the bank '
    'to the same account. What an inbound notification is matched on.';

-- ┌─────────────────────────────────────────────────────────────────────────────────────────────
-- │ was V20260918160000__the_url_coop_calls_is_ours.sql
-- └─────────────────────────────────────────────────────────────────────────────────────────────
-- ─────────────────────────────────────────────────────────────────────────────────────────────────
-- The notification URL is ours to state, not theirs to type. And the allowed addresses are one fact
-- about one bank, not a field on every account.
--
-- Both came across from a gateway that sat between Co-op and many businesses. There, a business
-- supplied the address it wanted calling on and declared the addresses it would be called from,
-- because the gateway called businesses. This platform is the end system: Co-op calls it, at routes
-- this application defines. Nothing here calls out to a business, so nothing here should be asking
-- an operator to type one of our own URLs into a form.
--
-- The cost of leaving it was not cosmetic. A URL somebody types is a URL that can have a typo in the
-- one address that cannot be wrong, and one nobody updates when the route changes.
-- ─────────────────────────────────────────────────────────────────────────────────────────────────

-- ① The inbound account: no notification URL to type, no address list to keep.
--
--    The URL is declared as a display field — labelled, shown, filled in by the server from
--    platform.public.url, never stored and never required. The operator reads it off the screen and
--    gives it to Co-op, which is the actual task.
UPDATE payment_types SET account_config_fields = jsonb_build_object(
    'accountKey', jsonb_build_array('accountNumber'),
    'fields', jsonb_build_array(
        jsonb_build_object('key', 'accountNumber', 'label', 'Account number credits arrive in',
                           'type', 'text', 'required', true),
        jsonb_build_object('key', 'notificationUrl', 'label', 'Give Co-op this notification URL',
                           'type', 'display', 'fullWidth', true)))
 WHERE provider_type = 'COOP_IPN_ACCOUNT';

-- ② The biller: the same, twice.
--
--    validationUrl and adviceUrl were the end system's own addresses, for the gateway to call. We are
--    the end system, so Co-op calls these routes here and both are ours to state. What stays is what
--    the bank issues and what it authenticates with, which nobody but the bank can tell us.
UPDATE payment_types SET account_config_fields = jsonb_build_object(
    'accountsLabel', 'Biller',
    'accountKey', jsonb_build_array('institutionCode', 'serviceName'),
    'fields', jsonb_build_array(
        jsonb_build_object('key', 'institutionCode', 'label', 'Institution code (assigned by Co-op)',
                           'type', 'text', 'required', true),
        jsonb_build_object('key', 'serviceName', 'label', 'Service name (assigned by Co-op)',
                           'type', 'text', 'required', true),
        jsonb_build_object('key', 'institutionName', 'label', 'Institution name',
                           'type', 'text', 'required', true, 'fullWidth', true),
        jsonb_build_object('key', 'connectionID', 'label', 'Co-op connection ID',
                           'type', 'text', 'required', true),
        jsonb_build_object('key', 'connectionPassword', 'label', 'Co-op connection password',
                           'type', 'password', 'required', true),
        jsonb_build_object('key', 'validationUrl', 'label', 'Give Co-op this validation URL',
                           'type', 'display', 'fullWidth', true),
        jsonb_build_object('key', 'adviceUrl', 'label', 'Give Co-op this advice URL',
                           'type', 'display', 'fullWidth', true)))
 WHERE provider_type = 'COOP_BILLER';

-- ③ The channel's own wiring keeps the endpoint and loses the callback.
--
--    The endpoint is where WE call Co-op, which only Co-op can tell us. The callback was, again, our
--    own address.
UPDATE payment_types SET required_config_fields = jsonb_build_object(
    'fields', jsonb_build_array(
        jsonb_build_object('key', 'endpoint', 'label', 'Endpoint path',
                           'type', 'text', 'required', true, 'fullWidth', true)))
 WHERE provider_name = 'Co-operative Bank';

-- ④ And nowhere else either.
--
--    Defensive rather than decorative: these two keys were copied between descriptors while the shape
--    was being worked out, and a stray one would render a box asking for something we already know.
UPDATE payment_types
   SET config = config - 'callbackUrl' - 'whitelistedIps'
 WHERE config ?| array['callbackUrl', 'whitelistedIps'];

-- ⑤ Which addresses may notify us — one list, for the bank.
--
--    Empty accepts any address, deliberately. A blank list is "not narrowed yet", and reading it as
--    "trust nobody" would make an unconfigured deployment discard notifications for money that is
--    already in the bank. HTTP Basic is the control that fails closed; this one narrows it.
INSERT INTO configurations (config_key, config_value, value_type, category, label, description,
                            is_secret, is_overridable, status, status_flag, created_by)
VALUES ('coop.ipn.allowed.ips', '', 'STRING', 'INTEGRATION',
        'Addresses Co-op notifies us from',
        'Comma-separated IP addresses allowed to post payment notifications. Empty accepts any '
        'address, leaving HTTP Basic as the control.',
        false, false, 1, 'Active', 'migration')
ON CONFLICT (config_key) DO NOTHING;

-- ┌─────────────────────────────────────────────────────────────────────────────────────────────
-- │ was V20260918180000__the_platform_asks_for_money.sql
-- └─────────────────────────────────────────────────────────────────────────────────────────────
-- ─────────────────────────────────────────────────────────────────────────────────────────────────
-- Asking for money, and finding out whether it arrived.
--
-- Until now this platform could receive a payment notification and could not initiate anything. The
-- missing piece is not the call — CoopClient has been able to make one since V20260918100000 — it is
-- the row that remembers we asked. Without it there is nothing to query the bank about, nothing for a
-- callback to match, and nothing for a sweep to find.
-- ─────────────────────────────────────────────────────────────────────────────────────────────────

-- ① The intent.
--
--    Written BEFORE the call goes out. A request that dies on the socket has still reached the bank
--    often enough that "we never recorded it" is the expensive answer: Co-op may already have pushed
--    the prompt to the customer's handset. Remembering only what the reply told us loses exactly the
--    payments that need chasing.
CREATE TABLE payment_intents (
    id                       bigserial PRIMARY KEY,
    reference                varchar(16)  NOT NULL UNIQUE,

    payment_type_id          bigint       NOT NULL REFERENCES payment_types (id),
    payment_account_id       bigint       REFERENCES payment_accounts (id),

    -- Who it is for. The booking is the thing being paid; the property and the buyer are copied so a
    -- payment can be found by listing or by person without a join through a booking that may be
    -- cancelled later.
    booking_id               bigint       REFERENCES unit_bookings (id),
    property_id              bigint,
    buyer_user_id            bigint,

    amount                   numeric(15,2) NOT NULL CHECK (amount > 0),
    currency                 varchar(3)   NOT NULL DEFAULT 'KES',
    phone_no                 varchar(32),
    narration                varchar(160),

    -- PENDING: written, not yet sent. PROCESSING: the bank accepted it for processing and the answer
    -- is owed. SUCCEEDED / FAILED: terminal, and only ever reached on a definite answer.
    state                    varchar(16)  NOT NULL DEFAULT 'PENDING'
                                 CHECK (state IN ('PENDING', 'PROCESSING', 'SUCCEEDED', 'FAILED')),

    -- What the bank calls it. Their conversation id, and later their receipt.
    bank_reference           varchar(64),
    receipt                  varchar(64),

    processed_at             timestamptz,
    -- Copied from the setting at the moment of asking, not read live: changing the setting must not
    -- retroactively move the deadline of something already in flight.
    callback_timeout_seconds integer      NOT NULL DEFAULT 60,
    status_query_attempts    integer      NOT NULL DEFAULT 0,

    -- The sentence a person reads. An intent nobody can explain is worse than one that failed.
    processing_reason        text,

    statement_id             bigint       REFERENCES coop_statements (id),
    payment_id               bigint       REFERENCES payments (id),

    tenant_id                bigint,
    institution_id           bigint,

    status                   integer      NOT NULL DEFAULT 1,
    status_flag              varchar(16)  NOT NULL DEFAULT 'Active',
    created_at               timestamptz  NOT NULL DEFAULT now(),
    created_by               varchar(64),
    updated_at               timestamptz,
    updated_by               varchar(64)
);

COMMENT ON TABLE payment_intents IS
    'One request for money: what we asked for, who we asked, and what came back. Written before the '
    'call so a request that fails in transit is still something we can ask the bank about.';
COMMENT ON COLUMN payment_intents.callback_timeout_seconds IS
    'How long to wait for a callback before the sweep queries the bank. Copied at creation so a '
    'changed setting cannot move the deadline of something already in flight.';
COMMENT ON COLUMN payment_intents.status_query_attempts IS
    'Automatic queries used. Capped — without a cap a stuck intent is re-queried every sweep forever. '
    'A person''s manual query is neither counted here nor limited.';

-- The sweep's own query: in flight, past its deadline, oldest first.
CREATE INDEX ix_payment_intent_in_flight ON payment_intents (state, processed_at)
    WHERE state = 'PROCESSING';
-- Matching a callback back to what we asked for.
CREATE INDEX ix_payment_intent_bank_ref ON payment_intents (bank_reference)
    WHERE bank_reference IS NOT NULL;
CREATE INDEX ix_payment_intent_booking ON payment_intents (booking_id);
CREATE INDEX ix_payment_intent_buyer ON payment_intents (buyer_user_id);

-- ② The status query is its own channel, because it is its own endpoint.
--
--    The catalogue already models one payment type as one operation against the bank. A status query
--    is a second operation with a second path, so it is a second row rather than a second field on the
--    prompt — which is also how the endpoint stays configuration rather than code.
INSERT INTO payment_types (code, name, description, provider_name, provider_type, category, method,
                           is_electronic, is_account_based, requires_short_code, sort_order,
                           required_config_fields,
                           status, status_flag, created_by)
SELECT 'COOP_STK_STATUS', 'Co-op phone prompt — status query',
       'Asks Co-op what became of a prompt when no callback arrived.',
       'Co-operative Bank', 'COOP_STK_STATUS', 'STK_PUSH', method,
       -- Started from the application, and holding no account of its own: it asks about somebody
       -- else's prompt rather than collecting anything.
       true, false, false, sort_order + 1,
       jsonb_build_object('fields', jsonb_build_array(
           jsonb_build_object('key', 'endpoint', 'label', 'Endpoint path',
                              'type', 'text', 'required', true, 'fullWidth', true))),
       -- Inactive until somebody gives it a path, like every other Co-op channel.
       4, 'Inactive', 'migration'
  FROM payment_types
 WHERE provider_type = 'COOP_STK_PUSH'
 ON CONFLICT (code) DO NOTHING;

-- It takes no account of its own: it asks about somebody else's prompt.
UPDATE payment_types
   SET account_config_fields = jsonb_build_object('fields', jsonb_build_array())
 WHERE provider_type = 'COOP_STK_STATUS';

-- ③ How a status answer is read, and how often we may ask.
--
--    Co-op's "still processing" is a code and a description rather than an HTTP state, and the exact
--    values are theirs to change — so they are configured, not compiled. Anything unrecognised is
--    treated as still-processing, never as failure.
INSERT INTO configurations (config_key, config_value, value_type, category, label, description,
                            is_secret, is_overridable, status, status_flag, created_by)
VALUES
    ('coop.pending.status.codes', 'S_001', 'STRING', 'INTEGRATION',
     'Co-op still-processing codes',
     'Comma-separated MessageCode values meaning the payment is still in progress. Matched case '
     'insensitively. Anything not listed and not success is treated as a failure only when Co-op '
     'gives a code at all.',
     false, false, 1, 'Active', 'migration'),
    ('coop.pending.status.descriptions', 'PROCESSING', 'STRING', 'INTEGRATION',
     'Co-op still-processing descriptions',
     'Comma-separated MessageDescription values meaning the payment is still in progress.',
     false, false, 1, 'Active', 'migration'),
    ('coop.status.query.max.attempts', '2', 'INTEGER', 'INTEGRATION',
     'Automatic status queries per payment',
     'How many times the sweep asks Co-op about one stuck payment before leaving it for a person. '
     'Without a cap a stuck payment is re-queried every sweep indefinitely.',
     false, false, 1, 'Active', 'migration'),
    ('coop.callback.timeout.seconds', '60', 'INTEGER', 'INTEGRATION',
     'Seconds to wait for a callback',
     'How long a payment waits for Co-op to call back before the sweep asks what became of it.',
     false, false, 1, 'Active', 'migration')
ON CONFLICT (config_key) DO NOTHING;

-- ┌─────────────────────────────────────────────────────────────────────────────────────────────
-- │ was V20260918210000__the_bank_told_us_its_endpoints.sql
-- └─────────────────────────────────────────────────────────────────────────────────────────────
-- ─────────────────────────────────────────────────────────────────────────────────────────────────
-- An inbound channel has no endpoint to configure, because the endpoint is ours.
--
-- Every Co-op channel was given the same "Endpoint path" field. That is right for the two we call —
-- the prompt and its status query — and a category error for the two that call us. Co-op posts to a
-- route this application serves, at an address the account form already displays for somebody to hand
-- to the bank. A field asking for it invited an operator to type our own address back to us, and an
-- empty one made the channel look unconfigured when there was nothing to configure.
--
-- Nothing here sets an endpoint. The two outbound paths are already set on this deployment, and a
-- migration that wrote a literal over a configured value would silently redirect live calls on the
-- strength of a path that was current when this file was written.
-- ─────────────────────────────────────────────────────────────────────────────────────────────────

UPDATE payment_types
   SET required_config_fields = jsonb_build_object('fields', jsonb_build_array())
 WHERE provider_type IN ('COOP_IPN_ACCOUNT', 'COOP_BILLER');

COMMENT ON COLUMN payment_types.required_config_fields IS
    'Form descriptor: the fields this channel needs before it can be called. Empty for an inbound '
    'channel — Co-op calls us, at a route this application serves and the account form displays.';

-- ┌─────────────────────────────────────────────────────────────────────────────────────────────
-- │ was V20260918220000__an_account_asks_only_what_nobody_else_knows.sql
-- └─────────────────────────────────────────────────────────────────────────────────────────────
-- ─────────────────────────────────────────────────────────────────────────────────────────────────
-- An account asks only for what nobody else can tell us.
--
-- With Co-op's own collections in hand, every field can be checked against what the bank actually
-- sends and what this application actually reads. Three do not survive that check.
--
-- The rule being applied: a field earns its place only if the value is the operator's to supply and
-- is read by something. A field that is read by nothing is worse than clutter — somebody fills it in,
-- it takes effect nowhere, and the next person debugging a failed payment has a plausible wrong
-- answer sitting in front of them.
-- ─────────────────────────────────────────────────────────────────────────────────────────────────

-- ① The phone prompt keeps its operator code, and loses the credentials.
--
--    consumerKey and consumerSecret are read by nothing on an account. The OAuth token is fetched by
--    CoopClient from coop.consumer.key and coop.consumer.secret — one credential for the bank, which
--    is the decision already taken: only Co-op transacts here, so there is one set of keys, held once.
--    Asking for them again per account invited somebody to paste a second copy that would never be
--    used, and to conclude their key was wrong when a payment failed for another reason.
--
--    What remains is the operator code — "HODI" in the bank's own example — which is per account and
--    which nothing else knows.
UPDATE payment_types SET account_config_fields = jsonb_build_object(
    'accountKey', jsonb_build_array('accountNumber'),
    'fields', jsonb_build_array(
        jsonb_build_object('key', 'accountNumber', 'label', 'Operator code (assigned by Co-op)',
                           'type', 'text', 'required', true)))
 WHERE provider_type = 'COOP_STK_PUSH';

-- Values follow the descriptor: a secret nobody reads should not sit encrypted in a row either.
UPDATE payment_types SET config = config - 'consumerKey' - 'consumerSecret'
 WHERE provider_type = 'COOP_STK_PUSH' AND config IS NOT NULL;
UPDATE payment_accounts SET config = config - 'consumerKey' - 'consumerSecret'
 WHERE config ?| array['consumerKey', 'consumerSecret'];

-- ② The biller loses its institution name, because Co-op sends it on every advice.
--
--    Their payload carries "InstitutionName": "BREEZE ESTATE" alongside the code and the service name.
--    Storing it here is a second copy of something the bank restates each time, and the two disagree
--    the day somebody is renamed.
--
--    What stays is what only Co-op can tell us: the institution code and service name, which compose
--    the key an advice is matched on, and the connection credentials Co-op authenticates to US with —
--    per biller, so genuinely not a platform setting.
UPDATE payment_types SET account_config_fields = jsonb_build_object(
    'accountsLabel', 'Biller',
    'accountKey', jsonb_build_array('institutionCode', 'serviceName'),
    'fields', jsonb_build_array(
        jsonb_build_object('key', 'institutionCode', 'label', 'Institution code (assigned by Co-op)',
                           'type', 'text', 'required', true),
        jsonb_build_object('key', 'serviceName', 'label', 'Service name (assigned by Co-op)',
                           'type', 'text', 'required', true),
        jsonb_build_object('key', 'connectionID', 'label', 'Co-op connection ID',
                           'type', 'text', 'required', true),
        jsonb_build_object('key', 'connectionPassword', 'label', 'Co-op connection password',
                           'type', 'password', 'required', true),
        jsonb_build_object('key', 'validationUrl', 'label', 'Give Co-op this validation URL',
                           'type', 'display', 'fullWidth', true),
        jsonb_build_object('key', 'adviceUrl', 'label', 'Give Co-op this advice URL',
                           'type', 'display', 'fullWidth', true)))
 WHERE provider_type = 'COOP_BILLER';

UPDATE payment_accounts SET config = config - 'institutionName'
 WHERE config ? 'institutionName';

-- ③ The inbound account is already only its account number, and the status query already asks
--    nothing. Left alone, and said here so the next reader knows they were checked rather than missed.

-- ┌─────────────────────────────────────────────────────────────────────────────────────────────
-- │ was V20260918240000__a_method_says_what_kind_it_is.sql
-- └─────────────────────────────────────────────────────────────────────────────────────────────
-- ─────────────────────────────────────────────────────────────────────────────────────────────────
-- A method says what kind it is, and only one kind is a way to pay.
--
-- The catalogue had one shape for everything with an endpoint, so a status enquiry looked exactly
-- like a way to take money: switchable on, given an account, and offerable to somebody settling a
-- booking. The first attempt at fixing that archived the row, which threw away the thing that was
-- right about it — it has an endpoint, and endpoints belong on the method.
--
-- So the row stays, visible and configurable, and says what it is:
--
--   COLLECT  money coming in. The only kind offered when somebody chooses how to pay.
--   SEND     money going out. A method, chosen deliberately, never offered to a payer.
--   ENQUIRY  a question about a payment that already exists. It collects nothing and cannot be
--            chosen, because on its own it does not mean anything — a status check without another
--            transaction's reference has nothing to ask about.
--
-- The endpoint of every one of them is set on the method, which is where they were always going to
-- be looked for.
-- ─────────────────────────────────────────────────────────────────────────────────────────────────

ALTER TABLE payment_types ADD COLUMN kind varchar(16) NOT NULL DEFAULT 'COLLECT';

ALTER TABLE payment_types ADD CONSTRAINT ck_payment_type_kind
    CHECK (kind IN ('COLLECT', 'SEND', 'ENQUIRY'));

COMMENT ON COLUMN payment_types.kind IS
    'COLLECT: a way to take money, and the only kind offered when choosing how to pay. SEND: money '
    'going out. ENQUIRY: a question about a payment that already exists — it collects nothing and is '
    'never selectable, but it has an endpoint like everything else.';

-- ① The status enquiry comes back, as what it is.
UPDATE payment_types
   SET kind = 'ENQUIRY', status = 1, status_flag = 'Active',
       name = 'Co-op phone prompt — status',
       description = 'Asks Co-op what became of a phone prompt. Not a way to pay: it answers about a '
                     'payment that already exists.',
       updated_by = 'migration'
 WHERE provider_type = 'COOP_STK_STATUS';

-- ② The two enquiries that were missing entirely.
INSERT INTO payment_types (code, name, description, provider_name, provider_type, category, method,
                           kind, is_electronic, is_account_based, requires_short_code, sort_order,
                           required_config_fields, account_config_fields,
                           status, status_flag, created_by)
VALUES
    ('COOP_FT_STATUS', 'Co-op transfer — status',
     'Asks Co-op what became of a funds transfer when no callback arrived.',
     'Co-operative Bank', 'COOP_FT_STATUS', 'TRANSFER', 'BANK_TRANSFER',
     'ENQUIRY', true, false, false, 92,
     jsonb_build_object('fields', jsonb_build_array(
         jsonb_build_object('key', 'endpoint', 'label', 'Endpoint path',
                            'type', 'text', 'required', true, 'fullWidth', true))),
     jsonb_build_object('fields', jsonb_build_array()),
     1, 'Active', 'migration'),
    ('COOP_ACCOUNT_VALIDATION', 'Co-op account validation',
     'Resolves a destination account and returns the name it is held in. Asked before money is sent, '
     'never after.',
     -- TRANSFER, not VALIDATE: ck_payment_type_electronic reserves VALIDATE for channels the bank
     -- calls us on, and this is one we call. It belongs to the transfer flow anyway — it is the
     -- question asked immediately before money moves.
     'Co-operative Bank', 'COOP_ACCOUNT_VALIDATION', 'TRANSFER', 'BANK_TRANSFER',
     'ENQUIRY', true, false, false, 93,
     jsonb_build_object('fields', jsonb_build_array(
         jsonb_build_object('key', 'endpoint', 'label', 'Endpoint path',
                            'type', 'text', 'required', true, 'fullWidth', true))),
     jsonb_build_object('fields', jsonb_build_array()),
     1, 'Active', 'migration')
ON CONFLICT (code) DO NOTHING;

-- ③ Money going out is a method, and it is not a way for a buyer to pay.
INSERT INTO payment_types (code, name, description, provider_name, provider_type, category, method,
                           kind, is_electronic, is_account_based, requires_short_code, sort_order,
                           required_config_fields, account_config_fields,
                           status, status_flag, created_by)
VALUES ('COOP_PESALINK', 'Co-op PesaLink transfer',
        'Sends money to an account at another bank. The destination is validated and the holder''s '
        'name confirmed before anything moves.',
        'Co-operative Bank', 'COOP_PESALINK', 'TRANSFER', 'BANK_TRANSFER',
        'SEND', true, true, false, 90,
        jsonb_build_object('fields', jsonb_build_array(
            jsonb_build_object('key', 'endpoint', 'label', 'Endpoint path',
                               'type', 'text', 'required', true, 'fullWidth', true))),
        jsonb_build_object(
            'accountKey', jsonb_build_array('accountNumber'),
            'fields', jsonb_build_array(
                jsonb_build_object('key', 'accountNumber', 'label', 'Account money is sent from',
                                   'type', 'text', 'required', true))),
        4, 'Inactive', 'migration')
ON CONFLICT (code) DO UPDATE
   SET kind = 'SEND',
       required_config_fields = excluded.required_config_fields,
       account_config_fields = excluded.account_config_fields;

UPDATE payment_types SET kind = 'SEND'
 WHERE category = 'TRANSFER' AND kind = 'COLLECT' AND provider_type IS NOT NULL;

-- ┌─────────────────────────────────────────────────────────────────────────────────────────────
-- │ was V20260918250000__an_enquiry_is_its_own_category.sql
-- └─────────────────────────────────────────────────────────────────────────────────────────────
-- ─────────────────────────────────────────────────────────────────────────────────────────────────
-- An enquiry is its own category, not a transfer out.
--
-- The three enquiries were filed under the category of the thing they ask about — the STK status as
-- STK_PUSH, transfer status and account validation as TRANSFER — because `category` was the only
-- classification available when they were written and `kind` came later. That reads as a screen
-- saying this platform has three ways to send money out, two of which send nothing.
--
-- `category` says what a method does; `kind` says whether anybody may choose it. They are different
-- questions and both are needed: a Co-op prompt and a Co-op biller are both COLLECT, and no amount of
-- kind tells a form which one renders a phone number.
-- ─────────────────────────────────────────────────────────────────────────────────────────────────

ALTER TABLE payment_types DROP CONSTRAINT ck_payment_type_category;
ALTER TABLE payment_types ADD CONSTRAINT ck_payment_type_category
    CHECK (category IN ('CASH', 'CHEQUE', 'STK_PUSH', 'TRANSFER', 'VALIDATE', 'ENQUIRY'));

-- Started from the application, like every other outbound call, so the electronic rule admits it.
ALTER TABLE payment_types DROP CONSTRAINT ck_payment_type_electronic;
ALTER TABLE payment_types ADD CONSTRAINT ck_payment_type_electronic
    CHECK (NOT is_electronic OR category IN ('STK_PUSH', 'TRANSFER', 'ENQUIRY'));

UPDATE payment_types
   SET category = 'ENQUIRY', updated_by = 'migration'
 WHERE kind = 'ENQUIRY';

-- The accounts table carries a copy of the category, stamped from the channel. No enquiry has an
-- account, so nothing should match — the statement is here so the copy cannot drift if one ever does.
ALTER TABLE payment_accounts DROP CONSTRAINT ck_payment_account_category;
ALTER TABLE payment_accounts ADD CONSTRAINT ck_payment_account_category
    CHECK (category IN ('CASH', 'CHEQUE', 'STK_PUSH', 'TRANSFER', 'VALIDATE', 'ENQUIRY'));

-- ┌─────────────────────────────────────────────────────────────────────────────────────────────
-- │ was V20260918260000__a_method_wears_a_short_name.sql
-- └─────────────────────────────────────────────────────────────────────────────────────────────
-- ─────────────────────────────────────────────────────────────────────────────────────────────────
-- A method wears a short name, and wears the bank's name only once.
--
-- The three enquiries and the transfer arrived with sentences for names — "Co-op phone prompt —
-- status", "Co-op PesaLink transfer" — beside a column that also printed "Co-operative Bank". On a
-- list where every row is Co-op's, the bank is the half that tells nothing apart, and the em dash
-- pushed the half that does off a narrow screen.
--
-- So: "Co-op Bank" then what it is, matching the two rows that already read that way. The screen no
-- longer prints the provider beside it, because the name carries it.
-- ─────────────────────────────────────────────────────────────────────────────────────────────────

UPDATE payment_types SET name = 'Co-op Bank STK',                 updated_by = 'migration'
 WHERE provider_type = 'COOP_STK_PUSH';
UPDATE payment_types SET name = 'Co-op Bank STK Status',          updated_by = 'migration'
 WHERE provider_type = 'COOP_STK_STATUS';
UPDATE payment_types SET name = 'Co-op Bank FT Status',           updated_by = 'migration'
 WHERE provider_type = 'COOP_FT_STATUS';
UPDATE payment_types SET name = 'Co-op Bank Account Validation',  updated_by = 'migration'
 WHERE provider_type = 'COOP_ACCOUNT_VALIDATION';
UPDATE payment_types SET name = 'Co-op Bank PesaLink',            updated_by = 'migration'
 WHERE provider_type = 'COOP_PESALINK';

-- ┌─────────────────────────────────────────────────────────────────────────────────────────────
-- │ Where each method reaches Co-op.
-- └─────────────────────────────────────────────────────────────────────────────────────────────
--
-- The paths are Co-op's, published in their own collection, and the same for every deployment. They
-- ship filled in because asking somebody to retype a constant out of the bank's documentation is a
-- step that can only be got wrong — and a wrong endpoint fails as an HTML page from a firewall, which
-- reads like an outage rather than a typo.
--
-- The host is not here: it is one platform setting, because it is the one part that differs between a
-- sandbox, a proxy and production.
--
-- coalesce, never assignment. On a deployment where somebody has already set these — as this one had —
-- a migration that wrote a literal over a configured endpoint would silently redirect live calls on
-- the strength of a path that was current when this file was written.
UPDATE payment_types
   SET config = coalesce(config, '{}'::jsonb) || jsonb_build_object('endpoint',
           coalesce(nullif(config->>'endpoint', ''), '/FT/stk/1.0.0'))
 WHERE provider_type = 'COOP_STK_PUSH';

UPDATE payment_types
   SET config = coalesce(config, '{}'::jsonb) || jsonb_build_object('endpoint',
           coalesce(nullif(config->>'endpoint', ''), '/Enquiry/STK/1.0.0/'))
 WHERE provider_type = 'COOP_STK_STATUS';

UPDATE payment_types
   SET config = coalesce(config, '{}'::jsonb) || jsonb_build_object('endpoint',
           coalesce(nullif(config->>'endpoint', ''), '/Enquiry/TransactionStatus_V3/3.0.0/'))
 WHERE provider_type = 'COOP_FT_STATUS';

UPDATE payment_types
   SET config = coalesce(config, '{}'::jsonb) || jsonb_build_object('endpoint',
           coalesce(nullif(config->>'endpoint', ''), '/Enquiry/Validation/IPSL/1.0.0/'))
 WHERE provider_type = 'COOP_ACCOUNT_VALIDATION';

UPDATE payment_types
   SET config = coalesce(config, '{}'::jsonb) || jsonb_build_object('endpoint',
           coalesce(nullif(config->>'endpoint', ''), '/FundsTransfer/External/A2A/PesaLink_v2/2.0.0'))
 WHERE provider_type = 'COOP_PESALINK';
