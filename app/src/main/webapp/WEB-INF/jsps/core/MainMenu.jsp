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

<%-- PROMPT: Welcome... you have no blog.

     This is the first screen a new account ever lands on, so it is the empty
     state that most has to read as an invitation rather than a shrug: one
     title line, one sentence, one primary action. #createWeblogLink moves onto
     the button rather than being dropped -- nothing in the repo selects on it
     today, but it is the only handle this action has ever had. --%>
<c:if test="${empty existingPermissions}">
    <div class="empty-state">
        <p class="empty-state-title"><spring:message code="empty.weblogs.title" /></p>
        <p class="empty-state-body"><spring:message code="empty.weblogs.body" /></p>
        <a id="createWeblogLink" class="btn btn-primary"
           href="<c:url value='/roller-ui/createWeblog.rol'/>">
            <spring:message code="empty.weblogs.action" />
        </a>
    </div>
</c:if>

<%-- PROMPT: default ... select a weblog to edit --%>
<c:if test="${not empty existingPermissions}">
    <p class="subtitle"><spring:message code="yourWebsites.prompt.hasBlog" /></p>
</c:if>

<%-- if we have weblogs, then loop through and list them --%>
<c:if test="${not empty existingPermissions}">

    <c:forEach items="${existingPermissions}" var="perms">

        <div class="card card-body yourWeblogBox">

            <%-- .section-head carries the caps-label type role; mm_weblog_name
                 has no CSS of its own and stays only because RouteSweepIT
                 identifies menu.rol by "h3.mm_weblog_name" (Routes.java).
                 Rename neither the element nor the class without moving that
                 marker in the same commit. --%>
            <h3 class="mm_weblog_name section-head">
                <span class="bi bi-folder2-open" aria-hidden="true"></span>
                &nbsp;${fn:escapeXml(perms.weblog.name)}
            </h3>

            <p> <a href='${fn:escapeXml(perms.weblog.absoluteURL)}'>
            ${fn:escapeXml(perms.weblog.absoluteURL)}</a></p>

            <p><c:out value="${perms.weblog.about}" escapeXml="false"/></p>

            <%-- Humanized, localized labels -- this used to print the raw
                 WeblogPermission action constants (ADMIN/POST/EDIT_DRAFT) in
                 upper case. inviteMember.administrator/.author/.limited
                 already exist (and are already translated in every locale
                 bundle) from the deleted invite screen; reused here rather
                 than adding new keys. This page is their only remaining
                 consumer -- do not delete them as orphans. --%>
            <c:set var="permLabels" value="false"/>
            <p>You have
            <c:if test='${perms.hasAction("admin")}'><spring:message code="inviteMember.administrator"/><c:set var="permLabels" value="true"/></c:if>
            <c:if test='${perms.hasAction("post")}'><c:if test="${permLabels}">, </c:if><spring:message code="inviteMember.author"/><c:set var="permLabels" value="true"/></c:if>
            <c:if test='${perms.hasAction("edit_draft")}'><c:if test="${permLabels}">, </c:if><spring:message code="inviteMember.limited"/><c:set var="permLabels" value="true"/></c:if>
            <spring:message code='yourWebsites.permission'/></p>

            <div class="btn-group" role="group" aria-label="...">

                <%-- New entry button --%>
                <c:url value="/roller-ui/authoring/entryAdd.rol" var="newEntry">
                    <c:param name="weblog" value="${perms.weblog.handle}"/>
                </c:url>
                <a href="${newEntry}" class="btn btn-secondary">
                    <span class="bi bi-pencil" aria-hidden="true"></span>
                    <spring:message code="yourWebsites.newEntry"/>
                </a>

                <c:if test='${!perms.hasAction("edit_draft")}'>

                    <%-- Show Entries button with count for users above LIMITED permission --%>
                    <c:url value="/roller-ui/authoring/entries.rol" var="editEntries">
                        <c:param name="weblog" value="${perms.weblog.handle}"/>
                    </c:url>
                    <a href="${editEntries}" class="btn btn-secondary">
                        <span class="bi bi-list" aria-hidden="true"></span>
                        <spring:message code="yourWebsites.editEntries"/>
                        <span class="badge bg-secondary">${perms.weblog.entryCount}</span>
                    </a>

                </c:if>

                <%-- Only admins get access to theme and config settings --%>
                <c:if test='${perms.hasAction("admin")}'>

                    <%-- Choosing among the shared themes is safe and always available.
                         A weblog already on a custom theme links to its own templates
                         instead, and that link stays behind the custom-theme flag --
                         same gate ThemeEditController enforces server-side. --%>
                    <c:choose>
                        <c:when test="${perms.weblog.editorTheme == 'custom'}">
                            <c:if test="${rc:getProp('themes.customtheme.allowed')}">
                                <c:url value="/roller-ui/authoring/templates.rol" var="weblogTheme">
                                    <c:param name="weblog" value="${perms.weblog.handle}" />
                                </c:url>
                                <a href='${weblogTheme}' class="btn btn-secondary">
                                    <span class="bi bi-eye" aria-hidden="true"></span>
                                    <spring:message code="yourWebsites.theme" />
                                </a>
                            </c:if>
                        </c:when>
                        <c:otherwise>
                            <c:url value="/roller-ui/authoring/themeEdit.rol" var="weblogTheme">
                                <c:param name="weblog" value="${perms.weblog.handle}" />
                            </c:url>
                            <a href='${weblogTheme}' class="btn btn-secondary">
                                <span class="bi bi-eye" aria-hidden="true"></span>
                                <spring:message code="yourWebsites.theme" />
                            </a>
                        </c:otherwise>
                    </c:choose>

                    <%-- settings button --%>
                    <c:url value="/roller-ui/authoring/weblogConfig.rol" var="manageWeblog">
                        <c:param name="weblog" value="${perms.weblog.handle}"/>
                    </c:url>
                    <a href='${manageWeblog}' class="btn btn-secondary">
                        <span class="bi bi-gear" aria-hidden="true"></span>
                        <spring:message code="yourWebsites.manage"/>
                    </a>

                </c:if>

            </div>

        </div>

    </c:forEach>

</c:if>
