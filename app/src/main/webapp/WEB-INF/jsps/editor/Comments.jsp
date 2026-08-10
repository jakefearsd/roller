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

<%-- Built to docs/design/tables/comments-moderation.html. One comment is one
     .comment-row on one surface: a metadata line, the entry it is about, then
     the body as prose. It replaces a nested <table class="innertable"> inside
     a <td> and a fake <tr class="actionrow"> of link cells.

     This one JSP serves BOTH /roller-ui/authoring/comments.rol and
     /roller-ui/admin/globalCommentManagement.rol (see RollerViewResolver's
     .Comments and .GlobalCommentManagement definitions) -- there is no
     GlobalCommentManagement.jsp. Everything gated on
     ${actionName == 'comments'} is per-weblog only: GlobalCommentManagementBean
     has no ids and no approvedComments property at all, so emitting either on
     the global screen reads a property that does not exist.

     Two contracts ride on this markup and must not move:
       - Routes.java pins the marker p.subtitle for BOTH routes, because the
         comment list itself renders only when comments exist and none are
         seeded.
       - CommentIT drives input[name='bean.deleteComments'][value],
         input[type='submit'].btn-primary, and reads a comment id off
         span[id^='comment-'] -- so the body text stays inside a <span> whose
         id is comment-<id>, in both the short and the truncated branch. --%>

<%-- are we on a blog's comment management page or the global admin's comment management page? --%>
<c:choose>
    <c:when test="${actionName == 'comments'}">
        <c:set var="mainAction" value="comments"/>
    </c:when>
    <c:otherwise>
        <c:set var="mainAction" value="globalCommentManagement"/>
    </c:otherwise>
</c:choose>


<p class="subtitle">
    <c:choose>
        <c:when test="${actionName == 'comments'}">
            <c:choose>
                <c:when test="${not empty bean.entryId}">
                    <spring:message code="commentManagement.entry.subtitle" arguments="${queryEntry.title}"/>
                </c:when>
                <c:otherwise>
                    <spring:message code="commentManagement.website.subtitle" arguments="${actionWeblog.handle}"/>
                </c:otherwise>
            </c:choose>
        </c:when>
        <c:otherwise>
            <spring:message code="commentManagement.subtitle"/>
        </c:otherwise>
    </c:choose>
</p>

<c:choose>
<c:when test="${empty pager.items}">
    <%-- An invitation, not a shrug: no action button, because there is nothing
         for the owner to do here but wait for a reader. --%>
    <div class="empty-state">
        <p class="empty-state-title"><spring:message code="commentManagement.noCommentsFound"/></p>
        <p class="empty-state-body"><spring:message code="empty.comments.body"/></p>
    </div>
