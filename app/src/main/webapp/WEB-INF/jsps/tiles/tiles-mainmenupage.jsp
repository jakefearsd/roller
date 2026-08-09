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
<%@ include file="/WEB-INF/jsps/tiles/menu-model.jsp" %>
<!doctype html>
<html>
<head>
    <meta charset="utf-8">
    <meta http-equiv="X-UA-Compatible" content="IE=edge">
    <meta name="viewport" content="width=device-width, initial-scale=1">
    <link rel="icon" href="<%= request.getContextPath() %>/favicon.svg" type="image/x-icon">
    <title><%= org.apache.roller.weblogger.config.WebloggerRuntimeConfig.getProperty("site.shortName") %>: <spring:message code="${pageTitle}" text="${pageTitle}"/></title>

    <jsp:include page="${tile_head}"/>
    <style>
        <jsp:include page="${tile_styles}" />
    </style>
</head>
<body>

<jsp:include page="${tile_banner}"/>

<div class="container-fluid">

    <div class="row">

        <div class="col-md-3 roller-column-left">

            <nav class="rail" id="adminRail">

                <c:if test="${not empty authenticatedUser or not empty actionWeblog}">
                    <div class="rail-context">
                        <jsp:include page="/WEB-INF/jsps/tiles/userStatus.jsp"/>
                    </div>
                </c:if>

                <c:if test="${navMenu != null}">
                    <c:forEach items="${navMenu.tabs}" var="tab">
                        <div class="rail-group">
                            <div class="rail-group-label"><spring:message code="${tab.key}"/></div>
                            <c:forEach items="${tab.items}" var="tabItem">
                                <c:choose>
                                    <c:when test="${actionWeblog != null}">
                                        <a class="rail-link${tabItem.selected ? ' rail-active' : ''}"
                                           ${tabItem.selected ? 'aria-current="page"' : ''}
                                           href="<c:url value="/roller-ui/authoring/${tabItem.action}.rol">
                                               <c:param name="weblog" value="${actionWeblog.handle}"/></c:url>">
                                            <spring:message code="${tabItem.key}"/>
                                        </a>
                                    </c:when>
                                    <c:otherwise>
                                        <a class="rail-link${tabItem.selected ? ' rail-active' : ''}"
                                           ${tabItem.selected ? 'aria-current="page"' : ''}
                                           href="<c:url value='/roller-ui/admin/${tabItem.action}.rol'/>">
                                            <spring:message code="${tabItem.key}"/>
                                        </a>
                                    </c:otherwise>
                                </c:choose>
                            </c:forEach>
                        </div>
                    </c:forEach>
                </c:if>

            </nav>

            <div class="card">
                <div class="card-body">

                    <jsp:include page="${tile_sidebar}"/>

                </div>
            </div>

        </div>

        <div class="col-md-9 roller-column-right">

            <div class="card">
                <div class="card-body">

                    <h2 class="roller-page-title"><spring:message code="${pageTitle}" text="${pageTitle}"/></h2>
                    <jsp:include page="${tile_messages}"/>
                    <jsp:include page="${tile_content}"/>

                </div>
            </div>

        </div>
    </div>
</div>

<footer class="footer">
    <div class="container-fluid">
        <jsp:include page="${tile_footer}"/>
    </div>
</footer>

</body>
</html>
