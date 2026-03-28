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

<p>
    <spring:message code="yourWebsites.confirmResignation" arguments="${actionWeblog.handle}"/>
</p>

<form action="${pageContext.request.contextPath}/roller-ui/authoring/memberResign!resign.rol" method="post" class="form-horizontal">
<input type="hidden" name="weblog" value="${actionWeblog.handle}"/>
    <button type="submit" class="form-horizontal"><spring:message code="generic.yes"/></button>&nbsp;
    <button type="submit" class="form-horizontal" formaction="${pageContext.request.contextPath}/roller-ui/authoring/menu.rol"><spring:message code="generic.no"/></button>
<sec:csrfInput/>
</form>
