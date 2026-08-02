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

-- Migration: share links and gallery metadata
--
-- Wave 2 (photography): a new roller_share_link table plus additive gallery
-- columns on the two media tables.
--
-- roller_share_link carries a tokened, optionally password-protected public
-- URL for a single weblog entry or media directory ("share this gallery with
-- the client"). target_type is 'entry' or 'directory' and target_id stores
-- the target's surrogate key WITHOUT a foreign key -- entries and directories
-- are deleted independently of their share links, and a dangling reference
-- should degrade to "nothing to show" rather than block the delete (the same
-- reasoning as weblogentry.featured_image_id in V006). The token is unique
-- and URL-safe (43 chars of Base64 today; 64 leaves headroom). password_hash
-- holds a DelegatingPasswordEncoder hash, null when the link is unprotected;
-- expires null means the link never expires.
--
-- roller_mediafile gains sort_order (explicit curated ordering within its
-- directory; null sorts after the curated block, name breaks ties -- ordering
-- is applied in code, the directory one-to-many stays name-ordered) and
-- focal_x/focal_y (fractional 0..1 focal-point coordinates for object-position
-- cropping; null means center).
--
-- roller_mediafiledir gains cover_mediafile_id (a roller_mediafile id, again
-- deliberately not a FK so deleting the cover image degrades to "no cover")
-- and is_private (a private directory is only reachable through a share link
-- or by the blog's own members, never listed publicly).
--
-- Prerequisites: V002__baseline_schema.

CREATE TABLE IF NOT EXISTS roller_share_link (
    id            varchar(48) NOT NULL PRIMARY KEY,
    weblogid      varchar(48) NOT NULL
                  CONSTRAINT sl_weblogid_fk REFERENCES weblog(id),
    target_type   varchar(16) NOT NULL,
    target_id     varchar(48) NOT NULL,
    token         varchar(64) NOT NULL CONSTRAINT sl_token_uq UNIQUE,
    password_hash varchar(255),
    created       timestamp(3) with time zone NOT NULL,
    expires       timestamp(3) with time zone
);
CREATE INDEX IF NOT EXISTS sl_weblogid_idx ON roller_share_link(weblogid);
CREATE INDEX IF NOT EXISTS sl_target_idx ON roller_share_link(target_type, target_id);

ALTER TABLE roller_mediafile ADD COLUMN IF NOT EXISTS sort_order integer;
ALTER TABLE roller_mediafile ADD COLUMN IF NOT EXISTS focal_x real;
ALTER TABLE roller_mediafile ADD COLUMN IF NOT EXISTS focal_y real;

ALTER TABLE roller_mediafiledir ADD COLUMN IF NOT EXISTS cover_mediafile_id varchar(48);
ALTER TABLE roller_mediafiledir ADD COLUMN IF NOT EXISTS is_private boolean DEFAULT false NOT NULL;

GRANT SELECT, INSERT, UPDATE, DELETE ON roller_share_link TO :app_user;
