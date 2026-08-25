-- ═════════════════════════════════════════════════════════════════════════════════════════════════
-- The auctioneer register is reference data, not platform-private data.
--
-- M6 shipped with AUCTIONEERS admitting only platform staff, on the reasoning that whoever benefits from a
-- sale should not be the one confirming the auctioneer's licence. That reasoning is right about *maintaining*
-- the register and wrong about *reading* it: a lender cannot publish a lot without naming an auctioneer, so a
-- module nobody but the platform could read left every lender with an empty picker and no way to publish
-- anything at all.
--
-- The invariant that mattered is carried by the permission, not the module. AUCTIONEERS_MANAGE is
-- platform_only, so widening the module lets a principal read who is licensed and still leaves adding,
-- editing and deactivating with the platform. AUCTIONEERS_VIEW is not platform_only, so the owner-group
-- top-up hands it to existing organisations on the next boot without anybody editing a group by hand.
--
-- `allowed_user_types` is not re-seeded once the row exists — it is the super admin's to edit at runtime —
-- so the enum default alone would have fixed only databases created after this deploy.
-- ═════════════════════════════════════════════════════════════════════════════════════════════════

UPDATE app_modules
SET allowed_user_types = 'SUPER_ADMIN,SUPPORT_ADMIN,PLATFORM_AUDITOR,SELLER_OWNER,LISTING_MANAGER,'
                         || 'LENDER_ADMIN,MORTGAGE_OFFICER,CREDIT_ANALYST',
    updated_at         = now(),
    updated_by         = 'system'
WHERE code = 'AUCTIONEERS'
  -- Only where nobody has since edited it themselves. Their configuration outranks this default.
  AND allowed_user_types = 'SUPER_ADMIN,SUPPORT_ADMIN,PLATFORM_AUDITOR';
