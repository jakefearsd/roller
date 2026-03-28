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

<%-- Body of the login page, invoked from login.jsp --%>
<%@ page import="org.apache.roller.weblogger.config.WebloggerConfig" %>
<%@ include file="/WEB-INF/jsps/taglibs-spring.jsp" %>

<%!
    String securityCheckUrl = null;
    boolean cmaEnabled = "CMA".equals( WebloggerConfig.getAuthMethod() );
%>

<%
    if (cmaEnabled) {
        securityCheckUrl = "/j_security_check";
    } else {
        securityCheckUrl = "/roller_j_security_check";
    }
%>

    <c:if test="${authMethod == 'OPENID' || authMethod == 'DB_OPENID'}">

        <form method="post" id="loginOpenIDForm" class="form-horizontal"
              action="/roller/roller_j_openid_security_check" onsubmit="saveOpenidIdentifier(this)">

            <div class="form-group">
                <legend><spring:message code="loginPage.openIdPrompt"/></legend>
            </div>

            <div class="form-group">
                <label for="openid_identifier"><spring:message code="loginPage.openID"/></label>
                <input class="form-control" type="text" name="openid_identifier" id="openid_identifier"/>
            </div>

            <sec:csrfInput/>
            <spring:message code="loginPage.loginOpenID" var="loginOpenIDLabel"/>
            <button type="submit" name="submit" id="submit" class="btn btn-primary"
                value="${loginOpenIDLabel}"></button>

        </form>

    </c:if>

    <c:if test="${authMethod != 'OPENID'}">

        <form method="post" id="loginForm" class="form-horizontal"
              action="<c:url value="<%= securityCheckUrl %>"/>" onsubmit="saveUsername(this)">

            <div class="form-group">
                <c:choose>
                    <c:when test="${authMethod == 'DB_OPENID'}">
                        <legend><spring:message code="loginPage.openIdHybridPrompt"/></legend>
                    </c:when>
                    <c:otherwise>
                        <legend><spring:message code="loginPage.prompt"/></legend>
                    </c:otherwise>
                </c:choose>
            </div>

            <div class="form-group">
                <label for="j_username" > <spring:message code="loginPage.userName"/> </label>
                <input type="text" class="form-control" name="j_username" id="j_username" placeholder="Username"/>
            </div>

            <div class="form-group">
                <label for="j_password" > <spring:message code="loginPage.password"/> </label>
                <input type="password" class="form-control" name="j_password" id="j_password" placeholder="Password"/>
            </div>

            <c:if test="${rememberMeEnabled}">
                <div class="form-group">
                    <input type="checkbox" name="_spring_security_remember_me" id="_spring_security_remember_me"/>
                    <label for="_spring_security_remember_me" > <spring:message code="loginPage.rememberMe"/> </label>
                </div>
            </c:if>

            <sec:csrfInput/>
            <div class="form-group">
                <button class="btn btn-primary" type="submit" name="login" id="login">
                    <spring:message code='loginPage.login'/>
                </button>

                <button class="btn" type="reset" name="reset" id="reset"
                        onclick="document.getElementById('j_username').focus()">
                    <spring:message code='loginPage.reset'/>
                </button>
            </div>

        </form>
    </c:if>

<script>
    <c:if test="${authMethod == 'OPENID' || authMethod == 'DB_OPENID'}">
    function focusToOpenidForm() {
        return (document.getElementById && document.getElementById("j_username") === null) ||
                getCookie("favorite_authentication_method") !== "username";
    }

    if (document.getElementById) {
        if (document.getElementById && getCookie("openid_identifier") !== null) {
            document.getElementById("openid_identifier").value = getCookie("openid_identifier");
        }
        if (focusToOpenidForm()) {
            document.getElementById("openid_identifier").focus();
        }
    }

    function saveOpenidIdentifier(theForm) {
        var expires = new Date();
        expires.setTime(expires.getTime() + 24 * 30 * 60 * 60 * 1000); // sets it for approx 30 days.
        setCookie("openid_identifier", theForm.openid_identifier.value, expires);
        setCookie("favorite_authentication_method", "openid");
    }
    </c:if>

    <c:if test="${authMethod != 'OPENID'}">
    function focusToUsernamePasswordForm() {
        return (document.getElementById && document.getElementById("openid_identifier") === null) ||
                getCookie("favorite_authentication_method") === "username";
    }

    if (document.getElementById) {
        if (getCookie("username") != null) {
            if (document.getElementById) {
                document.getElementById("j_username").value = getCookie("username");
                if (focusToUsernamePasswordForm()) {
                    document.getElementById("j_password").focus();
                }
            }
        } else if (focusToUsernamePasswordForm()) {
            document.getElementById("j_username").focus();
        }
    }

    function saveUsername(theForm) {
        var expires = new Date();
        expires.setTime(expires.getTime() + 24 * 30 * 60 * 60 * 1000); // sets it for approx 30 days.
        setCookie("username", theForm.j_username.value, expires);
        setCookie("favorite_authentication_method", "username");
    }
    </c:if>
</script>