</c:when>
<c:otherwise>
    <p class="pagetip">
        <c:choose>
            <c:when test="${actionName == 'comments'}">
                <spring:message code="commentManagement.tip"/>
            </c:when>
            <c:otherwise>
                <spring:message code="commentManagement.globalTip"/>
            </c:otherwise>
        </c:choose>
    </p>

    <c:choose>
        <c:when test="${actionName == 'comments'}">
            <c:url var="updateUrl" value="/roller-ui/authoring/comments!update.rol"/>
        </c:when>
        <c:otherwise>
            <c:url var="updateUrl" value="/roller-ui/admin/globalCommentManagement!update.rol"/>
        </c:otherwise>
    </c:choose>

    <form method="post" action="${updateUrl}">
        <sec:csrfInput/>
        <input type="hidden" name="bean.startDateString" value="${fn:escapeXml(bean.startDateString)}"/>
        <input type="hidden" name="bean.endDateString" value="${fn:escapeXml(bean.endDateString)}"/>
        <c:if test="${actionName == 'comments'}">
            <%-- bean.ids scopes the approve/disapprove sweep to the rows this
                 page displayed. Only the per-weblog screen has that sweep; the
                 global screen's bean has no ids property at all, so emitting
                 it there would be reading a property that does not exist. --%>
            <input type="hidden" name="bean.ids" value="${fn:escapeXml(bean.ids)}"/>
            <input type="hidden" name="bean.entryId" value="${fn:escapeXml(bean.entryId)}"/>
            <input type="hidden" name="bean.searchString" value="${fn:escapeXml(bean.searchString)}"/>
            <input type="hidden" name="bean.approvedString" value="${fn:escapeXml(bean.approvedString)}"/>
            <input type="hidden" name="weblog" value="${fn:escapeXml(param.weblog)}"/>
        </c:if>
        <c:if test="${actionName != 'comments'}">
            <%-- bean.offset and bean.count used to be carried here. Neither
                 property exists on either comment bean any more -- paging is
                 by bean.page -- so evaluating them threw
                 PropertyNotFoundException and the page 500'd. It only showed
                 once a comment existed, which no test had ever created. --%>
            <input type="hidden" name="bean.pendingString" value="${fn:escapeXml(bean.pendingString)}"/>
        </c:if>


        <%-- ============================================================= --%>
        <%-- Bulk comment delete link --%>

        <c:if test="${bulkDeleteCount > 0}">
            <p class="pagetip">
                <spring:message code="commentManagement.bulkDeletePrompt1" arguments="${bulkDeleteCount}"/>
                <a href="#" onclick="bulkDelete()">
                    <spring:message code="commentManagement.bulkDeletePrompt2"/>
                </a>
            </p>
        </c:if>


        <%-- ============================================================= --%>
        <%-- Selection bar: the bulk controls, above the list.

             There is no separate "approve selected" / "delete selected" verb
             here the way the design card sketches one, because the controller
             does not have those verbs: approval and deletion are two checkbox
             sets (bean.approvedComments / bean.deleteComments) applied by a
             single POST to !update.rol. So the bar carries the all/none
             toggles for each set plus the one submit that applies them.

             Delete wins over approve in CommentsController.update (an id in
             the delete list is skipped by the approval sweep), which is what
             stops a pre-ticked "approved" box from re-saving a comment you
             meant to delete. --%>

        <div class="selection-bar">
            <span><spring:message code="commentManagement.nowShowing" arguments="${fn:length(pager.items)}"/></span>

            <c:if test="${actionName == 'comments'}">
                <%-- only blog admins (not the global admin) can approve blog comments --%>
                <span>
                    <spring:message code="commentManagement.columnApproved"/>:
                    <a href="#" id="checkallapproved"><spring:message code="generic.all"/></a>
                    /
                    <a href="#" id="clearallapproved"><spring:message code="generic.none"/></a>
                </span>
            </c:if>

            <span>
                <spring:message code="generic.delete"/>:
                <a href="#" id="checkalldelete"><spring:message code="generic.all"/></a>
                /
                <a href="#" id="clearalldelete"><spring:message code="generic.none"/></a>
            </span>

            <spring:message code="commentManagement.update" var="updateLabel"/>
            <input type="submit" class="btn btn-primary ms-auto" value="${updateLabel}"/>
        </div>


        <%-- ============================================================= --%>
        <%-- The comments themselves --%>

        <div class="comment-list">
            <c:forEach var="comment" items="${pager.items}">

                <c:set var="deleteChecked" value=""/>
                <c:forEach var="dc" items="${bean.deleteComments}">
                    <c:if test="${dc == comment.id}"><c:set var="deleteChecked" value="checked='checked'"/></c:if>
                </c:forEach>

                <c:choose>
                    <c:when test="${comment.status == 'PENDING'}">
                        <c:set var="rowClass" value="comment-row is-pending"/>
                    </c:when>
                    <c:otherwise>
                        <c:set var="rowClass" value="comment-row"/>
                    </c:otherwise>
                </c:choose>

                <div class="${rowClass}">

                    <%-- The row's own selection box is the delete box: it is
                         the action both screens have, and the one CommentIT
                         drives. Approval, where it exists, is a labelled
                         control down in .comment-actions. --%>
                    <spring:message code="generic.delete" var="deleteLabel"/>
                    <input type="checkbox" class="form-check-input"
                           name="bean.deleteComments"
                           id="delete-${fn:escapeXml(comment.id)}"
                           value="${fn:escapeXml(comment.id)}"
                           title="${deleteLabel}" aria-label="${deleteLabel}" ${deleteChecked}/>

                    <div class="comment-body">

                        <div class="comment-meta">
                            <%-- Plain bold text, never a link: there is no
                                 commenter URL field (dropped in V019) and
                                 nothing verifies the name. --%>
                            <c:choose>
                                <c:when test="${not empty comment.name}">
                                    <c:set var="who" value="${fn:escapeXml(comment.name)}"/>
                                </c:when>
                                <c:otherwise>
                                    <spring:message var="who" code="commentManagement.commentByIP"
                                                    arguments="${fn:escapeXml(comment.remoteHost)}"/>
                                </c:otherwise>
                            </c:choose>
                            <span class="comment-who">${who}</span>

                            <c:if test="${not empty comment.email}"><span class="comment-contact">${fn:escapeXml(comment.email)}</span></c:if>
                            <c:if test="${not empty comment.name and not empty comment.remoteHost}"><span class="comment-contact">${fn:escapeXml(comment.remoteHost)}</span></c:if>

                            <span class="comment-when"><fmt:formatDate value="${comment.postTime}" type="both" dateStyle="short" timeStyle="short"/></span>

                            <c:choose>
                                <c:when test="${comment.status == 'APPROVED'}">
                                    <spring:message var="statusLabel" code="commentManagement.columnApproved"/>
                                    <span class="badge bg-success">${statusLabel}</span>
                                </c:when>
                                <c:when test="${comment.status == 'PENDING'}">
                                    <spring:message var="statusLabel" code="commentManagement.pending"/>
                                    <span class="badge bg-warning">${statusLabel}</span>
                                </c:when>
                                <%-- DISAPPROVED carries no pill: there is no
                                     message key for it, and the absence of an
                                     "Approved" pill beside an unticked approve
                                     box already says it. --%>
                            </c:choose>
                        </div>

                        <div class="comment-on">
                            <spring:message code="commentManagement.entryTitled"/>
                            <a href="${fn:escapeXml(comment.weblogEntry.permalink)}">${fn:escapeXml(comment.weblogEntry.title)}</a>
                        </div>

                        <p class="comment-text">
                            <c:choose>
                                <c:when test="${fn:length(comment.content) > 1000}">
                                    <span id="comment-${comment.id}">${fn:escapeXml(fn:substring(comment.content, 0, 1000))}...</span>
                                    <span id="link-${comment.id}">
                                        <a onclick='readMoreComment("${comment.id}")'><spring:message
                                                code="commentManagement.readmore"/></a>
                                    </span>
                                </c:when>
                                <c:otherwise>
                                    <span id="comment-${comment.id}">${fn:escapeXml(comment.content)}</span>
                                </c:otherwise>
                            </c:choose>
                        </p>

                        <c:if test="${actionName == 'comments'}">
                            <div class="comment-actions">

                                <c:set var="approvedChecked" value=""/>
                                <c:forEach var="ac" items="${bean.approvedComments}">
                                    <c:if test="${ac == comment.id}"><c:set var="approvedChecked" value="checked='checked'"/></c:if>
                                </c:forEach>

                                <div class="form-check">
                                    <input type="checkbox" class="form-check-input"
                                           name="bean.approvedComments"
                                           id="approve-${fn:escapeXml(comment.id)}"
                                           value="${fn:escapeXml(comment.id)}" ${approvedChecked}/>
                                    <label class="form-check-label" for="approve-${fn:escapeXml(comment.id)}">
                                        <spring:message code="commentManagement.columnApproved"/>
                                    </label>
                                </div>

                                <span id="editlink-${comment.id}">
                                    <a onclick='editComment("${comment.id}")'>
                                        <spring:message code="generic.edit"/>
                                    </a>
                                </span>
                                <%-- style="display:none" rather than hidden="hidden": Bootstrap's
                                     reboot ships [hidden]{display:none!important}, which jQuery's
                                     .show() (an inline display, no !important) cannot beat -- the
                                     save/cancel links would never come back. --%>
                                <span id="savelink-${comment.id}" style="display: none">
                                    <a onclick='saveComment("${comment.id}")'><spring:message code="generic.save"/></a>
                                </span>
                                <span id="cancellink-${comment.id}" style="display: none">
                                    <a onclick='editCommentCancel("${comment.id}")'><spring:message code="generic.cancel"/></a>
                                </span>

                            </div>
                        </c:if>

                    </div> <%-- class="comment-body" --%>
                </div> <%-- class="comment-row" --%>
            </c:forEach>
        </div> <%-- class="comment-list" --%>


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

    </form>

