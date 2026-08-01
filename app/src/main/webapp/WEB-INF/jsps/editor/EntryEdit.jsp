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

<style>
    #tagAutoCompleteWrapper {
        width: 40em; /* set width here or else widget will expand to fit its container */
        padding-bottom: 2em;
    }
</style>

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

<form id="entry" method="post">
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
            <input type="text" name="bean.tagsAsString" value="${bean.tagsAsString}" id="tagAutoComplete" maxlength="255" tabindex="2" class="form-control"/>
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

    $(function () {
        function split(val) {
            return val.split(/ \s*/);
        }

        function extractLast(term) {
            return split(term).pop();
        }

        $("#tagAutoComplete")
        // don't navigate away from the field on tab when selecting an item
            .bind("keydown", function (event) {
                if (event.keyCode === $.ui.keyCode.TAB && $(this).autocomplete("instance").menu.active) {
                    event.preventDefault();
                }
            })
            .autocomplete({
                delay: 500,
                source: function (request, response) {
                    $.getJSON("${jsonAutocompleteUrl}", {
                            format: 'json',
                            prefix: extractLast(request.term)
                        },
                        function (data) {
                            response($.map(data.tagcounts, function (dataValue) {
                                return {
                                    value: dataValue.tag
                                };
                            }))
                        })
                },
                focus: function () {
                    // prevent value inserted on focus
                    return false;
                },
                select: function (event, ui) {
                    var terms = split(this.value);
                    // remove the current input
                    terms.pop();
                    // add the selected item
                    terms.push(ui.item.value);
                    // add placeholder to get the space at the end
                    terms.push("");
                    this.value = terms.join(" ");
                    return false;
                }
            });
    });

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

    $(document).ready(function () {
        updateSeoSnippet();
        $("#seo_metaTitle, #seo_metaDescription, input[name='bean.title']").on('input', updateSeoSnippet);
    });

    <%-- Featured/social image pickers: same media chooser as the editor's
         "insert media file" link, routed to a hidden id input + thumbnail
         preview instead of the Summernote insertion. Shared by both targets:
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
