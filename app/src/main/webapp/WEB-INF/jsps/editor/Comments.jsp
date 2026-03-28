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
    <spring:message code="commentManagement.noCommentsFound"/>
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

    <%-- ============================================================= --%>
    <%-- Comment table / form with checkboxes --%>

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
        <input type="hidden" name="bean.ids" value="${fn:escapeXml(bean.ids)}"/>
        <input type="hidden" name="bean.startDateString" value="${fn:escapeXml(bean.startDateString)}"/>
        <input type="hidden" name="bean.endDateString" value="${fn:escapeXml(bean.endDateString)}"/>
        <c:if test="${actionName == 'comments'}">
            <input type="hidden" name="bean.entryId" value="${fn:escapeXml(bean.entryId)}"/>
            <input type="hidden" name="bean.searchString" value="${fn:escapeXml(bean.searchString)}"/>
            <input type="hidden" name="bean.approvedString" value="${fn:escapeXml(bean.approvedString)}"/>
            <input type="hidden" name="weblog" value="${fn:escapeXml(param.weblog)}"/>
        </c:if>
        <c:if test="${actionName != 'comments'}">
            <input type="hidden" name="bean.offset" value="${fn:escapeXml(bean.offset)}"/>
            <input type="hidden" name="bean.count" value="${fn:escapeXml(bean.count)}"/>
            <input type="hidden" name="bean.pendingString" value="${fn:escapeXml(bean.pendingString)}"/>
        </c:if>


        <%-- ============================================================= --%>
        <%-- Number of comments and date message --%>

        <div class="tablenav">

            <div style="float:left;">
                <spring:message code="commentManagement.nowShowing" arguments="${fn:length(pager.items)}"/>
            </div>
            <div style="float:right;">
                <c:if test="${firstComment.postTime != null}">
                    <fmt:formatDate value="${firstComment.postTime}" type="both" dateStyle="short" timeStyle="short"/>
                </c:if>
                ---
                <c:if test="${lastComment.postTime != null}">
                    <fmt:formatDate value="${lastComment.postTime}" type="both" dateStyle="short" timeStyle="short"/>
                </c:if>
            </div>
            <br/>


                <%-- ============================================================= --%>
                <%-- Next / previous links --%>

            <nav>
                <ul class="pager">
                    <c:if test="${pager.prevLink != null}">
                        <li class="previous">
                            <a href='${pager.prevLink}'>
                                <span aria-hidden="true">&larr;</span>Newer</a>
                        </li>
                    </c:if>
                    <c:if test="${pager.nextLink != null}">
                        <li class="next">
                            <a href='${pager.nextLink}'>Older
                                <span aria-hidden="true">&rarr;</span></a>
                        </li>
                    </c:if>
                </ul>
            </nav>

        </div> <%-- class="tablenav" --%>


        <%-- ============================================================= --%>
        <%-- Bulk comment delete link --%>
        <%-- ============================================================= --%>

        <c:if test="${bulkDeleteCount > 0}">
            <p>
                <spring:message code="commentManagement.bulkDeletePrompt1" arguments="${bulkDeleteCount}"/>
                <a href="#" onclick="bulkDelete()">
                    <spring:message code="commentManagement.bulkDeletePrompt2"/>
                </a>
            </p>
        </c:if>

        <table class="rollertable table table-striped" width="100%">

                <%-- ======================================================== --%>
                <%-- Comment table header --%>

            <tr>
                <c:if test="${actionName == 'comments'}">
                    <th class="rollertable" width="5%">
                        <spring:message code="commentManagement.columnApproved"/>
                    </th>
                </c:if>
                <th class="rollertable" width="5%">
                    <spring:message code="commentManagement.columnSpam"/>
                </th>
                <th class="rollertable" width="5%">
                    <spring:message code="generic.delete"/>
                </th>
                <th class="rollertable">
                    <spring:message code="commentManagement.columnComment"/>
                </th>
            </tr>

                <%-- ======================================================== --%>
                <%-- Select ALL and NONE buttons --%>

            <tr class="actionrow">
                <c:if test="${actionName == 'comments'}">
                    <td align="center">
                        <spring:message code="commentManagement.select"/><br/>

                        <span id="checkallapproved"><a href="#"><spring:message code="generic.all"/></a></span><br/>
                        <span id="clearallapproved"><a href="#"><spring:message code="generic.none"/></a></span>
                    </td>
                </c:if>
                <td align="center">
                    <spring:message code="commentManagement.select"/><br/>

                    <span id="checkallspam"><a href="#"><spring:message code="generic.all"/></a></span><br/>
                    <span id="clearallspam"><a href="#"><spring:message code="generic.none"/></a></span>
                </td>
                <td align="center">
                    <spring:message code="commentManagement.select"/><br/>

                    <span id="checkalldelete"><a href="#"><spring:message code="generic.all"/></a></span><br/>
                    <span id="clearalldelete"><a href="#"><spring:message code="generic.none"/></a></span>
                </td>
                <td align="right">
                    <br/>
                    <span class="pendingCommentBox">&nbsp;&nbsp;&nbsp;&nbsp;</span>
                    <spring:message code="commentManagement.pending"/>&nbsp;&nbsp;
                    <span class="spamCommentBox">&nbsp;&nbsp;&nbsp;&nbsp;</span>
                    <spring:message code="commentManagement.spam"/>&nbsp;&nbsp;
                </td>
            </tr>


                <%-- ========================================================= --%>
                <%-- Loop through comments --%>

            <c:forEach var="comment" items="${pager.items}" varStatus="rowstatus">
                <tr>
                    <c:if test="${actionName == 'comments'}">
                        <%-- only blog admins (not the global admin) can approve blog comments --%>
                        <td>
                            <c:set var="approvedChecked" value=""/>
                            <c:forEach var="ac" items="${bean.approvedComments}">
                                <c:if test="${ac == comment.id}"><c:set var="approvedChecked" value="checked='checked'"/></c:if>
                            </c:forEach>
                            <input type="checkbox" name="bean.approvedComments" class="comment-select"
                                   value="${fn:escapeXml(comment.id)}" ${approvedChecked}/>
                        </td>
                    </c:if>
                    <td>
                        <c:set var="spamChecked" value=""/>
                        <c:forEach var="sc" items="${bean.spamComments}">
                            <c:if test="${sc == comment.id}"><c:set var="spamChecked" value="checked='checked'"/></c:if>
                        </c:forEach>
                        <input type="checkbox" name="bean.spamComments" class="comment-select"
                               value="${fn:escapeXml(comment.id)}" ${spamChecked}/>
                    </td>
                    <td>
                        <c:set var="deleteChecked" value=""/>
                        <c:forEach var="dc" items="${bean.deleteComments}">
                            <c:if test="${dc == comment.id}"><c:set var="deleteChecked" value="checked='checked'"/></c:if>
                        </c:forEach>
                        <input type="checkbox" name="bean.deleteComments" class="comment-select"
                               value="${fn:escapeXml(comment.id)}" ${deleteChecked}/>
                    </td>

                        <%-- ======================================================== --%>
                        <%-- Display comment details and text --%>

                        <%-- <td> with style if comment is spam or pending --%>
                    <c:choose>
                        <c:when test="${comment.status == 'SPAM'}">
                    <td class="spamcomment">
                        </c:when>
                        <c:when test="${comment.status == 'PENDING'}">
                    <td class="pendingcomment">
                        </c:when>
                        <c:otherwise>
                    <td>
                        </c:otherwise>
                    </c:choose>

                            <%-- comment details table in table --%>
                        <table class="innertable">
                            <tr>
                                <td class="viewbody">

                                    <div class="viewdetails bot">

                                        <div class="details">
                                            <spring:message code="commentManagement.entryTitled"/>&nbsp;:&nbsp;
                                            <a href='${comment.weblogEntry.permalink}'>
                                                ${fn:escapeXml(comment.weblogEntry.title)}</a>
                                        </div>

                                        <div class="details">
                                            <spring:message code="commentManagement.commentBy"/>&nbsp;:&nbsp;
                                            <c:choose>
                                                <c:when test="${comment.email != null && comment.name != null}">
                                                    <spring:message code="commentManagement.commentByBoth"
                                                        arguments="${fn:escapeXml(comment.name)},${fn:escapeXml(comment.email)},${fn:escapeXml(comment.email)},${fn:escapeXml(comment.remoteHost)}"
                                                        argumentSeparator=","/>
                                                </c:when>
                                                <c:when test="${comment.email == null && comment.name == null}">
                                                    <spring:message code="commentManagement.commentByIP"
                                                        arguments="${fn:escapeXml(comment.remoteHost)}"/>
                                                </c:when>
                                                <c:otherwise>
                                                    <spring:message code="commentManagement.commentByName"
                                                        arguments="${fn:escapeXml(comment.name)},${fn:escapeXml(comment.remoteHost)}"
                                                        argumentSeparator=","/>
                                                </c:otherwise>
                                            </c:choose>
                                        </div>

                                        <c:if test="${not empty comment.url}">
                                            <div class="details">
                                                <spring:message code="commentManagement.commentByURL"/>&nbsp;:&nbsp;
                                                <a href='${fn:escapeXml(comment.url)}'>
                                                    <c:choose>
                                                        <c:when test="${fn:length(comment.url) > 60}">
                                                            ${fn:escapeXml(fn:substring(comment.url, 0, 60))}...
                                                        </c:when>
                                                        <c:otherwise>
                                                            ${fn:escapeXml(comment.url)}
                                                        </c:otherwise>
                                                    </c:choose>
                                                </a>
                                            </div>
                                        </c:if>

                                        <div class="details">
                                            <spring:message code="commentManagement.postTime"/>&nbsp;:&nbsp;
                                            <fmt:formatDate value="${comment.postTime}" type="both" dateStyle="short" timeStyle="short"/>
                                        </div>

                                    </div>
                                    <div class="viewdetails bot">

                                        <div class="details bot">

                                            <c:choose>
                                                <c:when test="${fn:length(comment.content) > 1000}">
                                                    <div class="bot" id="comment-${comment.id}">
                                                        ${fn:escapeXml(fn:substring(comment.content, 0, 1000))}...
                                                    </div>
                                                    <div id="link-${comment.id}">
                                                        <a onclick='readMoreComment("${comment.id}")'><spring:message
                                                                code="commentManagement.readmore"/></a>
                                                    </div>
                                                </c:when>
                                                <c:otherwise>
                                                    <span width="200px"
                                                          id="comment-${comment.id}">${fn:escapeXml(comment.content)}</span>
                                                </c:otherwise>
                                            </c:choose>
                                        </div>

                                        <c:if test="${actionName == 'comments'}">
                                            <div class="details">
                                                <a id="editlink-${comment.id}"
                                                   onclick='editComment("${comment.id}")'>
                                                    <spring:message code="generic.edit"/>
                                                </a>
                                            </div>
                                            <div class="details">
                                              <span id="savelink-${comment.id}"
                                                    style="display: none">
                                                   <a onclick='saveComment("${comment.id}")'><spring:message
                                                           code="generic.save"/></a> &nbsp;|&nbsp;
                                              </span>
                                                <span id="cancellink-${comment.id}"
                                                      style="display: none">
                                                   <a onclick='editCommentCancel("${comment.id}")'><spring:message code="generic.cancel"/></a>
                                              </span>
                                            </div>
                                        </c:if>

                                    </div>
                            </tr>
                        </table>
                            <%-- end comment details table in table --%>
                    </td>
                </tr>
            </c:forEach>
        </table>

        <%-- ============================================================= --%>
        <%-- Next / previous links --%>

        <nav>
            <ul class="pager">
                <c:if test="${pager.prevLink != null}">
                    <li class="previous">
                        <a href='${pager.prevLink}'>
                            <span aria-hidden="true">&larr;</span>Newer</a>
                    </li>
                </c:if>
                <c:if test="${pager.nextLink != null}">
                    <li class="next">
                        <a href='${pager.nextLink}'>Older
                            <span aria-hidden="true">&rarr;</span></a>
                    </li>
                </c:if>
            </ul>
        </nav>

        <%-- ========================================================= --%>
        <%-- Save changes and cancel buttons --%>

        <hr size="1" noshade="noshade"/>
        <spring:message code="commentManagement.update" var="updateLabel"/>
        <input type="submit" class="btn btn-primary" value="${updateLabel}"/>

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
        $('#checkallspam').click(function () {
            toggleFunction(true, "bean.spamComments");
        });
        $('#clearallspam').click(function () {
            toggleFunction(false, "bean.spamComments");
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

        // save the original comment value
        comments[id] = $("#comment-" + id).html();

        $("#editlink-" + id).hide();
        $("#savelink-" + id).show();
        $("#cancellink-" + id).show();

        // put comment in a textarea for editing
        $("#comment-" + id).html("<textarea style='width:100%' rows='10'>" + comments[id] + "</textarea>");
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
            $("#comment-" + id).html(comments[id]);
            comments[id] = null;
        }
    }

    function readMoreComment(id, callback) {
        $.ajax({
            type: "GET",
            url: '<%= request.getContextPath()%>/roller-ui/authoring/commentdata?id=' + id,
            success: function (data) {
                var cdata = eval("(" + data + ")");
                $("#comment-" + cdata.id).html(cdata.content);
                $("#link-" + id).detach();
                if (callback) callback(id);
            }
        });
    }

</script>