</c:otherwise>
</c:choose>


<script>

    <%-- setup check all/none checkbox controls --%>
    <c:if test="${not empty pager.items}">
    $(document).ready(function () {
        $('#checkallapproved').click(function () {
            toggleFunction(true, "bean.approvedComments");
        });
        $('#clearallapproved').click(function () {
            toggleFunction(false, "bean.approvedComments");
        });
        $('#checkalldelete').click(function () {
            toggleFunction(true, "bean.deleteComments");
        });
        $('#clearalldelete').click(function () {
            toggleFunction(false, "bean.deleteComments");
        });
    });
    </c:if>

    <%-- TODO: hook this up; it is currently not working in Roller trunk either --%>

    function bulkDelete() {
        if (window.confirm('<spring:message code="commentManagement.confirmBulkDelete" arguments="${bulkDeleteCount}" javaScriptEscape="true"/>')) {
            document.commentQueryForm.method.value = "bulkDelete";
            document.commentQueryForm.submit();
        }
    }

    var comments = {};

    function editComment(id) {
        // make sure we have the full comment
        if ($("#link-" + id).length > 0) readMoreComment(id, editComment);

        // save the original comment value. .text() reads the decoded
        // characters (never HTML markup), matching how the span was
        // populated server-side via fn:escapeXml.
        comments[id] = $("#comment-" + id).text();

        $("#editlink-" + id).hide();
        $("#savelink-" + id).show();
        $("#cancellink-" + id).show();

        // put comment in a textarea for editing. Built via element+.val(),
        // not string-concatenated markup passed to .html() -- comments[id]
        // is reader-supplied content and must never be parsed as HTML.
        var editArea = $("<textarea>", {rows: 10}).addClass("form-control").val(comments[id]);
        $("#comment-" + id).empty().append(editArea);
    }

    function saveComment(id) {
        var content = $("#comment-" + id).children()[0].value;
        $.ajax({
            type: "POST",
            url: '<%= request.getContextPath()%>/roller-ui/authoring/commentdata?id=' + id,
            data: content,
            dataType: "text",
            processData: "false",
            contentType: "text/plain",
            success: function (rdata) {
                if (status != "success") {
                    var cdata = eval("(" + rdata + ")");
                    $("#editlink-" + id).show();
                    $("#savelink-" + id).hide();
                    $("#cancellink-" + id).hide();
                    // .html() is safe here: CommentDataServlet#doPut runs
                    // Utilities.escapeHTML then StringEscapeUtils.escapeEcmaScript
                    // on c.getContent() before writing the JSON response, so
                    // cdata.content is HTML-entity-escaped text -- .html()
                    // renders it back to readable characters as inert text,
                    // never as live markup.
                    $("#comment-" + id).html(cdata.content);
                } else {
                    alert('<spring:message code="commentManagement.saveError" javaScriptEscape="true"/>');
                }
            }
        });
    }

    function editCommentCancel(id) {
        $("#editlink-" + id).show();
        $("#savelink-" + id).hide();
        $("#cancellink-" + id).hide();
        if (comments[id]) {
            $("#comment-" + id).text(comments[id]);
            comments[id] = null;
        }
    }

    function readMoreComment(id, callback) {
        $.ajax({
            type: "GET",
            url: '<%= request.getContextPath()%>/roller-ui/authoring/commentdata?id=' + id,
            success: function (data) {
                var cdata = eval("(" + data + ")");
                // .html() is safe here: CommentDataServlet#doGet runs the same
                // Utilities.escapeHTML + StringEscapeUtils.escapeEcmaScript
                // pipeline as doPut before writing the response.
                $("#comment-" + cdata.id).html(cdata.content);
                $("#link-" + id).detach();
                if (callback) callback(id);
            }
        });
    }

</script>
