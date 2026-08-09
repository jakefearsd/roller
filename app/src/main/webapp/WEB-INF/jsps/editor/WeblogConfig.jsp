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

<form action="${pageContext.request.contextPath}/roller-ui/authoring/weblogConfig!save.rol" method="post" class="form-stacked">
<input type="hidden" name="weblog" value="${actionWeblog.handle}"/>

    <%-- ***** General settings ***** --%>

    <h3><spring:message code="websiteSettings.generalSettings"/></h3>

    <div class="row mb-3">
        <label class="col-sm-3 col-form-label"><spring:message code="websiteSettings.websiteTitle"/></label>
        <div class="col-sm-9">
            <input type="text" name="bean.name" value="${bean.name}" size="30" maxlength="40" class="form-control"/>
        </div>
    </div>

    <div class="row mb-3">
        <label class="col-sm-3 col-form-label"><spring:message code="generic.tagline"/></label>
        <div class="col-sm-9">
            <input type="text" name="bean.tagline" value="${bean.tagline}" size="30" maxlength="255" class="form-control"/>
        </div>
    </div>

    <div class="row mb-3">
        <label class="col-sm-3 col-form-label"><spring:message code="websiteSettings.icon"/></label>
        <div class="col-sm-9">
            <input type="text" name="bean.icon" value="${bean.icon}" size="30" maxlength="40" class="form-control"/>
        </div>
    </div>

    <div class="row mb-3">
        <label class="col-sm-3 col-form-label"><spring:message code="websiteSettings.about"/></label>
        <div class="col-sm-9">
            <textarea name="bean.about" rows="3" cols="40" class="form-control">${bean.about}</textarea>
        </div>
    </div>

    <div class="row mb-3">
        <label class="col-sm-3 col-form-label"><spring:message code="websiteSettings.emailAddress"/></label>
        <div class="col-sm-9">
            <input type="text" name="bean.emailAddress" value="${bean.emailAddress}" size="30" maxlength="40" class="form-control"/>
        </div>
    </div>

    <div class="row mb-3">
        <label class="col-sm-3 col-form-label"><spring:message code="websiteSettings.entryDisplayCount"/></label>
        <div class="col-sm-9">
            <input type="number" name="bean.entryDisplayCount" value="${bean.entryDisplayCount}" size="4" class="form-control"/>
        </div>
    </div>

    <div class="row mb-3">
        <div class="offset-sm-3 col-sm-9">
            <div class="form-check">
                <label class="form-check-label"><input type="checkbox" class="form-check-input" name="bean.active" value="true" ${bean.active ? 'checked' : ''}/> <spring:message code="websiteSettings.active"/></label>
            </div>
        </div>
    </div>

    <%-- ***** Language/i18n settings ***** --%>

    <h3><spring:message code="websiteSettings.languageSettings"/></h3>

    <div class="row mb-3">
        <label class="col-sm-3 col-form-label"><spring:message code="createWebsite.locale"/></label>
        <div class="col-sm-9">
            <select name="bean.locale" class="form-select">
                <c:forEach items="${localesList}" var="opt">
                    <option value="${opt}" ${opt == bean.locale ? 'selected' : ''}>${opt}</option>
                </c:forEach>
            </select>
        </div>
    </div>

    <div class="row mb-3">
        <label class="col-sm-3 col-form-label"><spring:message code="createWebsite.timezone"/></label>
        <div class="col-sm-9">
            <select name="bean.timeZone" class="form-select">
                <c:forEach items="${timeZonesList}" var="opt">
                    <option value="${opt}" ${opt == bean.timeZone ? 'selected' : ''}>${opt}</option>
                </c:forEach>
            </select>
        </div>
    </div>

    <div class="row mb-3">
        <div class="offset-sm-3 col-sm-9">
            <div class="form-check">
                <label class="form-check-label"><input type="checkbox" class="form-check-input" name="bean.enableMultiLang" value="true" ${bean.enableMultiLang ? 'checked' : ''}/> <spring:message code="websiteSettings.enableMultiLang"/></label>
            </div>
        </div>
    </div>

    <div class="row mb-3">
        <div class="offset-sm-3 col-sm-9">
            <div class="form-check">
                <label class="form-check-label"><input type="checkbox" class="form-check-input" name="bean.showAllLangs" value="true" ${bean.showAllLangs ? 'checked' : ''}/> <spring:message code="websiteSettings.showAllLangs"/></label>
            </div>
        </div>
    </div>

    <%-- ***** Comment settings ***** --%>

    <h3><spring:message code="websiteSettings.commentSettings"/></h3>

    <div class="row mb-3">
        <div class="offset-sm-3 col-sm-9">
            <div class="form-check">
                <label class="form-check-label"><input type="checkbox" class="form-check-input" name="bean.allowComments" value="true" ${bean.allowComments ? 'checked' : ''}/> <spring:message code="websiteSettings.allowComments"/></label>
            </div>
        </div>
    </div>

    <c:choose>
