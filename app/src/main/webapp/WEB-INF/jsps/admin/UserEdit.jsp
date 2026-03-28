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


<%-- Titling, processing actions different between add and edit --%>
<c:choose>
    <c:when test="${actionName == 'createUser'}">
        <c:set var="subtitleKey" value="userAdmin.subtitle.createNewUser"/>
    </c:when>
    <c:otherwise>
        <c:set var="subtitleKey" value="userAdmin.subtitle.editUser"/>
    </c:otherwise>
</c:choose>

<p class="subtitle">
    <spring:message code="${subtitleKey}" arguments="${bean.userName}"/>
</p>

<p class="pagetip">
    <c:if test="${actionName == 'createUser'}">
        <spring:message code="userAdmin.addInstructions"/>
    </c:if>
    <c:if test="${authMethod == 'DB_OPENID'}">
         <spring:message code="userAdmin.noPasswordForOpenID"/>
    </c:if>
</p>

<c:choose>
    <c:when test="${actionName == 'createUser'}">
        <c:set var="saveAction" value="/roller-ui/admin/createUser!save.rol"/>
        <c:set var="cancelAction" value="/roller-ui/admin/createUser!cancel.rol"/>
    </c:when>
    <c:otherwise>
        <c:set var="saveAction" value="/roller-ui/admin/modifyUser!save.rol"/>
        <c:set var="cancelAction" value="/roller-ui/admin/modifyUser!cancel.rol"/>
    </c:otherwise>
</c:choose>

