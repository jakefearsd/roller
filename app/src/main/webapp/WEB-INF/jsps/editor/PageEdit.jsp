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

<%-- Prevent annoying scrolling when a collapse toggle's href is "#". --%>
<script type="text/javascript">
    $().ready(function () {
        $("a[href='#'][data-bs-toggle='collapse']").click(function (e) {
            e.preventDefault();
        });
    });
</script>

<p class="subtitle">
    <spring:message code="weblogPagesForm.subtitle" arguments="${actionWeblog.handle}"/>
</p>

<form id="pageEditForm" method="post" class="form-stacked"
      action="${pageContext.request.contextPath}/roller-ui/authoring/pageEdit!save.rol">
<input type="hidden" name="weblog" value="${actionWeblog.handle}"/>
    <input type="hidden" name="bean.id" value="${bean.id}"/>

    <div class="row mb-3">
        <label class="col-sm-3 col-form-label" for="page_bean_title"><spring:message code="weblogEdit.title"/></label>
        <div class="col-sm-9">
            <input type="text" id="page_bean_title" name="bean.title" value="${bean.title}" maxlength="255" class="form-control"/>
        </div>
    </div>

    <div class="row mb-3">
        <label class="col-sm-3 col-form-label" for="page_bean_slug"><spring:message code="weblogPagesForm.slug"/></label>
        <div class="col-sm-9">
            <div class="input-group">
                <span class="input-group-text">/${actionWeblog.handle}/</span>
                <input type="text" id="page_bean_slug" name="bean.slug" value="${bean.slug}" maxlength="255" class="form-control"/>
            </div>
        </div>
    </div>

    <div class="row mb-3">
        <label class="col-sm-3 col-form-label" for="page_bean_status"><spring:message code="weblogEdit.status"/></label>
        <div class="col-sm-9">
            <select id="page_bean_status" name="bean.status" class="form-select">
                <option value="DRAFT" ${bean.status == 'DRAFT' ? 'selected' : ''}><spring:message code="weblogEdit.draft"/></option>
                <option value="PUBLISHED" ${bean.status == 'PUBLISHED' ? 'selected' : ''}><spring:message code="weblogEdit.published"/></option>
            </select>
        </div>
    </div>

    <div class="row mb-3">
        <div class="offset-sm-3 col-sm-9">
            <div class="form-check">
                <%-- The "_showInNav" marker is Spring's documented way to tell
                     a plain HTML checkbox from "not part of this form":
                     PageBean.showInNav defaults to true (matching
                     WeblogPage's own default), so a browser leaving the box
                     unchecked submits no "bean.showInNav" param at all, and
                     without the marker WebDataBinder would fall back to that
                     true default -- meaning nav could be turned on but never
                     off. With the marker present, WebDataBinder treats a
                     missing real value as an explicit false.

                     The marker name is deliberately "_showInNav", NOT
                     "_bean.showInNav" -- BaseController#initBeanBinder sets
                     the binder's *field-default* prefix to "bean." so plain
                     "bean.xxx" params bind by their Struts2-style name, and
                     that rewrite (checkFieldDefaults) runs before the
                     *field-marker* pass (checkFieldMarkers) and only ever
                     touches params that literally start with "bean.". A
                     marker named "_bean.showInNav" starts with "_", not
                     "bean.", so checkFieldDefaults leaves it untouched;
                     checkFieldMarkers then strips only the "_" and looks for
                     a writable property named "bean.showInNav", which does
                     not exist on PageBean, so the marker was silently
                     discarded and an unchecked box never took effect. The
                     marker must name the bean's real property path
                     ("showInNav"), the same path checkFieldDefaults produces
                     for the checked case, not the raw submitted name. --%>
                <input type="hidden" name="_showInNav" value="on"/>
                <label class="form-check-label">
                    <input type="checkbox" class="form-check-input" name="bean.showInNav" value="true" ${bean.showInNav ? 'checked' : ''}/>
                    <spring:message code="weblogPagesForm.showInNav"/>
                </label>
            </div>
        </div>
    </div>

    <div class="row mb-3">
        <label class="col-sm-3 col-form-label" for="page_bean_navOrder"><spring:message code="weblogPagesForm.navOrder"/></label>
        <div class="col-sm-3">
            <input type="number" id="page_bean_navOrder" name="bean.navOrder" value="${bean.navOrder}" class="form-control"/>
        </div>
    </div>

    <div id="accordion">

        <%-- ============================================================ --%>
        <%-- Content editor. Same Markdown-with-preview surface the entry
             editor uses, driven through the same three functions
             (insertMediaFile, rollerSetEntryText, rollerGetEntryText) so a
             future editor swap only means reimplementing those. Not a
             literal jsp:include of EntryEditor.jsp: that partial binds to
             bean.text and carries entry-only pieces (summary, mediacast)
             that have no equivalent on a page. --%>

        <textarea name="bean.content" id="edit_content" rows="18" class="col-sm-12">${bean.content}</textarea>

        <div class="dropdown d-inline-block" id="shortcodeInsertMenu">
            <button class="btn btn-sm btn-outline-secondary dropdown-toggle" type="button"
                    id="shortcodeInsertButton" data-bs-toggle="dropdown" aria-expanded="false">
                <spring:message code="weblogEdit.insertShortcode"/>
            </button>
            <ul class="dropdown-menu" aria-labelledby="shortcodeInsertButton">
                <c:forEach items="${shortcodeCards}" var="card">
                    <li>
                        <a class="dropdown-item shortcode-card" href="#"
                           data-shortcode="<c:out value='${card.name}'/>"
                           data-snippet="<c:out value='${card.snippet}'/>"
                           data-chooser="${card.usesMediaChooser}"><spring:message code="${card.labelKey}"/></a>
                    </li>
                </c:forEach>
            </ul>
        </div>

        <a href="#" onclick="onClickPageMediaFileInsert();"><spring:message code="weblogEdit.insertMediaFile"/></a><br/>
        <img src="<c:url value='/roller-ui/images/spacer.png'/>" alt="spacer" style="min-height: 2em"/>

        <%-- ============================================================ --%>
        <%-- SEO and social sharing, matching EntryEdit.jsp's card --%>

        <div class="card" id="panel-seo">
            <div class="card-header">
                <h4 class="card-title">
                    <a class="collapsed" data-bs-toggle="collapse" data-bs-target="#collapseSeo" href="#">
                        <spring:message code="weblogEdit.seoSettings"/>
                    </a>
                </h4>
            </div>
            <div id="collapseSeo" class="collapse">
                <div class="card-body">

                    <div class="row mb-3">
                        <label class="col-sm-3 col-form-label" for="seo_metaTitle"><spring:message code="weblogEdit.metaTitle"/></label>
                        <div class="col-sm-9">
                            <input type="text" id="seo_metaTitle" name="bean.metaTitle" value="${bean.metaTitle}" maxlength="255" class="form-control"/>
                        </div>
                    </div>

                    <div class="row mb-3">
                        <label class="col-sm-3 col-form-label" for="seo_metaDescription"><spring:message code="weblogEdit.metaDescription"/></label>
                        <div class="col-sm-9">
                            <input type="text" id="seo_metaDescription" name="bean.searchDescription" value="${bean.searchDescription}" maxlength="255" class="form-control"/>
                        </div>
                    </div>

                    <div class="row mb-3">
                        <label class="col-sm-3 col-form-label"><spring:message code="weblogEdit.ogImage"/></label>
                        <div class="col-sm-9">
                            <input type="hidden" id="seo_ogImageId" name="bean.ogImageId" value="${bean.ogImageId}"/>
                            <div class="mb-2">
                                <img id="seo_ogImage_preview" src="${ogImageThumbnailUrl}" alt=""
                                     style="max-height:120px;${empty ogImageThumbnailUrl ? 'display:none;' : ''}"/>
                            </div>
                            <button type="button" class="btn btn-secondary btn-sm" onclick="onClickPageMediaFileInsert('ogImage')"><spring:message code="weblogEdit.chooseImage"/></button>
                            <button type="button" class="btn btn-outline-danger btn-sm" id="seo_ogImage_clear"
                                    style="${empty bean.ogImageId ? 'display:none;' : ''}"
                                    onclick="clearPickedOgImage()"><spring:message code="weblogEdit.clearImage"/></button>
                        </div>
                    </div>

                    <div class="row mb-3">
                        <label class="col-sm-3 col-form-label" for="seo_canonicalUrl"><spring:message code="weblogEdit.canonicalUrl"/></label>
                        <div class="col-sm-9">
                            <input type="text" id="seo_canonicalUrl" name="bean.canonicalUrl" value="${bean.canonicalUrl}" maxlength="255" class="form-control"/>
                        </div>
                    </div>

                    <div class="row mb-3">
                        <div class="offset-sm-3 col-sm-9">
                            <div class="form-check">
                                <label class="form-check-label">
                                    <input type="checkbox" class="form-check-input" id="seo_noindex" name="bean.noindex" value="true" ${bean.noindex ? 'checked' : ''}/>
                                    <spring:message code="weblogEdit.noindex"/>
                                </label>
                            </div>
                        </div>
                    </div>

                </div>
            </div>
        </div>

    </div>

    <%-- ================================================================== --%>
    <%-- Buttons --%>

    <button type="submit" class="btn btn-primary"><spring:message code="generic.save"/></button>

    <c:if test="${not empty bean.id}">
        <span style="float:right">
            <input class="btn btn-danger" type="button"
                   value="<spring:message code="generic.delete"/>"
                   onclick="showPageDeleteModal('${bean.id}', '${fn:escapeXml(bean.title)}')"/>
        </span>
    </c:if>

    <sec:csrfInput/>
</form>

<%-- ====================================================================== --%>
<%-- Delete confirmation --%>

<div id="delete-page-modal" class="modal fade" tabindex="-1" role="dialog">
    <div class="modal-dialog">
        <div class="modal-content">
            <form action="${pageContext.request.contextPath}/roller-ui/authoring/pageRemove.rol" method="post">
<input type="hidden" name="weblog" value="${actionWeblog.handle}"/>
                <input type="hidden" name="removeId" value="" id="page-delete-id"/>
                <div class="modal-header">
                    <h3><spring:message code="generic.delete"/>: <span id="page-delete-title"></span></h3>
                </div>
                <div class="modal-footer">
                    <button type="submit" class="btn btn-danger"><spring:message code="generic.yes"/></button>
                    <button type="button" class="btn btn-secondary" data-bs-dismiss="modal"><spring:message code="generic.no"/></button>
                </div>
                <sec:csrfInput/>
            </form>
        </div>
    </div>
</div>

<%-- ====================================================================== --%>
<%-- Media file chooser, for both "insert into the content editor" and the
     og:image picker. Its own ids so this JSP never collides with the entry
     editor's copy of the same modal. --%>

<div id="page_mediafile_edit_lightbox" class="modal fade" role="dialog">
    <div class="modal-dialog modal-lg">
        <div class="modal-content">
            <div class="modal-header">
                <h4 class="modal-title"><spring:message code="weblogEdit.insertMediaFile"/></h4>
                <button type="button" class="btn-close" data-bs-dismiss="modal" aria-label="Close"></button>
            </div>
            <div class="modal-body">
                <iframe id="pageMediaFileEditor" style="visibility:inherit" height="600" width="100%"
                        frameborder="no" scrolling="auto"></iframe>
            </div>
            <div class="modal-footer">
                <button type="button" class="btn btn-secondary" data-bs-dismiss="modal">Close</button>
            </div>
        </div>
    </div>
</div>

<script>

    var rollerEditor = null;

    $(document).ready(function () {
        rollerEditor = new EasyMDE({
            element: document.getElementById('edit_content'),
            autoDownloadFontAwesome: false,
            spellChecker: false,
            status: false,
            minHeight: '400px',
            toolbar: ['bold', 'italic', 'heading', '|',
                      'quote', 'unordered-list', 'ordered-list', '|',
                      'link', 'table', '|', 'preview', 'side-by-side', 'guide']
        });

        rollerEditor.codemirror.on('change', function () {
            var confirmLeaving = function (event) {
                if (event.originalEvent) {
                    event.originalEvent.returnValue = "Are you sure you want to leave?";
                }
                return "Are you sure you want to leave?";
            };
            $(window).on("beforeunload", confirmLeaving);
            $("#pageEditForm").on('submit', function () {
                $(window).off("beforeunload", confirmLeaving);
            });
        });

        $(".shortcode-card").on('click', function (event) {
            event.preventDefault();
            if (this.dataset.chooser === 'true') {
                onClickPageMediaFileInsert();
            } else {
                insertMediaFile(this.dataset.snippet);
                rollerEditor.codemirror.focus();
            }
        });
    });

    <%-- The one seam for putting text into the editor. --%>
    function insertMediaFile(toInsert) {
        rollerEditor.codemirror.replaceSelection(toInsert);
    }

    function rollerSetEntryText(text) {
        rollerEditor.value(text);
    }

    function rollerGetEntryText() {
        return rollerEditor.value();
    }

    <%-- Opens the media chooser. With no argument the chosen file is
         inserted into the editor; with 'ogImage' the choice is routed to the
         social-share image field instead. --%>
    function onClickPageMediaFileInsert(pickerTarget) {
        window.pageMediaPickerTarget = pickerTarget || null;
        window.pageMediaLightboxCloseRequested = false;
        <c:url var="mediaFileImageChooser" value="/roller-ui/authoring/overlay/mediaFileImageChooser.rol">
        <c:param name="weblog" value="${actionWeblog.handle}"/>
        </c:url>
        $("#pageMediaFileEditor").attr('src', '${mediaFileImageChooser}');
        bootstrap.Modal.getOrCreateInstance(document.getElementById('page_mediafile_edit_lightbox')).show();
    }

    function closePageMediaFileLightbox() {
        window.pageMediaLightboxCloseRequested = true;
        bootstrap.Modal.getOrCreateInstance(document.getElementById('page_mediafile_edit_lightbox')).hide();
    }

    document.getElementById('page_mediafile_edit_lightbox').addEventListener('shown.bs.modal', function () {
        if (window.pageMediaLightboxCloseRequested) {
            bootstrap.Modal.getOrCreateInstance(this).hide();
        }
    });

    <%-- Callback from MediaFileImageChooser.jsp inside the iframe. --%>
    function onSelectMediaFile(name, url, isImage, id) {
        closePageMediaFileLightbox();
        $("#pageMediaFileEditor").attr('src', 'about:blank');
        if (window.pageMediaPickerTarget === 'ogImage') {
            window.pageMediaPickerTarget = null;
            if (isImage === "true" && id) {
                $('#seo_ogImageId').val(id);
                $('#seo_ogImage_preview').attr('src', url + '?t=true').show();
                $('#seo_ogImage_clear').show();
            }
            return;
        }
        if (isImage === "true" && id) {
            insertMediaFile('[image id="' + id + '"]');
        } else if (isImage === "true") {
            insertMediaFile('<a href="' + url + '"><img src="' + url + '?t=true" alt="' + name + '" /></a>');
        } else {
            insertMediaFile('<a href="' + url + '">' + name + '</a>');
        }
    }

    function clearPickedOgImage() {
        $('#seo_ogImageId').val('');
        $('#seo_ogImage_preview').removeAttr('src').hide();
        $('#seo_ogImage_clear').hide();
    }

    function showPageDeleteModal(pageId, pageTitle) {
        $('#page-delete-id').val(pageId);
        $('#page-delete-title').html(pageTitle);
        bootstrap.Modal.getOrCreateInstance(document.getElementById('delete-page-modal')).show();
    }

</script>
