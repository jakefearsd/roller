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

-- Migration: drop roller_user.openid_url
--
-- OpenID authentication was removed from Roller, but the column and its JPA
-- mapping outlived it. V002 still created the column so the baseline matched
-- the schema that existed at the time; this drops it now that User.openIdUrl,
-- the User.getByOpenIdUrl named query and the OpenID branches in the login,
-- profile and user-admin controllers are all gone.
--
-- Prerequisites: V002__baseline_schema.

ALTER TABLE roller_user DROP COLUMN IF EXISTS openid_url;
