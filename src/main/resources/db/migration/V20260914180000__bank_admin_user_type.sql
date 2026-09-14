-- ─────────────────────────────────────────────────────────────────────────────────────────────────
-- LENDER_ADMIN becomes BANK_ADMIN.
--
-- The type has been called "Bank Administrator" on screen since V20260914120000 moved it onto the
-- platform actor class. The code underneath still said lender, and the code is what a super admin
-- reads on the user-types screen and types into a module's audience list.
--
-- Three tables carry it: the catalogue row, and the denormalised copy on every profile and group.
-- SeederService deliberately refuses to rewrite a user type's code, so a migration is the only way it
-- moves.
--
-- ── the duplicate this has to clean up ────────────────────────────────────────────────────────────
--
-- Renaming the enum constant before this migration existed meant the seeder, on its next boot, found
-- no row with code BANK_ADMIN and created one. A database that booted in that window now has two
-- rows — the real LENDER_ADMIN carrying every profile and group, and an empty BANK_ADMIN beside it.
-- Renaming the first into the second would then collide on the unique code.
--
-- So the empty one goes first, and only if it really is empty. If somebody has already attached a
-- person to it, this stops rather than guessing which of the two is the real one.
-- ─────────────────────────────────────────────────────────────────────────────────────────────────

DO $$
DECLARE
    stale_id  bigint;
    real_id   bigint;
    attached  bigint;
BEGIN
    SELECT id INTO real_id  FROM user_types WHERE code = 'LENDER_ADMIN';
    SELECT id INTO stale_id FROM user_types WHERE code = 'BANK_ADMIN';

    -- Nothing to rename: a fresh database, where the seeder created BANK_ADMIN and no lender row ever
    -- existed. That is the end state this migration is trying to reach, so it is already correct.
    IF real_id IS NULL THEN
        RETURN;
    END IF;

    IF stale_id IS NOT NULL THEN
        SELECT count(*) INTO attached FROM (
            SELECT 1 FROM user_profiles WHERE user_type_id = stale_id
            UNION ALL
            SELECT 1 FROM user_groups   WHERE user_type_id = stale_id
        ) held;

        IF attached > 0 THEN
            RAISE EXCEPTION
                'Both LENDER_ADMIN (id %) and BANK_ADMIN (id %) exist and the second has % profile(s) '
                'or group(s) attached. Merge them by hand: this migration will not choose which is real.',
                real_id, stale_id, attached;
        END IF;

        DELETE FROM user_types WHERE id = stale_id;
    END IF;

    UPDATE user_types    SET code = 'BANK_ADMIN', updated_at = now(), updated_by = 'system'
    WHERE id = real_id;
    UPDATE user_profiles SET user_type_code = 'BANK_ADMIN', updated_at = now(), updated_by = 'system'
    WHERE user_type_code = 'LENDER_ADMIN';
    UPDATE user_groups   SET user_type_code = 'BANK_ADMIN', updated_at = now(), updated_by = 'system'
    WHERE user_type_code = 'LENDER_ADMIN';
END $$;

-- Every module's audience names the types it admits, as a CSV the super admin edits. The seeder never
-- rewrites allowed_user_types once a row exists, so the rename has to reach it here or every module
-- would silently stop admitting the bank's administrator.
--
-- Token-precise: wrapping both sides in commas makes the match exact, so LENDER_ADMIN cannot partially
-- match some other code that happens to contain it.
UPDATE app_modules
SET allowed_user_types = trim(both ',' from replace(
        ',' || allowed_user_types || ',', ',LENDER_ADMIN,', ',BANK_ADMIN,')),
    updated_at         = now(),
    updated_by         = 'system'
WHERE ',' || allowed_user_types || ',' LIKE '%,LENDER_ADMIN,%';

-- ── the actor class ───────────────────────────────────────────────────────────────────────────────
--
-- No row has carried 'LENDER' since V20260914120000, and no user type produces it any more. Both
-- CHECKs still listed it and omitted the four classes that do exist — VALUER, AGENT and VENDOR were
-- added after these constraints were written and nobody widened them, which means a user type of
-- those classes could never have been created through the API. This narrows and widens in one step.
ALTER TABLE user_profiles DROP CONSTRAINT IF EXISTS ck_user_profiles_type;
ALTER TABLE user_profiles ADD CONSTRAINT ck_user_profiles_type
    CHECK (profile_type IN ('PLATFORM', 'SELLER', 'BUYER', 'VALUER', 'AGENT', 'VENDOR'));

ALTER TABLE user_types DROP CONSTRAINT IF EXISTS ck_user_types_actor_class;
ALTER TABLE user_types ADD CONSTRAINT ck_user_types_actor_class
    CHECK (actor_class IN ('PLATFORM', 'SELLER', 'BUYER', 'VALUER', 'AGENT', 'VENDOR'));
