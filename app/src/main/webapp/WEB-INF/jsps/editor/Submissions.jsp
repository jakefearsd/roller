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
    <spring:message code="submissions.subtitle" arguments="${actionWeblog.handle}"/>
</p>

<%-- ============================================================= --%>
<%-- Next / previous links --%>

<nav>
    <div class="d-flex justify-content-between">
        <c:if test="${page > 0}">
            <c:url var="prevUrl" value="/roller-ui/authoring/submissions.rol">
                <c:param name="weblog" value="${actionWeblog.handle}"/>
                <c:param name="page" value="${page - 1}"/>
            </c:url>
            <a href="${prevUrl}" class="btn btn-outline-secondary previous">
                <span aria-hidden="true">&larr;</span> Newer</a>
        </c:if>
        <c:if test="${(page + 1) * 30 < submissionCount}">
            <c:url var="nextUrl" value="/roller-ui/authoring/submissions.rol">
                <c:param name="weblog" value="${actionWeblog.handle}"/>
                <c:param name="page" value="${page + 1}"/>
            </c:url>
            <a href="${nextUrl}" class="btn btn-outline-secondary next ms-auto">Older
                <span aria-hidden="true">&rarr;</span></a>
        </c:if>
    </div>
</nav>

<%-- One form around the whole table, following the Entries.jsp/Pages.jsp
     pattern: the row checkboxes and the delete-selected button both post
     through it, rather than a form per row (which is not valid HTML).

     Every author-controlled field below -- name, email, subject, message,
     pageSlug/entryAnchor -- comes from an anonymous, unauthenticated visitor
     via ContactController, so each one is wrapped in <c:out>/fn:escapeXml,
     including inside the mailto href. --%>
<form id="submissionsDeleteForm" method="post"
      action="${pageContext.request.contextPath}/roller-ui/authoring/submissions!delete.rol">
<input type="hidden" name="weblog" value="${actionWeblog.handle}"/>
    <sec:csrfInput/>

    <table class="rollertable table table-striped" width="100%">
        <c:choose>
        <c:when test="${not empty submissions}">

            <tr>
                <th class="rollertable" width="3%">
                    <input type="checkbox" id="selectAllSubmissions" class="form-check-input"
                           title="<spring:message code="weblogEntryQuery.selectAll"/>"/>
                </th>
                <th class="rollertable"><spring:message code="submissions.column.received"/></th>
                <th class="rollertable"><spring:message code="submissions.column.from"/></th>
                <th class="rollertable"><spring:message code="submissions.column.subject"/></th>
                <th class="rollertable"><spring:message code="submissions.column.message"/></th>
                <th class="rollertable"><spring:message code="submissions.column.source"/></th>
            </tr>

            <c:forEach items="${submissions}" var="s">
                <tr>
                    <td>
                        <input type="checkbox" class="form-check-input submission-select"
                               name="deleteIds" value="${fn:escapeXml(s.id)}"/>
                    </td>
                    <td>
                        <c:if test="${s.created != null}">
                            <fmt:formatDate value="${s.created}" type="both"
                                            dateStyle="short" timeStyle="short"/>
                        </c:if>
                    </td>
                    <td>
                        <c:out value="${s.name}"/><br/>
                        <a href="mailto:${fn:escapeXml(s.email)}"><c:out value="${s.email}"/></a>
                    </td>
                    <td><c:out value="${s.subject}"/></td>
                    <td>
                        <c:choose>
                        <c:when test="${fn:length(s.message) > 140}">
                            <c:out value="${fn:substring(s.message, 0, 140)}"/>&hellip;
                            <details>
                                <summary>Show full message</summary>
                                <p><c:out value="${s.message}"/></p>
                            </details>
                        </c:when>
                        <c:otherwise>
                            <c:out value="${s.message}"/>
                        </c:otherwise>
                        </c:choose>
                    </td>
                    <td>
                        <c:choose>
                        <c:when test="${not empty s.entryAnchor}">
                            <c:out value="${s.entryAnchor}"/>
                        </c:when>
                        <c:when test="${not empty s.pageSlug}">
                            /<c:out value="${s.pageSlug}"/>
                        </c:when>
                        <c:otherwise>&mdash;</c:otherwise>
                        </c:choose>
                    </td>
                </tr>
            </c:forEach>

        </c:when>
        <c:otherwise>
            <tr>
                <td colspan="6"><spring:message code="submissions.none"/></td>
            </tr>
        </c:otherwise>
        </c:choose>
    </table>

    <c:if test="${not empty submissions}">
        <button type="submit" class="btn btn-danger" id="submissionsDeleteSelected">
            <spring:message code="generic.delete.selected"/>
        </button>
    </c:if>

</form>

<script>
    (function () {
        var selectAll = document.getElementById('selectAllSubmissions');
        if (!selectAll) {
            return;
        }
        selectAll.addEventListener('change', function () {
            var checked = this.checked;
            document.querySelectorAll('.submission-select').forEach(function (cb) {
                cb.checked = checked;
            });
        });
    })();
</script>
