-- ─────────────────────────────────────────────────────────────────────────────────────────────────
-- The Lenders and Partnerships modules are retired.
--
-- They described a marketplace: a directory of banks to browse and administer, and a negotiation by
-- which one of them was granted sight of one seller's portfolio. There is one bank, it runs the
-- platform, and its staff are platform staff — so there is no directory worth keeping and nothing
-- left to request, approve or revoke.
--
-- Why this file has to exist at all: SeederService only ever ADDS and reconciles. It has no step that
-- retires a row whose enum entry has disappeared, so deleting INSTITUTIONS / PARTNERSHIPS from
-- AppModuleEnum and their codes from AppPermissionEnum changes nothing about a database that already
-- has them. The permissions would stay attached to every group that holds them, the resolver would go
-- on handing them out, and the workspace navigation — which is filtered on the effective permission
-- set, not on a hardcoded list — would go on showing "Lenders" and "Partnerships". This is the half
-- that actually removes them.
--
-- Detach, then archive. Deleting the permission rows outright would break the foreign key from
-- user_group_permissions and lose the audit trail of who once held them; archiving keeps both while
-- making them unreachable, which is the same status = 5 soft lifecycle the rest of the platform uses.
--
-- What is deliberately NOT touched:
--   * lending_institutions — the bank's own record. mortgage_products.institution_id is NOT NULL and
--     references it, and mortgage products are staying. What is gone is the module that let somebody
--     manage a *list* of banks, not the one row the bank is.
--   * tenant_lender_partnerships — the table and its rows stay. Nothing reads it any more, so it is
--     inert, and dropping a table is the one step here that cannot be undone by an UPDATE.
--   * institution_id on any table. It is the "a bank owns this project" ownership axis, and three
--     tables CHECK that exactly one owner is present. See COOP_BANK_AS_PLATFORM_PLAN.md §3.
-- ─────────────────────────────────────────────────────────────────────────────────────────────────

-- 1. Take the permissions off every group that holds them. This is the step that empties the nav.
DELETE FROM user_group_permissions
WHERE permission_id IN (
    SELECT id FROM permissions
    WHERE module_code IN ('INSTITUTIONS', 'PARTNERSHIPS'));

-- 2. Archive the permissions themselves, so nothing can grant them again from the permission screen.
UPDATE permissions
SET status      = 5,
    status_flag = 'Deleted',
    updated_at  = now(),
    updated_by  = 'system'
WHERE module_code IN ('INSTITUTIONS', 'PARTNERSHIPS')
  AND status <> 5;

-- 3. And the modules, which also takes them off the module-configuration screen.
UPDATE app_modules
SET status      = 5,
    status_flag = 'Deleted',
    updated_at  = now(),
    updated_by  = 'system'
WHERE code IN ('INSTITUTIONS', 'PARTNERSHIPS')
  AND status <> 5;

-- 4. Nothing should still be switching these on per organisation.
DELETE FROM tenant_modules
WHERE app_module_id IN (SELECT id FROM app_modules WHERE code IN ('INSTITUTIONS', 'PARTNERSHIPS'));

-- ─────────────────────────────────────────────────────────────────────────────────────────────────
-- The one institution row is the bank, so it should say so.
--
-- The seeded demo row is named "Equatorial Bank", which is what the Lenders screen has been showing.
-- Renamed rather than deleted: mortgage_products.institution_id references it with NOT NULL, and
-- institution_name is denormalised onto mortgage_products and developments, so the label is re-stamped
-- in the same transaction rather than left to drift.
--
-- Guarded on there being exactly one row. A deployment that really does have several institutions is
-- not one this rename can reason about, and silently renaming the first of them would be worse than
-- leaving the data alone for somebody to look at.
-- ─────────────────────────────────────────────────────────────────────────────────────────────────
DO $$
DECLARE
    only_id bigint;
BEGIN
    SELECT id INTO only_id FROM lending_institutions WHERE status <> 5;
    IF only_id IS NULL OR (SELECT count(*) FROM lending_institutions WHERE status <> 5) <> 1 THEN
        RAISE NOTICE 'Not exactly one lending institution — leaving names alone for a person to review.';
        RETURN;
    END IF;

    UPDATE lending_institutions
    SET name = 'Co-operative Bank', updated_at = now(), updated_by = 'system'
    WHERE id = only_id AND name = 'Equatorial Bank';

    UPDATE mortgage_products SET institution_name = 'Co-operative Bank'
    WHERE institution_id = only_id AND institution_name = 'Equatorial Bank';

    UPDATE developments SET institution_name = 'Co-operative Bank'
    WHERE institution_id = only_id AND institution_name = 'Equatorial Bank';
END $$;
