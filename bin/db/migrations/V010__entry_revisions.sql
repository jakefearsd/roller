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
-- Migration: entry revisions
--
-- One row per content-changing save of an entry, holding the title, text and
-- summary as they were BEFORE that save. The editor lists them, diffs any two,
-- and restores one through the normal save path.
--
-- Full snapshots rather than diffs: entries are small, and a chain of diffs
-- makes every read depend on every earlier row being intact, which is a poor
-- trade for a table whose entire purpose is recovering from a mistake.
--
-- Nothing is pruned by default. The retention cap is the runtime property
-- entry.revisions.retention, which ships as -1 meaning unlimited; a positive
-- value keeps that many newest revisions per entry and 0 records none. The
-- entryid FK cascades on delete so a deleted entry cannot leave orphans behind
-- even if a caller forgets to clear them first.
--
-- Prerequisites: V002__baseline_schema.

CREATE TABLE IF NOT EXISTS weblogentry_revision (
    id           varchar(48) NOT NULL PRIMARY KEY,
    entryid      varchar(48) NOT NULL
                 CONSTRAINT wer_entryid_fk REFERENCES weblogentry(id) ON DELETE CASCADE,
    -- Microseconds, not the milliseconds used elsewhere in this schema: two
    -- saves of one entry can land inside the same millisecond, and ordering
    -- revisions is not cosmetic -- it decides which one the pruner drops and
    -- which one "restore the previous version" means.
    created      timestamp(6) with time zone NOT NULL,
    creator      varchar(255),
    title        varchar(255),
    text         text,
    summary      text
);

-- The editor reads a single entry's revisions newest-first; the pruner reads
-- the same order to find what falls off the end.
CREATE INDEX IF NOT EXISTS wer_entry_created_idx
    ON weblogentry_revision(entryid, created DESC);

GRANT SELECT, INSERT, UPDATE, DELETE ON weblogentry_revision TO :app_user;
