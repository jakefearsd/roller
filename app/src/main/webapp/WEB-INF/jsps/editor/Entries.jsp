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

<table class="rollertable table table-striped" width="100%">

<tr>
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
        <c:url var="editUrl" value="/roller-ui/authoring/entryEdit.rol">
            <c:param name="weblog" value="${actionWeblog.handle}"/>
            <c:param name="bean.id" value="${post.id}"/>
        </c:url>
        <a href="${editUrl}">
            <span class="glyphicon glyphicon-edit" data-toggle="tooltip" data-placement="top"
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
        <c:set var="postId" value="${post.id}"/>
        <c:set var="postTitle" value="${post.title}"/>
        <a href="#"
            onclick="showDeleteModal('${postId}', '${postTitle}' )">
            <span class="glyphicon glyphicon-trash"
                  data-toggle="tooltip" data-placement="top" title="<spring:message code="generic.delete"/>">
            </span>
        </a>
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
                    <span aria-hidden="true">&larr;</span> Older</a>
            </li>
        </c:if>
        <c:if test="${pager.nextLink != null}">
            <li class="next">
                <a href='${pager.nextLink}'>Newer
                    <span aria-hidden="true">&rarr;</span></a>
            </li>
        </c:if>
    </ul>
</nav>

<c:if test="${pager.items.isEmpty}">
    <spring:message code="weblogEntryQuery.noneFound"/>
</c:if>


<div id="delete-entry-modal" class="modal fade delete-entry-modal" tabindex="-1" role="dialog">

    <div class="modal-dialog modal-lg">

        <div class="modal-content">

            <c:set var="deleteAction">entryRemoveViaList!remove</c:set>
            
            <form action="${pageContext.request.contextPath}/roller-ui/authoring/${deleteAction}.rol" method="post" class="form-horizontal">
<input type="hidden" name="weblog" value="${weblog}"/>
                <input type="hidden" name="removeId" value="${removeId}" id="removeId"/>
            
                <div class="modal-header">
                    <div class="modal-title">
                        <h3><spring:message code="weblogEntryRemove.removeWeblogEntry"/></h3>
                        <p><spring:message code="weblogEntryRemove.areYouSure"/></p>
                    </div>
                </div>
                
                <div class="modal-body">

                    <div class="form-group">
                        <label class="col-sm-3 control-label">
                            <spring:message code="weblogEntryRemove.entryTitle"/>
                        </label>
                        <div class="col-sm-9 controls">
                            <p class="form-control-static" style="padding-top:0px" id="postTitleLabel"></p>
                        </div>
                    </div>

                    <div class="form-group">
                        <label class="col-sm-3 control-label">
                            <spring:message code="weblogEntryRemove.entryId"/>
                        </label>
                        <div class="col-sm-9 controls">
                            <p class="form-control-static" style="padding-top:0px" id="postIdLabel"></p>
                        </div>
                    </div>

                </div>
                
                <div class="modal-footer">
                    <button type="submit" class="btn"><spring:message code="generic.yes"/></button>
                    <button type="button" class="btn btn-default btn-primary" data-dismiss="modal">
                        <spring:message code="generic.no"/>
                    </button>
                </div>

            <sec:csrfInput/>
</form>
            
        </div>

    </div> 
    
</div>

<script>
    function showDeleteModal( postId, postTitle ) {
        $('#postIdLabel').html(postId);
        $('#postTitleLabel').html(postTitle);
        $('#removeId').val(postId);
        $('#delete-entry-modal').modal({show: true});
    }
</script>
