-- Migration: drop the W2 fossils.
--
-- Multi-locale (no theme ever read it), the Blogger API category (the API is
-- not in this codebase), the free-text analytics override (unreachable while
-- weblogAdminsUntrusted stays on), and the invitation `pending` flag (the
-- invite/accept ceremony is gone; access is granted directly).
--
-- Any roller_permission row still carrying pending = true is an unaccepted
-- invitation. Deleted below, before the column drops, rather than silently
-- promoted to an active grant the moment the column disappears -- see the
-- wave's task-9 report for the full reasoning. Guarded by an existence check
-- (rather than a bare DELETE) so re-applying this migration after the column
-- is already gone stays a no-op instead of failing on a missing column.

DO $$
DECLARE
    deleted_count integer;
BEGIN
    IF EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_name = 'roller_permission' AND column_name = 'pending'
    ) THEN
        DELETE FROM roller_permission WHERE pending = true;
        GET DIAGNOSTICS deleted_count = ROW_COUNT;
        RAISE NOTICE 'V023: removed % unaccepted-invitation row(s) from roller_permission (pending = true)', deleted_count;
    END IF;
END $$;

ALTER TABLE weblog
    DROP COLUMN IF EXISTS enablemultilang,
    DROP COLUMN IF EXISTS showalllangs,
    DROP COLUMN IF EXISTS bloggercatid,
    DROP COLUMN IF EXISTS analyticscode;

ALTER TABLE roller_permission
    DROP COLUMN IF EXISTS pending;
