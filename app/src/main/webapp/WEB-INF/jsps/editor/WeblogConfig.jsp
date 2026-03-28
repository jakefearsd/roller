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

    <div class="form-group">
        <label class="col-md-3 control-label"><spring:message code="websiteSettings.websiteTitle"/></label>
        <div class="col-md-9">
            <input type="text" name="bean.name" value="${bean.name}" size="30" maxlength="40" class="form-control"/>
        </div>
    </div>

    <div class="form-group">
        <label class="col-md-3 control-label"><spring:message code="generic.tagline"/></label>
        <div class="col-md-9">
            <input type="text" name="bean.tagline" value="${bean.tagline}" size="30" maxlength="255" class="form-control"/>
        </div>
    </div>

    <div class="form-group">
        <label class="col-md-3 control-label"><spring:message code="websiteSettings.icon"/></label>
        <div class="col-md-9">
            <input type="text" name="bean.icon" value="${bean.icon}" size="30" maxlength="40" class="form-control"/>
        </div>
    </div>

    <div class="form-group">
        <label class="col-md-3 control-label"><spring:message code="websiteSettings.about"/></label>
        <div class="col-md-9">
            <textarea name="bean.about" rows="3" cols="40" class="form-control">${bean.about}</textarea>
        </div>
    </div>

    <div class="form-group">
        <label class="col-md-3 control-label"><spring:message code="websiteSettings.emailAddress"/></label>
        <div class="col-md-9">
            <input type="text" name="bean.emailAddress" value="${bean.emailAddress}" size="30" maxlength="40" class="form-control"/>
        </div>
    </div>

    <div class="form-group">
        <label class="col-md-3 control-label"><spring:message code="websiteSettings.editor"/></label>
        <div class="col-md-9">
            <select name="bean.editorPage" class="form-control">
                <c:forEach items="${editorsList}" var="opt">
                    <option value="${opt.id}" ${opt.id == bean.editorPage ? 'selected' : ''}><spring:message code="${opt.name}"/></option>
                </c:forEach>
            </select>
        </div>
    </div>

    <div class="form-group">
        <label class="col-md-3 control-label"><spring:message code="websiteSettings.entryDisplayCount"/></label>
        <div class="col-md-9">
            <input type="number" name="bean.entryDisplayCount" value="${bean.entryDisplayCount}" size="4" class="form-control"/>
        </div>
    </div>

    <div class="form-group">
        <div class="col-md-offset-3 col-md-9">
            <label><input type="checkbox" name="bean.active" value="true" ${bean.active ? 'checked' : ''}/> <spring:message code="websiteSettings.active"/></label>
        </div>
    </div>

    <%-- ***** Language/i18n settings ***** --%>

    <h3><spring:message code="websiteSettings.languageSettings"/></h3>

    <div class="form-group">
        <label class="col-md-3 control-label"><spring:message code="createWebsite.locale"/></label>
        <div class="col-md-9">
            <select name="bean.locale" class="form-control">
                <c:forEach items="${localesList}" var="opt">
                    <option value="${opt}" ${opt == bean.locale ? 'selected' : ''}>${opt}</option>
                </c:forEach>
            </select>
        </div>
    </div>

    <div class="form-group">
        <label class="col-md-3 control-label"><spring:message code="createWebsite.timezone"/></label>
        <div class="col-md-9">
            <select name="bean.timeZone" class="form-control">
                <c:forEach items="${timeZonesList}" var="opt">
                    <option value="${opt}" ${opt == bean.timeZone ? 'selected' : ''}>${opt}</option>
                </c:forEach>
            </select>
        </div>
    </div>

    <div class="form-group">
        <div class="col-md-offset-3 col-md-9">
            <label><input type="checkbox" name="bean.enableMultiLang" value="true" ${bean.enableMultiLang ? 'checked' : ''}/> <spring:message code="websiteSettings.enableMultiLang"/></label>
        </div>
    </div>

    <div class="form-group">
        <div class="col-md-offset-3 col-md-9">
            <label><input type="checkbox" name="bean.showAllLangs" value="true" ${bean.showAllLangs ? 'checked' : ''}/> <spring:message code="websiteSettings.showAllLangs"/></label>
        </div>
    </div>

    <%-- ***** Comment settings ***** --%>

    <h3><spring:message code="websiteSettings.commentSettings"/></h3>

    <div class="form-group">
        <div class="col-md-offset-3 col-md-9">
            <label><input type="checkbox" name="bean.allowComments" value="true" ${bean.allowComments ? 'checked' : ''}/> <spring:message code="websiteSettings.allowComments"/></label>
        </div>
    </div>

    <c:choose>
