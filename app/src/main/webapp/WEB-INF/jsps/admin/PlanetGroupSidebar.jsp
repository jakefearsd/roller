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


<%-- ================================================================== --%>
<%-- add new custom planet group --%>

<h3><spring:message code="mainPage.actions"/></h3>
<hr size="1" noshade="noshade"/>

<c:url var="createNewUrl" value="/roller-ui/admin/planetGroupSubs.rol">
    <c:param name="createNew">true</c:param>
</c:url>

<a href="${createNewUrl}">
    <span class="glyphicon glyphicon-plus" aria-hidden="true"> </span>
    Create new Custom Planet Group
</a>
