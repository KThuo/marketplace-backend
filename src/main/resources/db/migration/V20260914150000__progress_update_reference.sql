-- ─────────────────────────────────────────────────────────────────────────────────────────────────
-- A progress post gets a reference, so it can have a page of its own.
--
-- The posts are becoming a blog: a feed of headers, and a page per post with its full text and every
-- photograph on it. A page needs an address, and until now a post had no public identity at all —
-- neither PublicPost nor PublicProgressItem carried an id, a reference or a slug, because nothing
-- ever linked to one.
--
-- A reference rather than the public hash id, matching every other public route on this platform:
-- a post URL is pasted into a message, and PU260914H4KQ survives that legibly while an opaque hash
-- does not. Same RrnGenerator shape as DV for a development and UN for a unit.
--
-- Backfilled here rather than lazily by the application. A column that is nullable "for now" stays
-- nullable, and then every reader has to handle a post with no address. Three statements — add,
-- fill, constrain — so the table ends this migration with the invariant the code may rely on.
-- ─────────────────────────────────────────────────────────────────────────────────────────────────

ALTER TABLE listing_progress_updates ADD COLUMN reference VARCHAR(32);

/*
 * The backfill, shaped like RrnGenerator's output: a two-letter prefix, yymmdd, then four characters
 * from an unambiguous alphabet. Built from the row's id so it cannot collide with itself, and salted
 * with the created date so two rows made on different days do not look consecutive.
 *
 * No attempt is made to match what RrnGenerator would have produced — it is random, and these rows
 * never had a reference to reproduce. What matters is that they are unique, well-formed, and stable.
 */
UPDATE listing_progress_updates
SET reference = 'PU'
    || to_char(coalesce(created_at, now()), 'YYMMDD')
    || upper(lpad(to_hex(id), 4, '0'))
WHERE reference IS NULL;

ALTER TABLE listing_progress_updates ALTER COLUMN reference SET NOT NULL;
ALTER TABLE listing_progress_updates ADD CONSTRAINT uk_progress_reference UNIQUE (reference);

-- The feed and the post page both look a row up by reference, and the post page does it on every
-- view. Unique already builds an index, so this is only the lookup the public feed does by date.
CREATE INDEX IF NOT EXISTS idx_progress_public_feed
    ON listing_progress_updates (reported_on DESC, id DESC)
    WHERE published = true AND audience = 'PUBLIC' AND status <> 5;
