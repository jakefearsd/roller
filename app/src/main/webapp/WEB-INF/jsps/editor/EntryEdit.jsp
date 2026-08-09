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

<%-- Prevent annoying scrolling. taken from http://stackoverflow.com/a/10548809/3591946 --%>
<script type="text/javascript">
    $().ready(function () {
        $("a[href='#'][data-bs-toggle='collapse']").click(function (e) {
            e.preventDefault();
        });
    });
</script>

<%-- Titling, processing actions different between entry add and edit --%>
<c:choose>
<c:when test="${actionName == 'entryEdit'}">
    <c:set var="subtitleKey">weblogEdit.subtitle.editEntry</c:set>
    <c:set var="mainAction">entryEdit</c:set>
</c:when>
<c:otherwise>
    <c:set var="subtitleKey">weblogEdit.subtitle.newEntry</c:set>
    <c:set var="mainAction">entryAdd</c:set>
</c:otherwise>
</c:choose><p class="subtitle">
    <spring:message code="${subtitleKey}" arguments="${actionWeblog.handle}"/>
</p>

<form id="entry" method="post" class="form-stacked">
<input type="hidden" name="weblog" value="${actionWeblog.handle}"/>
    <input type="hidden" name="bean.status" value="${bean.status}"/>
    <c:choose>
<c:when test="${actionName == 'entryEdit'}">
        <input type="hidden" name="bean.id" value="${bean.id}"/>
    </c:when>
</c:choose>

    <%-- ================================================================== --%>
    <%-- Title, category, dates and other metadata --%>

    <%-- title --%>
    <div class="row mb-3">
        <label class="col-sm-3 col-form-label"><spring:message code="weblogEdit.title"/></label>
        <div class="col-sm-9">
            <input type="text" name="bean.title" value="${bean.title}" maxlength="255" tabindex="1" class="form-control"/>
        </div>
    </div>

    <%-- permalink --%>
    <c:if test="${actionName == 'entryEdit'}">
        <div class="row mb-3">

            <label class="col-sm-3 col-form-label" for="entry_bean_permalink">
                <spring:message code="weblogEdit.permaLink"/>
            </label>

            <div class="col-sm-9">
                <p class="form-control-plaintext">
                    <c:choose>
<c:when test="${bean.published}">
                        <a id="entry_bean_permalink" href='${entry.permalink}'>
                            ${entry.permalink}
                        </a>
                        <img src='<c:url value="/images/launch-link.png"/>'/>
                    </c:when>
<c:otherwise>
                        ${entry.permalink}
                    </c:otherwise>
</c:choose></p>
            </div>

        </div>
    </c:if>

    <%-- tags --%>
    <div class="row mb-3">
        <label class="col-sm-3 col-form-label"><spring:message code="weblogEdit.tags"/></label>
        <div class="col-sm-9">
            <input type="text" name="bean.tagsAsString" value="${bean.tagsAsString}" id="entry_bean_tagsAsString" maxlength="255" tabindex="2" class="form-control"/>
        </div>
    </div>

    <%-- category --%>
    <div class="row mb-3">
        <label class="col-sm-3 col-form-label"><spring:message code="weblogEdit.category"/></label>
        <div class="col-sm-9">
            <select name="bean.categoryId" class="form-select" tabindex="3">
<c:forEach items="${categories}" var="opt">
<option value="${opt.id}" ${opt.id == bean.categoryId ? 'selected' : ''}>${opt.name}</option>
</c:forEach>
</select>
        </div>
    </div>

    <c:choose>
<c:when test="${actionWeblog.enableMultiLang}">
        <%-- language / locale --%>
        <div class="row mb-3">
            <label class="col-sm-3 col-form-label"><spring:message code="weblogEdit.locale"/></label>
            <div class="col-sm-9">
                <select name="bean.locale" class="form-select" tabindex="4">
<c:forEach items="${localesList}" var="opt">
<option value="${opt}" ${opt == bean.locale ? 'selected' : ''}>${opt}</option>
</c:forEach>
</select>
            </div>
        </div>
    </c:when>
<c:otherwise>
        <input type="hidden" name="bean.locale" value="${bean.locale}"/>
    </c:otherwise>
