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
                <span aria-hidden="true">&larr;</span>Newer</a>
        </c:if>
        <c:if test="${pager.nextLink != null}">
            <a href='${pager.nextLink}' class="btn btn-outline-secondary next ms-auto">Older
                <span aria-hidden="true">&rarr;</span></a>
        </c:if>
    </div>
</nav>


<%-- ============================================================= --%>
<%-- Entry table--%>

<p style="text-align: center">
    <span class="draftEntryBox">&nbsp;&nbsp;&nbsp;&nbsp;</span> 
    <spring:message code="weblogEntryQuery.draft"/>&nbsp;&nbsp;
    <span class="pendingEntryBox">&nbsp;&nbsp;&nbsp;&nbsp;</span>
    <spring:message code="weblogEntryQuery.pending"/>&nbsp;&nbsp;
    <span class="scheduledEntryBox">&nbsp;&nbsp;&nbsp;&nbsp;</span>
    <spring:message code="weblogEntryQuery.scheduled"/>&nbsp;&nbsp;
</p>

<%-- One form around the whole table. The row checkboxes, the duplicate
     button and the bulk action bar all post through it, which is why the
     duplicate control is a submit button with its own formaction rather than
     a form of its own: a form nested inside another is not valid HTML and
     browsers drop the inner one. --%>
<form id="entriesBulkForm" method="post"
      action="${pageContext.request.contextPath}/roller-ui/authoring/entries!bulkPublish.rol">
<input type="hidden" name="weblog" value="${actionWeblog.handle}"/>
<sec:csrfInput/>

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
    <%-- <td> with style if comment is spam or pending --%>
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
               name="selectedEntries" value="${post.id}"/>
    </td>

    <td>
        <c:url var="editUrl" value="/roller-ui/authoring/entryEdit.rol">
            <c:param name="weblog" value="${actionWeblog.handle}"/>
            <c:param name="bean.id" value="${post.id}"/>
        </c:url>
        <a href="${editUrl}">
            <span class="bi bi-pencil-square"
                  title="<spring:message code="generic.edit"/>">
            </span>
        </a>
    </td>

    <td>
        <c:if test="${post.pubTime != null}">
            <spring:message code="weblogEntryQuery.date.toStringFormat" arguments="${post.pubTime}"/>
        </c:if>
    </td>
    
    <td>
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
        ${post.category.name}
    </td>

    <td>
        <%-- A POST, not a link: duplicating writes a new draft, so it needs
             the CSRF token and must not be reachable by a prefetch or a
             crawler following hrefs. The clicked submit button is the only one
             whose name/value is sent, so duplicateId identifies this row
             without a hidden field per row. --%>
        <button type="submit" name="duplicateId" value="${post.id}"
                class="btn btn-link p-0 align-baseline border-0"
                formaction="${pageContext.request.contextPath}/roller-ui/authoring/entries!duplicate.rol">
            <span class="bi bi-files"
                  title="<spring:message code="generic.duplicate"/>">
            </span>
        </button>
    </td>

    <td>
        <c:set var="postId" value="${post.id}"/>
        <c:set var="postTitle" value="${post.title}"/>
        <a href="#"
            onclick="showDeleteModal('${postId}', '${postTitle}' )">
            <span class="bi bi-trash"
                  title="<spring:message code="generic.delete"/>">
            </span>
        </a>
    </td>

    </tr>
</c:forEach>

</table>

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
                <span aria-hidden="true">&larr;</span> Older</a>
        </c:if>
        <c:if test="${pager.nextLink != null}">
            <a href='${pager.nextLink}' class="btn btn-outline-secondary next ms-auto">Newer
                <span aria-hidden="true">&rarr;</span></a>
        </c:if>
    </div>
</nav>

<c:if test="${empty pager.items}">
    <spring:message code="weblogEntryQuery.noneFound"/>
</c:if>


<div id="delete-entry-modal" class="modal fade delete-entry-modal" tabindex="-1" role="dialog">

    <div class="modal-dialog modal-lg">

        <div class="modal-content">

            <c:set var="deleteAction">entryRemoveViaList!remove</c:set>
            
            <form action="${pageContext.request.contextPath}/roller-ui/authoring/${deleteAction}.rol" method="post">
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
                        <label class="col-sm-3 col-form-label">
                            <spring:message code="weblogEntryRemove.entryTitle"/>
                        </label>
                        <div class="col-sm-9">
                            <p class="form-control-plaintext" style="padding-top:0px" id="postTitleLabel"></p>
                        </div>
                    </div>

                    <div class="row mb-3">
                        <label class="col-sm-3 col-form-label">
                            <spring:message code="weblogEntryRemove.entryId"/>
                        </label>
                        <div class="col-sm-9">
                            <p class="form-control-plaintext" style="padding-top:0px" id="postIdLabel"></p>
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
<div id="bulk-delete-modal" class="modal fade" tabindex="-1" role="dialog">
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
        $('#postTitleLabel').html(postTitle);
        $('#removeId').val(postId);
        bootstrap.Modal.getOrCreateInstance(document.getElementById('delete-entry-modal')).show();
    }

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
