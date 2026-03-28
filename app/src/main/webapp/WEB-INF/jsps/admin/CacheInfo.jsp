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

<p class="subtitle"><spring:message code="cacheInfo.subtitle" />
<p><spring:message code="cacheInfo.prompt" />

<c:forEach var="cache" items="${stats}">
    <c:if test="${not empty cache.value}">

        <table class="table table-bordered">
            <tr>
                <th colspan="2">${fn:escapeXml(cache.key)}</th>
            </tr>

            <c:forEach var="prop" items="${cache.value}">
                <tr>
                    <td>${fn:escapeXml(prop.key)}</td>
                    <td>${fn:escapeXml(prop.value)}</td>
                </tr>
            </c:forEach>

            <tr>
                <td colspan="2">
                    <form method="post" action="<c:url value='/roller-ui/admin/cacheInfo!clear.rol'/>">
                        <sec:csrfInput/>
                        <input type="hidden" name="cache" value="${fn:escapeXml(cache.key)}" />
                        <button type="submit" class="btn btn-default"><spring:message code="cacheInfo.clear"/></button>
                    </form>
                </td>
            </tr>

        </table>

        <br>
    </c:if>
</c:forEach>
