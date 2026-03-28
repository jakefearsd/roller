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

<%-- PROMPT: Welcome... you have no blog --%>
<c:if test="${empty existingPermissions && empty pendingPermissions}">
    <p><spring:message code="yourWebsites.prompt.noBlog" />
    <a id="createWeblogLink" href="<c:url value='/roller-ui/createWeblog.rol'/>">
        <spring:message code="yourWebsites.createOne" />
    </a></p>
</c:if>

<%-- PROMPT: You have invitation(s) --%>
<c:if test="${not empty pendingPermissions}">
    <p><spring:message code="yourWebsites.invitationsPrompt" /></p>

    <c:forEach items="${pendingPermissions}" var="invite">
        <spring:message code="yourWebsites.youAreInvited" arguments="${invite.weblog.handle}" />

        <c:url value="/roller-ui/menu!accept.rol" var="acceptInvite">
            <c:param name="inviteId" value="${invite.weblog.id}" />
        </c:url>
        <a href='${acceptInvite}'>
            <spring:message code="yourWebsites.accept" />
        </a>
        &nbsp;|&nbsp;
        <c:url value="/roller-ui/menu!decline.rol" var="declineInvite">
            <c:param name="inviteId" value="${invite.weblog.id}" />
        </c:url>
        <a href='${declineInvite}'>
            <spring:message code="yourWebsites.decline" />
        </a><br />
    </c:forEach>
    <br />
</c:if>

<%-- PROMPT: default ... select a weblog to edit --%>
<c:if test="${not empty existingPermissions && empty pendingPermissions}">
    <p class="subtitle"><spring:message code="yourWebsites.prompt.hasBlog" /></p>
</c:if>

<%-- if we have weblogs, then loop through and list them --%>
<c:if test="${not empty existingPermissions}">

    <c:forEach items="${existingPermissions}" var="perms">

        <div class="well yourWeblogBox">

            <h3 class="mm_weblog_name">
                <span class="glyphicon glyphicon-folder-open" aria-hidden="true"></span>
                &nbsp;${fn:escapeXml(perms.weblog.name)}
            </h3>

            <p> <a href='${fn:escapeXml(perms.weblog.absoluteURL)}'>
            ${fn:escapeXml(perms.weblog.absoluteURL)}</a></p>

            <p><c:out value="${perms.weblog.about}" escapeXml="false"/></p>

            <p>You have
            <c:if test='${perms.hasAction("admin")}'>ADMIN </c:if>
            <c:if test='${perms.hasAction("post")}'>AUTHOR </c:if>
            <c:if test='${perms.hasAction("edit_draft")}'>LIMITED </c:if>
            <spring:message code='yourWebsites.permission'/></p>

            <div class="btn-group" role="group" aria-label="...">

                <%-- New entry button --%>
                <c:url value="/roller-ui/authoring/entryAdd.rol" var="newEntry">
                    <c:param name="weblog" value="${perms.weblog.handle}"/>
                </c:url>
                <a href="${newEntry}" class="btn btn-default">
                    <span class="glyphicon glyphicon-pencil" aria-hidden="true"></span>
                    <spring:message code="yourWebsites.newEntry"/>
                </a>

                <c:if test='${!perms.hasAction("edit_draft")}'>

                    <%-- Show Entries button with count for users above LIMITED permission --%>
                    <c:url value="/roller-ui/authoring/entries.rol" var="editEntries">
                        <c:param name="weblog" value="${perms.weblog.handle}"/>
                    </c:url>
                    <a href="${editEntries}" class="btn btn-default">
                        <span class="glyphicon glyphicon-list" aria-hidden="true"></span>
                        <spring:message code="yourWebsites.editEntries"/>
                        <span class="badge">${perms.weblog.entryCount}</span>
                    </a>

                </c:if>

                <c:if test='${!perms.hasAction("edit_draft")}'>

                    <%-- Show Comments button with count for users above LIMITED permission --%>
                    <c:url value="/roller-ui/authoring/comments.rol" var="manageComments">
                        <c:param name="weblog" value="${perms.weblog.handle}"/>
                    </c:url>
                    <a href="${manageComments}" class="btn btn-default">
                        <span class="glyphicon glyphicon-comment" aria-hidden="true"></span>
                        <spring:message code="yourWebsites.manageComments"/>
                        <span class="badge">${perms.weblog.commentCount}</span>
                    </a>

                </c:if>


                <%-- Only admins get access to theme and config settings --%>
                <c:if test='${perms.hasAction("admin")}'>

                    <%-- And only show theme option if custom themes are enabled --%>
                    <c:if test="${rc:getProp('themes.customtheme.allowed')}">
                        <c:choose>
                            <c:when test="${perms.weblog.editorTheme == 'custom'}">
                                <c:url value="/roller-ui/authoring/templates.rol" var="weblogTheme">
                                    <c:param name="weblog" value="${perms.weblog.handle}" />
                                </c:url>
                            </c:when>
                            <c:otherwise>
                                <c:url value="/roller-ui/authoring/themeEdit.rol" var="weblogTheme">
                                    <c:param name="weblog" value="${perms.weblog.handle}" />
                                </c:url>
                            </c:otherwise>
                        </c:choose>
                        <a href='${weblogTheme}' class="btn btn-default">
                            <span class="glyphicon glyphicon-eye-open" aria-hidden="true"></span>
                            <spring:message code="yourWebsites.theme" />
                        </a>
                    </c:if>

                    <%-- settings button --%>
                    <c:url value="/roller-ui/authoring/weblogConfig.rol" var="manageWeblog">
                        <c:param name="weblog" value="${perms.weblog.handle}"/>
                    </c:url>
                    <a href='${manageWeblog}' class="btn btn-default">
                        <span class="glyphicon glyphicon-cog" aria-hidden="true"></span>
                        <spring:message code="yourWebsites.manage"/>
                    </a>

                </c:if>

                <%-- don't allow last admin to resign from blog --%>
                <c:if test='${!(perms.hasAction("admin") && perms.weblog.adminUserCount == 1)}'>

                    <button type="button" class="btn btn-default">
                        <span class="glyphicon glyphicon-trash" aria-hidden="true"></span>
                        <c:url value="/roller-ui/authoring/memberResign.rol" var="resignWeblog">
                            <c:param name="weblog" value="${perms.weblog.handle}"/>
                        </c:url>
                        <a href='${resignWeblog}'>
                            <spring:message code='yourWebsites.resign'/>
                        </a>
                    </button>

                </c:if>

            </div>

        </div>

    </c:forEach>

</c:if>
