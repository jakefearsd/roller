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

<p class="subtitle"><spring:message code="createWebsite.prompt"/></p>

<br/>

<form:form modelAttribute="bean" action="${pageContext.request.contextPath}/roller-ui/createWeblog!save.rol"
           method="post" cssClass="form-horizontal validate-form">

    <sec:csrfInput/>

    <spring:message code="CreateWeblog.error.nameNull" var="nameRequired"/>
    <div class="form-group">
        <spring:message code="generic.name" var="nameLabel"/>
        <label class="col-sm-3 control-label">${nameLabel}</label>
        <div class="col-sm-9 controls">
            <form:input path="name" cssClass="form-control" size="30" maxlength="30"
                        data-msg-required="${nameRequired}" required="required"/>
        </div>
    </div>

    <spring:message code="CreateWeblog.error.handleNull" var="handleRequired"/>
    <div class="form-group">
        <spring:message code="createWebsite.handle" var="handleLabel"/>
        <label class="col-sm-3 control-label">${handleLabel}</label>
        <div class="col-sm-9 controls">
            <form:input path="handle" cssClass="form-control" size="30" maxlength="30"
                        onkeyup="handlePreview(this)" data-msg-required="${handleRequired}" required="required"/>
        </div>
    </div>

    <div class="form-group">
        <label class="col-sm-3"></label>
        <div class="col-sm-9 controls">
            <spring:message code="createWebsite.weblogUrl" />:&nbsp;
            ${absoluteSiteURL}/<span id="handlePreview" style="color:red"><c:choose><c:when test="${bean.handle != null}">${fn:escapeXml(bean.handle)}</c:when><c:otherwise>handle</c:otherwise></c:choose></span>
            <br>
        </div>
    </div>

    <spring:message code="CreateWeblog.error.emailAddressNull" var="emailRequired"/>
    <spring:message code="CreateWeblog.error.emailAddressInvalid" var="emailInvalid"/>
    <div class="form-group">
        <spring:message code="createWebsite.emailAddress" var="emailLabel"/>
        <label class="col-sm-3 control-label">${emailLabel}</label>
        <div class="col-sm-9 controls">
            <form:input path="emailAddress" cssClass="form-control validate-email" size="40" maxlength="50"
                        data-msg="${emailInvalid}" data-msg-required="${emailRequired}" required="required"/>
        </div>
    </div>

    <div class="form-group">
        <spring:message code="createWebsite.locale" var="localeLabel"/>
        <label class="col-sm-3 control-label">${localeLabel}</label>
        <div class="col-sm-9 controls">
            <form:select path="locale" items="${localesList}" itemValue="toString()" itemLabel="displayName" cssClass="form-control"/>
        </div>
    </div>

    <div class="form-group">
        <spring:message code="createWebsite.timezone" var="tzLabel"/>
        <label class="col-sm-3 control-label">${tzLabel}</label>
        <div class="col-sm-9 controls">
            <form:select path="timeZone" items="${timeZonesList}" cssClass="form-control"/>
        </div>
    </div>

    <div class="form-group" ng-app="themeSelectModule" ng-controller="themeController">
        <label class="col-sm-3 control-label">
            <spring:message code="createWebsite.theme" />
        </label>
        <div class="col-sm-9 controls">
            <form:select path="theme" items="${themes}" itemValue="id" itemLabel="name" cssClass="form-control"
                         onchange="previewImage(this[selectedIndex].value)"/>
            <p id="themedescription"></p>
            <p><img id="themeThumbnail" src="" class="img-responsive img-thumbnail" style="max-width: 30em" /></p>

        </div>
    </div>

    <button type="submit" class="btn btn-default"><spring:message code="createWebsite.button.save"/></button>

    <input class="btn" type="button" value="<spring:message code='generic.cancel'/>"
           onclick="window.location='<c:url value='/roller-ui/menu.rol'/>'"/>

</form:form>

<%-- ============================================================================== --%>

<script>

    document.forms[0].elements[0].focus();

    var saveButton;

    $( document ).ready(function() {

        saveButton = $("button[type='submit']");

        <c:choose>
        <c:when test="${bean.theme == null}">
        previewImage('${themes[0].id}');
        </c:when>
        <c:otherwise>
        previewImage('${bean.theme}');
        </c:otherwise>
        </c:choose>

    });

    function handlePreview(handle) {
        previewSpan = document.getElementById("handlePreview");
        var n1 = previewSpan.childNodes[0];
        var n2 = document.createTextNode(handle.value);
        if (handle.value == null) {
            previewSpan.appendChild(n2);
        } else {
            previewSpan.replaceChild(n2, n1);
        }
    }

    function previewImage(themeId) {
        $.ajax({ url: "${siteURL}/roller-ui/authoring/themedata",
            data: {theme:themeId}, success: function(data) {
                $('#themedescription').html(data.description);
                $('#themeThumbnail').attr('src','${siteURL}' + data.previewPath);
            }
        });
    }

</script>
