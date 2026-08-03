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

-- Migration: drop the entry content-format columns
--
-- Markdown is now the storage format for every entry: WeblogEntry.render()
-- converts unconditionally, and there is no per-entry alternative to express.
-- A nullable column that nothing reads is a trap -- the next person to open
-- WeblogEntry reasonably assumes content_type still means something, and a
-- bulk import or a well-meaning fix eventually writes 'text/html' into it,
-- leaving two formats and no test that fails. Removing it makes the invariant
-- structural rather than a convention.
--
-- content_src goes with it: an unreachable Roller-5 feature for entries whose
-- body lived at an external URL. It has had no reader in this codebase for as
-- long as the migration chain has existed.
--
-- Both columns were verified unused before removal: no template in
-- WEB-INF/velocity or any theme reads $entry.contentType, no rendering model
-- references it, and the remote-API surfaces that once did were deleted in an
-- earlier stage.
--
-- Prerequisites: V002__baseline_schema.

ALTER TABLE weblogentry DROP COLUMN IF EXISTS content_type;
ALTER TABLE weblogentry DROP COLUMN IF EXISTS content_src;