</c:choose><%-- status --%>
    <div class="row mb-3">
        <label class="col-sm-3 col-form-label" for="weblogEdit.status"><spring:message code="weblogEdit.status"/></label>

        <div class="col-sm-9">

            <p class="form-control-plaintext">
                <c:choose>
                <c:when test="${bean.published}">
                    <span class="badge bg-success">
                        <spring:message code="weblogEdit.published"/>
                        (<spring:message code="weblogEdit.updateTime"/>
                        <fmt:formatDate value="${entry.updateTime}"/>)
                    </span>
                </c:when>
                <c:when test="${bean.draft}">
                    <span class="badge bg-info">
                        <spring:message code="weblogEdit.draft"/>
                        (<spring:message code="weblogEdit.updateTime"/>
                        <fmt:formatDate value="${entry.updateTime}"/>)
                    </span>
                </c:when>
                <c:when test="${bean.pending}">
                    <span class="badge bg-warning">
                        <spring:message code="weblogEdit.pending"/>
                        (<spring:message code="weblogEdit.updateTime"/>
                        <fmt:formatDate value="${entry.updateTime}"/>)
                    </span>
                </c:when>
                <c:when test="${bean.scheduled}">
                    <span class="badge bg-info">
                        <spring:message code="weblogEdit.scheduled"/>
                        (<spring:message code="weblogEdit.updateTime"/>
                        <fmt:formatDate value="${entry.updateTime}"/>)
                    </span>
                </c:when>
                <c:otherwise>
                    <span class="badge bg-danger"><spring:message code="weblogEdit.unsaved"/></span>
                </c:otherwise>
                </c:choose>
            </p>

        </div>

    </div>


    <div id="accordion">

            <%-- Weblog editor --%>

        <jsp:include page="/WEB-INF/jsps/editor/EntryEditor.jsp"/>

            <%-- Plugins --%>

        <c:choose>
<c:when test="${not empty entryPlugins}">

            <div class="card" id="panel-plugins">
                <div class="card-header">

                    <h4 class="card-title">
                        <a class="collapsed" data-bs-toggle="collapse" data-bs-target="#collapsePlugins" href="#">
                            <spring:message code="weblogEdit.pluginsToApply"/> </a>
                    </h4>

                </div>
                <div id="collapsePlugins" class="collapse">
                    <div class="card-body">

                        <c:forEach items="${entryPlugins}" var="opt">
<div class="form-check"><label class="form-check-label"><input type="checkbox" class="form-check-input" name="bean.plugins" value="${opt.name}"/> ${opt.name}</label></div>
</c:forEach>

                    </div>
                </div>
            </div>

        </c:when>
</c:choose>

            <%-- Advanced settings --%>

        <div class="card" id="panel-settings">
            <div class="card-header">

                <h4 class="card-title">
                    <a class="collapsed" data-bs-toggle="collapse" data-bs-parent="#collapseAdvanced"
                       href="#collapseAdvanced">
                        <spring:message code="weblogEdit.miscSettings"/> </a>
                </h4>

            </div>
            <div id="collapseAdvanced" class="collapse">
                <div class="card-body">

                    <div class="row mb-3">

                        <label class="col-form-label col-sm-3"><spring:message code="weblogEdit.pubTime"/></label>

                        <div class="col-sm-9">

                            <select name="bean.hours">
<c:forEach items="${hoursList}" var="opt">
<option value="${opt}" ${opt == bean.hours ? 'selected' : ''}>${opt}</option>
</c:forEach>
</select> :
                            <select name="bean.minutes">
<c:forEach items="${minutesList}" var="opt">
<option value="${opt}" ${opt == bean.minutes ? 'selected' : ''}>${opt}</option>
</c:forEach>
</select> :
                            <select name="bean.seconds">
<c:forEach items="${secondsList}" var="opt">
<option value="${opt}" ${opt == bean.seconds ? 'selected' : ''}>${opt}</option>
</c:forEach>
</select> <br/>

                            <img src="<c:url value='/roller-ui/images/spacer.png'/>"
                                 alt="spacer" style="min-height: 0.3em"/>

                            <div class="input-group">
                                <input type="text" name="bean.dateString" value="${bean.dateString}" readonly class="date-picker form-control" style="width:15em"/>
                                <label for="bean.dateString" class="input-group-text" style="width:3em">
                                    <span class="bi bi-calendar"></span>
                                </label>
                            </div>

                            ${actionWeblog.timeZone}

                        </div>

                    </div>

                    <%-- Allow comments. This control was missing entirely, and
                         EntryBean.allowComments is a primitive defaulting to
                         false -- so every entry saved through this editor had
                         comments switched off no matter what the weblog's
                         default said, and editing a post silently closed
                         comments that were open. --%>
                    <div class="row mb-3">
                        <div class="offset-sm-3 col-sm-9">
                            <div class="form-check">
                                <label class="form-check-label"><input type="checkbox" class="form-check-input" id="entry_bean_allowComments" name="bean.allowComments" value="true" ${bean.allowComments ? 'checked' : ''}/> <spring:message code="weblogEdit.allowComments"/></label>
                            </div>
                        </div>
                    </div>

                    <div class="row mb-3">
                        <label class="col-sm-3 col-form-label"><spring:message code="weblogEdit.commentDays"/></label>
                        <div class="col-sm-9">
                            <select name="bean.commentDays" class="form-select">
