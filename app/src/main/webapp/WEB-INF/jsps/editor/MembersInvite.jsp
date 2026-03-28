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

<p class="subtitle"><spring:message code="inviteMember.subtitle"/></p>
<p><spring:message code="inviteMember.prompt"/></p>

<form action="${pageContext.request.contextPath}/roller-ui/authoring/invite!save.rol" method="post" class="form-horizontal">
<input type="hidden" name="weblog" value="${actionWeblog.handle}"/>

    <div class="formrow">
       <label for="userName" class="formrow">
           <spring:message code="inviteMember.userName"/></label>
       <div>
           <input name="userName" id="userName" size="30" maxlength="30"
               onfocus="onMemberNameFocus(true)" onkeyup="onMemberNameChange(true)" /><br />
       </div>
    </div>

    <div class="formrow">
       <label class="formrow" />&nbsp;</label>
       <div>
           <select id="userList" size="10" onchange="onMemberSelected()" style="width:400px"></select>
       </div>
    </div>

    <div style="clear:left">
       <label for="userName" class="formrow" />
           <spring:message code="inviteMember.permissions"/></label>
       <input type="radio" name="permissionString" value="admin"  />
       <spring:message code="inviteMember.administrator"/>
       <input type="radio" name="permissionString" value="post" checked />
       <spring:message code="inviteMember.author"/>
       <input type="radio" name="permissionString" value="edit_draft" />
       <spring:message code="inviteMember.limited"/>
    </div>

    <br />
    <button type="submit" id="inviteButton" class="btn btn-default"><spring:message code="inviteMember.button.save"/></button>
    <button type="submit" class="btn" formaction="${pageContext.request.contextPath}/roller-ui/authoring/invite!cancel.rol"><spring:message code="generic.cancel"/></button>

<sec:csrfInput/>
</form>

<script>

    <%@ include file="/roller-ui/scripts/ajax-user.js" %>

    $(document).ready(function () {
        $('#userName').focus();
        $('#inviteButton').attr("disabled", true);
    });

    function onMemberNameChange(enabled) {
        var u = userURL;
        if (enabled != null) {
            u = u + "&enabled=" + enabled;
        }

        var userName = $('#userName').val();
        if (userName.length > 0) {
            u = u + "&startsWith=" + userName;
        }

        sendUserRequest(u);
    }

    function onMemberSelected() {
        var userName = $('#userList').children("option:selected").val();
        if (userName !== '') {
            $('#inviteButton').attr("disabled", false);
            $('#userName').val(userName);
        }
    }

    function onMemberNameFocus(enabled) {
        if (!init) {
            init = true;
            var u = userURL;

            if (enabled != null) {
                u = u + "&enabled=" + enabled;
            }

            sendUserRequest(u);

        } else {
            $('#inviteButton').attr("disabled", false);
        }
    }


</script>
