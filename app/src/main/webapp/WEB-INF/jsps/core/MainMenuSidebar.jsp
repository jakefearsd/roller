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

<div class="sidebarFade">
    <div class="menu-tr">
        <div class="menu-tl">

            <div class="sidebarInner">

                <%-- Edit profile --%>

                <h4><span class="glyphicon glyphicon-user" aria-hidden="true"></span>
                <a href="<c:url value='/roller-ui/profile.rol'/>"><spring:message code="yourWebsites.editProfile" /></a></h4>
                <p><spring:message code="yourWebsites.editProfile.desc" /></p>

                <%-- Create weblog --%>

                <c:if test="${rc:getBooleanProp('site.allowUserWeblogCreation') && (rc:getBooleanProp('groupblogging.enabled') || (empty existingPermissions && empty pendingPermissions))}">
                    <h4><span class="glyphicon glyphicon-plus" aria-hidden="true"></span>
                    <a href="<c:url value='/roller-ui/createWeblog.rol'/>"><spring:message code="yourWebsites.createWeblog" /></a></h4>
                    <p><spring:message code="yourWebsites.createWeblog.desc" /></p>
                </c:if>

                <c:if test="${userIsAdmin}">

                    <%-- Roller settings --%>

                    <h4><span class="glyphicon glyphicon-wrench" aria-hidden="true"></span>
                    <a href="<c:url value='/roller-ui/admin/globalConfig.rol'/>"><spring:message code="yourWebsites.globalAdmin" /></a></h4>
                    <p><spring:message code="yourWebsites.globalAdmin.desc" /></p>

                </c:if>

                <br />
            </div>

        </div>
    </div>
</div>
