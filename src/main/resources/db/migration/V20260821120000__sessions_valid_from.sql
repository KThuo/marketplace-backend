-- A cutoff for access tokens, per user.
--
-- Revoking refresh tokens stops a session being *renewed*; it does nothing about the access token already
-- in the caller's hands, which stays cryptographically valid for the remainder of its idle window. So
-- "change your password" and "sign this person out of everything" both left a usable credential alive for up
-- to twenty minutes — which is exactly the window that matters when either action is a response to a
-- suspected compromise.
--
-- JwtAuthenticationFilter now refuses any token whose `iat` predates this stamp. One column closes the whole
-- class: it applies to every outstanding token for that user, not just the one the caller happened to
-- present, which token blacklisting alone could never do (we do not hold the others).
--
-- Nullable, and null means "no cutoff" — the correct reading for every account that has never had one.
ALTER TABLE users ADD COLUMN sessions_valid_from TIMESTAMPTZ;

COMMENT ON COLUMN users.sessions_valid_from IS
    'Access tokens issued before this instant are refused. Bumped on password change, administrator '
    'password reset, and revoke-all-sessions.';
