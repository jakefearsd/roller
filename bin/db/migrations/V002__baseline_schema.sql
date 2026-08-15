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

-- Migration: Roller baseline schema
--
-- The complete Roller schema as of this fork's move to PostgreSQL. This
-- replaces the createdb.vm Velocity template and the 310-to-400 ...
-- 610-to-620 upgrade chain, which generated per-vendor DDL for seven
-- databases. Roller is now PostgreSQL-only and this is the baseline; there
-- is no upgrade path from 6.1.x, so the tables belonging to the removed
-- Planet Aggregator, Bookmarks/Blogroll and Pings/Trackbacks features
-- simply never exist here.
--
-- Tables are ordered by foreign-key dependency so every constraint can be
-- declared inline. That keeps the whole migration idempotent using nothing
-- but CREATE TABLE IF NOT EXISTS / CREATE INDEX IF NOT EXISTS -- PostgreSQL
-- has no ALTER TABLE ... ADD CONSTRAINT IF NOT EXISTS.
--
-- Prerequisites: V001__schema_migrations.

-- ============================================================
-- Root tables (no foreign keys)
-- ============================================================

CREATE TABLE IF NOT EXISTS weblog (
    id                   varchar(48) NOT NULL PRIMARY KEY,
    name                 varchar(255) NOT NULL,
    handle               varchar(255) NOT NULL CONSTRAINT ws_handle_uq UNIQUE,
    tagline              varchar(255),
    creator              varchar(255),
    enablebloggerapi     boolean DEFAULT false NOT NULL,
    editorpage           varchar(255),
    bloggercatid         varchar(48),
    allowcomments        boolean DEFAULT true NOT NULL,
    emailcomments        boolean DEFAULT false NOT NULL,
    emailaddress         varchar(255) NOT NULL,
    editortheme          varchar(255),
    locale               varchar(20),
    timezone             varchar(50),
    defaultplugins       varchar(255),
    visible              boolean DEFAULT true NOT NULL,
    isactive             boolean DEFAULT true NOT NULL,
    datecreated          timestamp(3) with time zone NOT NULL,
    bannedwordslist      text,
    defaultallowcomments boolean DEFAULT true NOT NULL,
    defaultcommentdays   integer DEFAULT 7 NOT NULL,
    commentmod           boolean DEFAULT false NOT NULL,
    displaycnt           integer DEFAULT 15 NOT NULL,
    lastmodified         timestamp(3) with time zone,
    enablemultilang      boolean DEFAULT false NOT NULL,
    showalllangs         boolean DEFAULT true NOT NULL,
    about                varchar(255),
    icon                 varchar(255),
    analyticscode        text
);
CREATE INDEX IF NOT EXISTS ws_visible_idx ON weblog(visible);

-- openid_url is retained because User.orm.xml still maps it. The OpenID
-- authentication path itself was removed; dropping the column is deferred
-- feature-removal work, not a schema concern.
CREATE TABLE IF NOT EXISTS roller_user (
    id           varchar(48) NOT NULL PRIMARY KEY,
    username     varchar(255) NOT NULL CONSTRAINT ru_username_uq UNIQUE,
    passphrase   varchar(255) NOT NULL,
    openid_url   varchar(255),
    screenname   varchar(255) NOT NULL,
    fullname     varchar(255) NOT NULL,
    emailaddress varchar(255) NOT NULL,
    datecreated  timestamp(3) with time zone NOT NULL,
    locale       varchar(20),
    timezone     varchar(50),
    isenabled    boolean DEFAULT true NOT NULL
);

CREATE TABLE IF NOT EXISTS userrole (
    id       varchar(48) NOT NULL PRIMARY KEY,
    rolename varchar(255) NOT NULL,
    username varchar(255) NOT NULL
);
CREATE INDEX IF NOT EXISTS ur_username_idx ON userrole(username);

-- actions:    comma separated list of actions permitted by the permission
-- objectid:   for now this always stores a weblogid
-- objecttype: for now this is always 'Weblog'
CREATE TABLE IF NOT EXISTS roller_permission (
    id          varchar(48) NOT NULL PRIMARY KEY,
    username    varchar(255) NOT NULL,
    actions     varchar(255),
    objectid    varchar(48),
    objecttype  varchar(255),
    pending     boolean DEFAULT true,
    datecreated timestamp(3) with time zone NOT NULL
);

-- Audit log records time and comment about a change.
CREATE TABLE IF NOT EXISTS roller_audit_log (
    id           varchar(48) NOT NULL PRIMARY KEY,
    user_id      varchar(48) NOT NULL,
    object_id    varchar(48),
    object_class varchar(255),
    comment_text varchar(255) NOT NULL,
    change_time  timestamp(3) with time zone
);

CREATE TABLE IF NOT EXISTS roller_properties (
    name  varchar(255) NOT NULL PRIMARY KEY,
    value text
);

