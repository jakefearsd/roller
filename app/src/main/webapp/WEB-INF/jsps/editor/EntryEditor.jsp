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
<%-- This page is designed to be included in EntryEdit.jsp --%>
<%@ include file="/WEB-INF/jsps/taglibs-spring.jsp" %>


<%-- ********************************************************************* --%>

<%-- content --%>
<textarea name="bean.text" id="edit_content" rows="18" tabindex="5" class="col-sm-12">${bean.text}</textarea>

<%-- The insert menu is generated from the shortcode registry (model attribute
     shortcodeCards), so adding a sixth shortcode means writing its handler and
     nothing here. Snippets ride in a data attribute rather than inline
     JavaScript: JSTL escapes them for the attribute, and the browser hands
     back the exact text through dataset. --%>
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

<%-- mb-4 rather than a spacer.png with an inline min-height: the gap before
     the Summary card is margin, and margin is what should express it. --%>
<div class="mb-4">
    <a href="#" onClick="onClickMediaFileInsert();"><spring:message code="weblogEdit.insertMediaFile"/></a>
</div>

<%-- summary --%>

<div class="card" id="panel-summary">
    <div class="card-header">

        <h4 class="card-title">
            <a href="#" class="collapsed"
               data-bs-toggle="collapse" data-bs-target="#collapseSummaryEditor">
                <spring:message code="weblogEdit.summary"/>
            </a>
        </h4>

    </div>
    <div id="collapseSummaryEditor" class="collapse">
        <div class="card-body">

            <textarea name="bean.summary" id="edit_summary" rows="10" tabindex="6" class="col-sm-12">${bean.summary}</textarea>

        </div>
    </div>
</div>

<%-- ********************************************************************* --%>


<%-- Media File Insert for plain textarea editor --%>

<div id="mediafile_edit_lightbox" class="modal" role="dialog">

    <div class="modal-dialog modal-lg">

        <div class="modal-content">

            <div class="modal-header">
                <h4 class="modal-title"><spring:message code="weblogEdit.insertMediaFile"/></h4>
                <button type="button" class="btn-close" data-bs-dismiss="modal" aria-label="Close"></button>
            </div>

            <div class="modal-body">
                <iframe id="mediaFileEditor"
                        style="visibility:inherit"
                        height="600" <%-- pixels, sigh, this is suboptimal--%>
                        width="100%"
                        frameborder="no"
                        scrolling="auto">
                </iframe>
            </div>

            <div class="modal-footer">
                <button type="button" class="btn btn-secondary" data-bs-dismiss="modal">Close</button>
            </div>

        </div>
    </div>

</div>

