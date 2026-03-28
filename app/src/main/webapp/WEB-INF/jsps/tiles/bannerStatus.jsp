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

<nav class="navbar navbar-default navbar-static-top navbar-inverse">
    <div class="container-fluid">
        <div id="navbar" class="navbar-collapse collapse">

            <div class="navbar-header">
                <button type="button" class="navbar-toggle collapsed"
                        data-toggle="collapse" data-target="#navbar" aria-expanded="false" aria-controls="navbar">
                    <span class="sr-only">Toggle navigation</span>
                    <span class="icon-bar"></span>
                    <span class="icon-bar"></span>
                    <span class="icon-bar"></span>
                </button>
                <a class="navbar-brand" href="#">${getProp('site.name')}</a>
            </div>

            <ul class="nav navbar-nav">

                <c:choose>
                    <c:when test="${actionWeblog != null}">

                        <c:set var="tabMenu" value="${menu}"/>
                        <c:if test="${tabMenu != null}">
                            <c:forEach items="${tabMenu.tabs}" var="tab">
                                <li class="dropdown">
                                    <a href="#" class="dropdown-toggle" data-toggle="dropdown" role="button"
                                       aria-haspopup="true" aria-expanded="false">
                                        <spring:message code="${tab.key}"/> <span class="caret"></span>
                                    </a>
                                    <ul class="dropdown-menu">
                                        <c:forEach items="${tab.items}" var="tabItem" varStatus="stat">
                                            <li>
                                                <a href="<c:url value="/roller-ui/authoring/${tabItem.action}.rol">
                                                    <c:param name="weblog" value="${actionWeblog.handle}"/></c:url>">
                                                    <spring:message code="${tabItem.key}"/>
                                                </a>
                                            </li>
                                        </c:forEach>
                                    </ul>
                                </li>
                            </c:forEach>
                        </c:if>

                    </c:when>
                    <c:otherwise>

                        <c:set var="tabMenu" value="${menu}"/>
                        <c:if test="${tabMenu != null}">
                            <c:forEach items="${tabMenu.tabs}" var="tab">
                                <li class="dropdown">
                                    <a href="#" class="dropdown-toggle" data-toggle="dropdown" role="button"
                                       aria-haspopup="true" aria-expanded="false">
                                        <spring:message code="${tab.key}"/> <span class="caret"></span>
                                    </a>
                                    <ul class="dropdown-menu">
                                        <c:forEach items="${tab.items}" var="tabItem" varStatus="stat">
                                            <li>
                                                <a href="<c:url value='/roller-ui/authoring/${tabItem.action}.rol'/>">
                                                    <spring:message code="${tabItem.key}"/>
                                                </a>
                                            </li>
                                        </c:forEach>
                                    </ul>
                                </li>
                            </c:forEach>
                        </c:if>

                    </c:otherwise>
                </c:choose>

            </ul>

            <ul class="nav navbar-nav navbar-right">

                <li><a href="<c:url value='/'/>">${getProp('site.shortName')}</a></li>

                <li>
                    <a href="<c:url value="/roller-ui/menu.rol"/>">
                        <spring:message code="mainPage.mainMenu"/></a>
                </li>

                <c:choose>
                    <c:when test="${authenticatedUser != null}">
                        <li>
                            <a href="<c:url value="/roller-ui/logout.rol"/>">
                                <spring:message code="navigationBar.logout"/></a>
                        </li>
                    </c:when>
                    <c:otherwise>
                        <li>
                            <a href="<c:url value="/roller-ui/login-redirect.rol"/>">
                                <spring:message code="navigationBar.login"/></a>
                        </li>

                        <c:choose>
                            <c:when test="${getBooleanProp('users.registration.enabled') && getProp('authentication.method') != 'ldap'}">
                                <li>
                                    <a href="<c:url value="/roller-ui/register.rol"/>">
                                        <spring:message code="navigationBar.register"/></a>
                                </li>
                            </c:when>
                            <c:when test="${not empty getProp('users.registration.url')}">
                                <li>
                                    <a href="${getProp('users.registration.url')}">
                                        <spring:message code="navigationBar.register"/></a>
                                </li>
                            </c:when>
                        </c:choose>
                    </c:otherwise>
                </c:choose>

            </ul>
        </div><!--/.nav-collapse -->
    </div>
</nav>
