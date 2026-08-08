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
-- Migration: newsletter wiring
--
-- weblog.newsletter_list_uuid: which Listmonk list this weblog's subscribe
-- form feeds. Roller stores no subscriber data -- Listmonk owns addresses,
-- double opt-in and unsubscribe; this column is the only newsletter state
-- the blog itself holds, and it is configuration, not subscriber data.
--
-- weblogentry.newsletter_sent_at: stamped when "Send as newsletter"
-- succeeds, so an entry cannot be mailed twice. Null means never sent.
--
-- Prerequisites: V002__baseline_schema.

ALTER TABLE weblog
    ADD COLUMN IF NOT EXISTS newsletter_list_uuid varchar(64);

ALTER TABLE weblogentry
    ADD COLUMN IF NOT EXISTS newsletter_sent_at timestamp(3) with time zone;
