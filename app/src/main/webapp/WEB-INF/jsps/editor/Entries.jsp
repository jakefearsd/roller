<!--
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
-->
<%@ include file="/WEB-INF/jsps/taglibs-spring.jsp" %>

<p class="subtitle">
    <spring:message code="weblogEntryQuery.subtitle" arguments="${actionWeblog.handle}"/>
</p>
<p class="pagetip">
    <spring:message code="weblogEntryQuery.tip"/>
</p>


<%-- ============================================================= --%>
<%-- Next / previous links --%>

<nav>
    <div class="d-flex justify-content-between">
        <c:if test="${pager.prevLink != null}">
            <a href='${pager.prevLink}' class="btn btn-outline-secondary previous">
                <span aria-hidden="true">&larr;</span><spring:message code="pager.newer"/></a>
        </c:if>
        <c:if test="${pager.nextLink != null}">
            <a href='${pager.nextLink}' class="btn btn-outline-secondary next ms-auto"><spring:message code="pager.older"/>
                <span aria-hidden="true">&rarr;</span></a>
        </c:if>
    </div>
</nav>


<%-- ============================================================= --%>
<%-- Entry table--%>

<%-- Legend, table and bulk-action bar are all gated on there being entries
     to show -- an empty weblog must never render a bare table strip above
     the empty-state invitation below. The <form> itself stays unconditional
     (unchanged id/name) because the bulk-delete confirmation modal further
     down references #entriesBulkForm via form="..."; only its contents are
     conditional. --%>
<c:if test="${not empty pager.items}">
<p style="text-align: center">
    <span class="draftEntryBox">&nbsp;&nbsp;&nbsp;&nbsp;</span>
    <spring:message code="weblogEntryQuery.draft"/>&nbsp;&nbsp;
    <span class="pendingEntryBox">&nbsp;&nbsp;&nbsp;&nbsp;</span>
    <spring:message code="weblogEntryQuery.pending"/>&nbsp;&nbsp;
    <span class="scheduledEntryBox">&nbsp;&nbsp;&nbsp;&nbsp;</span>
    <spring:message code="weblogEntryQuery.scheduled"/>&nbsp;&nbsp;
</p>
</c:if>

<%-- One form around the whole table. The row checkboxes, the duplicate
     button and the bulk action bar all post through it, which is why the
     duplicate control is a submit button with its own formaction rather than
     a form of its own: a form nested inside another is not valid HTML and
     browsers drop the inner one. --%>
<%-- entries-list-marker wraps the list region in BOTH states (table or
     empty-state invitation): the browser ITs identify this page by
     "#entries-list-marker, table.rollertable", and gating the table on
     emptiness left an empty weblog's Entries page with neither match. --%>
<div id="entries-list-marker">
<form id="entriesBulkForm" method="post"
      action="${pageContext.request.contextPath}/roller-ui/authoring/entries!bulkPublish.rol">
<input type="hidden" name="weblog" value="${actionWeblog.handle}"/>
<sec:csrfInput/>

<c:if test="${not empty pager.items}">
<table class="rollertable table table-striped" width="100%">

<tr>
    <th class="rollertable" width="3%">
        <input type="checkbox" id="selectAllEntries" class="form-check-input"
               title="<spring:message code="weblogEntryQuery.selectAll"/>"/>
    </th>
    <th class="rollertable" width="3%"> </th>
    <th class="rollertable" width="7%">
        <spring:message code="weblogEntryQuery.pubTime"/>
    </th>
    <th class="rollertable" width="7%">
        <spring:message code="weblogEntryQuery.updateTime"/>
    </th>
    <th class="rollertable">
        <spring:message code="weblogEntryQuery.title"/>
    </th>
    <th class="rollertable" width="15%">
        <spring:message code="weblogEntryQuery.category"/>
    </th>
    <th class="rollertable" width="3%"> </th>
    <th class="rollertable" width="3%"> </th>
</tr>

