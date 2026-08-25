-- ═════════════════════════════════════════════════════════════════════════════════════════════════
-- M5 — the actor-class CHECKs learn about VALUER
--
-- Two CHECKs, written in Phase 0a when there were four kinds of actor, now enumerate five. Found by the
-- seeder refusing to insert the new user type on boot — which is the constraint doing its job: a new actor
-- class is a deliberate change to who exists on this platform, and it should not be possible to introduce
-- one by editing an enum alone.
--
-- <p>Both are widened in the same migration because a user type nobody can hold a profile of is a row with
-- no purpose, and the pair is what makes the class real.
-- ═════════════════════════════════════════════════════════════════════════════════════════════════

ALTER TABLE user_types DROP CONSTRAINT ck_user_types_actor_class;
ALTER TABLE user_types ADD CONSTRAINT ck_user_types_actor_class
    CHECK (actor_class IN ('PLATFORM', 'SELLER', 'LENDER', 'BUYER', 'VALUER'));

ALTER TABLE user_profiles DROP CONSTRAINT ck_user_profiles_type;
ALTER TABLE user_profiles ADD CONSTRAINT ck_user_profiles_type
    CHECK (profile_type IN ('PLATFORM', 'SELLER', 'LENDER', 'BUYER', 'VALUER'));

-- The one-organisation CHECK already covers a valuer correctly: they carry neither, and "at most one" is
-- satisfied by none. Left exactly as it is — see the column comment on user_profiles.profile_type for why
-- the class is stored rather than inferred from those two nulls.