CREATE TABLE IF NOT EXISTS roller_tasklock (
    id           varchar(48) NOT NULL PRIMARY KEY,
    name         varchar(255) NOT NULL CONSTRAINT rtl_name_uq UNIQUE,
    islocked     boolean DEFAULT false,
    timeacquired timestamp(3) with time zone NULL,
    timeleased   integer,
    lastrun      timestamp(3) with time zone NULL,
    client       varchar(255)
);
CREATE INDEX IF NOT EXISTS rtl_taskname_idx ON roller_tasklock(name);

CREATE TABLE IF NOT EXISTS roller_hitcounts (
    id        varchar(48) NOT NULL PRIMARY KEY,
    websiteid varchar(48) NOT NULL,
    dailyhits integer
);
CREATE INDEX IF NOT EXISTS rhc_websiteid_idx ON roller_hitcounts(websiteid);
CREATE INDEX IF NOT EXISTS rhc_dailyhits_idx ON roller_hitcounts(dailyhits);

CREATE TABLE IF NOT EXISTS roller_weblogentrytagagg (
    id        varchar(48) NOT NULL PRIMARY KEY,
    websiteid varchar(48),
    name      varchar(255) NOT NULL,
    total     integer NOT NULL,
    lastused  timestamp(3) with time zone NOT NULL,
    CONSTRAINT weta_weblog_tag_uq UNIQUE (websiteid, name)
);
CREATE INDEX IF NOT EXISTS weta_weblogid_idx ON roller_weblogentrytagagg(websiteid);
CREATE INDEX IF NOT EXISTS weta_name_idx ON roller_weblogentrytagagg(name);
CREATE INDEX IF NOT EXISTS weta_lastused_idx ON roller_weblogentrytagagg(lastused);

-- ============================================================
-- Weblog-scoped tables
-- ============================================================

CREATE TABLE IF NOT EXISTS weblogcategory (
    id          varchar(48) NOT NULL PRIMARY KEY,
    name        varchar(255) NOT NULL,
    description varchar(255),
    websiteid   varchar(48) NOT NULL
                CONSTRAINT wc_weblogid_fk REFERENCES weblog(id),
    image       varchar(255),
    position    integer DEFAULT 0 NOT NULL
);
CREATE INDEX IF NOT EXISTS wc_weblogid_idx ON weblogcategory(websiteid);

CREATE TABLE IF NOT EXISTS weblog_custom_template (
    id          varchar(48) NOT NULL PRIMARY KEY,
    name        varchar(255) NOT NULL,
    description varchar(255),
    link        varchar(255),
    websiteid   varchar(48) NOT NULL
                CONSTRAINT wct_weblogid_fk REFERENCES weblog(id),
    updatetime  timestamp(3) with time zone NOT NULL,
    hidden      boolean DEFAULT false NOT NULL,
    navbar      boolean DEFAULT false NOT NULL,
    outputtype  varchar(48) DEFAULT NULL,
    action      varchar(16) NOT NULL DEFAULT 'custom'
);
CREATE INDEX IF NOT EXISTS wp_name_idx ON weblog_custom_template(name);
CREATE INDEX IF NOT EXISTS wp_link_idx ON weblog_custom_template(link);
CREATE INDEX IF NOT EXISTS wp_id_idx ON weblog_custom_template(websiteid);

CREATE TABLE IF NOT EXISTS custom_template_rendition (
    id           varchar(48) NOT NULL PRIMARY KEY,
    templateid   varchar(48) NOT NULL
                 CONSTRAINT ctr_templateid_fk REFERENCES weblog_custom_template(id),
    template     text NOT NULL,
    templatelang varchar(48),
    type         varchar(16) NOT NULL DEFAULT 'STANDARD'
);

CREATE TABLE IF NOT EXISTS weblogentry (
    id                 varchar(48) NOT NULL PRIMARY KEY,
    anchor             varchar(255) NOT NULL,
    creator            varchar(255) NOT NULL,
    title              varchar(255) NOT NULL,
    text               text NOT NULL,
    pubtime            timestamp(3) with time zone NULL,
    updatetime         timestamp(3) with time zone NOT NULL,
    websiteid          varchar(48) NOT NULL
                       CONSTRAINT we_weblogid_fk REFERENCES weblog(id),
    categoryid         varchar(48) NOT NULL
                       CONSTRAINT we_categoryid_fk REFERENCES weblogcategory(id),
    publishentry       boolean DEFAULT true NOT NULL,
    link               varchar(255),
    plugins            varchar(255),
    allowcomments      boolean DEFAULT false NOT NULL,
    commentdays        integer DEFAULT 7 NOT NULL,
    righttoleft        boolean DEFAULT false NOT NULL,
    pinnedtomain       boolean DEFAULT false NOT NULL,
    locale             varchar(20),
    status             varchar(20) NOT NULL,
    summary            text DEFAULT NULL,
    content_type       varchar(48) DEFAULT NULL,
    content_src        varchar(255) DEFAULT NULL,
    search_description varchar(255) DEFAULT NULL
);
CREATE INDEX IF NOT EXISTS we_weblogid_idx ON weblogentry(websiteid);
CREATE INDEX IF NOT EXISTS we_categoryid_idx ON weblogentry(categoryid);
CREATE INDEX IF NOT EXISTS we_pinnedtom_idx ON weblogentry(pinnedtomain);
CREATE INDEX IF NOT EXISTS we_creator_idx ON weblogentry(creator);
CREATE INDEX IF NOT EXISTS we_status_idx ON weblogentry(status);
CREATE INDEX IF NOT EXISTS we_locale_idx ON weblogentry(locale);
CREATE INDEX IF NOT EXISTS we_combo1_idx ON weblogentry(status, pubtime, websiteid);
CREATE INDEX IF NOT EXISTS we_combo2_idx ON weblogentry(websiteid, pubtime, status);

