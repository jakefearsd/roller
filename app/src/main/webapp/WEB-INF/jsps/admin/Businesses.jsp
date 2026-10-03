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

<p class="subtitle"><spring:message code="businesses.subtitle"/></p>

<spring:message code="businesses.delete.confirm" var="businessDeleteConfirm"/>
<div id="businesses-list-marker">

<p>
    <c:url var="newBusinessUrl" value="/roller-ui/admin/businesses!edit.rol"/>
    <a href="${newBusinessUrl}" class="btn btn-primary" id="business-new"><spring:message code="businesses.new"/></a>
</p>

<c:choose>
<c:when test="${empty businesses}">
    <div class="empty-state">
        <p class="empty-state-title"><spring:message code="businesses.empty"/></p>
    </div>
</c:when>
<c:otherwise>
<table class="rollertable table table-striped" width="100%">
    <tr>
        <th scope="col"><spring:message code="businesses.name"/></th>
        <th scope="col"><spring:message code="businesses.type"/></th>
        <th scope="col"><spring:message code="businesses.usedBy"/></th>
        <th scope="col">&nbsp;</th>
        <th scope="col">&nbsp;</th>
    </tr>
    <c:forEach var="b" items="${businesses}">
    <tr>
        <td>${fn:escapeXml(b.name)}</td>
        <td><spring:message code="businesses.type.${b.businessType}"/></td>
        <td>${fn:escapeXml(usage[b.id])}</td>
        <td>
            <c:url var="editBusinessUrl" value="/roller-ui/admin/businesses!edit.rol">
                <c:param name="id" value="${b.id}"/>
            </c:url>
            <a href="${editBusinessUrl}"><spring:message code="generic.edit"/></a>
        </td>
        <td>
            <form method="post" action="<c:url value='/roller-ui/admin/businesses!delete.rol'/>">
                <sec:csrfInput/>
                <input type="hidden" name="id" value="${fn:escapeXml(b.id)}"/>
                <button type="submit" class="btn btn-link p-0 align-baseline border-0 text-danger"
                        data-confirm="${fn:escapeXml(businessDeleteConfirm)}">
                    <spring:message code="generic.delete"/>
                </button>
            </form>
        </td>
    </tr>
    </c:forEach>
</table>
</c:otherwise>
</c:choose>

</div>
