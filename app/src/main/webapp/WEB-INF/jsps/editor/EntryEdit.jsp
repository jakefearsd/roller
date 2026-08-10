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

<%-- The editor is a writing surface with a publish rail (see
     docs/design/editor/editor-writing-surface.html, the approved card):
     the main column carries title, permalink and the Markdown editor;
     everything about *managing* the entry lives in a 252px rail. The form
     is display:contents so the newsletter/revisions cards -- which carry
     their own <form>s and therefore cannot nest inside #entry -- can still
     join the rail's grid column below it. --%>
<div class="editor-grid">

<form id="entry" method="post" class="form-stacked editor-form">
<input type="hidden" name="weblog" value="${actionWeblog.handle}"/>
    <input type="hidden" name="bean.status" value="${bean.status}"/>
    <c:choose>
<c:when test="${actionName == 'entryEdit'}">
        <input type="hidden" name="bean.id" value="${bean.id}"/>
    </c:when>
</c:choose>

    <%-- ================================================================== --%>
    <%-- The writing surface: title, permalink, editor --%>

    <div class="editor-main">

        <%-- title: the page's one piece of layout hierarchy. Large serif,
             borderless -- emphasis elsewhere is weight, never size. --%>
        <input type="text" name="bean.title" value="${bean.title}" maxlength="255" tabindex="1"
               class="editor-title"
               placeholder="<spring:message code="weblogEdit.title"/>"
               aria-label="<spring:message code="weblogEdit.title"/>"/>

        <%-- permalink: small mono line with a copy control --%>
        <c:if test="${actionName == 'entryEdit'}">
            <p class="editor-permalink" title="<spring:message code="weblogEdit.permaLink"/>">
                <c:choose>
<c:when test="${bean.published}">
                    <a id="entry_bean_permalink" href='${entry.permalink}'>${entry.permalink}</a>
                </c:when>
<c:otherwise>
                    ${entry.permalink}
                </c:otherwise>
</c:choose>
                &#183;
                <button type="button" class="editor-permalink-copy"
                        data-permalink="${entry.permalink}"
                        onclick="copyPermalink(this)"><spring:message code="weblogEdit.copyPermalink"/></button>
            </p>
        </c:if>

        <%-- Weblog editor --%>
        <jsp:include page="/WEB-INF/jsps/editor/EntryEditor.jsp"/>

    </div>

    <%-- ================================================================== --%>
    <%-- The publish rail --%>

    <div class="editor-rail">

        <%-- Publish: status, one visible time field, the submit buttons --%>
        <div class="editor-box">
            <p class="rail-group-label"><spring:message code="weblogEdit.publishGroup"/></p>

            <div class="editor-statusrow">
                <c:choose>
                <c:when test="${bean.published}">
                    <span class="badge bg-success"><spring:message code="weblogEdit.published"/></span>
                    <span class="editor-when" title="<spring:message code="weblogEdit.updateTime"/>"><fmt:formatDate value="${entry.updateTime}"/></span>
                </c:when>
                <c:when test="${bean.draft}">
                    <span class="badge bg-info"><spring:message code="weblogEdit.draft"/></span>
                    <span class="editor-when" title="<spring:message code="weblogEdit.updateTime"/>"><fmt:formatDate value="${entry.updateTime}"/></span>
                </c:when>
                <c:when test="${bean.pending}">
                    <span class="badge bg-warning"><spring:message code="weblogEdit.pending"/></span>
                    <span class="editor-when" title="<spring:message code="weblogEdit.updateTime"/>"><fmt:formatDate value="${entry.updateTime}"/></span>
                </c:when>
                <c:when test="${bean.scheduled}">
                    <span class="badge bg-info"><spring:message code="weblogEdit.scheduled"/></span>
                    <span class="editor-when" title="<spring:message code="weblogEdit.updateTime"/>"><fmt:formatDate value="${entry.updateTime}"/></span>
                </c:when>
                <c:otherwise>
                    <span class="badge bg-danger"><spring:message code="weblogEdit.unsaved"/></span>
                </c:otherwise>
                </c:choose>
            </div>

            <label class="editor-field-label" for="entry_bean_pubTimeLocal"><spring:message code="weblogEdit.pubTime"/></label>
            <input type="datetime-local" name="bean.pubTimeLocal" id="entry_bean_pubTimeLocal"
                   value="${bean.pubTimeLocal}" class="editor-dt"/>
            <div class="editor-tz">${actionWeblog.timeZone}</div>

            <div class="editor-btnrow">
                <c:choose>
<c:when test="${userAnAuthor}">
                    <%-- publish --%>
                    <button type="submit" class="btn btn-success" formaction="${pageContext.request.contextPath}/roller-ui/authoring/${mainAction}!publish.rol"><spring:message code="weblogEdit.post"/></button>
                </c:when>
<c:otherwise>
                    <%-- submit for review --%>
                    <button type="submit" class="btn btn-success" formaction="${pageContext.request.contextPath}/roller-ui/authoring/${mainAction}!publish.rol"><spring:message code="weblogEdit.submitForReview"/></button>
                </c:otherwise>
