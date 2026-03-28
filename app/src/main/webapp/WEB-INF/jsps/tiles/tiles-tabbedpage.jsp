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
<!DOCTYPE html>
<html>
<head>
    <meta charset="utf-8">
    <meta http-equiv="X-UA-Compatible" content="IE=edge">
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

            <div class="panel panel-default">
                <div class="panel-body" style="text-align: center">

                    <img src='<c:url value="/roller-ui/images/feather.svg" />'
                         alt="ASF feat" height="100" align="center"/>
                    <h4><spring:message code="generic.poweredBy" /></h4>

                    <c:if test="${not empty authenticatedUser or not empty actionWeblog}">
                        <jsp:include page="${tile_userStatus}"/>
                    </c:if>

                </div>
            </div>

            <c:if test="${tile_sidebar != '/WEB-INF/jsps/tiles/empty.jsp'}">
                <div class="panel panel-default">
                    <div class="panel-body">
                        <jsp:include page="${tile_sidebar}"/>
                    </div>
                </div>
            </c:if>

        </div>

        <div class="col-md-9 roller-column-right">
            <div class="panel panel-default">
                <div class="panel-body" style="min-height: 30em">

                    <jsp:include page="${tile_messages}"/>

                    <h2 class="roller-page-title"><spring:message code="${pageTitle}" text="${pageTitle}"/></h2>
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
