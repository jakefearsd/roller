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
<!doctype html>
<html>
<head>
    <meta charset="utf-8">
    <meta http-equiv="X-UA-Compatible" content="IE=edge">
    <meta name="viewport" content="width=device-width, initial-scale=1">
    <link rel="icon" href="<%= request.getContextPath() %>/favicon.svg" type="image/x-icon">
    <title><%= org.apache.roller.weblogger.config.WebloggerRuntimeConfig.getProperty("site.shortName") %>: <spring:message code="${pageTitle}" text="${pageTitle}"/></title>
    <jsp:include page="${head}"/>
    <style>
        <jsp:include page="${styles}" />
    </style>
</head>
<body>

<jsp:include page="${banner}"/>

<div class="container-fluid">

    <div class="row">
        <div class="col-md-1"></div>
        <div class="col-md-10">

            <h1 class="roller-page-title"><spring:message code="${pageTitle}" text="${pageTitle}"/></h1>
            <p><jsp:include page="${messages}"/>
            <div class="panel">
                <div class="panel-body">
                    <jsp:include page="${content}"/>
                </div>
            </div>

        </div>
        <div class="col-md-1"></div>
    </div>
</div>

</body>
</html>
