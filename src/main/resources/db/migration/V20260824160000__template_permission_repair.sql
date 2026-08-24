-- ═════════════════════════════════════════════════════════════════════════════════════════════════
-- Templates that named permissions their own user type cannot hold.
--
-- A shared template is the shape an organisation clones to build a role. Three of the seeded ones carried a
-- permission the module matrix does not admit for their user type, which made them un-clonable: the resolver
-- refuses the whole set with "these permissions are not available for this kind of user", so the template
-- looked fine on the groups screen and failed the moment anybody tried to use it.
--
-- Two of the three are the matrix being wrong, one is the template being wrong:
--
--   * Platform Auditor held TENANTS_VIEW and INSTITUTIONS_VIEW, and neither module admitted the type. An
--     auditor who cannot resolve an organisation's name is reading a trail of bare ids, so the modules are
--     widened — read-only, since the template holds nothing but the VIEW.
--   * Support Administrator held AUDIT_VIEW, and AUDIT admits SUPER_ADMIN and PLATFORM_AUDITOR. Here the
--     template is the wrong one: the audit trail is precisely what separates an auditor from support, and
--     widening AUDIT would erase a distinction the user types exist to draw. The permission is removed.
--
-- The seeder does not rewrite `allowed_user_types` once a row exists — it is the field a super admin edits at
-- runtime, and a deploy silently reverting their configuration would be worse than this migration. So the two
-- widenings are applied here, and only where the token is genuinely absent.
-- ═════════════════════════════════════════════════════════════════════════════════════════════════

UPDATE app_modules
SET allowed_user_types = allowed_user_types || ',PLATFORM_AUDITOR'
WHERE code IN ('TENANTS', 'INSTITUTIONS')
  AND 'PLATFORM_AUDITOR' <> ALL (
      SELECT btrim(x) FROM unnest(string_to_array(allowed_user_types, ',')) AS x);

-- The general repair, not a one-off: any template permission whose module does not admit the template's user
-- type is dropped. After the widenings above this removes exactly the Support Administrator row, and it keeps
-- the invariant true for anything seeded later.
DELETE FROM user_group_permissions ugp
USING user_groups g, permissions p, app_modules m
WHERE ugp.user_group_id = g.id
  AND ugp.permission_id = p.id
  AND m.code = p.module_code
  AND g.is_template = true
  AND g.user_type_code <> ALL (
      SELECT btrim(x) FROM unnest(string_to_array(m.allowed_user_types, ',')) AS x);
