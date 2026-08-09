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
-- Migration: retire legacy themes
--
-- The basic, fauxcoly and gaurav shared themes are removed from the theme
-- directory in the same commit as this migration. Any weblog still running
-- one of them is moved to journal, the current default. Weblogs on a custom
-- theme store 'custom' in editortheme and are untouched by definition.
--
-- Idempotent: once no weblog names a retired theme, the UPDATE matches
-- nothing and re-application is a no-op.
--
-- Prerequisites: V002__baseline_schema.

UPDATE weblog
    SET editortheme = 'journal'
    WHERE editortheme IN ('basic', 'fauxcoly', 'gaurav');
