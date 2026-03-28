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

<p class="subtitle"><spring:message code="planetGroups.subtitle"/></p>


<%-- ================================================================== --%>
<%-- table of custom planet groups (excluding the default group) --%>

<c:if test="${!empty groups}">

    <table class="table">

        <tr>
            <th width="50%"> <spring:message code="planetGroups.column.title"/> </th>
            <th width="20%"> <spring:message code="planetGroups.column.handle"/> </th>
            <th width="15%"> <spring:message code="generic.edit"/> </th>
            <th width="15%"> <spring:message code="generic.delete"/> </th>
        </tr>

        <c:forEach var="group" items="${groups}">
            <tr>
                <td> ${fn:escapeXml(group.title)} </td>
                <td> ${fn:escapeXml(group.handle)} </td>

                <td>
                    <c:url var="groupUrl" value="/roller-ui/admin/planetGroupSubs.rol">
                        <c:param name="group.id" value="${group.id}"/>
                    </c:url>
                    <a href="${groupUrl}">
                        <span class="glyphicon glyphicon-edit" aria-hidden="true"></span>
                        <spring:message code='generic.edit'/>
                    </a>
                </td>

                <td>
                    <a href="javascript: void(0);" onclick="confirmDelete('${fn:escapeXml(group.handle)}')">
                        <span class="glyphicon glyphicon-remove" aria-hidden="true"> </span>
                        <spring:message code="generic.delete"/>
                    </a>
                </td>

            </tr>
        </c:forEach>

    </table>

    <%-- planet group delete logic --%>

    <form method="post" action="<c:url value='/roller-ui/admin/planetGroups!delete.rol'/>" id="deleteForm">
        <sec:csrfInput/>
        <input type="hidden" name="group.handle"/>
    </form>

    <script>
        function confirmDelete(groupHandle) {
            if (window.confirm('<spring:message code="planetGroups.delete.confirm" />')) {
                var form = $("#deleteForm");
                form.find('input[name="group.handle"]').val(groupHandle);
                form.submit();
            }
        }
    </script>

</c:if>
<c:if test="${empty groups}">
    <spring:message code="planetGroups.noneDefined"/>
</c:if>
