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

-- Migration: travel JSON-LD fields
--
-- Wave 3 (travel): additive per-entry structured-data columns behind the
-- JSON-LD type selector in the editor's SEO card.
--
-- weblogentry gains jsonld_type, the schema.org type the entry's head emits
-- alongside the always-present BlogPosting block. It stores a JsonLdType enum
-- name (BLOG_POSTING, TOURIST_ATTRACTION, TOURIST_TRIP, EVENT, FAQ_PAGE);
-- null means the BlogPosting default, so every pre-existing entry renders
-- exactly as before.
--
-- The typed fields the travel types need and the entry does not already
-- carry: geo_latitude/geo_longitude back a TouristAttraction's
-- GeoCoordinates (and double as the default centre for a bare [map]), and
-- event_start/event_end/event_location back an Event's startDate, endDate,
-- and name-only Place location. All nullable -- an emitter omits what the
-- author left blank rather than fabricating values.
--
-- Prerequisites: V002__baseline_schema.

ALTER TABLE weblogentry ADD COLUMN IF NOT EXISTS jsonld_type VARCHAR(32);
ALTER TABLE weblogentry ADD COLUMN IF NOT EXISTS geo_latitude DOUBLE PRECISION;
ALTER TABLE weblogentry ADD COLUMN IF NOT EXISTS geo_longitude DOUBLE PRECISION;
ALTER TABLE weblogentry ADD COLUMN IF NOT EXISTS event_start TIMESTAMP(3) WITH TIME ZONE;
ALTER TABLE weblogentry ADD COLUMN IF NOT EXISTS event_end TIMESTAMP(3) WITH TIME ZONE;
ALTER TABLE weblogentry ADD COLUMN IF NOT EXISTS event_location VARCHAR(255);
