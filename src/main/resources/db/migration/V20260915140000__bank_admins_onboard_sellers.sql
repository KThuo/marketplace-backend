-- ─────────────────────────────────────────────────────────────────────────────────────────────────
-- The bank's administrators onboard sellers.
--
-- SELLERS_VIEW and SELLERS_DECIDE live in the TENANTS module, which is right — deciding a seller
-- application and administering the organisation it becomes are the same job. But that module's audience
-- was written before Co-op ran the platform and still reads:
--
--     SUPER_ADMIN, SUPPORT_ADMIN, PLATFORM_AUDITOR, SELLER_OWNER
--
-- So a BANK_ADMIN could hold neither, and EffectivePermissionResolver would have dropped both at login
-- even once they were granted — a queue that renders for nobody who is meant to work it.
--
-- The seeder deliberately never rewrites allowed_user_types once a row exists (an operator narrowing a
-- module's audience should not have it widened back on the next boot), so this is the only place it moves.
--
-- SELLER_OWNER stays: they reach TENANTS_SELF_VIEW through this module, which is how an organisation sees
-- its own record.
-- ─────────────────────────────────────────────────────────────────────────────────────────────────

UPDATE app_modules
SET allowed_user_types = allowed_user_types || ',BANK_ADMIN',
    updated_at         = now(),
    updated_by         = 'system'
WHERE code = 'TENANTS'
  -- Token-precise, so re-running adds nothing and a row that already names it is left alone.
  AND ',' || allowed_user_types || ',' NOT LIKE '%,BANK_ADMIN,%';
