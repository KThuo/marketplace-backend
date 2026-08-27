-- ═════════════════════════════════════════════════════════════════════════════════════════════════
-- A post is written for the public, or it is written for the file
--
-- The public progress feed was built to show every published update in full: the percentage, the stage, the
-- phase it belonged to. That is detailed build reporting, and detailed build reporting is not what a stranger
-- browsing a marketplace should be reading. What belongs in front of them is the other thing entirely — a
-- post written to interest somebody in a project, with photographs.
--
-- The two have different audiences, different tone, and different consequences if they leak. So the row says
-- which it is.
--
--   * PUBLIC       — a blog or newsletter post. Title, words, photographs. On the project's page and the
--                    site's feed.
--   * STAKEHOLDERS — a detailed update: the percentage, the stage, the phase. In the workspace, for the
--                    people running and financing the build.
--
-- <h2>Why PUBLIC is the default</h2>
--
-- Every existing row is a listing's progress update, and those are already shown on the listing's own page
-- today. Defaulting to STAKEHOLDERS would silently withdraw content sellers have already published, which is
-- a behaviour change nobody asked for and nobody would see happen. New development posts are asked which they
-- are; a listing's timeline behaves exactly as it did.
--
-- <h2>What this does not do</h2>
--
-- It does not make a PUBLIC post safe by itself. The public *shape* also has to stop carrying the percentage
-- and the stage, because a marketing post that happens to be published should not become a detailed report
-- through the fields around it. That is in the response records, not here.
-- ═════════════════════════════════════════════════════════════════════════════════════════════════

ALTER TABLE listing_progress_updates
    ADD COLUMN audience VARCHAR(24) NOT NULL DEFAULT 'PUBLIC';

ALTER TABLE listing_progress_updates ADD CONSTRAINT ck_progress_audience
    CHECK (audience IN ('PUBLIC', 'STAKEHOLDERS'));

COMMENT ON COLUMN listing_progress_updates.audience IS
    'PUBLIC is a blog or newsletter post written to interest somebody in the project. STAKEHOLDERS is a '
    'detailed update — percentage, stage, phase — for the people running and financing the build.';

/*
 * The public reads gain the audience.
 *
 * Replacing the two indexes rather than adding a third: every public query now filters on audience as well,
 * and an index that does not carry it would be scanned and then filtered, which is the shape that looks fine
 * on a hundred rows and does not on a hundred thousand.
 */
DROP INDEX IF EXISTS idx_progress_development_public;
CREATE INDEX idx_progress_development_public ON listing_progress_updates (development_id, reported_on DESC)
    WHERE development_id IS NOT NULL AND published AND audience = 'PUBLIC' AND status <> 5;

DROP INDEX IF EXISTS idx_progress_feed;
CREATE INDEX idx_progress_feed ON listing_progress_updates (reported_on DESC, id DESC)
    WHERE published AND audience = 'PUBLIC' AND status <> 5;
