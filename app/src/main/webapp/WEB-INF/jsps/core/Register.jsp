<!--
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
-->
<%@ include file="/WEB-INF/jsps/taglibs-spring.jsp" %>

<p><spring:message code="userRegister.prompt" /></p>

<form:form modelAttribute="bean" action="${pageContext.request.contextPath}/roller-ui/register!save.rol" method="post" cssClass="form-horizontal">
    <sec:csrfInput/>
    <form:hidden path="id" />

    <h2><spring:message code="userRegister.heading.identification" /></h2>
    <p><spring:message code="userRegister.tip.identification" /></p>

    <c:if test="${authMethod == 'LDAP'}">

        <div class="form-group">

            <label class="col-sm-3 control-label">
                <spring:message code="userSettings.username" />
            </label>

            <div class="col-sm-9 controls">
                <p class="form-control-static">
                    ${fn:escapeXml(bean.userName)}
                </p>
            </div>

        </div>

    </c:if>
    <c:if test="${authMethod != 'LDAP'}">
        <div class="form-group">
            <spring:message code="userSettings.username" var="usernameLabel"/>
            <label class="col-sm-3 control-label">${usernameLabel}</label>
            <div class="col-sm-9 controls">
                <form:input path="userName" cssClass="form-control" size="30" maxlength="30" onkeyup="onChange()"/>
            </div>
        </div>
    </c:if>

    <div class="form-group">
        <spring:message code="userSettings.screenname" var="screennameLabel"/>
        <label class="col-sm-3 control-label">${screennameLabel}</label>
        <div class="col-sm-9 controls">
            <form:input path="screenName" cssClass="form-control" size="30" maxlength="30" onkeyup="onChange()"/>
        </div>
    </div>

    <div class="form-group">
        <spring:message code="userSettings.fullname" var="fullnameLabel"/>
        <label class="col-sm-3 control-label">${fullnameLabel}</label>
        <div class="col-sm-9 controls">
            <form:input path="fullName" cssClass="form-control" size="30" maxlength="30" onkeyup="onChange()"/>
        </div>
    </div>

    <div class="form-group">
        <spring:message code="userSettings.email" var="emailLabel"/>
        <label class="col-sm-3 control-label">${emailLabel}</label>
        <div class="col-sm-9 controls">
            <form:input path="emailAddress" cssClass="form-control" size="40" maxlength="255" onkeyup="onChange()"/>
        </div>
    </div>

    <c:if test="${authMethod != 'LDAP'}">

        <h2><spring:message code="userRegister.heading.authentication" /></h2>

        <c:if test="${authMethod == 'ROLLERDB'}">
            <p><spring:message code="userRegister.tip.openid.disabled" /></p>
        </c:if>

        <c:if test="${authMethod == 'DB_OPENID'}">
            <p><spring:message code="userRegister.tip.openid.hybrid" /></p>
        </c:if>

        <c:if test="${authMethod == 'OPENID'}">
            <p><spring:message code="userRegister.tip.openid.only" /></p>
        </c:if>

        <c:if test="${authMethod == 'ROLLERDB' || authMethod == 'DB_OPENID'}">
            <div class="form-group">
                <spring:message code="userSettings.password" var="passwordLabel"/>
                <label class="col-sm-3 control-label">${passwordLabel}</label>
                <div class="col-sm-9 controls">
                    <form:password path="passwordText" cssClass="form-control" size="20" maxlength="20" onkeyup="onChange()"/>
                </div>
            </div>

            <div class="form-group">
                <spring:message code="userSettings.passwordConfirm" var="passwordConfirmLabel"/>
                <label class="col-sm-3 control-label">${passwordConfirmLabel}</label>
                <div class="col-sm-9 controls">
                    <form:password path="passwordConfirm" cssClass="form-control" size="20" maxlength="20" onkeyup="onChange()"/>
                </div>
            </div>
        </c:if>
        <c:if test="${authMethod != 'ROLLERDB' && authMethod != 'DB_OPENID'}">
            <form:hidden path="password" />
            <form:hidden path="passwordText" />
            <form:hidden path="passwordConfirm" />
        </c:if>

        <c:if test="${authMethod == 'OPENID' || authMethod == 'DB_OPENID'}">
            <div class="form-group">
                <spring:message code="userSettings.openIdUrl" var="openIdUrlLabel"/>
                <label class="col-sm-3 control-label">${openIdUrlLabel}</label>
                <div class="col-sm-9 controls">
                    <form:input path="openIdUrl" cssClass="form-control" size="40" maxlength="255" onkeyup="onChange()"/>
                </div>
            </div>
        </c:if>

    </c:if>

    <h2><spring:message code="userRegister.heading.locale" /></h2>
    <p><spring:message code="userRegister.tip.localeAndTimeZone" /></p>

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

    <h2><spring:message code="userRegister.heading.ready" /></h2>

    <p id="readytip"><spring:message code="userRegister.tip.ready" /></p>

    <button type="submit" id="submit" class="btn btn-default"><spring:message code="userRegister.button.save"/></button>
    <input type="button" class="btn btn-cancel"
           value="<spring:message code='generic.cancel'/>" onclick="window.location='<c:url value="/"/>'" />

</form:form>

<%-- ============================================================================== --%>

<script type="text/javascript">

    function onChange() {
        var disabled = true;
        var authMethod    = "${authMethod}";
        var emailAddress    = document.querySelector('[name="emailAddress"]').value;
        var userName = passwordText = passwordConfirm = openIdUrl = "";

        if (!validateEmail(emailAddress)) {
            document.getElementById('submit').disabled = true;
            return;
        }

        if (authMethod === 'LDAP') {
            userName = '${fn:escapeXml(bean.userName)}';
        } else {
            userName = document.querySelector('[name="userName"]').value;
        }

        if (authMethod === "ROLLERDB" || authMethod === "DB_OPENID") {
            passwordText    = document.querySelector('[name="passwordText"]').value;
            passwordConfirm = document.querySelector('[name="passwordConfirm"]').value;
        }
        if (authMethod === "OPENID" || authMethod === "DB_OPENID") {
            openIdUrl = document.querySelector('[name="openIdUrl"]').value;
        }

        if (authMethod === "LDAP") {
            if (emailAddress) disabled = false;
        } else if (authMethod === "ROLLERDB") {
            if (emailAddress && userName && passwordText && passwordConfirm) disabled = false;
        } else if (authMethod === "OPENID") {
            if (emailAddress && openIdUrl) disabled = false;
        } else if (authMethod === "DB_OPENID") {
            if (emailAddress && ((passwordText && passwordConfirm) || (openIdUrl)) ) disabled = false;
        }

        if (authMethod !== 'LDAP') {
            if ((passwordText || passwordConfirm) && !(passwordText === passwordConfirm)) {
                document.getElementById('readytip').innerHTML = '<spring:message code="userRegister.error.mismatchedPasswords" />';
                disabled = true;
            } else if (disabled) {
                document.getElementById('readytip').innerHTML = '<spring:message code="userRegister.tip.ready" />'
            } else {
                document.getElementById('readytip').innerHTML = '<spring:message code="userRegister.success.ready" />'
            }
        }
        document.getElementById('submit').disabled = disabled;
    }
    document.getElementById('submit').disabled = true;

</script>
