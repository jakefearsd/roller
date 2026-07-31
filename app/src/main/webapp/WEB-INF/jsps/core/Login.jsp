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
<%@ include file="/WEB-INF/jsps/taglibs-spring.jsp" %>




        <form method="post" id="loginForm" class="form-horizontal"
              action="<c:url value='/roller_j_security_check'/>" onsubmit="saveUsername(this)">

            <div class="form-group">
                <legend><spring:message code="loginPage.prompt"/></legend>
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

<script>

    // There used to be a second, OpenID login form on this page, and
    // focusToUsernamePasswordForm() decided which of the two deserved focus.
    // With OpenID gone there is only one form, so focus is unconditional.
    if (getCookie("username") != null) {
        document.getElementById("j_username").value = getCookie("username");
        document.getElementById("j_password").focus();
    } else {
        document.getElementById("j_username").focus();
    }

    function saveUsername(theForm) {
        var expires = new Date();
        expires.setTime(expires.getTime() + 24 * 30 * 60 * 60 * 1000); // sets it for approx 30 days.
        setCookie("username", theForm.j_username.value, expires);
        setCookie("favorite_authentication_method", "username");
    }
</script>
