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
--
-- Migration: a shared business record and per-blog place fields.
--
-- A business (the organisation behind several guide blogs) is stored once
-- and referenced by weblog.business_id, so its phone, booking link and
-- sameAs profiles are edited in one place and emitted as schema.org JSON-LD
-- on every blog that uses it. The FK is on weblog: deleting a weblog never
-- touches a business, and a business in use cannot be deleted. See
-- docs/superpowers/specs/2026-10-03-business-profile-and-cta-attribution-design.md.
--
-- place_* describe the location a blog is about (rounded to two decimals,
-- about 1 km, on purpose: a guide, not a street address). booking_url is the
-- blog-level override of the business booking link. All columns are nullable.
--
-- Idempotent: safe to run on a database that already has some of it.

CREATE TABLE IF NOT EXISTS roller_business (
    id            varchar(48)  NOT NULL PRIMARY KEY,
    name          varchar(255) NOT NULL,
    business_type varchar(32)  NOT NULL,
    website_url   varchar(255),
    booking_url   varchar(255),
    telephone     varchar(32),
    email         varchar(255),
    logo_url      varchar(255),
    same_as       text,
    area_served   varchar(255),
    description   text,
    created       timestamp    NOT NULL,
    last_modified timestamp    NOT NULL
);

ALTER TABLE weblog ADD COLUMN IF NOT EXISTS business_id varchar(48)
    CONSTRAINT weblog_business_fk REFERENCES roller_business(id);
ALTER TABLE weblog ADD COLUMN IF NOT EXISTS place_type     varchar(32);
ALTER TABLE weblog ADD COLUMN IF NOT EXISTS place_locality varchar(128);
ALTER TABLE weblog ADD COLUMN IF NOT EXISTS place_region   varchar(128);
ALTER TABLE weblog ADD COLUMN IF NOT EXISTS place_country  char(2);
ALTER TABLE weblog ADD COLUMN IF NOT EXISTS place_lat      numeric(5,2);
ALTER TABLE weblog ADD COLUMN IF NOT EXISTS place_lng      numeric(6,2);
ALTER TABLE weblog ADD COLUMN IF NOT EXISTS booking_url    varchar(255);
CREATE INDEX IF NOT EXISTS weblog_business_idx ON weblog(business_id);