<c:when test="${rc:getBooleanProp('users.comments.emailnotify')}">
        <div class="form-group">
            <div class="col-md-offset-3 col-md-9">
                <label><input type="checkbox" name="bean.emailComments" value="true" ${bean.emailComments ? 'checked' : ''}/> <spring:message code="websiteSettings.emailComments"/></label>
            </div>
        </div>
    </c:when>
</c:choose>

    <c:if test="${!rc:getBooleanProp('users.moderation.required')}">
        <div class="form-group">
            <div class="col-md-offset-3 col-md-9">
                <label><input type="checkbox" name="bean.moderateComments" value="true" ${bean.moderateComments ? 'checked' : ''}/> <spring:message code="websiteSettings.moderateComments"/></label>
            </div>
        </div>
    </c:if>

    <%-- ***** Default entry comment settings ***** --%>

    <h3><spring:message code="websiteSettings.defaultCommentSettings"/></h3>

    <div class="form-group">
        <label class="col-md-3 control-label"><spring:message code="websiteSettings.applyCommentDefaults"/></label>
        <div class="col-md-9">
            <select name="bean.defaultCommentDays" class="form-control">
                <c:forEach items="${commentDaysList}" var="opt">
                    <option value="${opt.key}" ${opt.key == bean.defaultCommentDays ? 'selected' : ''}>${opt.value}</option>
                </c:forEach>
            </select>
        </div>
    </div>

    <div class="form-group">
        <div class="col-md-offset-3 col-md-9">
            <label><input type="checkbox" name="bean.defaultAllowComments" value="true" ${bean.defaultAllowComments ? 'checked' : ''}/> <spring:message code="websiteSettings.defaultAllowComments"/></label>
        </div>
    </div>

    <div class="form-group">
        <div class="col-md-offset-3 col-md-9">
            <label><input type="checkbox" name="bean.applyCommentDefaults" value="true" ${bean.applyCommentDefaults ? 'checked' : ''}/> <spring:message code="websiteSettings.applyCommentDefaults"/></label>
        </div>
    </div>

    <%-- ***** Blogger API setting settings ***** --%>

    <h3><spring:message code="websiteSettings.bloggerApi"/></h3>

    <div class="form-group">
        <label class="col-md-3 control-label"><spring:message code="websiteSettings.bloggerApiCategory"/></label>
        <div class="col-md-9">
            <select name="bean.bloggerCategoryId" class="form-control">
                <c:forEach items="${weblogCategories}" var="opt">
                    <option value="${opt.id}" ${opt.id == bean.bloggerCategoryId ? 'selected' : ''}>${opt.name}</option>
                </c:forEach>
            </select>
        </div>
    </div>

    <div class="form-group">
        <div class="col-md-offset-3 col-md-9">
            <label><input type="checkbox" name="bean.enableBloggerApi" value="true" ${bean.enableBloggerApi ? 'checked' : ''}/> <spring:message code="websiteSettings.enableBloggerApi"/></label>
        </div>
    </div>

    <%-- ***** Plugins "formatting" settings ***** --%>

    <h3><spring:message code="websiteSettings.formatting"/></h3>

    <c:choose>
<c:when test="${not empty pluginsList}">

        <c:forEach items="${pluginsList}" var="opt">
<label><input type="checkbox" name="bean.defaultPluginsArray" value="${opt.name}"/> ${opt.name}</label>
</c:forEach>

    </c:when>
<c:otherwise>
        <input type="hidden" name="defaultPlugins" value="${defaultPlugins}"/>
    </c:otherwise>
</c:choose><%-- ***** Spam prevention settings ***** --%>

    <h3><spring:message code="websiteSettings.spamPrevention"/></h3>

    <div class="form-group">
        <label class="col-md-3 control-label"><spring:message code="websiteSettings.bannedWordsList"/></label>
        <div class="col-md-9">
            <textarea name="bean.bannedwordslist" rows="7" cols="40" class="form-control">${bean.bannedwordslist}</textarea>
        </div>
    </div>

    <%-- ***** Web analytics settings ***** --%>

    <c:if test="${rc:getBooleanProp('analytics.code.override.allowed') && !weblogAdminsUntrusted}">
        <h3><spring:message code="configForm.webAnalytics"/></h3>

        <div class="form-group">
            <label class="col-md-3 control-label"><spring:message code="websiteSettings.analyticsTrackingCode"/></label>
            <div class="col-md-9">
                <textarea name="bean.analyticsCode" rows="10" cols="70" class="form-control">${bean.analyticsCode}</textarea>
            </div>
        </div>
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