</c:choose>
                <%-- save draft --%>
                <button type="submit" class="btn" formaction="${pageContext.request.contextPath}/roller-ui/authoring/${mainAction}!saveDraft.rol"><spring:message code="weblogEdit.save"/></button>
            </div>

            <c:if test="${actionName == 'entryEdit'}">
                <%-- preview mode --%>
                <button type="button" name="fullPreview" class="editor-preview-link"
                        onclick="fullPreviewMode()"><spring:message code="weblogEdit.fullPreviewMode"/></button>
            </c:if>

            <%-- global admin can pin items to front page weblog --%>
            <c:if test="${authenticatedUser.hasGlobalPermission('admin')}">
                <div class="form-check editor-quiet-check">
                    <label class="form-check-label"><input type="checkbox" class="form-check-input" name="bean.pinnedToMain" value="true" ${bean.pinnedToMain ? 'checked' : ''}/> <spring:message code="weblogEdit.pinnedToMain"/></label>
                </div>
            </c:if>
        </div>

        <%-- Organize: category, tags, language --%>
        <div class="editor-box">
            <p class="rail-group-label"><spring:message code="weblogEdit.organizeGroup"/></p>

            <label class="editor-field-label" for="entry_bean_categoryId"><spring:message code="weblogEdit.category"/></label>
            <select name="bean.categoryId" id="entry_bean_categoryId" class="form-select">
<c:forEach items="${categories}" var="opt">
<option value="${opt.id}" ${opt.id == bean.categoryId ? 'selected' : ''}>${opt.name}</option>
</c:forEach>
</select>

            <label class="editor-field-label" for="entry_bean_tagsAsString"><spring:message code="weblogEdit.tags"/></label>
            <input type="text" name="bean.tagsAsString" value="${bean.tagsAsString}" id="entry_bean_tagsAsString" maxlength="255" class="form-control"/>

            <c:choose>
<c:when test="${actionWeblog.enableMultiLang}">
                <label class="editor-field-label" for="entry_bean_locale"><spring:message code="weblogEdit.locale"/></label>
                <select name="bean.locale" id="entry_bean_locale" class="form-select">
<c:forEach items="${localesList}" var="opt">
<option value="${opt}" ${opt == bean.locale ? 'selected' : ''}>${opt}</option>
</c:forEach>
</select>
            </c:when>
<c:otherwise>
                <input type="hidden" name="bean.locale" value="${bean.locale}"/>
            </c:otherwise>
</c:choose>
        </div>

        <%-- SEO and social sharing: the whole card survives intact behind a
             quiet drawer. Field ids/names and the picker/snippet JS below are
             a browser-test contract -- do not rename. --%>
        <div class="editor-box">
            <a class="editor-drawer collapsed" data-bs-toggle="collapse" data-bs-target="#collapseSeo" href="#">
                <spring:message code="weblogEdit.seoSettings"/>
            </a>
            <div id="collapseSeo" class="collapse">
                <div class="editor-drawer-body">

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

                    <%-- Lightweight search-snippet preview, fed by JS from the
                         title/description fields.

                         The three lines used to carry inline font-size bumps
                         (1.15em / 0.85em / 0.9em) -- size-as-emphasis, on the
                         one page whose borderless 26px title is the spec's
                         canonical example of "emphasis is weight, never size".
                         They are named classes now and sit on the 12 / 14.5 /
                         16 scale: 16px title, 12px mono URL, 14.5px
                         description. That still reads as a search result --
                         the hierarchy is intact -- without inventing a support
                         tier the scale does not have.

                         The rules themselves live in roller.css beside the
                         other .editor-* rules. --%>
                    <div class="row mb-3">
                        <label class="col-sm-3 col-form-label"><spring:message code="weblogEdit.snippetPreview"/></label>
                        <div class="col-sm-9">
                            <div id="seo_snippet_preview" class="border rounded p-2 bg-body">
                                <div id="seo_snippet_title" class="seo-snippet-title"></div>
                                <div id="seo_snippet_url" class="seo-snippet-url"><c:if test="${actionName == 'entryEdit'}">${entry.permalink}</c:if></div>
                                <div id="seo_snippet_description" class="seo-snippet-description"></div>
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

        <%-- Comments: settings plus the count line the retired sidebar used to
             carry. The collapse keeps the id the Advanced-settings drawer had
             because CommentIT opens it via a[href='#collapseAdvanced'] to
             reach the allow-comments checkbox -- a pinned selector, not a
             description of what is inside. --%>
        <div class="editor-box">
            <a class="editor-drawer collapsed" data-bs-toggle="collapse" href="#collapseAdvanced">
                <spring:message code="weblogEdit.comments"/>
            </a>
            <div id="collapseAdvanced" class="collapse">
                <div class="editor-drawer-body">

                    <%-- Allow comments. This control was missing entirely, and
                         EntryBean.allowComments is a primitive defaulting to
                         false -- so every entry saved through this editor had
                         comments switched off no matter what the weblog's
                         default said, and editing a post silently closed
                         comments that were open. --%>
                    <div class="form-check editor-quiet-check">
                        <label class="form-check-label"><input type="checkbox" class="form-check-input" id="entry_bean_allowComments" name="bean.allowComments" value="true" ${bean.allowComments ? 'checked' : ''}/> <spring:message code="weblogEdit.allowComments"/></label>
                    </div>

                    <label class="editor-field-label" for="entry_bean_commentDays"><spring:message code="weblogEdit.commentDays"/></label>
                    <select name="bean.commentDays" id="entry_bean_commentDays" class="form-select">
