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

<h3><spring:message code="mainPage.actions"/></h3>
<hr size="1" noshade="noshade" />

<span class="glyphicon glyphicon-plus"></span>
<c:url var="inviteUrl" value="/roller-ui/authoring/invite.rol">
   <c:param name="weblog" value="${actionWeblog.handle}"/>
</c:url>
<a href='${inviteUrl}'>
    <spring:message code="memberPermissions.inviteMember"/>
</a>
<spring:message code="memberPermissions.whyInvite"/>

<h3> <spring:message code="memberPermissions.permissionsHelpTitle"/> </h3>
<hr size="1" noshade="noshade" />

<spring:message code="memberPermissions.permissionHelp"/>
