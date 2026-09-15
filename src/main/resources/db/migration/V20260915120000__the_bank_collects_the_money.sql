-- ─────────────────────────────────────────────────────────────────────────────────────────────────
-- The bank collects. A seller reads the receipts.
--
-- PAYMENTS_RECEIVE, PAYMENTS_VOID and PAYMENT_TYPES_MANAGE were not platform-only, and
-- TenantService.createOwnerGroup grants a seller's owner group every non-platform-only permission whose
-- module admits SELLER_OWNER. So every seller organisation on the platform got all three at the moment it
-- was onboarded: a seller could write down that money had arrived against their own booking, reverse a
-- receipt a buyer had already seen, and change the account collections land in.
--
-- The enum now marks all three platform-only, which stops it happening again. The seeder only ever adds
-- permissions to a group — deliberately, so that something an organisation removed on purpose stays
-- removed — so the grants already out there have to be taken back here.
--
-- ── what is NOT touched ──────────────────────────────────────────────────────────────────────────
--
-- PAYMENTS_VIEW and PAYMENT_TYPES_VIEW. A seller has to be able to see what a buyer has paid against
-- their own unit and which account it went to; that is the whole of "sellers should only be able to view
-- the payment activities and status". Nothing about reading changes.
--
-- The bank's own Institution Administrator group keeps all three. Its user type is BANK_ADMIN, whose actor
-- class is PLATFORM — Co-op runs this platform, so its staff are the platform's staff. SeederService's
-- organisation top-up now tests the group's actor class rather than whether it carries an organisation id,
-- which is what makes "platform only" mean a platform actor rather than a null column.
-- ─────────────────────────────────────────────────────────────────────────────────────────────────

-- The catalogue first, so a boot that happens before the seeder reconciles it still reads correctly.
UPDATE permissions
SET platform_only = true, updated_at = now(), updated_by = 'system'
WHERE action_code IN ('PAYMENTS_RECEIVE', 'PAYMENTS_VOID', 'PAYMENT_TYPES_MANAGE');

-- ── revoke from every group whose user type is not a platform actor ──────────────────────────────
--
-- By actor class rather than by naming SELLER_OWNER, so an agent's or a vendor's group is covered by the
-- same statement — none of them should be recording money either, and listing the types by hand is how one
-- gets missed.
DO $$
DECLARE
    revoked bigint;
BEGIN
    WITH gone AS (
        DELETE FROM user_group_permissions gp
        USING user_groups g, permissions p, user_types t
        WHERE gp.user_group_id = g.id
          AND gp.permission_id = p.id
          AND t.code = g.user_type_code
          AND t.actor_class <> 'PLATFORM'
          AND p.action_code IN ('PAYMENTS_RECEIVE', 'PAYMENTS_VOID', 'PAYMENT_TYPES_MANAGE')
        RETURNING gp.user_group_id
    )
    SELECT count(*) INTO revoked FROM gone;

    IF revoked > 0 THEN
        RAISE NOTICE 'Revoked % payment-write grant(s) from non-platform groups', revoked;
    END IF;
END $$;