<form method="post" action="<c:url value='${saveAction}'/>" class="form-horizontal">
    <sec:csrfInput/>
    <c:if test="${actionName == 'modifyUser'}">
        <%-- bean for add does not have a bean id yet --%>
        <input type="hidden" name="bean.id" value="${fn:escapeXml(bean.id)}" />
    </c:if>

    <c:choose>
        <c:when test="${actionName == 'modifyUser'}">
            <div class="form-group">
                <label class="col-sm-3 control-label"><spring:message code="userSettings.username"/></label>
                <div class="col-sm-9 controls">
                    <input type="text" name="bean.userName" value="${fn:escapeXml(bean.userName)}"
                           size="30" maxlength="30" onkeyup="formChanged()"
                           readonly="readonly" style="background: #e5e5e5" class="form-control"
                           title="<spring:message code='userSettings.tip.username'/>"/>
                </div>
            </div>
        </c:when>
        <c:otherwise>
            <div class="form-group">
                <label class="col-sm-3 control-label"><spring:message code="userSettings.username"/></label>
                <div class="col-sm-9 controls">
                    <input type="text" name="bean.userName" value="${fn:escapeXml(bean.userName)}"
                           size="30" maxlength="30" onkeyup="formChanged()" class="form-control"
                           title="<spring:message code='userAdmin.tip.username'/>"/>
                </div>
            </div>
        </c:otherwise>
    </c:choose>

    <div class="form-group">
        <label class="col-sm-3 control-label"><spring:message code="userSettings.screenname"/></label>
        <div class="col-sm-9 controls">
            <input type="text" id="bean_userName" name="bean.screenName" value="${fn:escapeXml(bean.screenName)}"
                   size="30" maxlength="30" onkeyup="formChanged()" class="form-control"
                   title="<spring:message code='userAdmin.tip.screenName'/>"/>
        </div>
    </div>

    <div class="form-group">
        <label class="col-sm-3 control-label"><spring:message code="userSettings.fullname"/></label>
        <div class="col-sm-9 controls">
            <input type="text" id="bean_fullName" name="bean.fullName" value="${fn:escapeXml(bean.fullName)}"
                   size="30" maxlength="30" onkeyup="formChanged()" class="form-control"
                   title="<spring:message code='userAdmin.tip.fullName'/>"/>
        </div>
    </div>

    <c:if test="${authMethod == 'ROLLERDB' || authMethod == 'DB_OPENID'}">
        <div class="form-group">
            <label class="col-sm-3 control-label"><spring:message code="userSettings.password"/></label>
            <div class="col-sm-9 controls">
                <input type="password" name="bean.password" size="30" maxlength="30"
                       onkeyup="formChanged()" class="form-control"
                       title="<spring:message code='userAdmin.tip.password'/>"/>
            </div>
        </div>
    </c:if>

    <c:if test="${authMethod == 'OPENID' || authMethod == 'DB_OPENID'}">
        <div class="form-group">
            <label class="col-sm-3 control-label"><spring:message code="userSettings.openIdUrl"/></label>
            <div class="col-sm-9 controls">
                <input type="text" name="bean.openIdUrl" value="${fn:escapeXml(bean.openIdUrl)}"
                       size="30" maxlength="255" id="f_openid_identifier" class="form-control"
                       title="<spring:message code='userAdmin.tip.openIdUrl'/>"/>
            </div>
        </div>
    </c:if>

    <div class="form-group">
        <label class="col-sm-3 control-label"><spring:message code="userSettings.email"/></label>
        <div class="col-sm-9 controls">
            <input type="text" id="bean_email" name="bean.emailAddress" value="${fn:escapeXml(bean.emailAddress)}"
                   size="30" maxlength="255" onkeyup="formChanged()" class="form-control"
                   title="<spring:message code='userAdmin.tip.email'/>"/>
        </div>
    </div>

    <div class="form-group">
        <label class="col-sm-3 control-label"><spring:message code="userSettings.locale"/></label>
        <div class="col-sm-9 controls">
            <select name="bean.locale" class="form-control"
                    title="<spring:message code='userAdmin.tip.locale'/>">
                <c:forEach var="loc" items="${localesList}">
                    <option value="${fn:escapeXml(loc)}"
                        <c:if test="${loc == bean.locale}">selected="selected"</c:if>
                    >${fn:escapeXml(loc.displayName)}</option>
                </c:forEach>
            </select>
        </div>
    </div>

    <div class="form-group">
        <label class="col-sm-3 control-label"><spring:message code="userSettings.timeZone"/></label>
        <div class="col-sm-9 controls">
            <select name="bean.timeZone" class="form-control"
                    title="<spring:message code='userAdmin.tip.timeZone'/>">
                <c:forEach var="tz" items="${timeZonesList}">
                    <option value="${fn:escapeXml(tz)}"
                        <c:if test="${tz == bean.timeZone}">selected="selected"</c:if>
                    >${fn:escapeXml(tz)}</option>
                </c:forEach>
            </select>
        </div>
    </div>

    <div class="form-group">
        <label class="col-sm-3 control-label"><spring:message code="userAdmin.enabled"/></label>
        <div class="col-sm-9 controls">
            <input type="checkbox" name="bean.enabled" value="true"
                <c:if test="${bean.enabled}">checked="checked"</c:if>
                   title="<spring:message code='userAdmin.tip.userEnabled'/>"/>
        </div>
    </div>

    <div class="form-group">
        <label class="col-sm-3 control-label"><spring:message code="userAdmin.userAdmin"/></label>
        <div class="col-sm-9 controls">
            <input type="checkbox" name="bean.administrator" value="true"
                <c:if test="${bean.administrator}">checked="checked"</c:if>
                   title="<spring:message code='userAdmin.tip.userAdmin'/>"/>
        </div>
    </div>


    <c:if test="${actionName == 'modifyUser'}">
        <h2><spring:message code="userAdmin.userWeblogs" /></h2>

        <c:if test="${not empty permissions}">
            <p><spring:message code="userAdmin.userMemberOf" />:</p>
            <table class="table" style="width: 80%">
                <c:forEach var="perms" items="${permissions}">
                    <tr>
                        <td width="%30">
                            <a href='${perms.weblog.absoluteURL}'>
                                ${fn:escapeXml(perms.weblog.name)} [${fn:escapeXml(perms.weblog.handle)}]
                            </a>
                        </td>
                        <td width="%15">
                            <c:url var="newEntry" value="/roller-ui/authoring/entryAdd.rol">
                                <c:param name="weblog" value="${perms.weblog.handle}" />
                            </c:url>
                            <img src='<c:url value="/images/page_white_edit.png"/>' />
                            <a href='${newEntry}'>
                            <spring:message code="userAdmin.newEntry" /></a>
                        </td>
                        <td width="%15">
                            <c:url var="editEntries" value="/roller-ui/authoring/entries.rol">
                                <c:param name="weblog" value="${perms.weblog.handle}" />
                            </c:url>
                            <img src='<c:url value="/images/page_white_edit.png"/>' />
                            <a href='${editEntries}'>
                            <spring:message code="userAdmin.editEntries" /></a>
                        </td>
                        <td width="%15">
                            <c:url var="manageWeblog" value="/roller-ui/authoring/weblogConfig.rol">
                                <c:param name="weblog" value="${perms.weblog.handle}" />
                            </c:url>
                            <img src='<c:url value="/images/page_white_edit.png"/>' />
                            <a href='${manageWeblog}'>
                            <spring:message code="userAdmin.manage" /></a>
                        </td>
                    </tr>
                </c:forEach>
            </table>
        </c:if>
        <c:if test="${empty permissions}">
            <spring:message code="userAdmin.userHasNoWeblogs" />
        </c:if>
    </c:if>

    <br />
    <br />

    <div class="control">
        <button type="submit" class="btn btn-default" id="save_button">
            <spring:message code="generic.save"/>
        </button>
        <a href="<c:url value='${cancelAction}'/>" class="btn">
            <spring:message code="generic.cancel"/>
        </a>
    </div>

</form>


<script>

    document.forms[0].elements[0].focus();
    let saveButton;

    $( document ).ready(function() {
        saveButton = $("#save_button");
        formChanged()
    });

    function formChanged() {
        let userName = $("#bean_userName:first").val();
        let fullName = $("#bean_fullName:first").val();
        let email = $("#bean_email:first").val();

        let valid = (userName && userName.trim().length > 0
            && fullName && fullName.trim().length > 0
            && email && email.trim().length > 0
            && validateEmail(email));

        if (valid) {
            saveButton.attr("disabled", false);
        } else {
            saveButton.attr("disabled", true);
        }
    }

</script>

