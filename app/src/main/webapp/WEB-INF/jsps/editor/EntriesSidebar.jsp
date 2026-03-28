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

<h3><spring:message code="weblogEntryQuery.sidebarTitle"/></h3>
<hr size="1" noshade="noshade"/>

<p><spring:message code="weblogEntryQuery.sidebarDescription"/></p>

<form action="${pageContext.request.contextPath}/roller-ui/authoring/entries.rol" method="post" class="form-vertical">
<input type="hidden" name="weblog" value="${actionWeblog.handle}"/>

    <%-- ========================================================= --%>
    <%-- filter by category --%>

    <label><spring:message code="weblogEntryQuery.label.category"/></label>
    <select name="bean.categoryName" class="form-control" size="1">
<c:forEach items="${categories}" var="opt">
<option value="${opt.name}" ${opt.name == bean.categoryName ? 'selected' : ''}>${opt.name}</option>
</c:forEach>
</select>

    <%-- ========================================================= --%>
    <%-- filter by tag --%>

    <label><spring:message code="weblogEntryQuery.label.tags"/></label>
    <input type="text" name="bean.tagsAsString" value="${bean.tagsAsString}" size="14" class="form-control"/>

    <%-- ========================================================= --%>
    <%-- filter by text --%>

    <label><spring:message code="weblogEntryQuery.label.text"/></label>
    <input type="text" name="bean.text" value="${bean.text}" size="14" class="form-control"/>

    <%-- ========================================================= --%>
    <%-- filter by date --%>

    <div class="control-group">
        <label for="bean.startDateString" class="control-label">
            <spring:message code="weblogEntryQuery.label.startDate"/>
        </label>
        <div class="controls">
            <div class="input-group">

                <input type="text" name="bean.startDateString" value="${bean.startDateString}" readonly class="date-picker form-control"/>
                <label for="bean.startDateString" class="input-group-addon btn">
                    <span class="glyphicon glyphicon-calendar"></span>
                </label>

            </div>
        </div>
    </div>

    <div class="control-group">
        <label for="bean.endDateString" class="control-label">
            <spring:message code="weblogEntryQuery.label.endDate"/>
        </label>
        <div class="controls">
            <div class="input-group">

                <input type="text" name="bean.endDateString" value="${bean.endDateString}" readonly class="date-picker form-control"/>
                <label for="bean.endDateString" class="input-group-addon btn">
                    <span class="glyphicon glyphicon-calendar"></span>
                </label>

            </div>
        </div>
    </div>

    <br/>

    <%-- ========================================================= --%>
    <%-- filter by status --%>

    <c:forEach items="${statusOptions}" var="opt">
<label><input type="radio" name="bean.status" value="${opt.key}" ${opt.key == bean.status ? 'checked' : ''}/> ${opt.value}</label>
</c:forEach>

    <%-- ========================================================= --%>
    <%-- sort by --%>

    <c:forEach items="${sortByOptions}" var="opt">
<label><input type="radio" name="bean.sortBy" value="${opt.key}" ${opt.key == bean.sortBy ? 'checked' : ''}/> ${opt.value}</label>
</c:forEach>

    
    <%-- ========================================================= --%>
    <%-- filter button --%>

    <button type="submit" class="btn"><spring:message code="weblogEntryQuery.button.query"/></button>

<sec:csrfInput/>
</form>

<script>

    $(document).ready(function () {
        $("#entries_bean_startDateString").datepicker();
        $("#entries_bean_endDateString").datepicker();
    });

</script>

