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

-- Migration: drop weblog.bannedwordslist
--
-- The weblog-level banned-words list only ever fed CommentServlet's dead
-- validator chain and PageServlet's referrer spam check -- both unreachable
-- (no CommentValidator implementations ever shipped) or off by default and
-- recording nothing (site.bannedwordslist.enable.referrers=false). With that
-- machinery removed, the CRUD/preview UI on WeblogConfig.jsp, the bean field,
-- the ORM mapping and the Weblog accessors are gone too, so the column has no
-- reader left.
--
-- Prerequisites: V002__baseline_schema.

ALTER TABLE weblog DROP COLUMN IF EXISTS bannedwordslist;