<c:forEach items="${pager.items}" var="post">
    <c:choose>
    <c:when test="${post.status.name() == 'DRAFT'}">
        <tr class="draftentry">
    </c:when>
    <c:when test="${post.status.name() == 'PENDING'}">
        <tr class="pendingentry">
    </c:when>
    <c:when test="${post.status.name() == 'SCHEDULED'}">
        <tr class="scheduledentry">
    </c:when>
    <c:otherwise>
        <tr>
    </c:otherwise>
    </c:choose>
    <td>
        <input type="checkbox" class="form-check-input entry-select"
               name="selectedEntries" value="${post.id}"
               aria-label="${fn:escapeXml(post.title)}"/>
    </td>

    <td>
        <c:url var="editUrl" value="/roller-ui/authoring/entryEdit.rol">
            <c:param name="weblog" value="${actionWeblog.handle}"/>
            <c:param name="bean.id" value="${post.id}"/>
        </c:url>
        <a href="${editUrl}" aria-label="<spring:message code='generic.edit'/>: ${fn:escapeXml(post.title)}">
            <span class="bi bi-pencil-square" aria-hidden="true"
                  title="<spring:message code="generic.edit"/>">
            </span>
        </a>
    </td>

    <td class="data">
        <c:if test="${post.pubTime != null}">
            <spring:message code="weblogEntryQuery.date.toStringFormat" arguments="${post.pubTime}"/>
        </c:if>
    </td>

    <td class="data">
        <c:if test="${post.updateTime != null}">
            <spring:message code="weblogEntryQuery.date.toStringFormat" arguments="${post.updateTime}"/>
        </c:if>
    </td>
    
    <td>
        <c:choose>
        <c:when test="${post.status.name() == 'PUBLISHED'}">
            <a href='${post.permalink}'>
                <str:truncateNicely upper="80">${post.displayTitle}</str:truncateNicely>
            </a>
        </c:when>
        <c:otherwise>
            <str:truncateNicely upper="80">${post.displayTitle}</str:truncateNicely>
        </c:otherwise>
        </c:choose>
    </td>
    
    <td>
        ${fn:escapeXml(post.category.name)}
    </td>

    <td>
        <%-- A POST, not a link: duplicating writes a new draft, so it needs
             the CSRF token and must not be reachable by a prefetch or a
             crawler following hrefs. The clicked submit button is the only one
             whose name/value is sent, so duplicateId identifies this row
             without a hidden field per row. --%>
        <button type="submit" name="duplicateId" value="${post.id}"
                class="btn btn-link p-0 align-baseline border-0"
                aria-label="<spring:message code='generic.duplicate'/>: ${fn:escapeXml(post.title)}"
                formaction="${pageContext.request.contextPath}/roller-ui/authoring/entries!duplicate.rol">
            <span class="bi bi-files" aria-hidden="true"
                  title="<spring:message code="generic.duplicate"/>">
            </span>
        </button>
    </td>

    <td>
        <%-- id/title ride in data-* attributes, not an interpolated onclick
             string -- fn:escapeXml renders an apostrophe as &#039;, which the
             HTML parser decodes back to ' BEFORE the onclick attribute
             compiles as JavaScript, so an entry titled e.g. "Maiia's trip"
             made this control a permanent SyntaxError. Delegated handler
             below (same convention as MediaFileView.jsp:493). --%>
        <button type="button" class="btn btn-link p-0 align-baseline border-0 entry-delete-btn"
                data-entry-id="${post.id}" data-entry-title="${fn:escapeXml(post.title)}"
                aria-label="<spring:message code='generic.delete'/>: ${fn:escapeXml(post.title)}">
            <span class="bi bi-trash" aria-hidden="true"
                  title="<spring:message code="generic.delete"/>">
            </span>
        </button>
    </td>

    </tr>
</c:forEach>

</table>
</c:if>

<%-- Bulk action bar. Each button carries its own formaction, so the server
     endpoint is chosen by which button was pressed rather than by JavaScript
     rewriting the form's action. Delete is the exception: it opens the
     confirmation modal instead of submitting, because it is the only one of
     the three that cannot be undone. --%>
<c:if test="${not empty pager.items}">
    <div class="d-flex flex-wrap gap-2 align-items-center mb-3" id="entriesBulkActions">
        <button type="submit" class="btn btn-primary"
                formaction="${pageContext.request.contextPath}/roller-ui/authoring/entries!bulkPublish.rol">
            <c:choose>
                <c:when test="${userAnAuthor}">
                    <spring:message code="weblogEntryQuery.bulkPublish"/>
                </c:when>
                <c:otherwise>
                    <spring:message code="weblogEntryQuery.bulkSubmit"/>
                </c:otherwise>
            </c:choose>
        </button>

        <div class="input-group" style="width: 22em">
            <input type="text" name="bulkTag" id="bulkTag" class="form-control"
                   placeholder="<spring:message code="weblogEntryQuery.bulkTagPlaceholder"/>"/>
            <button type="submit" class="btn btn-outline-secondary"
                    formaction="${pageContext.request.contextPath}/roller-ui/authoring/entries!bulkTag.rol">
                <spring:message code="weblogEntryQuery.bulkTagAdd"/>
            </button>
        </div>

        <button type="button" class="btn btn-danger" id="bulkDeleteButton">
            <spring:message code="weblogEntryQuery.bulkDelete"/>
        </button>
    </div>
</c:if>

</form>


<%-- ============================================================= --%>
<%-- Next / previous links --%>

<nav>
    <div class="d-flex justify-content-between">
        <c:if test="${pager.prevLink != null}">
            <a href='${pager.prevLink}' class="btn btn-outline-secondary previous">
                <span aria-hidden="true">&larr;</span> <spring:message code="pager.newer"/></a>
        </c:if>
        <c:if test="${pager.nextLink != null}">
            <a href='${pager.nextLink}' class="btn btn-outline-secondary next ms-auto"><spring:message code="pager.older"/>
                <span aria-hidden="true">&rarr;</span></a>
        </c:if>
    </div>
</nav>

