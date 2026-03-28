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

<p class="subtitle">
    <spring:message code="websiteSettings.subtitle" arguments="${actionWeblog.handle}"/>
</p>

<form action="${pageContext.request.contextPath}/roller-ui/authoring/weblogConfig!save.rol" method="post" class="form-horizontal">
<input type="hidden" name="weblog" value="${actionWeblog.handle}"/>

    <%-- ***** General settings ***** --%>

    <h3><spring:message code="websiteSettings.generalSettings"/></h3>

    <input type="text" name="bean.name" value="${bean.name}" size="30" maxlength="40" class="form-control"/>

    <input type="text" name="bean.tagline" value="${bean.tagline}" size="30" maxlength="255" class="form-control"/>

    <input type="text" name="bean.icon" value="${bean.icon}" size="30" maxlength="40" class="form-control"/>

    <textarea name="bean.about" rows="3" cols="40 ">${bean.about}</textarea>

    <input type="text" name="bean.emailAddress" value="${bean.emailAddress}" size="30" maxlength="40" class="form-control"/>

    <select name="bean.editorPage" class="form-control">
<c:forEach items="${editorsList}" var="opt">
<option value="${opt.id}" ${opt.id == bean.editorPage ? 'selected' : ''}><spring:message code="${opt.name}"/></option>
</c:forEach>
</select>

    <input type="number" name="bean.entryDisplayCount" value="${bean.entryDisplayCount}" size="4" class="form-control"/>

    <input type="checkbox" name="bean.active" value="true" ${bean.active ? 'checked' : ''}/>

    <%-- ***** Language/i18n settings ***** --%>

    <h3><spring:message code="websiteSettings.languageSettings"/></h3>

    <select name="bean.locale" class="form-control">
<c:forEach items="${localesList}" var="opt">
<option value="${opt}" ${opt == bean.locale ? 'selected' : ''}>${opt}</option>
</c:forEach>
</select>

    <select name="bean.timeZone" class="form-control">
<c:forEach items="${timeZonesList}" var="opt">
<option value="${opt}" ${opt == bean.timeZone ? 'selected' : ''}>${opt}</option>
</c:forEach>
</select>

    <input type="checkbox" name="bean.enableMultiLang" value="true" ${bean.enableMultiLang ? 'checked' : ''}/>

    <input type="checkbox" name="bean.showAllLangs" value="true" ${bean.showAllLangs ? 'checked' : ''}/>

    <%-- ***** Comment settings ***** --%>

    <h3><spring:message code="websiteSettings.commentSettings"/></h3>

    <input type="checkbox" name="bean.allowComments" value="true" ${bean.allowComments ? 'checked' : ''}/>

    <c:choose>
<c:when test="${rc:getBooleanProp('users.comments.emailnotify')}">
        <input type="checkbox" name="bean.emailComments" value="true" ${bean.emailComments ? 'checked' : ''}/>
    </c:if>

    <c:if test="${!rc:getBooleanProp('users.moderation.required')}">
        <input type="checkbox" name="bean.moderateComments" value="true" ${bean.moderateComments ? 'checked' : ''}/>
    </c:if>

    <%-- ***** Default entry comment settings ***** --%>

    <h3><spring:message code="websiteSettings.defaultCommentSettings"/></h3>

    <select name="bean.defaultCommentDays" class="form-control">
<c:forEach items="${commentDaysList}" var="opt">
<option value="${opt.key}" ${opt.key == bean.defaultCommentDays ? 'selected' : ''}>${opt.value}</option>
</c:forEach>
</select>

    <input type="checkbox" name="bean.defaultAllowComments" value="true" ${bean.defaultAllowComments ? 'checked' : ''}/>

    <input type="checkbox" name="bean.applyCommentDefaults" value="true" ${bean.applyCommentDefaults ? 'checked' : ''}/>

    <%-- ***** Blogger API setting settings ***** --%>

    <h3><spring:message code="websiteSettings.bloggerApi"/></h3>

    <select name="bean.bloggerCategoryId" class="form-control">
<c:forEach items="${weblogCategories}" var="opt">
<option value="${opt.id}" ${opt.id == bean.bloggerCategoryId ? 'selected' : ''}>${opt.name}</option>
</c:forEach>
</select>

    <input type="checkbox" name="bean.enableBloggerApi" value="true" ${bean.enableBloggerApi ? 'checked' : ''}/>

    <%-- ***** Plugins "formatting" settings ***** --%>

    <h3><spring:message code="websiteSettings.formatting"/></h3>

    <c:if test="${!pluginsList.isEmpty}">

        <c:forEach items="${pluginsList}" var="opt">
<label><input type="checkbox" name="bean.defaultPluginsArray" value="${opt.name}"/> ${opt.name}</label>
</c:forEach>

    </c:when>
<c:otherwise>
        <input type="hidden" name="defaultPlugins" value="${defaultPlugins}"/>
    </c:otherwise>
</c:choose><%-- ***** Spam prevention settings ***** --%>

    <h3><spring:message code="websiteSettings.spamPrevention"/></h3>

    <textarea name="bean.bannedwordslist" rows="7" cols="40">${bean.bannedwordslist}</textarea>

    <%-- ***** Web analytics settings ***** --%>

    <c:if test="${rc:getBooleanProp('analytics.code.override.allowed') && !weblogAdminsUntrusted}">
        <h3><spring:message code="configForm.webAnalytics"/></h3>

        <textarea name="bean.analyticsCode" rows="10" cols="70">${bean.analyticsCode}</textarea>
    </c:if>

    <div class="control" style="margin-bottom:5em">
        <button type="submit" class="btn btn-success"><spring:message code="websiteSettings.button.update"/></button>
    </div>

<sec:csrfInput/>
</form>


<form action="${pageContext.request.contextPath}/roller-ui/authoring/weblogRemove.rol" method="post" class="form-horizontal">
<input type="hidden" name="weblog" value="${actionWeblog.handle}"/>

    <h3><spring:message code="websiteSettings.removeWebsiteHeading"/></h3>
    <spring:message code="websiteSettings.removeWebsite"/><br/><br/>
    <div class="alert alert-danger" role="alert">
        <spring:message code="websiteSettings.removeWebsiteWarning"/>
    </div>
    <button type="submit" class="btn btn-danger"><spring:message code="websiteSettings.button.remove"/></button>

<sec:csrfInput/>
</form>
