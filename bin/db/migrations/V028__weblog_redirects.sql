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
-- Migration: URL redirects -- 301s for URIs that would otherwise 404.
--
-- A rule is consulted only at code points where a 404 has already been
-- decided, so it can never shadow live content; see
-- docs/superpowers/specs/2026-08-24-url-redirects-design.md. source_path and
-- target_path are weblog-relative and normalized (leading slash, trailing
-- slashes stripped) before they are stored, which is what lets the unique
-- index below double as the "one answer per URL" guarantee.
--
-- origin records whether an operator wrote the rule (MANUAL) or a page-slug
-- rename minted it (SLUG_HISTORY) -- the first question when a redirect
-- fires unexpectedly is where the rule came from.
--
-- hit_count/last_hit_at are the observability half: the count says a stale
-- URL is still being asked for, the timestamp says whether that stopped.
--
-- Prerequisites: V002__baseline_schema.

CREATE TABLE IF NOT EXISTS roller_weblog_redirect (
    id          varchar(48)  NOT NULL PRIMARY KEY,
    weblogid    varchar(48)  NOT NULL,
    source_path varchar(255) NOT NULL,
    target_path varchar(255) NOT NULL,
    origin      varchar(16)  NOT NULL,
    created_at  timestamp(3) with time zone NOT NULL,
    hit_count   bigint       NOT NULL DEFAULT 0,
    last_hit_at timestamp(3) with time zone,
    CONSTRAINT rwr_weblog_fk FOREIGN KEY (weblogid) REFERENCES weblog(id)
);

-- One rule per URL: resolution looks up (weblog, source) and a duplicate
-- would make which target a reader lands on a matter of chance.
CREATE UNIQUE INDEX IF NOT EXISTS rwr_weblog_source_uq
    ON roller_weblog_redirect(weblogid, source_path);