<c:forEach items="${commentDaysList}" var="opt">
<option value="${opt.key}" ${opt.key == bean.commentDays ? 'selected' : ''}>${opt.value}</option>
</c:forEach>
</select>

                    <%-- comments on this entry (absorbed from the retired sidebar) --%>
                    <c:if test="${actionName == 'entryEdit'}">
                        <p class="editor-comment-count">
                            <c:choose>
<c:when test="${bean.commentCount > 0}">
                                <c:url var="commentsURL" value="/roller-ui/authoring/comments.rol">
                                    <c:param name="bean.entryId" value="${bean.id}"/>
                                    <c:param name="weblog" value="${actionWeblog.handle}"/>
                                </c:url>
                                <spring:message code="weblogEdit.hasComments" arguments="${commentsURL},${bean.commentCount}"/>
                            </c:when>
<c:otherwise>
                                <spring:message code="generic.none"/>
                            </c:otherwise>
</c:choose>
                        </p>
                    </c:if>

                </div>
            </div>
        </div>

        <c:if test="${actionName == 'entryEdit'}">
            <%-- delete: a quiet text link, not a red button --%>
            <button type="button" class="delete-link"
                    onclick="showDeleteModal('${entry.id}', '${fn:escapeXml(entry.title)}' )"><spring:message code="weblogEdit.deleteEntry"/></button>
        </c:if>

    </div>

<sec:csrfInput/>
</form>


<%-- "Send as newsletter": a manual, synchronous, cannot-double-send action.
     Shown for published entries only -- an unpublished draft has no rendered
     content to mail out. Outside the main entry form (its modal is its own
     POST with its own CSRF input), but placed in the rail's grid column so it
     reads as one more quiet box below the rail. --%>

<c:if test="${actionName == 'entryEdit' && entry.published}">
    <div id="newsletterCard" class="card editor-rail-extra">
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
</c:if>

<%-- entry revisions: every content-changing save leaves one. Outside the main
     entry form for the same reason the newsletter card is: restore is its own
     POST with its own CSRF token, and forms must not nest. --%>

<c:if test="${actionName == 'entryEdit' && not empty entryRevisions}">
    <div id="entryRevisionsCard" class="card editor-rail-extra">
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
</c:if>

</div><%-- /editor-grid --%>


<%-- ========================================================================================== --%>

<%-- newsletter confirmation modal + script (the card lives in the rail above) --%>

<c:if test="${actionName == 'entryEdit' && entry.published}">
    <div id="newsletter-confirm-modal" class="modal" tabindex="-1" role="dialog">
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

<%-- revision diff modal + script (the card lives in the rail above) --%>

<c:if test="${actionName == 'entryEdit' && not empty entryRevisions}">
    <div id="revision-diff-modal" class="modal" tabindex="-1" role="dialog">
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

<%-- delete entry confirmation modal --%>

<div id="delete-entry-modal" class="modal delete-entry-modal" tabindex="-1" role="dialog">

    <div class="modal-dialog modal-lg">

        <div class="modal-content">

            <c:set var="deleteAction">entryRemoveViaList!remove</c:set>

            <form class="form-stacked" action="${pageContext.request.contextPath}/roller-ui/authoring/${deleteAction}.rol" method="post">
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
                            <p class="form-control-plaintext" id="postTitleLabel"></p>
                        </div>
                    </div>

                    <div class="row mb-3">
                        <label class="col-sm-3 col-form-label">
                            <spring:message code="weblogEntryRemove.entryId"/>
                        </label>
                        <div class="col-sm-9">
                            <p class="form-control-plaintext" id="postIdLabel"></p>
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

    function fullPreviewMode() {
        window.open('${previewURL}', 'roller-preview');
    }


    function showDeleteModal(postId, postTitle) {
        $('#postIdLabel').html(postId);
        $('#postTitleLabel').text(postTitle);
        $('#removeId').val(postId);
        bootstrap.Modal.getOrCreateInstance(document.getElementById('delete-entry-modal')).show();
    }

    <%-- permalink copy control --%>

    function copyPermalink(el) {
        navigator.clipboard.writeText(el.dataset.permalink).then(function () {
            el.classList.add('copied');
            setTimeout(function () { el.classList.remove('copied'); }, 1500);
        });
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