<c:when test="${rc:getBooleanProp('users.comments.emailnotify')}">
        <div class="row mb-3">
            <div class="offset-sm-3 col-sm-9">
                <div class="form-check">
                    <label class="form-check-label"><input type="checkbox" class="form-check-input" name="bean.emailComments" value="true" ${bean.emailComments ? 'checked' : ''}/> <spring:message code="websiteSettings.emailComments"/></label>
                </div>
            </div>
        </div>
    </c:when>
</c:choose>

    <div class="row mb-3">
        <div class="offset-sm-3 col-sm-9">
            <div class="form-check">
                <label class="form-check-label"><input type="checkbox" class="form-check-input" id="bean_requireAuthenticatedComments" name="bean.requireAuthenticatedComments" value="true" ${bean.requireAuthenticatedComments ? 'checked' : ''}/> <spring:message code="websiteSettings.requireAuthenticatedComments"/></label>
                <div class="form-text"><spring:message code="websiteSettings.requireAuthenticatedComments.tip"/></div>
            </div>
        </div>
    </div>

    <c:if test="${!rc:getBooleanProp('users.moderation.required')}">
        <div class="row mb-3">
            <div class="offset-sm-3 col-sm-9">
                <div class="form-check">
                    <label class="form-check-label"><input type="checkbox" class="form-check-input" name="bean.moderateComments" value="true" ${bean.moderateComments ? 'checked' : ''}/> <spring:message code="websiteSettings.moderateComments"/></label>
                </div>
            </div>
        </div>
    </c:if>

    <%-- ***** Default entry comment settings ***** --%>

    <h3><spring:message code="websiteSettings.defaultCommentSettings"/></h3>

    <div class="row mb-3">
        <label class="col-sm-3 col-form-label"><spring:message code="websiteSettings.applyCommentDefaults"/></label>
        <div class="col-sm-9">
            <select name="bean.defaultCommentDays" class="form-select">
                <c:forEach items="${commentDaysList}" var="opt">
                    <option value="${opt.key}" ${opt.key == bean.defaultCommentDays ? 'selected' : ''}>${opt.value}</option>
                </c:forEach>
            </select>
        </div>
    </div>

    <div class="row mb-3">
        <div class="offset-sm-3 col-sm-9">
            <div class="form-check">
                <label class="form-check-label"><input type="checkbox" class="form-check-input" name="bean.defaultAllowComments" value="true" ${bean.defaultAllowComments ? 'checked' : ''}/> <spring:message code="websiteSettings.defaultAllowComments"/></label>
            </div>
        </div>
    </div>

    <div class="row mb-3">
        <div class="offset-sm-3 col-sm-9">
            <div class="form-check">
                <label class="form-check-label"><input type="checkbox" class="form-check-input" name="bean.applyCommentDefaults" value="true" ${bean.applyCommentDefaults ? 'checked' : ''}/> <spring:message code="websiteSettings.applyCommentDefaults"/></label>
            </div>
        </div>
    </div>

    <%-- ***** Blogger API setting settings ***** --%>

    <h3><spring:message code="websiteSettings.bloggerApi"/></h3>

    <div class="row mb-3">
        <label class="col-sm-3 col-form-label"><spring:message code="websiteSettings.bloggerApiCategory"/></label>
        <div class="col-sm-9">
            <select name="bean.bloggerCategoryId" class="form-select">
                <c:forEach items="${weblogCategories}" var="opt">
                    <option value="${opt.id}" ${opt.id == bean.bloggerCategoryId ? 'selected' : ''}>${opt.name}</option>
                </c:forEach>
            </select>
        </div>
    </div>


    <%-- ***** Plugins "formatting" settings ***** --%>

    <h3><spring:message code="websiteSettings.formatting"/></h3>

    <c:choose>
<c:when test="${not empty pluginsList}">

        <c:forEach items="${pluginsList}" var="opt">
