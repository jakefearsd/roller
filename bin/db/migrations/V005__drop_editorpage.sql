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

-- Migration: drop weblog.editorpage
--
-- The dual-editor plugin layer (UIPluginManager/TextEditor/Summernote) let a
-- weblog pick between a plain-textarea editor and Summernote by storing the
-- chosen editor's JSP id in this column. Both editors rendered through the
-- same EntryEditor.jsp and only differed in which arm of a JS conditional
-- ran; Summernote has been the only sensible choice for a while (the plain
-- text arm was rarely picked deliberately, and new weblogs were being pinned
-- to it by a live bug in CreateWeblogController). The plugin layer, the JSP
-- branch, the WeblogConfig editor picker, and the Weblog/WeblogConfigBean/
-- WeblogWrapper accessors are all gone now, so the column has no reader left.
--
-- Prerequisites: V002__baseline_schema.

ALTER TABLE weblog DROP COLUMN IF EXISTS editorpage;
