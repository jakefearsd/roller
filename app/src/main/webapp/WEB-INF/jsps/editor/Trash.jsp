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
    <spring:message code="trash.subtitle" arguments="${actionWeblog.handle}"/>
</p>

<%-- One form around the whole table, following Entries.jsp's pattern: every
     row's Restore/Delete forever control is a submit button carrying its own
     name/value and formaction, not a form of its own (a form nested inside
     another is not valid HTML). trash-list-marker wraps the list region in
     BOTH states (table or empty-state invitation) the same way
     entries-list-marker does on Entries.jsp, so the route sweep has something
     to find whichever one rendered. --%>
<div id="trash-list-marker">
<form id="trashForm" method="post"
      action="${pageContext.request.contextPath}/roller-ui/authoring/trash!restore.rol">
<input type="hidden" name="weblog" value="${actionWeblog.handle}"/>
<sec:csrfInput/>

<c:if test="${not empty trashedEntries}">
<table class="rollertable table table-striped" width="100%">

<tr>
    <th class="rollertable"><spring:message code="trash.column.title"/></th>
    <th class="rollertable" width="15%"><spring:message code="trash.column.trashed"/></th>
    <th class="rollertable" width="15%"><spring:message code="trash.column.category"/></th>
    <th class="rollertable" width="10%"> </th>
    <th class="rollertable" width="10%"> </th>
</tr>

<c:forEach items="${trashedEntries}" var="entry">
    <tr>
        <td>${entry.displayTitle}</td>
        <td class="data">
            <c:if test="${entry.trashedAt != null}">
                <spring:message code="weblogEntryQuery.date.toStringFormat" arguments="${entry.trashedAt}"/>
            </c:if>
        </td>
        <td>${entry.category.name}</td>
        <td>
            <%-- The clicked submit button is the only one whose name/value is
                 sent, so restoreId identifies this row without a hidden field
                 per row -- same reasoning as Entries.jsp's duplicateId. --%>
            <button type="submit" name="restoreId" value="${entry.id}"
                    class="btn btn-outline-secondary btn-sm"
                    formaction="${pageContext.request.contextPath}/roller-ui/authoring/trash!restore.rol">
                <spring:message code="trash.restore"/>
            </button>
        </td>
        <td>
            <button type="submit" name="deleteId" value="${entry.id}"
                    class="btn btn-link p-0 align-baseline border-0 text-danger"
                    formaction="${pageContext.request.contextPath}/roller-ui/authoring/trash!delete.rol"
                    onclick="return confirmDeleteForever();">
                <spring:message code="trash.deleteForever"/>
            </button>
        </td>
    </tr>
</c:forEach>

</table>
</c:if>

<c:if test="${not empty trashedEntries}">
    <div class="d-flex justify-content-end mb-3">
        <button type="submit" class="btn btn-danger"
                formaction="${pageContext.request.contextPath}/roller-ui/authoring/trash!empty.rol"
                onclick="return confirmEmptyTrash();">
            <spring:message code="trash.empty"/>
        </button>
    </div>
</c:if>

<%-- This is the one empty state in the app that is not an invitation: there
     is nothing here to invite -- an empty trash is just empty, so no action
     button. --%>
<c:if test="${empty trashedEntries}">
    <div class="empty-state">
        <p class="empty-state-title"><spring:message code="trash.none.title"/></p>
        <p class="empty-state-body"><spring:message code="trash.none.body"/></p>
    </div>
</c:if>

</form>
</div>

<script>
    // Delete forever and Empty trash are the only two irreversible actions on
    // this screen, so both confirm before submitting; Restore is not
    // destructive (the entry just goes back to the trash if that turns out to
    // be wrong) and needs none. Following the confirm(spring:message) idiom
    // used on MediaFileView.jsp/Members.jsp rather than a modal: neither
    // confirmation needs to report anything a plain sentence cannot say.
    function confirmDeleteForever() {
        return confirm("<spring:message code="trash.deleteForeverConfirm"/>");
    }

    function confirmEmptyTrash() {
        return confirm("<spring:message code="trash.emptyConfirm" arguments="${fn:length(trashedEntries)}"/>");
    }
</script>
