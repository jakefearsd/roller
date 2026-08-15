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

-- Migration: schema_migrations tracking table
--
-- Establishes the table used by migrate.sh (and by DatabaseInstaller, when
-- the schema is created through the web install wizard) to determine which
-- migrations have already been applied. This migration always runs first;
-- it is idempotent and self-registering.
--
-- This replaces the pre-fork scheme, which stored a single
-- 'roller.database.version' row in roller_properties and drove a hardcoded
-- upgradeToNNN() chain in DatabaseInstaller.

CREATE TABLE IF NOT EXISTS schema_migrations (
    version    VARCHAR(64) PRIMARY KEY,
    applied_at TIMESTAMP   NOT NULL DEFAULT NOW()
);

-- The application role needs read access so Roller can report its own
-- migration state (startup logging, and the install wizard's status page).
GRANT SELECT ON schema_migrations TO :app_user;
