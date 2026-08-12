-- Migration: drop the comment subsystem.
--
-- Comments were unreachable by any real reader: comment_auth_required
-- defaulted to true (V013) and public self-registration had already been
-- removed, so only an administrator-provisioned account could post one.
-- The contact form and newsletter are the reader channels now.
--
-- NOTE: roller_audit_log.comment_text is NOT a comment column -- it is the
-- audit log's change note. It is deliberately untouched here.

DROP INDEX IF EXISTS co_entryid_idx;
DROP INDEX IF EXISTS co_status_idx;
DROP TABLE IF EXISTS roller_comment;

ALTER TABLE weblog
    DROP COLUMN IF EXISTS allowcomments,
    DROP COLUMN IF EXISTS emailcomments,
    DROP COLUMN IF EXISTS defaultallowcomments,
    DROP COLUMN IF EXISTS defaultcommentdays,
    DROP COLUMN IF EXISTS commentmod,
    DROP COLUMN IF EXISTS comment_auth_required;

ALTER TABLE weblogentry
    DROP COLUMN IF EXISTS allowcomments,
    DROP COLUMN IF EXISTS commentdays;
