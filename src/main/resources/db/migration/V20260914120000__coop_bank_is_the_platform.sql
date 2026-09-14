-- ─────────────────────────────────────────────────────────────────────────────────────────────────
-- The bank's staff become platform staff.
--
-- The platform was built for a marketplace of lending institutions: several banks, each partnered
-- with several sellers, each bank's people seeing exactly the sellers their institution had an
-- approved partnership with. There is one lender and they own the product, so there is no
-- partnership to negotiate and nobody to negotiate it with. The bank is not a participant here — it
-- is the platform.
--
-- So LENDER_ADMIN, MORTGAGE_OFFICER and CREDIT_ANALYST move from the LENDER actor class to
-- PLATFORM. That is the entire behavioural change: PrincipalFactory.resolveVisibleTenants tests
-- isPlatformActor() before it tests anything else, so these profiles stop resolving their visible
-- tenants through tenant_lender_partnerships and become unrestricted. No query is rewritten and the
-- partnership branch still exists — it is simply no longer reached by these types.
--
-- Two tables, because the actor class is stored twice on purpose. user_types.actor_class is the
-- definition; user_profiles.profile_type is the copy taken when a profile is provisioned, stored
-- rather than joined so that "both organisation columns are null" cannot be mistaken for a platform
-- administrator. Updating one without the other would classify a live user differently depending on
-- which of the two the reading code happened to consult.
--
-- Why a migration rather than the seeder: SeederService.seedUserTypes deliberately refuses to
-- rewrite actor_class from the enum and logs the mismatch at ERROR instead, because a type's actor
-- class decides which organisation column its holders carry and how their rows resolve. An edit
-- there is meant to arrive as a migration. This is that migration.
--
-- institution_id is deliberately left alone on every table. These people keep their institution, and
-- the bank keeps owning the developments it owns — the column is the "a bank owns this project"
-- axis, not a lending feature, and removing it is a separate stage with its own constraints to
-- relax. See claudedocs/COOP_BANK_AS_PLATFORM_PLAN.md §3.
--
-- Nothing is dropped and no row is deleted, so the way back is the same two UPDATEs reversed.
-- ─────────────────────────────────────────────────────────────────────────────────────────────────

-- ─────────────────────────────────────────────────────────────────────────────────────────────────
-- The one way the UPDATEs below can collide, checked first so it says what happened.
--
-- uk_user_profile_scope is unique on (user_id, profile_type, coalesce(tenant_id,0),
-- coalesce(institution_id,0)). Three distinct profile types collapsing into one PLATFORM value means
-- somebody holding, say, both a Mortgage Officer and a Credit Analyst profile at the same
-- institution ends up with two identical rows and the index refuses the second.
--
-- Raised before the UPDATE rather than caught after it: a unique-violation stack trace on a boot-time
-- migration names an index, not the two people it is actually about, and whoever reads it at six in
-- the morning has to work back from the index definition to the data. This names them.
-- ─────────────────────────────────────────────────────────────────────────────────────────────────
DO $$
DECLARE
    clashes text;
BEGIN
    SELECT string_agg(format('user_id=%s institution_id=%s (%s profiles)',
                             user_id, coalesce(institution_id, 0), n), '; ')
    INTO clashes
    FROM (SELECT user_id, institution_id, count(*) AS n
          FROM user_profiles
          WHERE user_type_code IN ('LENDER_ADMIN', 'MORTGAGE_OFFICER', 'CREDIT_ANALYST')
            AND status <> 5
          GROUP BY user_id, institution_id
          HAVING count(*) > 1) dupes;

    IF clashes IS NOT NULL THEN
        RAISE EXCEPTION
            'Cannot move the bank''s user types to PLATFORM: these people hold more than one of '
            'LENDER_ADMIN / MORTGAGE_OFFICER / CREDIT_ANALYST at the same institution, and the '
            'profiles would become indistinguishable. Retire the spare profile first. %', clashes;
    END IF;
END $$;

UPDATE user_types
SET actor_class = 'PLATFORM',
    updated_at  = now(),
    updated_by  = 'system'
WHERE code IN ('LENDER_ADMIN', 'MORTGAGE_OFFICER', 'CREDIT_ANALYST')
  AND actor_class <> 'PLATFORM';

-- Matched on the type code rather than on profile_type = 'LENDER', so a profile already corrected by
-- hand is not disturbed and a VALUER or AGENT row can never be caught by a broad class match.
UPDATE user_profiles
SET profile_type = 'PLATFORM',
    updated_at   = now(),
    updated_by   = 'system'
WHERE user_type_code IN ('LENDER_ADMIN', 'MORTGAGE_OFFICER', 'CREDIT_ANALYST')
  AND profile_type <> 'PLATFORM';

-- The names follow the meaning. "Lender Administrator" described somebody running one institution
-- among several; the same person now runs the bank's platform.
UPDATE user_types
SET name        = 'Bank Administrator',
    description = 'Full control of the bank''s platform: staff, user groups, lending configuration',
    updated_at  = now(),
    updated_by  = 'system'
WHERE code = 'LENDER_ADMIN';

UPDATE user_types
SET description = 'Works finance cases against seller portfolios',
    updated_at  = now(),
    updated_by  = 'system'
WHERE code = 'MORTGAGE_OFFICER';
