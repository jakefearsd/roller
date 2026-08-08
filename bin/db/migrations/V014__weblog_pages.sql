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
-- Migration: static pages, authored in Markdown
--
-- A page is deliberately NOT a weblog entry with a flag. Entries are read by
-- 25 query paths in JPAWeblogEntryManagerImpl -- feeds, archives, the Lucene
-- index, sitemaps, tag aggregates, pagers, next/prev navigation -- and a
-- discriminator would mean auditing every one of them, where missing a single
-- path silently puts an About page in the RSS feed. A separate table cannot
-- leak into any of them.
--
-- Absent on purpose: category, tags, pubtime, comment settings, locale. A page
-- has no position in a chronology and nothing to file it under; columns that
-- mean nothing are how a model starts lying.
--
-- Prerequisites: V002__baseline_schema.

CREATE TABLE IF NOT EXISTS roller_weblogpage (
    id                 varchar(48)  NOT NULL PRIMARY KEY,
    weblogid           varchar(48)  NOT NULL,
    slug               varchar(255) NOT NULL,
    title              varchar(255) NOT NULL,
    content            text,
    status             varchar(20)  NOT NULL DEFAULT 'DRAFT',
    show_in_nav        boolean      NOT NULL DEFAULT true,
    nav_order          integer      NOT NULL DEFAULT 0,
    created            timestamp(3) with time zone NOT NULL,
    updated            timestamp(3) with time zone NOT NULL,
    meta_title         varchar(255),
    search_description varchar(255),
    canonical_url      varchar(255),
    noindex            boolean      NOT NULL DEFAULT false,
    og_image_id        varchar(48),
    CONSTRAINT rwp_weblog_fk FOREIGN KEY (weblogid) REFERENCES weblog(id)
);

-- One slug per weblog: the routing lookup is (weblog, slug) and a duplicate
-- would make which page a URL resolves to a matter of chance.
CREATE UNIQUE INDEX IF NOT EXISTS rwp_weblog_slug_uq
    ON roller_weblogpage(weblogid, slug);

-- Nav rendering and the sitemap both read published pages for one weblog in
-- nav order.
CREATE INDEX IF NOT EXISTS rwp_weblog_status_idx
    ON roller_weblogpage(weblogid, status, nav_order);
