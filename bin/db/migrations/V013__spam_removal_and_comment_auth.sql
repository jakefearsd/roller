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
-- Migration: delete spam-flagged comments, require authenticated commenters
--
-- Two changes, both about the same thing: a comment on this server should be
-- something a known person wrote, or it should not be here at all.
--
-- 1. Spam is no longer a state a comment can rest in.
--
-- ApprovalStatus.SPAM parked a comment in a status that fed nothing. There has
-- never been a spam filter in this codebase -- no scoring, no blocklist, no
-- export to a service -- so the flag's only effect was to hold the row out of
-- the approved-only public query, which DISAPPROVED already did. It was also
-- unreachable in practice: the moderation screen pre-ticked "approved" for
-- every approved comment, and the update loop tested approved before spam, so
-- ticking "spam" on a live comment re-saved it as APPROVED and it stayed on
-- the page. The flag looked like it worked and did nothing.
--
-- Marking spam now removes the comment, so the status has no rows to hold and
-- the enum constant goes with it. Any comment currently sitting in SPAM is
-- deleted here rather than migrated to DISAPPROVED: it was junk a moderator
-- had already judged, and leaving rows behind whose status no longer maps to
-- an enum constant would fail on load.
--
-- 2. weblog.comment_auth_required, defaulting to true.
--
-- Per-weblog, because the decision belongs to whoever runs the blog. Defaulting
-- to true for existing weblogs as well as new ones: an anonymous comment box is
-- the thing spam arrives through, and turning it back on is one checkbox on the
-- Weblog Settings page. A default that has to be tightened after the first
-- attack is the wrong way round.
--
-- Prerequisites: V002__baseline_schema.

DELETE FROM roller_comment WHERE status = 'SPAM';

ALTER TABLE weblog
    ADD COLUMN IF NOT EXISTS comment_auth_required boolean DEFAULT true NOT NULL;