<script>

    <%-- The editor. Entries are stored as Markdown -- always, with no
         alternative format to switch to -- so this is a Markdown editor with a
         preview, not a rich-text editor. A WYSIWYG surface may replace it one
         day, but it would edit Markdown rather than produce HTML. --%>
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
                      'link', 'table', '|', 'preview', 'side-by-side', 'guide'],
            previewRender: rollerRenderPreview
        });

        <%-- Warn before leaving with unsaved edits, and stand down on submit.
             Bound ONCE, tracking a dirty flag. The previous version registered
             both leave-warning handlers *inside* the change callback, so every
             keystroke added another one of each -- and the one that actually
             mattered, another submit handler on the form about to be posted.

             The warning stays even though drafts now survive a lost tab: a
             local snapshot is a recovery mechanism, not a reason to stop
             telling someone they are walking away from unsaved work. --%>
        var rollerEntryDirty = false;
        rollerEditor.codemirror.on('change', function () {
            rollerEntryDirty = true;
        });
        $("#entry").on('input change', function () {
            rollerEntryDirty = true;
        });
        $(window).on("beforeunload", function (event) {
            if (!rollerEntryDirty) {
                return undefined;
            }
            if (event.originalEvent) {
                event.originalEvent.returnValue = "Are you sure you want to leave?";
            }
            return "Are you sure you want to leave?";
        });
        $("#entry").on('submit', function () {
            rollerEntryDirty = false;
            $(window).off("beforeunload");
        });

        <%-- Local draft recovery. The key carries the context path so two
             Roller installs on one origin cannot share storage, and the
             action name so entryAdd and entryEdit are distinct. staleKeys
             names the entryAdd:new snapshot: saving a new entry redirects
             here under a real id, and without this the "new" draft would
             linger and be offered to whoever starts the next entry. --%>
        if (window.rollerDraft) {
            window.rollerDraft.install({
                form: document.getElementById('entry'),
                key: '${draftKey}',
                staleKeys: ['${draftNewKey}'],
                bar: document.getElementById('draftRecoveryBar'),
                csrfName: '${_csrf.parameterName}',
                getText: rollerGetEntryText,
                setText: rollerSetEntryText,
                onEditorChange: function (callback) {
                    rollerEditor.codemirror.on('change', callback);
                }
            });
        }

        <%-- Every card goes through insertMediaFile, the editor's one insert
             seam, so the WYSIWYG-for-Markdown surface that may replace EasyMDE
             inherits the whole menu by reimplementing a single function. --%>
        $(".shortcode-card").on('click', function (event) {
            event.preventDefault();
            if (this.dataset.chooser === 'true') {
                onClickMediaFileInsert();
            } else {
                insertMediaFile(this.dataset.snippet);
                rollerEditor.codemirror.focus();
            }
        });
    });

    <%-- The preview is rendered by the SERVER, not by a Markdown library in
         the browser. Only the server can expand [gallery], [map] and the rest,
         and a preview that disagreed with the published page about those would
         be worse than no preview at all. --%>
    function rollerRenderPreview(plainText, preview) {
        $.ajax({
            type: 'POST',
            url: '<c:url value="/roller-ui/authoring/entryEdit!preview.rol"/>',
            data: {
                id: $("input[name='bean.id']").val(),
                text: plainText,
                weblog: $("input[name='weblog']").val(),
                '${_csrf.parameterName}': '${_csrf.token}'
            },
            success: function (html) { preview.innerHTML = html; },
            error: function () {
                preview.textContent = '<spring:message code="weblogEdit.previewFailed"/>';
            }
        });
        return '<spring:message code="weblogEdit.previewLoading"/>';
    }

    <%-- The one seam for putting text into the editor, used by the media
         chooser (which inserts an [image id=..] shortcode) and by the browser
         tests. Everything else talks to the editor through here, so swapping
         the editor again does not mean hunting down its callers. --%>
    function insertMediaFile(toInsert) {
        rollerEditor.codemirror.replaceSelection(toInsert);
    }

    function rollerSetEntryText(text) {
        rollerEditor.value(text);
    }

    function rollerGetEntryText() {
        return rollerEditor.value();
    }

    <%-- Common functions --%>

    <%-- Opens the media chooser. With no argument the chosen file is inserted
         into the editor at the cursor; with a picker
         target ('featuredImage' / 'ogImage') the choice is routed to
         onImagePicked in EntryEdit.jsp instead. --%>
    function onClickMediaFileInsert(pickerTarget) {
        window.mediaPickerTarget = pickerTarget || null;
        window.mediaLightboxCloseRequested = false;
        <c:url var="mediaFileImageChooser" value="/roller-ui/authoring/overlay/mediaFileImageChooser.rol">
        <c:param name="weblog" value="${actionWeblog.handle}"/>
        </c:url>
        $("#mediaFileEditor").attr('src', '${mediaFileImageChooser}');
        bootstrap.Modal.getOrCreateInstance(document.getElementById('mediafile_edit_lightbox')).show();
    }

    function onClose() {
        $("#mediaFileEditor").attr('src', 'about:blank');
    }

    <%-- Bootstrap silently ignores hide() while the fade-in transition is
         still running, so selecting a file quickly after opening the chooser
         would leave the modal stuck open. Ask for the close, and if the modal
         is still transitioning, the shown.bs.modal listener below re-issues
         it once the fade-in has finished. --%>
    function closeMediaFileLightbox() {
        window.mediaLightboxCloseRequested = true;
        bootstrap.Modal.getOrCreateInstance(document.getElementById('mediafile_edit_lightbox')).hide();
    }

    document.getElementById('mediafile_edit_lightbox').addEventListener('shown.bs.modal', function () {
        if (window.mediaLightboxCloseRequested) {
            bootstrap.Modal.getOrCreateInstance(this).hide();
        }
    });

    <%-- Callback from MediaFileImageChooser.jsp inside the iframe. The id is a
         later addition; callers that only pass (name, url, isImage) still work. --%>
    function onSelectMediaFile(name, url, isImage, id) {
        closeMediaFileLightbox();
        $("#mediaFileEditor").attr('src', 'about:blank');
        if (window.mediaPickerTarget) {
            var target = window.mediaPickerTarget;
            window.mediaPickerTarget = null;
            if (typeof onImagePicked === 'function') {
                onImagePicked(target, name, url, isImage, id);
            }
            return;
        }
        if (isImage === "true") {
            if (id) {
                <%-- The [image] shortcode expands at render time into a
                     responsive <figure><picture> with the full srcset ladder,
                     so authors get the rendition pipeline automatically.
                     Existing entries with the old raw <img> markup are left
                     exactly as they are. --%>
                insertMediaFile('[image id="' + id + '"]');
            } else {
                <%-- Historic fallback for callers that never pass the id. --%>
                insertMediaFile('<a href="' + url + '"><img src="' + url + '?t=true" alt="' + name + '" /></a>');
            }
        } else {
            insertMediaFile('<a href="' + url + '">' + name + '</a>');
        }
    }

</script>
