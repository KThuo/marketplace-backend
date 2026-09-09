-- Indexed fuzzy search for every list screen: a generated, stored, lowercased concatenation of the
-- columns a person would actually search by, with a pg_trgm GIN index over it. Filtering, ordering and
-- pagination then all run in Postgres instead of loading whole tables into the JVM and scanning them.
--
-- Why a generated column rather than an expression index per field: one index serves a term typed
-- against any of the fields, in any word order, and the column cannot drift out of step with the row
-- because Postgres maintains it. SearchSpecs.fuzzy() splits the term on whitespace and ANDs substring
-- matches against this column, which is what makes "nairobi cash" find "Cashier — Nairobi Branch".
--
-- pg_trgm's operator class, wherever the extension lives.
-- An extension exists once per database, in one schema, and on a shared server that schema is whichever
-- application installed it first — public on one machine, another application's schema on the next. Rather
-- than guess, look it up and put it on this transaction's search path; Flyway runs the whole script in one
-- transaction, so every gin_trgm_ops below resolves. Installs it into our own schema if nobody has yet.
--
-- plpgsql first: the lookup is a DO block, and a database created from template0 has no procedural language.
-- It is a trusted extension, so the database owner may create it, and it is a no-op everywhere else.
CREATE EXTENSION IF NOT EXISTS plpgsql;

DO $$
DECLARE ext_schema text;
BEGIN
    SELECT n.nspname INTO ext_schema
      FROM pg_extension e JOIN pg_namespace n ON n.oid = e.extnamespace
     WHERE e.extname = 'pg_trgm';
    IF ext_schema IS NULL THEN
        EXECUTE 'CREATE EXTENSION pg_trgm';
        ext_schema := current_schema();
    END IF;
    EXECUTE format('SET LOCAL search_path TO %I, %I', current_schema(), ext_schema);
END $$;

-- ─────────────────────────────────────────────────────────────────────────────────────────────────
-- users
--
-- Includes the denormalised label columns on purpose: somebody looking for "mortgage officer equity"
-- is searching by role and organisation, not by a foreign key, and those labels are already on the row.
-- Both organisation names are in here because a user belongs to at most one, so only one is ever
-- non-empty and the concatenation stays short.
-- ─────────────────────────────────────────────────────────────────────────────────────────────────
ALTER TABLE users ADD COLUMN search_text text
    GENERATED ALWAYS AS (
        lower(coalesce(first_name, '') || ' ' || coalesce(last_name, '') || ' ' ||
              coalesce(username, '') || ' ' || coalesce(email, '') || ' ' ||
              coalesce(phone, '') || ' ' || coalesce(user_group_name, '') || ' ' ||
              coalesce(user_type_name, '') || ' ' || coalesce(tenant_name, '') || ' ' ||
              coalesce(institution_name, ''))
    ) STORED;

-- NOTE at production scale: rebuild these as CREATE INDEX CONCURRENTLY in a separate,
-- non-transactional migration — the form below takes an ACCESS EXCLUSIVE lock on the table.
CREATE INDEX idx_users_search_trgm ON users USING gin (search_text gin_trgm_ops);

-- ─────────────────────────────────────────────────────────────────────────────────────────────────
-- user_groups
-- ─────────────────────────────────────────────────────────────────────────────────────────────────
ALTER TABLE user_groups ADD COLUMN search_text text
    GENERATED ALWAYS AS (
        lower(coalesce(name, '') || ' ' || coalesce(description, '') || ' ' ||
              coalesce(user_type_code, '') || ' ' || coalesce(user_type_name, ''))
    ) STORED;

CREATE INDEX idx_user_groups_search_trgm ON user_groups USING gin (search_text gin_trgm_ops);

-- ─────────────────────────────────────────────────────────────────────────────────────────────────
-- user_types
-- ─────────────────────────────────────────────────────────────────────────────────────────────────
ALTER TABLE user_types ADD COLUMN search_text text
    GENERATED ALWAYS AS (
        lower(coalesce(code, '') || ' ' || coalesce(name, '') || ' ' ||
              coalesce(description, '') || ' ' || coalesce(actor_class, ''))
    ) STORED;

CREATE INDEX idx_user_types_search_trgm ON user_types USING gin (search_text gin_trgm_ops);