CREATE TABLE IF NOT EXISTS roller_weblogentrytag (
    id        varchar(48) NOT NULL PRIMARY KEY,
    entryid   varchar(48) NOT NULL
              CONSTRAINT rwtg_entryid_fk REFERENCES weblogentry(id),
    websiteid varchar(48) NOT NULL,
    creator   varchar(255) NOT NULL,
    name      varchar(255) NOT NULL,
    time      timestamp(3) with time zone NOT NULL
);
CREATE INDEX IF NOT EXISTS wet_entryid_idx ON roller_weblogentrytag(entryid);
CREATE INDEX IF NOT EXISTS wet_weblogid_idx ON roller_weblogentrytag(websiteid);
CREATE INDEX IF NOT EXISTS wet_creator_idx ON roller_weblogentrytag(creator);
CREATE INDEX IF NOT EXISTS wet_name_idx ON roller_weblogentrytag(name);

CREATE TABLE IF NOT EXISTS newsfeed (
    id          varchar(48) NOT NULL PRIMARY KEY,
    name        varchar(255) NOT NULL,
    description varchar(255) NOT NULL,
    link        varchar(255) NOT NULL,
    websiteid   varchar(48) NOT NULL
                CONSTRAINT nf_weblogid_fk REFERENCES weblog(id)
);
CREATE INDEX IF NOT EXISTS nf_weblogid_idx ON newsfeed(websiteid);

CREATE TABLE IF NOT EXISTS roller_comment (
    id          varchar(48) NOT NULL PRIMARY KEY,
    entryid     varchar(48) NOT NULL
                CONSTRAINT co_entryid_fk REFERENCES weblogentry(id),
    name        varchar(255),
    email       varchar(255),
    url         varchar(255),
    content     text,
    posttime    timestamp(3) with time zone NOT NULL,
    notify      boolean DEFAULT false NOT NULL,
    remotehost  varchar(128),
    referrer    varchar(255),
    useragent   varchar(255),
    status      varchar(20) NOT NULL,
    plugins     varchar(255),
    contenttype varchar(128) DEFAULT 'text/plain' NOT NULL
);
CREATE INDEX IF NOT EXISTS co_entryid_idx ON roller_comment(entryid);
CREATE INDEX IF NOT EXISTS co_status_idx ON roller_comment(status);

-- Entry attribute: metadata for weblog entries
CREATE TABLE IF NOT EXISTS entryattribute (
    id      varchar(48) NOT NULL PRIMARY KEY,
    entryid varchar(48) NOT NULL
            CONSTRAINT att_entryid_fk REFERENCES weblogentry(id),
    name    varchar(255) NOT NULL,
    value   text NOT NULL,
    CONSTRAINT ea_name_uq UNIQUE (entryid, name)
);
CREATE INDEX IF NOT EXISTS ea_entryid_idx ON entryattribute(entryid);

-- ============================================================
-- Media blogging
-- ============================================================

CREATE TABLE IF NOT EXISTS roller_mediafiledir (
    id          varchar(48) NOT NULL PRIMARY KEY,
    name        varchar(255) NOT NULL,
    description varchar(255),
    websiteid   varchar(48) NOT NULL
                CONSTRAINT mf_weblogid_fk REFERENCES weblog(id)
);

CREATE TABLE IF NOT EXISTS roller_mediafile (
    id             varchar(48) NOT NULL PRIMARY KEY,
    name           varchar(255) NOT NULL,
    description    varchar(255),
    origpath       varchar(255),
    content_type   varchar(50) NOT NULL,
    copyright_text varchar(1023),
    directoryid    varchar(48) NOT NULL
                   CONSTRAINT roller_mediafiledir_id_fk REFERENCES roller_mediafiledir(id),
    weblogid       varchar(48) NOT NULL,
    width          integer,
    height         integer,
    size_in_bytes  integer,
    date_uploaded  timestamp(3) with time zone NOT NULL,
    last_updated   timestamp(3) with time zone,
    anchor         varchar(255),
    creator        varchar(255),
    is_public      boolean DEFAULT false NOT NULL
);

CREATE TABLE IF NOT EXISTS roller_mediafiletag (
    id           varchar(48) NOT NULL PRIMARY KEY,
    mediafile_id varchar(48) NOT NULL
                 CONSTRAINT roller_mediafile_id_tag_fk REFERENCES roller_mediafile(id),
    name         varchar(30) NOT NULL
);

-- ============================================================
-- Grants
-- ============================================================

GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO :app_user;
