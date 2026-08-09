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
<%@ include file="/WEB-INF/jsps/taglibs-spring.jsp" %>
<%-- A layout for transient iframe documents that exist only to run a script
     and hand control back to their parent (MediaFileEditSuccess). It loads
     NO stylesheets, NO fonts and NO script libraries on purpose: such a page
     is destroyed by the parent milliseconds after it arrives, and every
     asset it requests is a fetch the browser has to abort mid-flight --
     which BrowserHealth rightly reports as a failed request. Do not "fix"
     this layout by including ${tile_head}. --%>
<!doctype html>
<html>
    <head>
        <meta charset="utf-8">
    </head>
    <body>
        <jsp:include page="${tile_content}" />
    </body>
</html>
