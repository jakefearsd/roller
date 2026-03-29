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
function save() {
    radios = document.getElementsByTagName("input");
    var removing = false;
    for (var i=0; i<radios.length; i++) {
        if (radios[i].value === -1 && radios[i].checked) {
            removing = true;
        }
    }
    if (removing && !confirm("<spring:message code="memberPermissions.confirmRemove"/>")) return;
    document.memberPermissionsForm.submit();
}
// -->
</script>

<p class="subtitle">
    <spring:message code="memberPermissions.subtitle" arguments="${actionWeblog.handle}"/>
</p>

<p><spring:message code="memberPermissions.description"/></p>

<form action="${pageContext.request.contextPath}/roller-ui/authoring/members!save.rol" method="post" class="form-horizontal">
<input type="hidden" name="weblog" value="${actionWeblog.handle}"/>
    
    <div style="text-align: right; padding-bottom: 6px;">
        <span class="pendingCommentBox">&nbsp;&nbsp;&nbsp;&nbsp;</span>
            <spring:message code="commentManagement.pending"/>&nbsp;
    </div>
    
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
<c:when test="${perm.pending}">
                <tr class="rollertable_pending">
            </c:when>
<c:when test="${rowstatus.index % 2 != 0}">
                <tr class="rollertable_odd">
            </c:when>
            <c:otherwise>
                <tr class="rollertable_even">
            </c:otherwise>
</c:choose><td class="rollertable">
                    <span class="glyphicon glyphicon-user"></span>
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
    
<sec:csrfInput/>
</form>
