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

<form action="${pageContext.request.contextPath}/roller-ui/authoring/entries.rol" method="get" class="form-vertical">
<input type="hidden" name="weblog" value="${actionWeblog.handle}"/>

    <%-- ========================================================= --%>
    <%-- filter by category --%>

    <label for="entries_bean_categoryName"><spring:message code="weblogEntryQuery.label.category"/></label>
    <select id="entries_bean_categoryName" name="bean.categoryName" class="form-select" size="1">
<option value="" ${empty bean.categoryName ? 'selected' : ''}><spring:message code="weblogEntryQuery.label.anyCategory"/></option>
<c:forEach items="${categories}" var="opt">
<option value="${opt.name}" ${opt.name == bean.categoryName ? 'selected' : ''}>${opt.name}</option>
</c:forEach>
</select>

    <%-- ========================================================= --%>
    <%-- filter by tag --%>

    <label for="entries_bean_tagsAsString"><spring:message code="weblogEntryQuery.label.tags"/></label>
    <input type="text" id="entries_bean_tagsAsString" name="bean.tagsAsString" value="${fn:escapeXml(bean.tagsAsString)}" size="14" class="form-control"/>

    <%-- ========================================================= --%>
    <%-- filter by text --%>

    <label for="entries_bean_text"><spring:message code="weblogEntryQuery.label.text"/></label>
    <input type="text" id="entries_bean_text" name="bean.text" value="${fn:escapeXml(bean.text)}" size="14" class="form-control"/>

    <%-- ========================================================= --%>
    <%-- filter by date --%>

    <div class="mb-3">
        <label for="entries_bean_startDateString" class="form-label">
            <spring:message code="weblogEntryQuery.label.startDate"/>
        </label>
        <div class="input-group">

            <input type="text" id="entries_bean_startDateString" name="bean.startDateString" value="${bean.startDateString}" placeholder="MM/DD/YY" readonly class="date-picker form-control"/>
            <label for="entries_bean_startDateString" class="input-group-text">
                <span class="bi bi-calendar"></span>
            </label>

        </div>
    </div>

    <div class="mb-3">
        <label for="entries_bean_endDateString" class="form-label">
            <spring:message code="weblogEntryQuery.label.endDate"/>
        </label>
        <div class="input-group">

            <input type="text" id="entries_bean_endDateString" name="bean.endDateString" value="${bean.endDateString}" placeholder="MM/DD/YY" readonly class="date-picker form-control"/>
            <label for="entries_bean_endDateString" class="input-group-text">
                <span class="bi bi-calendar"></span>
            </label>

        </div>
    </div>

    <br/>

    <%-- ========================================================= --%>
    <%-- filter by status --%>

    <c:forEach items="${statusOptions}" var="opt">
<div class="form-check"><label class="form-check-label"><input type="radio" class="form-check-input" name="bean.status" value="${opt.key}" ${opt.key == bean.status ? 'checked' : ''}/> ${opt.value}</label></div>
</c:forEach>

    <%-- ========================================================= --%>
    <%-- sort by --%>

    <c:forEach items="${sortByOptions}" var="opt">
<div class="form-check"><label class="form-check-label"><input type="radio" class="form-check-input" name="bean.sortBy" value="${opt.key}" ${opt.key == bean.sortBy ? 'checked' : ''}/> ${opt.value}</label></div>
</c:forEach>

    
    <%-- ========================================================= --%>
    <%-- filter button --%>

    <button type="submit" class="btn"><spring:message code="weblogEntryQuery.button.query"/></button>

</form>

<script>

    $(document).ready(function () {
        // 'mm/dd/y' matches EntriesBean's strict MM/dd/yy parse -- jQuery UI's
        // two-digit year token is lowercase 'y', not 'yy' (four-digit).
        $("#entries_bean_startDateString").datepicker({dateFormat: 'mm/dd/y'});
        $("#entries_bean_endDateString").datepicker({dateFormat: 'mm/dd/y'});
    });

</script>

