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


<%-- ================================================================== --%>
<%-- add/edit custom planet group form --%>


<%-- title for default planet group --%>
<c:if test="${groupHandle == 'all'}">
    <p class="subtitle"><spring:message code="planetGroupSubs.default.subtitle" /></p>
    <p><spring:message code="planetGroupSubs.default.desc" /></p>
</c:if>

<%-- title for a custom planet group --%>
<c:if test="${groupHandle != 'all'}">
    <c:if test="${createNew}">
        <p class="subtitle">
            <spring:message code="planetGroupSubs.custom.subtitle.new" />
        </p>
    </c:if>
    <c:if test="${!createNew}">
        <p class="subtitle">
            <spring:message code="planetGroupSubs.custom.subtitle" arguments="${groupHandle}" />
        </p>
    </c:if>
    <p><spring:message code="planetGroupSubs.custom.desc" /></p>
</c:if>


<%-- only show edit form for custom group --%>
<c:if test="${groupHandle != 'all'}">

    <div class="panel panel-default">
        <div class="panel-heading">
            <p><spring:message code="planetGroupSubs.properties"/></p>
        </div>
        <div class="panel-body">
            <c:if test="${createNew}">
                <spring:message code="planetGroupSubs.creatingNewGroup" />
            </c:if>
            <c:if test="${!createNew}">
                <spring:message code="planetGroupSubs.editingExistingGroup" />
            </c:if>

            <form method="post" action="<c:url value='/roller-ui/admin/planetGroupSubs!saveGroup.rol'/>"
                  class="form-horizontal" style="margin-top:1em">
                <sec:csrfInput/>
                <input type="hidden" name="group.id" value="${fn:escapeXml(group.id)}"/>

                <div class="form-group">
                    <label class="col-sm-3 control-label"><spring:message code="planetGroups.title"/></label>
                    <div class="col-sm-9 controls">
                        <input type="text" name="group.title" size="40" maxlength="255"
                               value="${fn:escapeXml(group.title)}"
                               onchange="validate()" onkeyup="validate()"
                               class="form-control"/>
                    </div>
                </div>

                <div class="form-group">
                    <label class="col-sm-3 control-label"><spring:message code="planetGroups.handle"/></label>
                    <div class="col-sm-9 controls">
                        <input type="text" name="group.handle" size="40" maxlength="255"
                               value="${fn:escapeXml(group.handle)}"
                               onchange="validate()" onkeyup="validate()"
                               class="form-control"/>
                    </div>
                </div>


                <div class="form-group ">
                    <label class="col-sm-3 control-label"></label>
                    <div class="col-sm-9 controls">
                        <button type="submit" class="btn btn-default"><spring:message code="generic.save"/></button>
                        <c:if test="${createNew}">
                            <input type="button" class="btn"
                                   value='<spring:message code="generic.cancel" />'
                                   onclick="window.location='<c:url value="/roller-ui/admin/planetGroups.rol"/>'"/>
                        </c:if>
                    </div>
                </div>

            </form>

        </div>
    </div>

</c:if>


<%-- ================================================================== --%>
<%-- table of planet group's subscription  --%>

<c:if test="${!createNew}">

    <h3><spring:message code="planetGroupSubs.subscriptions"/></h3>
    <spring:message code="planetGroupSubs.subscriptionDesc" />

    <c:if test="${empty subscriptions}">
        <c:if test="${groupHandle == 'all'}">
            <spring:message code="planetGroupSubs.noneDefinedDefault" />
        </c:if>
        <c:if test="${groupHandle != 'all'}">
            <spring:message code="planetGroupSubs.noneDefinedCustom" />
        </c:if>
    </c:if>
    <c:if test="${!empty subscriptions}">

        <table class="table">
            <tr>
                <th width="30%"> <spring:message code="planetGroupSubs.column.title"/> </th>
                <th width="55%"> <spring:message code="planetGroupSubs.column.feedUrl"/> </th>
                <th width="15%"> <spring:message code="generic.delete"/> </th>
            </tr>

            <c:forEach var="sub" items="${subscriptions}">
                <tr>
                    <td class="rollertable">${fn:escapeXml(sub.title)}</td>
                    <td><c:set var="feedURL" value="${sub.feedURL}"/> ${fn:substring(feedURL, 0, 100)} </td>
                    <td>
                        <a href="javascript: void(0);" onclick="confirmDelete('${fn:escapeXml(feedURL)}')">
                            <span class="glyphicon glyphicon-remove" aria-hidden="true"> </span>
                            <spring:message code="generic.delete"/>
                        </a>
                    </td>
                </tr>
            </c:forEach>
        </table>

        <%-- planet subscription delete logic --%>

        <form method="post" action="<c:url value='/roller-ui/admin/planetGroupSubs!deleteSubscription.rol'/>" id="deleteForm">
            <sec:csrfInput/>
            <input type="hidden" name="group.handle" value="${fn:escapeXml(group.handle)}"/>
            <input type="hidden" name="subUrl"/>
        </form>

    </c:if>

</c:if>


<%-- ================================================================== --%>

<script>

    function confirmDelete(subUrl) {
        if (window.confirm('<spring:message code="planetGroupSubs.delete.confirm" />')) {
            var form = $("#deleteForm");
            form.find('input[name="subUrl"]').val(subUrl);
            form.find('input[name="groupHandle"]').val('${fn:escapeXml(groupHandle)}');
            form.submit();
        }
    }

</script>
