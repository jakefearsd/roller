-- Licensed to the Apache Software Foundation (ASF) under one or more
-- contributor license agreements.  The ASF licenses this file to You
-- under the Apache License, Version 2.0 (the "License"); you may not
-- use this file except in compliance with the License.
-- You may obtain a copy of the License at
--
--     http://www.apache.org/licenses/LICENSE-2.0
--
-- Unless required by applicable law or agreed to in writing, software
-- distributed under the License is distributed on an "AS IS" BASIS,
-- WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
-- See the License for the specific language governing permissions and
-- limitations under the License.
--
-- Migration: the analytics contract
--
-- 1. Per-weblog Umami wiring: analytics_site_id is the Umami website UUID
--    the theme macro builds the tracker tag from -- structured data, not
--    raw HTML, which is what lets per-weblog analytics coexist with
--    weblogAdminsUntrusted. analytics_share_url is the operator's saved
--    link to the Umami share dashboard; display-only.
--
-- 2. The Grafana contract, rollerdb half: versioned views over first-party
--    events, plus the site-id-to-handle mapping Grafana joins Umami traffic
--    against. The Umami half (analytics_traffic) lives in the umami
--    DATABASE -- PostgreSQL has no cross-database queries, so it ships as
--    deploy/analytics/umami-views.sql, applied by deploy.sh. Both halves
--    are views this repo owns: replacing Umami later rewrites views, not
--    dashboards.
--
-- 3. grafana_ro: cluster-global role, SELECT on the contract views and
--    nothing else. Created NOLOGIN with no password (a migration cannot
--    carry a secret); the operator enables login out of band. The DO block
--    guard is what makes a cluster-global CREATE ROLE idempotent, and the
--    install wizard's SQL splitter learned dollar quoting in the same wave
--    precisely so this block survives all three appliers.
--
-- 4. roller_hitcounts drops: Umami owns traffic counting now. The table
--    held one zeroed-daily number per weblog, fed a sidebar no bundled
--    theme except frontpage rendered, and reset itself nightly -- there is
--    no history to migrate.
--
-- Prerequisites: V015__form_submissions_and_tokens (roller_event),
-- V016__newsletter_wiring.

ALTER TABLE weblog
    ADD COLUMN IF NOT EXISTS analytics_site_id varchar(64);

ALTER TABLE weblog
    ADD COLUMN IF NOT EXISTS analytics_share_url varchar(255);

DROP TABLE IF EXISTS roller_hitcounts;

-- First-party outcomes by weblog and day. page_slug and entry_anchor on
-- FORM_SUBMITTED rows originate from a reader-controlled field: treat them
-- as untrusted display text in any dashboard. ENTRY_PUBLISHED counts
-- publish EVENTS; a republished entry records again.
CREATE OR REPLACE VIEW analytics_events AS
SELECT w.handle                              AS weblog_handle,
       e.event_type                          AS event_type,
       e.entry_anchor                        AS entry_anchor,
       e.page_slug                           AS page_slug,
       CAST(date_trunc('day', e.occurred_at) AS date) AS day,
       count(*)                              AS events
FROM roller_event e
JOIN weblog w ON w.id = e.weblogid
GROUP BY w.handle, e.event_type, e.entry_anchor, e.page_slug,
         CAST(date_trunc('day', e.occurred_at) AS date);

-- The join key between the two databases: which Umami website id is which
-- weblog. Grafana joins analytics_traffic (umami database) to this.
--
-- DROP + CREATE, not CREATE OR REPLACE: V027__weblog_custom_domain.sql
-- widens this view with a trailing custom_domain column, and Postgres
-- refuses a CREATE OR REPLACE VIEW that would drop a column -- exactly what
-- re-applying THIS (narrower) definition does once V027 has already run.
-- Dropping first keeps this file idempotent on its own regardless of what a
-- later migration does to the same view.
DROP VIEW IF EXISTS analytics_weblog_sites;
CREATE VIEW analytics_weblog_sites AS
SELECT handle            AS weblog_handle,
       analytics_site_id AS website_id
FROM weblog
WHERE analytics_site_id IS NOT NULL;

-- Cluster-global; guarded so re-applying the chain (and applying it to the
-- test cluster's many databases) is a no-op.
DO $$ BEGIN
    CREATE ROLE grafana_ro NOLOGIN;
EXCEPTION WHEN duplicate_object THEN
    NULL;
END $$;

GRANT SELECT ON analytics_events TO grafana_ro;
GRANT SELECT ON analytics_weblog_sites TO grafana_ro;
