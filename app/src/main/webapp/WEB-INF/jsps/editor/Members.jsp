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
        
<script>
// <!--
function confirmMemberRemoval() {
    var radios = document.getElementById("memberPermissionsForm").getElementsByTagName("input");
    var removing = false;
    for (var i=0; i<radios.length; i++) {
        if (radios[i].type === "radio" && radios[i].value === "-1" && radios[i].checked) {
            removing = true;
        }
    }
    if (removing) {
        return confirm("<spring:message code="memberPermissions.confirmRemove"/>");
    }
    return true;
}
// -->
</script>

<p class="subtitle">
    <spring:message code="memberPermissions.subtitle" arguments="${actionWeblog.handle}"/>
</p>

<p><spring:message code="memberPermissions.description"/></p>

<%-- Grants an existing account access to this weblog immediately -- no
     invitation, no acceptance step. Its own form/CSRF, separate from the
     table below's.

     Hidden when groupblogging.enabled is off, matching the refusal
     MembersController.grant() enforces server-side; the table below stays so
     an operator who turns the setting off can still revoke the members it
     left behind. This page is reachable by URL even with the setting off --
     the menu gate only hides the tab -- so the form has to be gated here too,
     not merely left off the menu. --%>
<c:if test="${rc:getBooleanProp('groupblogging.enabled')}">
<form class="form-stacked mb-4"
      action="${pageContext.request.contextPath}/roller-ui/authoring/members!grant.rol" method="post">
<input type="hidden" name="weblog" value="${actionWeblog.handle}"/>
    <div class="row mb-3">
        <label for="grantUserName" class="col-sm-3 col-form-label">
            <spring:message code="memberPermissions.userName"/>
        </label>
        <div class="col-sm-9">
            <input type="text" class="form-control" name="userName" id="grantUserName"
                   size="30" maxlength="30"/>
        </div>
    </div>
    <div class="row mb-3">
        <label for="grantPermission" class="col-sm-3 col-form-label">
            <spring:message code="yourWebsites.permission"/>
        </label>
        <div class="col-sm-9">
            <select class="form-select" name="permissionString" id="grantPermission">
                <option value="post" selected>
                    <spring:message code="memberPermissions.author"/>
                </option>
                <option value="admin">
                    <spring:message code="memberPermissions.administrator"/>
                </option>
                <option value="edit_draft">
                    <spring:message code="memberPermissions.limited"/>
                </option>
            </select>
        </div>
    </div>
    <div class="control">
        <button type="submit" class="btn btn-primary"><spring:message code="generic.save"/></button>
    </div>
<sec:csrfInput/>
</form>
</c:if>

<form id="memberPermissionsForm" onsubmit="return confirmMemberRemoval();" action="${pageContext.request.contextPath}/roller-ui/authoring/members!save.rol" method="post">
<input type="hidden" name="weblog" value="${actionWeblog.handle}"/>

    <%-- Table is gated on there being members to show; the <form>
         itself stays unconditional because RouteSweepIT identifies this route
         by "form[action$='/roller-ui/authoring/members!save.rol']"
         (Routes.java). --%>
    <c:if test="${not empty weblogPermissions}">

    <table class="rollertable table table-striped">
        <tr class="rHeaderTr">
           <th class="rollertable" width="20%">
               <spring:message code="memberPermissions.userName"/>
           </th>
           <th class="rollertable" width="20%">
               <spring:message code="memberPermissions.administrator"/>
           </th>
           <th class="rollertable" width="20%">
               <spring:message code="memberPermissions.author"/>
           </th>
           <th class="rollertable" width="20%">
               <spring:message code="memberPermissions.limited"/>
           </th>
           <th class="rollertable" width="20%">
               <spring:message code="memberPermissions.remove"/>
           </th>
        </tr>
        <c:forEach items="${weblogPermissions}" var="perm" varStatus="rowstatus">
            <c:choose>
<c:when test="${rowstatus.index % 2 != 0}">
                <tr class="rollertable_odd">
            </c:when>
            <c:otherwise>
                <tr class="rollertable_even">
            </c:otherwise>
</c:choose><td class="rollertable">
                    <span class="bi bi-person"></span>
	                ${perm.user.userName}
                </td>               
                <td class="rollertable">
                    <input type="radio" 
                        <c:if test='${perm.hasAction("admin")}'>checked</c:if>
                        name='perm-${perm.user.id}' value="admin" />
                </td>
                <td class="rollertable">
	                <input type="radio" 
                        <c:if test='${perm.hasAction("post")}'>checked</c:if>
                        name='perm-${perm.user.id}' value="post" />
                </td>                
                <td class="rollertable">
                    <input type="radio" 
                        <c:if test='${perm.hasAction("edit_draft")}'>checked</c:if>
                        name='perm-${perm.user.id}' value="edit_draft" />
                </td>                
                <td class="rollertable">
                    <input type="radio" 
                        name='perm-${perm.user.id}' value="-1" />
                </td>
           </tr>
       </c:forEach>
    </table>
    <br />

    <div class="control">
       <button type="submit" class="btn"><spring:message code="generic.save"/></button>
    </div>

    </c:if>

    <c:if test="${empty weblogPermissions}">
        <div class="empty-state">
            <p class="empty-state-title"><spring:message code="empty.members.title"/></p>
            <p class="empty-state-body"><spring:message code="empty.members.body"/></p>
        </div>
    </c:if>


<sec:csrfInput/>
</form>
