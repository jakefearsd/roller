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
-- Migration: audience wiring -- inquiries, account tokens, first-party events
--
-- Three tables for Wave B (Audience):
--
-- 1. roller_event: outcomes the analytics tier cannot see from traffic alone
--    (form submitted, newsletter subscribed, entry published). Wave B writes
--    these rows; Wave C only adds SQL views over them for Grafana. metadata
--    is jsonb per the analytics contract but is deliberately not mapped in
--    JPA yet -- nothing writes it, and the cast layer can wait for a writer.
--
-- 2. roller_form_submission: contact-form inquiries, persisted BEFORE any
--    notification email is attempted. If SMTP is down the lead survives,
--    which for a business running on leads is the failure that matters.
--
-- 3. roller_user_token: single-use, expiring account tokens serving both
--    forgot-password and the admin "send set-password link". Only a SHA-256
--    digest of the token is stored: a database read must not yield working
--    reset links. The raw token exists only in the emailed URL.
--
-- Prerequisites: V002__baseline_schema.

CREATE TABLE IF NOT EXISTS roller_event (
    id           varchar(48)  NOT NULL PRIMARY KEY,
    weblogid     varchar(48)  NOT NULL CONSTRAINT rev_weblog_fk REFERENCES weblog(id),
    event_type   varchar(32)  NOT NULL,
    entry_anchor varchar(255),
    page_slug    varchar(255),
    occurred_at  timestamp(3) with time zone NOT NULL,
    metadata     jsonb
);

-- Wave C's views group by weblog, type and day.
CREATE INDEX IF NOT EXISTS rev_weblog_type_idx
    ON roller_event(weblogid, event_type, occurred_at);

CREATE TABLE IF NOT EXISTS roller_form_submission (
    id           varchar(48)  NOT NULL PRIMARY KEY,
    weblogid     varchar(48)  NOT NULL CONSTRAINT rfs_weblog_fk REFERENCES weblog(id),
    name         varchar(255) NOT NULL,
    email        varchar(255) NOT NULL,
    subject      varchar(255),
    message      text         NOT NULL,
    page_slug    varchar(255),
    entry_anchor varchar(255),
    client_ip    varchar(64),
    created      timestamp(3) with time zone NOT NULL
);

-- The inbox lists one weblog's submissions newest-first.
CREATE INDEX IF NOT EXISTS rfs_weblog_created_idx
    ON roller_form_submission(weblogid, created);

CREATE TABLE IF NOT EXISTS roller_user_token (
    id           varchar(48)  NOT NULL PRIMARY KEY,
    userid       varchar(48)  NOT NULL CONSTRAINT rut_user_fk REFERENCES roller_user(id),
    token_sha256 varchar(64)  NOT NULL CONSTRAINT rut_token_uq UNIQUE,
    purpose      varchar(16)  NOT NULL,
    created      timestamp(3) with time zone NOT NULL,
    expires      timestamp(3) with time zone NOT NULL,
    used_at      timestamp(3) with time zone
);

CREATE INDEX IF NOT EXISTS rut_user_idx ON roller_user_token(userid);
