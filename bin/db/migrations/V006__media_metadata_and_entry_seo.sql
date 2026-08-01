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

-- Migration: media metadata and entry SEO fields
--
-- Wave 1 (media & SEO foundation): two independent groups of additive columns
-- that land together because they share this migration slot.
--
-- weblogentry gains the per-entry SEO/featured-image fields the editor's new
-- SEO panel (a later task) will expose: an optional featured image and a
-- separate Open Graph image (both store a roller_mediafile id, not a FK --
-- media files are deleted independently and a dangling reference should
-- degrade to "no image" rather than block the delete), an SEO <title>
-- override, a canonical URL override, and a noindex flag. Meta description
-- deliberately reuses the existing search_description column rather than
-- adding a second one.
--
-- roller_mediafile gains EXIF metadata (camera/lens/exposure/aperture/iso/
-- focal length/taken timestamp) read from uploaded images with
-- metadata-extractor, a blurhash placeholder string encoded from the image's
-- smallest available rendition, and GPS coordinates -- nullable, and left
-- null whenever the site-wide uploads.exif.stripGps setting (default on)
-- strips them for privacy at upload time.
--
-- Prerequisites: V002__baseline_schema.

ALTER TABLE weblogentry ADD COLUMN IF NOT EXISTS featured_image_id VARCHAR(48);
ALTER TABLE weblogentry ADD COLUMN IF NOT EXISTS meta_title VARCHAR(255);
ALTER TABLE weblogentry ADD COLUMN IF NOT EXISTS og_image_id VARCHAR(48);
ALTER TABLE weblogentry ADD COLUMN IF NOT EXISTS canonical_url VARCHAR(255);
ALTER TABLE weblogentry ADD COLUMN IF NOT EXISTS noindex BOOLEAN DEFAULT FALSE;

ALTER TABLE roller_mediafile ADD COLUMN IF NOT EXISTS blurhash VARCHAR(64);
ALTER TABLE roller_mediafile ADD COLUMN IF NOT EXISTS exif_camera VARCHAR(128);
ALTER TABLE roller_mediafile ADD COLUMN IF NOT EXISTS exif_lens VARCHAR(128);
ALTER TABLE roller_mediafile ADD COLUMN IF NOT EXISTS exif_exposure VARCHAR(32);
ALTER TABLE roller_mediafile ADD COLUMN IF NOT EXISTS exif_aperture VARCHAR(32);
ALTER TABLE roller_mediafile ADD COLUMN IF NOT EXISTS exif_iso INTEGER;
ALTER TABLE roller_mediafile ADD COLUMN IF NOT EXISTS exif_focal_length VARCHAR(32);
ALTER TABLE roller_mediafile ADD COLUMN IF NOT EXISTS exif_taken TIMESTAMP(3) WITH TIME ZONE;
ALTER TABLE roller_mediafile ADD COLUMN IF NOT EXISTS gps_latitude DOUBLE PRECISION;
ALTER TABLE roller_mediafile ADD COLUMN IF NOT EXISTS gps_longitude DOUBLE PRECISION;