<c:if test="${empty pager.items}">
    <div class="empty-state">
        <p class="empty-state-title"><spring:message code="empty.entries.title"/></p>
        <p class="empty-state-body"><spring:message code="empty.entries.body"/></p>
        <c:url var="emptyEntriesAddUrl" value="/roller-ui/authoring/entryAdd.rol">
            <c:param name="weblog" value="${actionWeblog.handle}"/>
        </c:url>
        <a href="${emptyEntriesAddUrl}" class="btn btn-primary">
            <spring:message code="empty.entries.action"/>
        </a>
    </div>
</c:if>
</div>


<div id="delete-entry-modal" class="modal delete-entry-modal" tabindex="-1" role="dialog">

    <div class="modal-dialog modal-lg">

        <div class="modal-content">

            <c:set var="deleteAction">entryRemoveViaList!remove</c:set>
            
            <form class="form-stacked" action="${pageContext.request.contextPath}/roller-ui/authoring/${deleteAction}.rol" method="post">
<input type="hidden" name="weblog" value="${actionWeblog.handle}"/>
                <input type="hidden" name="removeId" value="${removeId}" id="removeId"/>

                <div class="modal-header">
                    <div class="modal-title">
                        <h3><spring:message code="weblogEntryRemove.removeWeblogEntry"/></h3>
                        <p><spring:message code="weblogEntryRemove.areYouSure"/></p>
                    </div>
                </div>

                <div class="modal-body">

                    <div class="row mb-3">
                        <span class="col-sm-3 col-form-label">
                            <spring:message code="weblogEntryRemove.entryTitle"/>
                        </span>
                        <div class="col-sm-9">
                            <p class="form-control-plaintext" id="postTitleLabel"></p>
                        </div>
                    </div>

                    <div class="row mb-3">
                        <span class="col-sm-3 col-form-label">
                            <spring:message code="weblogEntryRemove.entryId"/>
                        </span>
                        <div class="col-sm-9">
                            <p class="form-control-plaintext" id="postIdLabel"></p>
                        </div>
                    </div>

                </div>

                <div class="modal-footer">
                    <button type="submit" class="btn"><spring:message code="generic.yes"/></button>
                    <button type="button" class="btn btn-secondary" data-bs-dismiss="modal">
                        <spring:message code="generic.no"/>
                    </button>
                </div>

            <sec:csrfInput/>
</form>
            
        </div>

    </div> 
    
</div>

<%-- Confirmation for the bulk delete. A modal rather than window.confirm:
     the native dialog blocks the page for automated tests and cannot say
     how many entries are about to go. --%>
<div id="bulk-delete-modal" class="modal" tabindex="-1" role="dialog">
    <div class="modal-dialog">
        <div class="modal-content">
            <div class="modal-header">
                <div class="modal-title">
                    <h3><spring:message code="weblogEntryQuery.bulkDeleteConfirm"/></h3>
                    <p><spring:message code="weblogEntryQuery.bulkDeleteWarning"/></p>
                </div>
            </div>
            <div class="modal-body">
                <p id="bulkDeleteCount" class="form-control-plaintext"></p>
            </div>
            <div class="modal-footer">
                <%-- Submits the table's form, which is where the selection
                     lives; this button is outside it, hence the form= --%>
                <button type="submit" class="btn btn-danger" id="bulkDeleteConfirm"
                        form="entriesBulkForm"
                        formaction="${pageContext.request.contextPath}/roller-ui/authoring/entries!bulkDelete.rol">
                    <spring:message code="generic.yes"/>
                </button>
                <button type="button" class="btn btn-secondary" data-bs-dismiss="modal">
                    <spring:message code="generic.no"/>
                </button>
            </div>
        </div>
    </div>
</div>

<script>
    function showDeleteModal( postId, postTitle ) {
        $('#postIdLabel').html(postId);
        $('#postTitleLabel').text(postTitle);
        $('#removeId').val(postId);
        bootstrap.Modal.getOrCreateInstance(document.getElementById('delete-entry-modal')).show();
    }

    <%-- Delegated on the table body: a row's id/title ride in data-*
         attributes (see the comment above the button), never in an inline
         onclick string. --%>
    $(document).on('click', '.entry-delete-btn', function () {
        showDeleteModal(this.dataset.entryId, this.dataset.entryTitle);
    });

    $(function () {
        var selection = function () {
            return $(".entry-select:checked");
        };

        $("#selectAllEntries").on('change', function () {
            $(".entry-select").prop('checked', this.checked);
        });

        // A row unchecked by hand must not leave the header claiming all are
        // selected, which is the state that gets someone to delete more than
        // they meant to.
        $(".entry-select").on('change', function () {
            $("#selectAllEntries").prop('checked',
                    selection().length === $(".entry-select").length);
        });

        $("#bulkDeleteButton").on('click', function () {
            $("#bulkDeleteCount").text(
                    selection().length + " " + "<spring:message code="weblogEntryQuery.selectedCount"/>");
            bootstrap.Modal.getOrCreateInstance(document.getElementById('bulk-delete-modal')).show();
        });
    });
</script>
