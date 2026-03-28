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

<p class="subtitle"><spring:message code="userAdmin.title.editUser"/></p>

<c:if test="${authMethod == 'DB_OPENID'}">
    <p class="pagetip">
        <spring:message code="userAdmin.noPasswordForOpenID"/>
    </p>
</c:if>


<form:form modelAttribute="bean" action="${pageContext.request.contextPath}/roller-ui/profile!save.rol" method="post" cssClass="form-horizontal">
    <sec:csrfInput/>

    <div class="form-group">
        <spring:message code="userSettings.username" var="usernameLabel"/>
        <label class="col-sm-3 control-label">${usernameLabel}</label>
        <div class="col-sm-9 controls">
            <form:input path="userName" cssClass="form-control" size="30" maxlength="30" readonly="true"
                        onchange="formChanged()" onkeyup="formChanged()"/>
        </div>
    </div>

    <div class="form-group">
        <spring:message code="userSettings.screenname" var="screennameLabel"/>
        <label class="col-sm-3 control-label">${screennameLabel}</label>
        <div class="col-sm-9 controls">
            <form:input path="screenName" cssClass="form-control" size="30" maxlength="30"
                        onchange="formChanged()" onkeyup="formChanged()"/>
        </div>
    </div>

    <div class="form-group">
        <spring:message code="userSettings.fullname" var="fullnameLabel"/>
        <label class="col-sm-3 control-label">${fullnameLabel}</label>
        <div class="col-sm-9 controls">
            <form:input path="fullName" cssClass="form-control" size="30" maxlength="30"
                        onchange="formChanged()" onkeyup="formChanged()"/>
        </div>
    </div>

    <div class="form-group">
        <spring:message code="userSettings.email" var="emailLabel"/>
        <label class="col-sm-3 control-label">${emailLabel}</label>
        <div class="col-sm-9 controls">
            <form:input path="emailAddress" cssClass="form-control" size="40" maxlength="40"
                        onchange="formChanged()" onkeyup="formChanged()"/>
        </div>
    </div>

    <c:if test="${authMethod == 'ROLLERDB' || authMethod == 'DB_OPENID'}">
        <div class="form-group">
            <spring:message code="userSettings.password" var="passwordLabel"/>
            <label class="col-sm-3 control-label">${passwordLabel}</label>
            <div class="col-sm-9 controls">
                <form:password path="passwordText" cssClass="form-control" size="20" maxlength="20"
                               onchange="formChanged()" onkeyup="formChanged()"/>
            </div>
        </div>

        <div class="form-group">
            <spring:message code="userSettings.passwordConfirm" var="passwordConfirmLabel"/>
            <label class="col-sm-3 control-label">${passwordConfirmLabel}</label>
            <div class="col-sm-9 controls">
                <form:password path="passwordConfirm" cssClass="form-control" size="20" maxlength="20"
                               onchange="formChanged()" onkeyup="formChanged()"/>
            </div>
        </div>
    </c:if>
    <c:if test="${authMethod != 'ROLLERDB' && authMethod != 'DB_OPENID'}">
        <form:hidden path="password"/>
    </c:if>

    <c:if test="${authMethod == 'OPENID' || authMethod == 'DB_OPENID'}">
        <div class="form-group">
            <spring:message code="userSettings.openIdUrl" var="openIdUrlLabel"/>
            <label class="col-sm-3 control-label">${openIdUrlLabel}</label>
            <div class="col-sm-9 controls">
                <form:input path="openIdUrl" cssClass="form-control" size="40" maxlength="255"
                            id="f_openid_identifier" style="width:75%"/>
            </div>
        </div>
    </c:if>

    <div class="form-group">
        <spring:message code="userSettings.locale" var="localeLabel"/>
        <label class="col-sm-3 control-label">${localeLabel}</label>
        <div class="col-sm-9 controls">
            <form:select path="locale" items="${localesList}"  itemLabel="displayName" cssClass="form-control"/>
        </div>
    </div>

    <div class="form-group">
        <spring:message code="userSettings.timeZone" var="tzLabel"/>
        <label class="col-sm-3 control-label">${tzLabel}</label>
        <div class="col-sm-9 controls">
            <form:select path="timeZone" items="${timeZonesList}" cssClass="form-control"/>
        </div>
    </div>

    <button type="submit" id="saveButton" class="btn btn-default"><spring:message code="generic.save"/></button>

    <input class="btn" type="button" value="<spring:message code='generic.cancel'/>"
           onclick="window.location='<c:url value='/roller-ui/menu.rol'/>'"/>

</form:form>

<%-- -------------------------------------------------------- --%>

<script type="text/javascript">

    var saveButton;

    $(document).ready(function () {
        saveButton = $("#saveButton");
        formChanged();
    });

    function formChanged() {
        var valid = false;

        var screenName = $("[name='screenName']").val();
        var fullName = $("[name='fullName']").val();
        var email = $("[name='emailAddress']").val();
        var password = $("[name='passwordText']").val();
        var passwordConfirm = $("[name='passwordConfirm']").val();

        if (screenName && screenName.trim().length > 0
            && fullName && fullName.trim().length > 0
            && email && email.trim().length > 0 && validateEmail(email)) {
            valid = true;

        } else {
            valid = false;
        }

        if ((password && password.trim().length) || (passwordConfirm && passwordConfirm.trim().length > 0)) {
            if (password !== passwordConfirm) {
                valid = false;
            }
        }

        if (valid) {
            saveButton.attr("disabled", false);
        } else {
            saveButton.attr("disabled", true);
        }

    }

</script>
