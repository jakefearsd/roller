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

<c:choose>
    <c:when test="${actionName == 'comments'}">
        <c:set var="mainAction" value="comments"/>
        <c:url var="queryUrl" value="/roller-ui/authoring/comments!query.rol"/>
    </c:when>
    <c:otherwise>
        <c:set var="mainAction" value="globalCommentManagement"/>
        <c:url var="queryUrl" value="/roller-ui/admin/globalCommentManagement!query.rol"/>
    </c:otherwise>
</c:choose>

<h3><spring:message code="commentManagement.sidebarTitle"/></h3>
<hr size="1" noshade="noshade"/>

<p><spring:message code="commentManagement.sidebarDescription"/></p>

<form method="get" action="${queryUrl}" id="commentsQuery" class="form-vertical">
    <c:if test="${actionName == 'comments'}">
        <input type="hidden" name="weblog" value="${fn:escapeXml(param.weblog)}"/>
    </c:if>

    <%-- ========================================================= --%>
    <%-- filter by search string --%>

    <div class="form-group">
        <label for="bean_searchString"><spring:message code="commentManagement.searchString"/></label>
        <input type="text" name="bean.searchString" id="bean_searchString"
               value="${fn:escapeXml(bean.searchString)}" size="15" class="form-control"/>
    </div>

    <%-- ========================================================= --%>
    <%-- filter by date --%>

    <script>
        $(function () {
            $("#bean_startDateString").datepicker();
        });
    </script>

    <div class="form-group">
        <label for="bean_startDateString">
            <spring:message code="commentManagement.startDate"/>
        </label>
        <div class="input-group">
            <input type="text" name="bean.startDateString" id="bean_startDateString"
                   value="${fn:escapeXml(bean.startDateString)}" readonly="readonly"
                   class="date-picker form-control"/>
            <label for="bean_startDateString" class="input-group-addon btn">
                <span class="glyphicon glyphicon-calendar"></span>
            </label>
        </div>
    </div>

    <script>
        $(function () {
            $("#bean_endDateString").datepicker();
        });
    </script>

    <div class="form-group">
        <label for="bean_endDateString">
            <spring:message code="commentManagement.endDate"/>
        </label>
        <div class="input-group">
            <input type="text" name="bean.endDateString" id="bean_endDateString"
                   value="${fn:escapeXml(bean.endDateString)}" readonly="readonly"
                   class="date-picker form-control"/>
            <label for="bean_endDateString" class="input-group-addon btn">
                <span class="glyphicon glyphicon-calendar"></span>
            </label>
        </div>
    </div>

    <br/>

    <%-- ========================================================= --%>
    <%-- filter by status --%>

    <div class="form-group">
        <label><spring:message code="commentManagement.pendingStatus"/></label>
        <c:forEach var="opt" items="${commentStatusOptions}">
            <div class="radio">
                <label>
                    <input type="radio" name="bean.approvedString" value="${fn:escapeXml(opt.key)}"
                        <c:if test="${bean.approvedString == opt.key}">checked="checked"</c:if>
                    /> ${fn:escapeXml(opt.value)}
                </label>
            </div>
        </c:forEach>
    </div>

    <%-- ========================================================= --%>
    <%-- filter button --%>

    <spring:message code="commentManagement.query" var="queryLabel"/>
    <input type="submit" class="btn btn-default" value="${queryLabel}"/>

</form>
