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
<%--
  The share-link password gate (ShareController). Deliberately a complete,
  theme-independent page: the visitor is anonymous and the content behind the
  gate must not leak anything -- not even the weblog's name -- until the
  password matches. The <sec:csrfInput/> is what lets an anonymous visitor
  POST at all (Spring Security CSRF is on globally; the token repository is
  session-backed and the session is created on demand for this render).
--%>
<%@ include file="/WEB-INF/jsps/taglibs-spring.jsp" %>
<!DOCTYPE html>
<html>
<head>
    <meta charset="utf-8">
    <meta name="viewport" content="width=device-width, initial-scale=1">
    <meta name="robots" content="noindex, nofollow">
    <title><spring:message code="sharePassword.title"/></title>
    <style>
        body { margin: 0 auto; max-width: 24rem; padding: 4rem 1rem; font-family: system-ui, sans-serif; }
        input[type=password] { width: 100%; padding: .4rem; margin: .5rem 0; box-sizing: border-box; }
        button { padding: .4rem 1.2rem; }
        .share-password-error { color: #b00020; }
    </style>
</head>
<body>
<h1><spring:message code="sharePassword.title"/></h1>
<p><spring:message code="sharePassword.prompt"/></p>
<c:if test="${shareWrongPassword}">
    <p class="share-password-error"><spring:message code="sharePassword.wrong"/></p>
</c:if>
<form id="sharePasswordForm" method="post"
      action="${pageContext.request.contextPath}/share/${shareToken}">
    <input type="password" name="sharePassword" autofocus autocomplete="current-password"/>
    <button type="submit"><spring:message code="sharePassword.submit"/></button>
    <sec:csrfInput/>
</form>
</body>
</html>
