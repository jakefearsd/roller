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
<%@ page import="org.apache.roller.weblogger.ui.core.util.menu.Menu" %>

<%-- Extract the menu from the request. The model attribute "menu" may collide
     with other attributes during request dispatching, so we safely cast it. --%>
<%
    Object menuObj = request.getAttribute("menu");
    Menu navMenu = (menuObj instanceof Menu) ? (Menu) menuObj : null;
    request.setAttribute("navMenu", navMenu);
%>

<nav class="navbar navbar-expand-md navbar-dark bg-dark">
    <div class="container-fluid">

        <a class="navbar-brand" href="#">${rc:getProp('site.name')}</a>

        <button type="button" class="navbar-toggler collapsed"
                data-bs-toggle="collapse" data-bs-target="#navbar" aria-expanded="false" aria-controls="navbar"
                aria-label="Toggle navigation">
            <span class="visually-hidden">Toggle navigation</span>
            <span class="navbar-toggler-icon"></span>
        </button>

        <div id="navbar" class="navbar-collapse collapse">

            <ul class="navbar-nav">

                <c:if test="${navMenu != null}">
                    <c:forEach items="${navMenu.tabs}" var="tab">
                        <li class="nav-item dropdown">
                            <a href="#" class="nav-link dropdown-toggle" data-bs-toggle="dropdown" role="button"
                               aria-haspopup="true" aria-expanded="false">
                                <spring:message code="${tab.key}"/>
                            </a>
                            <ul class="dropdown-menu">
                                <c:forEach items="${tab.items}" var="tabItem" varStatus="stat">
                                    <li>
                                        <c:choose>
                                            <c:when test="${actionWeblog != null}">
                                                <a class="dropdown-item" href="<c:url value="/roller-ui/authoring/${tabItem.action}.rol">
                                                    <c:param name="weblog" value="${actionWeblog.handle}"/></c:url>">
                                                    <spring:message code="${tabItem.key}"/>
                                                </a>
                                            </c:when>
                                            <c:otherwise>
                                                <a class="dropdown-item" href="<c:url value='/roller-ui/admin/${tabItem.action}.rol'/>">
                                                    <spring:message code="${tabItem.key}"/>
                                                </a>
                                            </c:otherwise>
                                        </c:choose>
                                    </li>
                                </c:forEach>
                            </ul>
                        </li>
                    </c:forEach>
                </c:if>

            </ul>

            <ul class="navbar-nav ms-auto">

                <li class="nav-item"><a class="nav-link" href="<c:url value='/'/>">${rc:getProp('site.shortName')}</a></li>

                <li class="nav-item">
                    <a class="nav-link" href="<c:url value="/roller-ui/menu.rol"/>">
                        <spring:message code="mainPage.mainMenu"/></a>
                </li>

                <c:choose>
                    <c:when test="${authenticatedUser != null}">
                        <li class="nav-item">
                            <a class="nav-link" href="<c:url value="/roller-ui/logout.rol"/>">
                                <spring:message code="navigationBar.logout"/></a>
                        </li>
                    </c:when>
                    <c:otherwise>
                        <li class="nav-item">
                            <a class="nav-link" href="<c:url value="/roller-ui/login-redirect.rol"/>">
                                <spring:message code="navigationBar.login"/></a>
                        </li>

                    </c:otherwise>
                </c:choose>

            </ul>
        </div><!--/.navbar-collapse -->
    </div>
</nav>
