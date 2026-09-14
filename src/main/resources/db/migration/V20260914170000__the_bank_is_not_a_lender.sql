-- ─────────────────────────────────────────────────────────────────────────────────────────────────
-- "Lender" leaves the schema.
--
-- The word described a party this platform no longer has: one bank among several, admitted to a
-- seller's portfolio by an arrangement it had to negotiate. There is one bank, it runs the platform,
-- and its people are platform staff. What was left was the vocabulary — a table called
-- lending_institutions holding exactly one row, which is the bank.
--
-- Renames, not rewrites. Every row, every foreign key and every id survives: RENAME changes a name in
-- the catalogue and nothing else, so this migration moves no data and cannot lose any. The columns
-- that point here keep the name institution_id, which is deliberate — that is the second ownership
-- axis on fifteen tables and three CHECK constraints, and renaming it is a separate piece of work
-- with its own risks. See COOP_BANK_AS_PLATFORM_PLAN.md §3.
--
-- Postgres carries indexes and constraints across a table rename automatically, so the only things
-- named explicitly below are the ones whose own names contain the word.
-- ─────────────────────────────────────────────────────────────────────────────────────────────────

ALTER TABLE lending_institutions RENAME TO banks;

-- The foreign keys still read *_institution_id_fkey, which is accurate: the column is institution_id.
-- Only the constraint that names the old table in its own name is renamed.
ALTER TABLE banks RENAME CONSTRAINT lending_institutions_pkey TO banks_pkey;

-- ── the partnership table ─────────────────────────────────────────────────────────────────────────
--
-- tenant_lender_partnerships is the last thing in the schema with "lender" in its name. Nothing has
-- read it since the Partnerships module was retired — PrincipalFactory lost the branch, and the
-- service, controller and repository were deleted — so it is inert.
--
-- Renamed rather than dropped. The rows are a record of who was partnered with whom and when it was
-- approved, which is the kind of thing somebody asks about a year later, and a DROP is the one step
-- in this file that could not be undone by another rename.
ALTER TABLE tenant_lender_partnerships RENAME TO retired_tenant_partnerships;
ALTER TABLE retired_tenant_partnerships
    RENAME CONSTRAINT tenant_lender_partnerships_pkey TO retired_tenant_partnerships_pkey;

