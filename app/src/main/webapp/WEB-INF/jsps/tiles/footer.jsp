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


<% request.setAttribute("rollerVersion",
      org.apache.roller.weblogger.business.WebloggerFactory.getWeblogger().getVersion()); %>
<% request.setAttribute("rollerRevision",
      org.apache.roller.weblogger.business.WebloggerFactory.getWeblogger().getRevision()); %>

<span class="roller-footer">
<img src='<c:url value="/images/tinyfeather.png"/>' alt="ASF logo" class="roller-footer-mark" />
<%-- "${request.rollerVersion}" would be wrong here: inside JSP EL, the bare
     identifier "request" is the *implicit* HttpServletRequest object, not a
     shorthand for requestScope -- ".rollerVersion" would try (and fail) to
     call a getRollerVersion() bean-property getter on the servlet request
     itself, not read the attribute set above. That mistake ("${request.version}"
     /"${request.revision}") is exactly what rendered every footer as
     "Version  ()": both resolved to null, and spring:message dutifully
     formatted the two blanks into the pattern. Bare "${rollerVersion}" lets
     EL's normal scope search (page/request/session/application) find the
     request attribute directly. When it is somehow still empty (a version
     resource that failed to load), skip the "Version" clause instead of
     rendering empty parens. --%>
<c:choose>
<c:when test="${not empty rollerVersion}">
<spring:message code="footer.productName" arguments="${rollerVersion},${rollerRevision}"/>
</c:when>
<c:otherwise>
<spring:message code="footer.productNameNoVersion"/>
</c:otherwise>
</c:choose>
</span>