-- ─────────────────────────────────────────────────────────────────────────────────────────────────
-- app_modules
--
-- allowed_user_types is in the search text so a super admin can find "which modules admit
-- MORTGAGE_OFFICER" by typing the code. It stays out of every WHERE clause — see the baseline's note
-- on why a LIKE against that column is wrong.
-- ─────────────────────────────────────────────────────────────────────────────────────────────────
ALTER TABLE app_modules ADD COLUMN search_text text
    GENERATED ALWAYS AS (
        lower(coalesce(code, '') || ' ' || coalesce(name, '') || ' ' ||
              coalesce(description, '') || ' ' || coalesce(allowed_user_types, ''))
    ) STORED;

CREATE INDEX idx_app_modules_search_trgm ON app_modules USING gin (search_text gin_trgm_ops);

-- ─────────────────────────────────────────────────────────────────────────────────────────────────
-- tenants and lending_institutions — both searched by name, reference and contact
-- ─────────────────────────────────────────────────────────────────────────────────────────────────
ALTER TABLE tenants ADD COLUMN search_text text
    GENERATED ALWAYS AS (
        lower(coalesce(name, '') || ' ' || coalesce(slug, '') || ' ' ||
              coalesce(tenant_ref, '') || ' ' || coalesce(contact_name, '') || ' ' ||
              coalesce(contact_email, '') || ' ' || coalesce(contact_phone, '') || ' ' ||
              coalesce(onboarding_status, ''))
    ) STORED;

CREATE INDEX idx_tenants_search_trgm ON tenants USING gin (search_text gin_trgm_ops);

ALTER TABLE lending_institutions ADD COLUMN search_text text
    GENERATED ALWAYS AS (
        lower(coalesce(name, '') || ' ' || coalesce(slug, '') || ' ' ||
              coalesce(institution_ref, '') || ' ' || coalesce(institution_type, '') || ' ' ||
              coalesce(licence_number, '') || ' ' || coalesce(contact_name, '') || ' ' ||
              coalesce(contact_email, ''))
    ) STORED;

CREATE INDEX idx_institutions_search_trgm
    ON lending_institutions USING gin (search_text gin_trgm_ops);

-- ─────────────────────────────────────────────────────────────────────────────────────────────────
-- tenant_lender_partnerships — searched from all three sides, by the other side's name
-- ─────────────────────────────────────────────────────────────────────────────────────────────────
ALTER TABLE tenant_lender_partnerships ADD COLUMN search_text text
    GENERATED ALWAYS AS (
        lower(coalesce(tenant_name, '') || ' ' || coalesce(institution_name, '') || ' ' ||
              coalesce(portfolio_scope, '') || ' ' || coalesce(requested_by_side, ''))
    ) STORED;

CREATE INDEX idx_partnerships_search_trgm
    ON tenant_lender_partnerships USING gin (search_text gin_trgm_ops);

-- ─────────────────────────────────────────────────────────────────────────────────────────────────
-- configurations
-- ─────────────────────────────────────────────────────────────────────────────────────────────────
-- config_value is deliberately NOT in here. Some values are encrypted secrets, and a generated
-- column is stored in the clear and indexed — putting them in would defeat the encryption for anyone
-- who can read the table, which is the population the encryption exists for.
ALTER TABLE configurations ADD COLUMN search_text text
    GENERATED ALWAYS AS (
        lower(coalesce(config_key, '') || ' ' || coalesce(category, '') || ' ' ||
              coalesce(label, '') || ' ' || coalesce(description, ''))
    ) STORED;

CREATE INDEX idx_configurations_search_trgm
    ON configurations USING gin (search_text gin_trgm_ops);

-- ─────────────────────────────────────────────────────────────────────────────────────────────────
-- audit_logs
--
-- actor_username is what actually identifies a person here, because the user row it refers to may
-- have been archived — so a trigram lookup over these labels is the right way to find somebody's
-- trail rather than a convenience.
-- ─────────────────────────────────────────────────────────────────────────────────────────────────
ALTER TABLE audit_logs ADD COLUMN search_text text
    GENERATED ALWAYS AS (
        lower(coalesce(actor_username, '') || ' ' || coalesce(operation, '') || ' ' ||
              coalesce(entity, '') || ' ' || coalesce(outcome, '') || ' ' ||
              coalesce(actor_user_type, '') || ' ' || coalesce(ip_address, ''))
    ) STORED;

CREATE INDEX idx_audit_search_trgm ON audit_logs USING gin (search_text gin_trgm_ops);
