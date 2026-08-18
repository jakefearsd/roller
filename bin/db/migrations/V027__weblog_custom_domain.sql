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

-- The Grafana/SEO join key. analytics_weblog_sites already carries
-- handle <-> Umami website id; adding the hostname makes it the single place
-- that maps a Search Console property to a weblog. The WHERE is widened
-- (OR, not AND) so a weblog with a hostname but no Umami id still appears --
-- exactly the state a weblog is in immediately after being given a domain,
-- which is precisely when the SEO tooling needs to find it.
CREATE OR REPLACE VIEW analytics_weblog_sites AS
SELECT handle            AS weblog_handle,
       analytics_site_id AS website_id,
       custom_domain     AS custom_domain
FROM weblog
WHERE analytics_site_id IS NOT NULL
   OR custom_domain IS NOT NULL;

GRANT SELECT ON analytics_weblog_sites TO grafana_ro;
