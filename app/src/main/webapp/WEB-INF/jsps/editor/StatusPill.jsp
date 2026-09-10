<%--
  Licensed to the Apache Software Foundation (ASF) under one or more
   contributor license agreements.  The ASF licenses this file to You
  under the Apache License, Version 2.0 (the "License"); you may not
  use this file except in compliance with the License.
  You may obtain a copy of the License at

      http://www.apache.org/licenses/LICENSE-2.0

  Unless required by applicable law or agreed to in writing, software
  distributed under the License is distributed on an "AS IS" BASIS,
  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
  See the License for the specific language governing permissions and
  limitations under the License.  For additional information regarding
  copyright in this work, please see the NOTICE file in the top level
  directory of this distribution.
--%>
<%--
  The one status-pill component (task B1, docs/design components-pills
  card). Callers set two REQUEST-scoped attributes with <c:set scope="request">
  immediately before <jsp:include>-ing this file:

    pillStatus  (required) -- a PubStatus name, e.g. "PUBLISHED"/"DRAFT"/
                 "PENDING"/"SCHEDULED"/"TRASHED". Lowercased, it is both the
                 ".status-pill.status-<x>" CSS class and the
                 "weblogEdit.<x>" message key -- the two vocabularies are
                 the same word on purpose, so a new PubStatus value needs
                 only a matching CSS rule and message key, not a new branch
                 here.
    pillWhen    (optional) -- a java.util.Date rendered as the pill's
                 trailing timestamp (e.g. a Scheduled entry's pubTime).

  Request scope, not page scope: <jsp:include> runs the included page in a
  fresh JspContext whose page scope does not see the includer's attributes,
  but request scope is shared by both. The timestamp goes through <rc:date>
  (task B5), which reads actionWeblog off that same shared request -- so it
  renders in the weblog's timezone here exactly as it does in the caller.

  The message code below is composed via EL (weblogEdit.${fn:toLowerCase
  (pillStatus)}), so MessageKeyTest's literal-code scan cannot see which
  keys it actually resolves to; naming them here, literally, is what keeps
  its orphan check (reportsBundleKeysNoJspOrControllerUses) from flagging
  weblogEdit.trashed as unused -- the same reason PageEdit.jsp/EntryEdit.jsp
  already carry weblogEdit.published/draft/pending/scheduled as literals.
  The full set this include can resolve to: weblogEdit.published,
  weblogEdit.draft, weblogEdit.pending, weblogEdit.scheduled,
  weblogEdit.trashed.
--%>
<%@ include file="/WEB-INF/jsps/taglibs-spring.jsp" %>
<span class="status-pill status-${fn:toLowerCase(pillStatus)}"><spring:message code="weblogEdit.${fn:toLowerCase(pillStatus)}"/><c:if test="${not empty pillWhen}"> <span class="status-when"><rc:date value="${pillWhen}"/></span></c:if></span>
