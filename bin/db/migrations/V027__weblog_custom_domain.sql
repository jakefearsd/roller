-- Migration: per-weblog custom domain (virtual-host support).
--
-- NULL means "this weblog has no hostname of its own" and is served under
-- /<handle>/ on the site host, which is every weblog's behaviour before this
-- migration and stays the default afterwards.
--
-- The unique index is the real guarantee that two weblogs cannot claim one
-- hostname; the save-time 409 in WeblogConfigController exists to produce a
-- readable error rather than a constraint-violation 500. It is a partial index
-- so that the many NULL rows do not collide with each other -- PostgreSQL
-- already treats NULLs as distinct in a unique index, but stating it makes the
-- intent explicit and keeps the index small.

ALTER TABLE weblog
    ADD COLUMN IF NOT EXISTS custom_domain varchar(255);

CREATE UNIQUE INDEX IF NOT EXISTS uq_weblog_custom_domain
    ON weblog (custom_domain)
    WHERE custom_domain IS NOT NULL;