<div class="form-check"><label class="form-check-label"><input type="checkbox" class="form-check-input" name="bean.defaultPluginsArray" value="${opt.name}"/> ${opt.name}</label></div>
</c:forEach>

    </c:when>
<c:otherwise>
        <input type="hidden" name="defaultPlugins" value="${defaultPlugins}"/>
    </c:otherwise>
</c:choose>

    <%-- ***** Web analytics settings ***** --%>

    <c:if test="${rc:getBooleanProp('analytics.code.override.allowed') && !weblogAdminsUntrusted}">
        <h3><spring:message code="configForm.webAnalytics"/></h3>

        <div class="row mb-3">
            <label class="col-sm-3 col-form-label"><spring:message code="websiteSettings.analyticsTrackingCode"/></label>
            <div class="col-sm-9">
                <textarea name="bean.analyticsCode" rows="10" cols="70" class="form-control">${bean.analyticsCode}</textarea>
            </div>
        </div>
    </c:if>

    <%-- ***** Analytics settings ***** --%>

    <h3><spring:message code="websiteSettings.analyticsSettings"/></h3>

    <div class="row mb-3">
        <label class="col-sm-3 col-form-label"><spring:message code="websiteSettings.analyticsSiteId"/></label>
        <div class="col-sm-9">
            <input type="text" name="bean.analyticsSiteId" value="${fn:escapeXml(bean.analyticsSiteId)}" size="40" maxlength="64" class="form-control"/>
            <div class="form-text"><spring:message code="websiteSettings.analyticsSiteId.tip"/></div>
        </div>
    </div>

    <div class="row mb-3">
        <label class="col-sm-3 col-form-label"><spring:message code="websiteSettings.analyticsShareUrl"/></label>
        <div class="col-sm-9">
            <input type="text" name="bean.analyticsShareUrl" value="${fn:escapeXml(bean.analyticsShareUrl)}" size="40" maxlength="255" class="form-control"/>
            <div class="form-text"><spring:message code="websiteSettings.analyticsShareUrl.tip"/></div>
            <%-- A rejected save re-renders this page with the raw submitted bean, so
                 "not empty" alone would echo an unvalidated value (e.g. javascript:...)
                 as a live link. The prefix check below is the scheme allowlist for the
                 echoed link, case-insensitively mirroring myValidate's own check; "not
                 empty" short-circuits first so fn:toLowerCase never sees a null value. --%>
            <c:if test="${not empty bean.analyticsShareUrl
                    and (fn:startsWith(fn:toLowerCase(bean.analyticsShareUrl), 'https://')
                    or fn:startsWith(fn:toLowerCase(bean.analyticsShareUrl), 'http://'))}">
                <div class="form-text"><a href="${fn:escapeXml(bean.analyticsShareUrl)}" target="_blank" rel="noopener">${fn:escapeXml(bean.analyticsShareUrl)}</a></div>
            </c:if>
        </div>
    </div>

    <%-- ***** Newsletter settings ***** --%>

    <h3><spring:message code="websiteSettings.newsletterSettings"/></h3>

    <div class="row mb-3">
        <label class="col-sm-3 col-form-label"><spring:message code="websiteSettings.newsletterListUuid"/></label>
        <div class="col-sm-9">
            <input type="text" name="bean.newsletterListUuid" value="${fn:escapeXml(bean.newsletterListUuid)}" size="40" maxlength="64" class="form-control"/>
            <div class="form-text"><spring:message code="websiteSettings.newsletterListUuid.tip"/></div>
        </div>
    </div>

    <div class="control" style="margin-bottom:5em">
        <button type="submit" class="btn btn-success"><spring:message code="websiteSettings.button.update"/></button>
    </div>

<sec:csrfInput/>
</form>


<form action="${pageContext.request.contextPath}/roller-ui/authoring/weblogRemove.rol" method="post">
<input type="hidden" name="weblog" value="${actionWeblog.handle}"/>

    <h3><spring:message code="websiteSettings.removeWebsiteHeading"/></h3>
    <spring:message code="websiteSettings.removeWebsite"/><br/><br/>
    <div class="alert alert-danger" role="alert">
        <spring:message code="websiteSettings.removeWebsiteWarning"/>
    </div>
    <button type="submit" class="btn btn-danger"><spring:message code="websiteSettings.button.remove"/></button>

<sec:csrfInput/>
</form>
