-- Migration: soft delete for weblog entries.
--
-- trashed_at is nullable and carries no default: NULL means "not in the
-- trash", and the stamp exists only so the purge sweep and the trash list can
-- order and expire. The trash state itself lives in weblogentry.status as
-- PubStatus.TRASHED rather than in this column, because every query that
-- names a status then excludes trash by construction instead of needing a
-- condition that fails open when forgotten.

ALTER TABLE weblogentry
    ADD COLUMN IF NOT EXISTS trashed_at timestamp(3) with time zone;
