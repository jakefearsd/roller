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

<p class="subtitle">
    <spring:message code="weblogPagesForm.subtitle" arguments="${actionWeblog.handle}"/>
</p>

<p class="pagetip">
    <c:url var="addUrl" value="/roller-ui/authoring/pageEdit.rol">
        <c:param name="weblog" value="${actionWeblog.handle}"/>
    </c:url>
    <a href="${addUrl}" class="btn btn-primary btn-sm">
        <spring:message code="weblogPagesForm.add"/>
    </a>
</p>

<%-- One form around the whole table, following Entries.jsp's pattern: the
     remove action is a hidden field this table's rows set via JS and submit,
     rather than a nested form per row (which is not valid HTML). --%>
<form id="pageRemoveForm" method="post"
      action="${pageContext.request.contextPath}/roller-ui/authoring/pageRemove.rol">
<input type="hidden" name="weblog" value="${actionWeblog.handle}"/>
    <input type="hidden" name="removeId" id="removeId" value=""/>
    <sec:csrfInput/>

    <table class="rollertable table table-striped" width="100%">
        <c:choose>
        <c:when test="${not empty pages}">

            <tr>
                <th class="rollertable"><spring:message code="weblogPagesForm.slug"/></th>
                <th class="rollertable"><spring:message code="weblogEdit.title"/></th>
                <th class="rollertable" width="10%"><spring:message code="weblogEdit.status"/></th>
                <th class="rollertable" width="10%"><spring:message code="weblogPagesForm.showInNav"/></th>
                <th class="rollertable" width="10%"><spring:message code="weblogPagesForm.navOrder"/></th>
                <th class="rollertable" width="5%"> </th>
                <th class="rollertable" width="5%"> </th>
            </tr>

            <c:forEach items="${pages}" var="p">
                <tr data-page-id="${fn:escapeXml(p.id)}" data-page-slug="${fn:escapeXml(p.slug)}">
                    <td>/<c:out value="${p.slug}"/></td>
                    <td>
                        <c:url var="editUrl" value="/roller-ui/authoring/pageEdit.rol">
                            <c:param name="weblog" value="${actionWeblog.handle}"/>
                            <c:param name="id" value="${p.id}"/>
                        </c:url>
                        <a href="${editUrl}"><c:out value="${p.title}"/></a>
                    </td>
                    <td>
                        <c:choose>
                        <c:when test="${p.status.name() == 'PUBLISHED'}">
                            <span class="badge bg-success"><spring:message code="weblogEdit.published"/></span>
                        </c:when>
                        <c:otherwise>
                            <span class="badge bg-info"><spring:message code="weblogEdit.draft"/></span>
                        </c:otherwise>
                        </c:choose>
                    </td>
                    <td>${p.showInNav ? '&#10003;' : ''}</td>
                    <td>${p.navOrder}</td>
                    <td>
                        <a href="${editUrl}">
                            <span class="bi bi-pencil-square" title="<spring:message code="generic.edit"/>"></span>
                        </a>
                    </td>
                    <td>
                        <a href="#" onclick="confirmPageDelete('${p.id}', '${fn:escapeXml(p.title)}'); return false;">
                            <span class="bi bi-trash" title="<spring:message code="generic.delete"/>"></span>
                        </a>
                    </td>
                </tr>
            </c:forEach>

        </c:when>
        <c:otherwise>
            <tr>
                <td colspan="7"><spring:message code="weblogPagesForm.noneFound"/></td>
            </tr>
        </c:otherwise>
        </c:choose>
    </table>

</form>

<script>
    function confirmPageDelete(pageId, pageTitle) {
        // A native confirm is acceptable here: unlike the bulk-delete flow on
        // Entries.jsp, there is exactly one thing being removed and no count
        // to report.
        if (window.confirm('<spring:message code="weblogPagesForm.removeConfirm"/>: \'' + pageTitle + '\'?')) {
            document.getElementById('removeId').value = pageId;
            document.getElementById('pageRemoveForm').submit();
        }
    }
</script>
