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
<%@ include file="/roller-ui/scripts/ajax-user.js" %>
</script>

<p class="subtitle">
<b><spring:message code="userAdmin.subtitle.searchUser" /></b>
<spring:message code="userAdmin.prompt.searchUser" />
</p>

<form method="post" action="<c:url value='/roller-ui/admin/userAdmin!edit.rol'/>" class="form-vertical">
    <sec:csrfInput/>

    <div class="form-group">
        <label for="userName"><spring:message code="inviteMember.userName"/></label>
        <input type="text" class="form-control" id="userName" name="bean.userName"
               value="${fn:escapeXml(bean.userName)}"
               onfocus="onUserNameFocus(null)"
               onkeyup="onUserNameChange(null)" />
    </div>

    <div class="form-group">
        <select class="form-control" id="userList" size="10" onchange="onUserSelected()">
            <c:forEach var="item" items="${bean.list}">
                <option value="${fn:escapeXml(item)}">${fn:escapeXml(item)}</option>
            </c:forEach>
        </select>
    </div>

    <button type="submit" class="btn btn-default" id="user-submit">
        <spring:message code="generic.edit" />
    </button>

</form>

<c:if test="${authMethod != 'LDAP'}"> <%-- if we're not doing LDAP we can create new users in Roller --%>

    <h3><spring:message code="userAdmin.subtitle.userCreation" /></h3>
    <spring:message code="userAdmin.prompt.orYouCan" />
    <c:url var="createUserUrl" value="/roller-ui/admin/createUser.rol" />
    <a href="${createUserUrl}">
        <spring:message code="userAdmin.prompt.createANewUser" />
    </a>

</c:if>

<script>


$(document).ready(function () {

    document.getElementById('userName').focus();
    onUserNameFocus(false);

});

</script>