<c:forEach items="${commentDaysList}" var="opt">
<option value="${opt.key}" ${opt.key == bean.commentDays ? 'selected' : ''}>${opt.value}</option>
</c:forEach>
</select>
                        </div>
                    </div>

                    <div class="row mb-3">
                        <div class="offset-sm-3 col-sm-9">
                            <div class="form-check">
                                <label class="form-check-label"><input type="checkbox" class="form-check-input" name="bean.rightToLeft" value="true" ${bean.rightToLeft ? 'checked' : ''}/> <spring:message code="weblogEdit.rightToLeft"/></label>
                            </div>
                        </div>
                    </div>

                        <%-- global admin can pin items to front page weblog --%>
                    <c:if test="${authenticatedUser.hasGlobalPermission('admin')}">
                        <div class="row mb-3">
                            <div class="offset-sm-3 col-sm-9">
                                <div class="form-check">
                                    <label class="form-check-label"><input type="checkbox" class="form-check-input" name="bean.pinnedToMain" value="true" ${bean.pinnedToMain ? 'checked' : ''}/> <spring:message code="weblogEdit.pinnedToMain"/></label>
                                </div>
                            </div>
                        </div>
                    </c:if>

                    <div class="row mb-3">
                        <label class="col-sm-3 col-form-label"><spring:message code="weblogEdit.enclosureURL"/></label>
                        <div class="col-sm-9">
                            <input type="text" name="bean.enclosureURL" value="${bean.enclosureURL}" maxlength="255" class="form-control"/>
                        </div>
                    </div>

                    <c:if test="${actionName == 'entryEdit'}">
                        <c:if test="${not empty bean.enclosureURL}">
                            <spring:message code="weblogEdit.enclosureType"/>:
                            ${entry.findEntryAttribute("att_mediacast_type")}
                            <spring:message code="weblogEdit.enclosureLength"/>:
                            ${entry.findEntryAttribute("att_mediacast_length")}
                        </c:if>
                    </c:if>

                </div>

            </div>

        </div>

            <%-- SEO and social sharing --%>

        <div class="card" id="panel-seo">
            <div class="card-header">

                <h4 class="card-title">
                    <a class="collapsed" data-bs-toggle="collapse" data-bs-target="#collapseSeo" href="#">
                        <spring:message code="weblogEdit.seoSettings"/> </a>
                </h4>

            </div>
            <div id="collapseSeo" class="collapse">
                <div class="card-body">

                    <%-- meta title (falls back to the entry title on the public page) --%>
                    <div class="row mb-3">
                        <label class="col-sm-3 col-form-label" for="seo_metaTitle"><spring:message code="weblogEdit.metaTitle"/></label>
                        <div class="col-sm-9">
                            <input type="text" id="seo_metaTitle" name="bean.metaTitle" value="${bean.metaTitle}" maxlength="255" class="form-control"/>
                        </div>
                    </div>

                    <%-- meta description (the entry's searchDescription column) --%>
                    <div class="row mb-3">
                        <label class="col-sm-3 col-form-label" for="seo_metaDescription"><spring:message code="weblogEdit.metaDescription"/></label>
                        <div class="col-sm-9">
                            <input type="text" id="seo_metaDescription" name="bean.searchDescription" value="${bean.searchDescription}" maxlength="255" class="form-control"/>
                        </div>
                    </div>

                    <%-- lightweight search-snippet preview, fed by JS from the title/description fields --%>
                    <div class="row mb-3">
                        <label class="col-sm-3 col-form-label"><spring:message code="weblogEdit.snippetPreview"/></label>
                        <div class="col-sm-9">
                            <div id="seo_snippet_preview" class="border rounded p-2 bg-body">
                                <div id="seo_snippet_title" style="color:#1a0dab; font-size:1.15em;"></div>
                                <div id="seo_snippet_url" style="color:#006621; font-size:0.85em;"><c:if test="${actionName == 'entryEdit'}">${entry.permalink}</c:if></div>
                                <div id="seo_snippet_description" style="color:#545454; font-size:0.9em;"></div>
                            </div>
                        </div>
                    </div>

                    <%-- featured image --%>
                    <div class="row mb-3">
                        <label class="col-sm-3 col-form-label"><spring:message code="weblogEdit.featuredImage"/></label>
                        <div class="col-sm-9">
                            <input type="hidden" id="seo_featuredImageId" name="bean.featuredImageId" value="${bean.featuredImageId}"/>
                            <div class="mb-2">
                                <img id="seo_featuredImage_preview" src="${featuredImageThumbnailUrl}" alt=""
                                     style="max-height:120px;${empty featuredImageThumbnailUrl ? 'display:none;' : ''}"/>
                            </div>
                            <button type="button" class="btn btn-secondary btn-sm" onclick="openImagePicker('featuredImage')"><spring:message code="weblogEdit.chooseImage"/></button>
                            <button type="button" class="btn btn-outline-danger btn-sm" id="seo_featuredImage_clear"
                                    style="${empty bean.featuredImageId ? 'display:none;' : ''}"
                                    onclick="clearPickedImage('featuredImage')"><spring:message code="weblogEdit.clearImage"/></button>
                        </div>
                    </div>

                    <%-- social share (Open Graph) image; when unset the featured image is used --%>
                    <div class="row mb-3">
                        <label class="col-sm-3 col-form-label"><spring:message code="weblogEdit.ogImage"/></label>
                        <div class="col-sm-9">
                            <input type="hidden" id="seo_ogImageId" name="bean.ogImageId" value="${bean.ogImageId}"/>
                            <div class="mb-2">
                                <img id="seo_ogImage_preview" src="${ogImageThumbnailUrl}" alt=""
                                     style="max-height:120px;${empty ogImageThumbnailUrl ? 'display:none;' : ''}"/>
                            </div>
                            <button type="button" class="btn btn-secondary btn-sm" onclick="openImagePicker('ogImage')"><spring:message code="weblogEdit.chooseImage"/></button>
                            <button type="button" class="btn btn-outline-danger btn-sm" id="seo_ogImage_clear"
                                    style="${empty bean.ogImageId ? 'display:none;' : ''}"
                                    onclick="clearPickedImage('ogImage')"><spring:message code="weblogEdit.clearImage"/></button>
                        </div>
                    </div>

                    <%-- canonical URL override --%>
                    <div class="row mb-3">
                        <label class="col-sm-3 col-form-label" for="seo_canonicalUrl"><spring:message code="weblogEdit.canonicalUrl"/></label>
                        <div class="col-sm-9">
                            <input type="text" id="seo_canonicalUrl" name="bean.canonicalUrl" value="${bean.canonicalUrl}" maxlength="255" class="form-control"/>
                        </div>
                    </div>

                    <%-- noindex --%>
                    <div class="row mb-3">
                        <div class="offset-sm-3 col-sm-9">
                            <div class="form-check">
                                <label class="form-check-label"><input type="checkbox" class="form-check-input" id="seo_noindex" name="bean.noindex" value="true" ${bean.noindex ? 'checked' : ''}/> <spring:message code="weblogEdit.noindex"/></label>
                            </div>
                        </div>
                    </div>

                    <%-- structured data: the schema.org type this entry's head declares.
                         The event rows below are shown/hidden by
                         updateSeoJsonLdRows() and stay in the DOM when hidden, so
                         switching type never silently drops dates the author
                         already entered. --%>
                    <div class="row mb-3">
                        <label class="col-sm-3 col-form-label" for="seo_jsonldType"><spring:message code="weblogEdit.jsonLdType"/></label>
                        <div class="col-sm-9">
                            <select id="seo_jsonldType" name="bean.jsonLdType" class="form-select">
                                <option value="BLOG_POSTING" ${empty bean.jsonLdType or bean.jsonLdType == 'BLOG_POSTING' ? 'selected' : ''}><spring:message code="weblogEdit.jsonLdType.blogPosting"/></option>
                                <option value="TOURIST_ATTRACTION" ${bean.jsonLdType == 'TOURIST_ATTRACTION' ? 'selected' : ''}><spring:message code="weblogEdit.jsonLdType.touristAttraction"/></option>
                                <option value="TOURIST_TRIP" ${bean.jsonLdType == 'TOURIST_TRIP' ? 'selected' : ''}><spring:message code="weblogEdit.jsonLdType.touristTrip"/></option>
                                <option value="EVENT" ${bean.jsonLdType == 'EVENT' ? 'selected' : ''}><spring:message code="weblogEdit.jsonLdType.event"/></option>
                                <option value="FAQ_PAGE" ${bean.jsonLdType == 'FAQ_PAGE' ? 'selected' : ''}><spring:message code="weblogEdit.jsonLdType.faqPage"/></option>
                            </select>
                            <div class="form-text"><spring:message code="weblogEdit.jsonLdType.tip"/></div>
                        </div>
                    </div>

                    <%-- coordinates: a TouristAttraction's geo, and the default centre
                         for a bare [map]. Always shown, because the [map] use has
                         nothing to do with the structured-data type -- an ordinary
                         blog post may well want a map of where it happened. --%>
                    <div id="seo_row_geo">
                        <div class="row mb-3">
                            <label class="col-sm-3 col-form-label" for="seo_geoLatitude"><spring:message code="weblogEdit.geoLatitude"/></label>
                            <div class="col-sm-9">
                                <input type="number" step="any" min="-90" max="90" id="seo_geoLatitude" name="bean.geoLatitude" value="${bean.geoLatitude}" class="form-control"/>
                            </div>
                        </div>
                        <div class="row mb-3">
                            <label class="col-sm-3 col-form-label" for="seo_geoLongitude"><spring:message code="weblogEdit.geoLongitude"/></label>
                            <div class="col-sm-9">
                                <input type="number" step="any" min="-180" max="180" id="seo_geoLongitude" name="bean.geoLongitude" value="${bean.geoLongitude}" class="form-control"/>
                                <div class="form-text"><spring:message code="weblogEdit.geoCoordinates.tip"/></div>
                            </div>
                        </div>
                    </div>

                    <%-- event schedule and venue --%>
                    <div id="seo_row_event" style="${bean.jsonLdType == 'EVENT' ? '' : 'display:none;'}">
                        <div class="row mb-3">
                            <label class="col-sm-3 col-form-label" for="seo_eventStart"><spring:message code="weblogEdit.eventStart"/></label>
                            <div class="col-sm-9">
                                <input type="datetime-local" id="seo_eventStart" name="bean.eventStartLocal" value="${bean.eventStartLocal}" class="form-control"/>
                                <div class="form-text"><spring:message code="weblogEdit.eventStart.tip"/></div>
                            </div>
                        </div>
                        <div class="row mb-3">
                            <label class="col-sm-3 col-form-label" for="seo_eventEnd"><spring:message code="weblogEdit.eventEnd"/></label>
                            <div class="col-sm-9">
                                <input type="datetime-local" id="seo_eventEnd" name="bean.eventEndLocal" value="${bean.eventEndLocal}" class="form-control"/>
                            </div>
                        </div>
                        <div class="row mb-3">
                            <label class="col-sm-3 col-form-label" for="seo_eventLocation"><spring:message code="weblogEdit.eventLocation"/></label>
                            <div class="col-sm-9">
                                <input type="text" id="seo_eventLocation" name="bean.eventLocation" value="${bean.eventLocation}" maxlength="255" class="form-control"/>
                            </div>
                        </div>
                    </div>

                </div>
            </div>
        </div>

    </div>


    <%-- ================================================================== --%>
    <%-- The button box --%>

    <%-- save draft --%>
    <button type="submit" class="btn btn-warning" formaction="${pageContext.request.contextPath}/roller-ui/authoring/${mainAction}!saveDraft.rol"><spring:message code="weblogEdit.save"/></button>

    <c:if test="${actionName == 'entryEdit'}">

        <%-- preview mode --%>
        <input class="btn btn-secondary" type="button" name="fullPreview"
               value="<spring:message code="weblogEdit.fullPreviewMode"/>"
               onclick="fullPreviewMode()"/>
    </c:if>
    <c:choose>
<c:when test="${userAnAuthor}">

        <%-- publish --%>
        <button type="submit" class="btn btn-success" formaction="${pageContext.request.contextPath}/roller-ui/authoring/${mainAction}!publish.rol"><spring:message code="weblogEdit.post"/></button>
    </c:when>
<c:otherwise>

        <%-- submit for review --%>
        <button type="submit" class="btn btn-info" formaction="${pageContext.request.contextPath}/roller-ui/authoring/${mainAction}!publish.rol"><spring:message code="weblogEdit.submitForReview"/></button>
    </c:otherwise>
</c:choose><c:if test="${actionName == 'entryEdit'}">

        <%-- delete --%>
        <span style="float:right">
            <input class="btn btn-danger" type="button"
                   value="<spring:message code="weblogEdit.deleteEntry"/>"
                   onclick="showDeleteModal('${entry.id}', '${entry.title}' )">
        </span>
    </c:if>


<sec:csrfInput/>
</form>


<%-- ========================================================================================== --%>

<%-- per-entry share link: create (optional password), copy URL, revoke.
     Outside the main entry form -- these are separate POST actions with
     their own CSRF inputs, and forms must not nest. Only a saved entry can
     be shared, so the card renders for entryEdit only. --%>

<c:if test="${actionName == 'entryEdit'}">
    <div id="entryShareCard" class="card mt-3">
        <div class="card-header"><spring:message code="shareLink.title"/></div>
        <div class="card-body">
            <c:choose>
                <c:when test="${not empty entryShareLink}">
                    <div class="input-group mb-2" style="max-width: 44em">
                        <input type="text" readonly id="entryShareLinkUrl" class="form-control"
                               value="${entryShareURL}"/>
                        <button type="button" class="btn btn-secondary" id="copyEntryShareLinkButton"
                                onclick="copyEntryShareLink()"><spring:message code="shareLink.copy"/></button>
                    </div>
                    <form method="post" style="display:inline"
                          action="${pageContext.request.contextPath}/roller-ui/authoring/entryEdit!revokeShareLink.rol">
                        <input type="hidden" name="weblog" value="${actionWeblog.handle}"/>
                        <input type="hidden" name="entryId" value="${entry.id}"/>
                        <button type="submit" id="revokeEntryShareLinkButton" class="btn btn-danger btn-sm"><spring:message
                                code="shareLink.revoke"/></button>
                        <c:if test="${not empty entryShareLink.expires}">
                            <span class="pagetip ms-2" id="entryShareLinkExpires">
                                <spring:message code="shareLink.expiresOn"
                                                arguments="${entryShareLink.expires}"/>
                            </span>
                        </c:if>
                        <sec:csrfInput/>
                    </form>
                </c:when>
                <c:otherwise>
                    <p class="pagetip"><spring:message code="shareLink.none"/></p>
                    <form method="post"
                          action="${pageContext.request.contextPath}/roller-ui/authoring/entryEdit!createShareLink.rol">
                        <input type="hidden" name="weblog" value="${actionWeblog.handle}"/>
                        <input type="hidden" name="entryId" value="${entry.id}"/>
                        <div class="input-group" style="max-width: 44em">
                            <input type="password" name="sharePassword" id="entryShareLinkPassword"
                                   class="form-control" autocomplete="new-password"
                                   placeholder="<spring:message code="shareLink.passwordOptional"/>"/>
                            <input type="number" name="shareExpiryDays" id="entryShareLinkExpiryDays"
                                   class="form-control" min="1" max="3650" style="max-width: 10em"
                                   placeholder="<spring:message code="shareLink.expiryOptional"/>"/>
                            <button type="submit" id="createEntryShareLinkButton" class="btn btn-primary"><spring:message
                                    code="shareLink.create"/></button>
                        </div>
                        <sec:csrfInput/>
                    </form>
                </c:otherwise>
            </c:choose>
        </div>
    </div>
    <script>
        function copyEntryShareLink() {
            var input = document.getElementById('entryShareLinkUrl');
            input.select();
            if (navigator.clipboard && navigator.clipboard.writeText) {
                navigator.clipboard.writeText(input.value);
            } else {
                document.execCommand('copy');
            }
        }
    </script>
</c:if>


<%-- ========================================================================================== --%>

<%-- "Send as newsletter": a manual, synchronous, cannot-double-send action.
     Shown for published entries only -- an unpublished draft has no rendered
     content to mail out. Outside the main entry form, its own POST with its
     own CSRF input, exactly like the share-link card above. --%>

<c:if test="${actionName == 'entryEdit' && entry.published}">
    <div id="newsletterCard" class="card mt-3">
        <div class="card-header"><spring:message code="newsletter.cardTitle"/></div>
        <div class="card-body">
            <c:choose>
                <c:when test="${not empty entry.newsletterSentAt}">
                    <p class="pagetip" id="newsletterSentAt">
                        <spring:message code="newsletter.sentAt" arguments="${entry.newsletterSentAt}"/>
                    </p>
                </c:when>
                <c:when test="${empty actionWeblog.newsletterListUuid}">
                    <p class="pagetip" id="newsletterNoList">
                        <spring:message code="newsletter.noList"/>
                        <c:url value="/roller-ui/authoring/weblogConfig.rol" var="newsletterWeblogConfigUrl">
                            <c:param name="weblog" value="${actionWeblog.handle}"/>
                        </c:url>
                        <a href="${newsletterWeblogConfigUrl}"><spring:message code="tabbedmenu.website.settings"/></a>
                    </p>
                </c:when>
                <c:otherwise>
                    <button type="button" class="btn btn-primary" id="sendNewsletterButton"
                            onclick="showNewsletterModal()">
                        <spring:message code="newsletter.send"/>
                    </button>
                </c:otherwise>
            </c:choose>
        </div>
    </div>

    <div id="newsletter-confirm-modal" class="modal fade" tabindex="-1" role="dialog">
        <div class="modal-dialog">
            <div class="modal-content">
                <form action="${pageContext.request.contextPath}/roller-ui/authoring/entryEdit!sendNewsletter.rol"
                      method="post">
                    <input type="hidden" name="weblog" value="${actionWeblog.handle}"/>
                    <input type="hidden" name="bean.id" value="${entry.id}"/>

                    <div class="modal-header">
                        <h4 class="modal-title"><spring:message code="newsletter.confirmTitle"/></h4>
                        <button type="button" class="btn-close" data-bs-dismiss="modal" aria-label="Close"></button>
                    </div>

                    <div class="modal-body">
                        <p><spring:message code="newsletter.confirmBody"/></p>
                    </div>

                    <div class="modal-footer">
                        <button type="submit" class="btn btn-primary" id="confirmSendNewsletterButton">
                            <spring:message code="newsletter.send"/>
                        </button>
                        <button type="button" class="btn btn-secondary" data-bs-dismiss="modal">
                            <spring:message code="generic.no"/>
                        </button>
                    </div>

                    <sec:csrfInput/>
                </form>
            </div>
        </div>
    </div>

    <script>
        function showNewsletterModal() {
            bootstrap.Modal.getOrCreateInstance(document.getElementById('newsletter-confirm-modal')).show();
        }
    </script>
</c:if>


<%-- ========================================================================================== --%>

<%-- entry revisions: every content-changing save leaves one. Outside the main
     entry form for the same reason the share card is: restore is its own POST
     with its own CSRF token, and forms must not nest. --%>

<c:if test="${actionName == 'entryEdit' && not empty entryRevisions}">
    <div id="entryRevisionsCard" class="card mt-3">
        <div class="card-header"><spring:message code="weblogEdit.revisions"/></div>
        <div class="card-body">
            <p class="pagetip"><spring:message code="weblogEdit.revisionsTip"/></p>
            <table class="table table-sm" id="entryRevisionsTable">
                <c:forEach items="${entryRevisions}" var="revision">
                    <tr>
                        <td>
                            <spring:message code="weblogEntryQuery.date.toStringFormat"
                                            arguments="${revision.created}"/>
                        </td>
                        <td><c:out value="${revision.creator}"/></td>
                        <td>
                            <button type="button" class="btn btn-link btn-sm revision-diff-button"
                                    data-revision-id="${revision.id}">
                                <spring:message code="weblogEdit.revisionCompare"/>
                            </button>
                        </td>
                        <td>
                            <form method="post" style="display:inline"
                                  action="${pageContext.request.contextPath}/roller-ui/authoring/entryEdit!restoreRevision.rol">
                                <input type="hidden" name="weblog" value="${actionWeblog.handle}"/>
                                <input type="hidden" name="bean.id" value="${entry.id}"/>
                                <input type="hidden" name="revisionId" value="${revision.id}"/>
                                <sec:csrfInput/>
                                <button type="submit" class="btn btn-outline-secondary btn-sm revision-restore-button">
                                    <spring:message code="weblogEdit.revisionRestore"/>
                                </button>
                            </form>
                        </td>
                    </tr>
                </c:forEach>
            </table>
        </div>
    </div>

    <div id="revision-diff-modal" class="modal fade" tabindex="-1" role="dialog">
        <div class="modal-dialog modal-lg">
            <div class="modal-content">
                <div class="modal-header">
                    <h4 class="modal-title"><spring:message code="weblogEdit.revisionCompare"/></h4>
                    <button type="button" class="btn-close" data-bs-dismiss="modal" aria-label="Close"></button>
                </div>
                <div class="modal-body" id="revisionDiffBody"></div>
            </div>
        </div>
    </div>

    <style>
        #revisionDiffBody pre { white-space: pre-wrap; }
        #revisionDiffBody .diff-added { background-color: color-mix(in srgb, var(--good) 15%, var(--surface)); display: block; }
        #revisionDiffBody .diff-removed { background-color: color-mix(in srgb, var(--bad) 15%, var(--surface)); display: block; }
        #revisionDiffBody .diff-same { color: var(--ink-soft); display: block; }
    </style>

    <script>
        <%-- The diff is computed on the SERVER against the entry as saved, so
             it reflects what is actually stored rather than whatever is
             currently unsaved in the editor. --%>
        $(function () {
            $(".revision-diff-button").on('click', function () {
                var revisionId = this.dataset.revisionId;
                $("#revisionDiffBody").text('<spring:message code="weblogEdit.previewLoading"/>');
                bootstrap.Modal.getOrCreateInstance(document.getElementById('revision-diff-modal')).show();
                $.ajax({
                    type: 'POST',
                    url: '<c:url value="/roller-ui/authoring/entryEdit!revisionDiff.rol"/>',
                    data: {
                        id: '${entry.id}',
                        revisionId: revisionId,
                        weblog: '${actionWeblog.handle}',
                        '${_csrf.parameterName}': '${_csrf.token}'
                    },
                    success: function (html) { $("#revisionDiffBody").html(html); },
                    error: function () {
                        $("#revisionDiffBody").text('<spring:message code="weblogEdit.previewFailed"/>');
                    }
                });
            });
        });
    </script>
</c:if>


<%-- ========================================================================================== --%>

<%-- delete blogroll confirmation modal --%>

<div id="delete-entry-modal" class="modal fade delete-entry-modal" tabindex="-1" role="dialog">

    <div class="modal-dialog modal-lg">

        <div class="modal-content">

            <c:set var="deleteAction">entryRemoveViaList!remove</c:set>

            <form action="${pageContext.request.contextPath}/roller-ui/authoring/${deleteAction}.rol" method="post">
<input type="hidden" name="weblog" value="${actionWeblog.handle}"/>
                <input type="hidden" name="removeId" value="${removeId}" id="removeId"/>

                <div class="modal-header">
                    <div class="modal-title">
                        <h3><spring:message code="weblogEntryRemove.removeWeblogEntry"/></h3>
                        <p><spring:message code="weblogEntryRemove.areYouSure"/></p>
                    </div>
                </div>

                <div class="modal-body">

                    <div class="row mb-3">
                        <label class="col-sm-3 col-form-label">
                            <spring:message code="weblogEntryRemove.entryTitle"/>
                        </label>
                        <div class="col-sm-9">
                            <p class="form-control-plaintext" style="padding-top:0px" id="postTitleLabel"></p>
                        </div>
                    </div>

                    <div class="row mb-3">
                        <label class="col-sm-3 col-form-label">
                            <spring:message code="weblogEntryRemove.entryId"/>
                        </label>
                        <div class="col-sm-9">
                            <p class="form-control-plaintext" style="padding-top:0px" id="postIdLabel"></p>
                        </div>
                    </div>

                </div>

                <div class="modal-footer">
                    <button type="submit" class="btn"><spring:message code="generic.yes"/></button>
                    <button type="button" class="btn btn-secondary" data-bs-dismiss="modal">
                        <spring:message code="generic.no"/>
                    </button>
                </div>

            <sec:csrfInput/>
</form>

        </div>

    </div>

</div>

<%-- ========================================================================================== --%>

<script>

    $(document).ready(function () {
        $("#entry_bean_dateString").datepicker();
    });

    function fullPreviewMode() {
        window.open('${previewURL}', 'roller-preview');
    }


    function showDeleteModal(postId, postTitle) {
        $('#postIdLabel').html(postId);
        $('#postTitleLabel').html(postTitle);
        $('#removeId').val(postId);
        bootstrap.Modal.getOrCreateInstance(document.getElementById('delete-entry-modal')).show();
    }

    <%-- SEO panel: search-snippet preview fed from the title/description fields --%>

    function updateSeoSnippet() {
        var title = $('#seo_metaTitle').val() || $("input[name='bean.title']").val() || '';
        var description = $('#seo_metaDescription').val() || '';
        $('#seo_snippet_title').text(title);
        $('#seo_snippet_description').text(description);
    }

    <%-- SEO panel: the event rows appear only for the Event type. They keep
         their inputs (and their values) in the form while hidden, so a type
         switch is reversible and never wipes what the author typed. The
         coordinate rows are not conditional -- they also centre a bare [map],
         which any entry may use. --%>

    function updateSeoJsonLdRows() {
        $('#seo_row_event').toggle($('#seo_jsonldType').val() === 'EVENT');
    }

    $(document).ready(function () {
        updateSeoSnippet();
        $("#seo_metaTitle, #seo_metaDescription, input[name='bean.title']").on('input', updateSeoSnippet);
        updateSeoJsonLdRows();
        $('#seo_jsonldType').on('change', updateSeoJsonLdRows);
    });

    <%-- Featured/social image pickers: same media chooser as the editor's
         "insert media file" link, routed to a hidden id input + thumbnail
         preview instead of inserting into the editor. Shared by both targets:
         'featuredImage' and 'ogImage'. --%>

    function openImagePicker(target) {
        onClickMediaFileInsert(target);
    }

    <%-- Called by onSelectMediaFile (EntryEditor.jsp) when a picker target is active. --%>
    function onImagePicked(target, name, url, isImage, id) {
        if (isImage !== "true" || !id) {
            return;
        }
        $('#seo_' + target + 'Id').val(id);
        $('#seo_' + target + '_preview').attr('src', url + '?t=true').show();
        $('#seo_' + target + '_clear').show();
    }

    function clearPickedImage(target) {
        $('#seo_' + target + 'Id').val('');
        $('#seo_' + target + '_preview').removeAttr('src').hide();
        $('#seo_' + target + '_clear').hide();
    }

</script>
