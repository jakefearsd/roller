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

<%-- This form was never migrated off the pre-Bootstrap markup: it used
     <div class="formrow">/<label class="formrow">, and "formrow" has no rule
     anywhere in roller.css, so every "row" was an unstyled block. The inputs
     carried no .form-control, the select was sized with an inline
     style="width:400px", the radios had no .form-check wrapper and no label
     of their own, and two of the labels were written "<label ... />...</label>"
     -- self-closed and then closed again, which leaves an empty label element
     and loose text beside it.

     The element ids are load-bearing and unchanged: #userName and #userList
     are read by roller-ui/scripts/ajax-user.js, which this page pulls in as a
     translation-time include and shares with UserAdmin.jsp (a page that has a
     #user-submit this one does not -- hence the null guard over there), and
     #inviteButton is enabled/disabled by the script at the bottom of this
     file. --%>
<form class="form-stacked" action="${pageContext.request.contextPath}/roller-ui/authoring/invite!save.rol" method="post">
<input type="hidden" name="weblog" value="${actionWeblog.handle}"/>

    <%-- Text box and the matching-users list are one control group: the list
         is what the box filters, so it sits under the box rather than beside
         an empty label of its own, which is what the old markup did. --%>
    <div class="row mb-3">
        <label for="userName" class="col-sm-3 col-form-label">
            <spring:message code="inviteMember.userName"/>
        </label>
        <div class="col-sm-9">
            <input type="text" class="form-control" name="userName" id="userName"
                   size="30" maxlength="30"
                   onfocus="onMemberNameFocus(true)" onkeyup="onMemberNameChange(true)"/>
            <select class="form-select mt-2" id="userList" size="10"
                    onchange="onMemberSelected()"></select>
        </div>
    </div>

    <div class="row mb-3">
        <label class="col-sm-3 col-form-label">
            <spring:message code="inviteMember.permissions"/>
        </label>
        <div class="col-sm-9">
            <div class="form-check">
                <input class="form-check-input" type="radio" name="permissionString"
                       id="permissionAdmin" value="admin"/>
                <label class="form-check-label" for="permissionAdmin">
                    <spring:message code="inviteMember.administrator"/>
                </label>
            </div>
            <div class="form-check">
                <input class="form-check-input" type="radio" name="permissionString"
                       id="permissionPost" value="post" checked/>
                <label class="form-check-label" for="permissionPost">
                    <spring:message code="inviteMember.author"/>
                </label>
            </div>
            <div class="form-check">
                <input class="form-check-input" type="radio" name="permissionString"
                       id="permissionEditDraft" value="edit_draft"/>
                <label class="form-check-label" for="permissionEditDraft">
                    <spring:message code="inviteMember.limited"/>
                </label>
            </div>
        </div>
    </div>

    <%-- .control is the button-row class the rest of the admin forms use; it
         has no rule in roller.css today, so mt-3 carries the actual spacing
         until it gets one. --%>
    <div class="control mt-3">
        <button type="submit" id="inviteButton" class="btn btn-primary">
            <spring:message code="inviteMember.button.save"/>
        </button>
        <button type="submit" class="btn"
                formaction="${pageContext.request.contextPath}/roller-ui/authoring/invite!cancel.rol">
            <spring:message code="generic.cancel"/>
        </button>
    </div>

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
