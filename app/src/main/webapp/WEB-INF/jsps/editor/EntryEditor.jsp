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

<%-- The writing surface. RollerEditor (roller-editor.js, built from
     app/frontend by Maven) mounts on the textarea below; the textarea stays
     in the form as the posted field and the editor keeps it in sync on every
     change, so the server side never learns which editor is on the page.

     The insert menu is generated from the shortcode registry (model attribute
     shortcodeCards), so adding a sixth shortcode means writing its handler and
     nothing here. Snippets ride in a data attribute rather than inline
     JavaScript: JSTL escapes them for the attribute, and the browser hands
     back the exact text through dataset. --%>
<div class="editor-surface" id="editorSurface" data-mode="write">
    <div class="editor-toolbar" id="editorToolbar" role="toolbar" aria-label="<spring:message code='editor.toolbar'/>">
        <button type="button" class="editor-tool" data-cmd="bold" title="<spring:message code='editor.bold'/> (Ctrl+B)"><span class="bi bi-type-bold" aria-hidden="true"></span><span class="visually-hidden"><spring:message code='editor.bold'/></span></button>
        <button type="button" class="editor-tool" data-cmd="italic" title="<spring:message code='editor.italic'/> (Ctrl+I)"><span class="bi bi-type-italic" aria-hidden="true"></span><span class="visually-hidden"><spring:message code='editor.italic'/></span></button>
        <button type="button" class="editor-tool" data-cmd="heading" title="<spring:message code='editor.heading'/>"><span class="bi bi-type-h2" aria-hidden="true"></span><span class="visually-hidden"><spring:message code='editor.heading'/></span></button>
        <span class="editor-tool-sep" aria-hidden="true"></span>
        <button type="button" class="editor-tool" data-cmd="quote" title="<spring:message code='editor.quote'/>"><span class="bi bi-blockquote-left" aria-hidden="true"></span><span class="visually-hidden"><spring:message code='editor.quote'/></span></button>
        <button type="button" class="editor-tool" data-cmd="ul" title="<spring:message code='editor.bulletList'/>"><span class="bi bi-list-ul" aria-hidden="true"></span><span class="visually-hidden"><spring:message code='editor.bulletList'/></span></button>
        <button type="button" class="editor-tool" data-cmd="ol" title="<spring:message code='editor.numberedList'/>"><span class="bi bi-list-ol" aria-hidden="true"></span><span class="visually-hidden"><spring:message code='editor.numberedList'/></span></button>
        <span class="editor-tool-sep" aria-hidden="true"></span>
        <button type="button" class="editor-tool" data-cmd="link" title="<spring:message code='editor.link'/> (Ctrl+K)"><span class="bi bi-link-45deg" aria-hidden="true"></span><span class="visually-hidden"><spring:message code='editor.link'/></span></button>
        <button type="button" class="editor-tool" data-cmd="code" title="<spring:message code='editor.code'/>"><span class="bi bi-code" aria-hidden="true"></span><span class="visually-hidden"><spring:message code='editor.code'/></span></button>
        <button type="button" class="editor-tool" data-cmd="table" title="<spring:message code='editor.table'/>"><span class="bi bi-table" aria-hidden="true"></span><span class="visually-hidden"><spring:message code='editor.table'/></span></button>
        <button type="button" class="editor-tool" data-cmd="image" title="<spring:message code='weblogEdit.insertMediaFile'/>"><span class="bi bi-image" aria-hidden="true"></span><span class="visually-hidden"><spring:message code='weblogEdit.insertMediaFile'/></span></button>
        <div class="dropdown d-inline-block" id="shortcodeInsertMenu">
            <button class="editor-tool dropdown-toggle" type="button" id="shortcodeInsertButton"
                    data-bs-toggle="dropdown" aria-expanded="false"><spring:message code="weblogEdit.insertShortcode"/></button>
            <ul class="dropdown-menu" aria-labelledby="shortcodeInsertButton">
                <c:forEach items="${shortcodeCards}" var="card">
                    <li><button type="button" class="dropdown-item shortcode-card"
                                data-shortcode="<c:out value='${card.name}'/>"
                                data-snippet="<c:out value='${card.snippet}'/>"
                                data-chooser="${card.usesMediaChooser}"><spring:message code="${card.labelKey}"/></button></li>
                </c:forEach>
            </ul>
        </div>
        <div class="editor-mode" id="editorMode" role="radiogroup" aria-label="<spring:message code='editor.mode'/>">
            <button type="button" role="radio" aria-checked="true" data-mode="write"><spring:message code="editor.mode.write"/></button>
            <button type="button" role="radio" aria-checked="false" data-mode="split"><spring:message code="editor.mode.split"/></button>
            <button type="button" role="radio" aria-checked="false" data-mode="preview"><spring:message code="editor.mode.preview"/></button>
        </div>
        <button type="button" class="editor-tool editor-help" data-cmd="help" title="<spring:message code='editor.guide'/> (Ctrl+/)"><span class="bi bi-question-circle" aria-hidden="true"></span><span class="visually-hidden"><spring:message code='editor.guide'/></span></button>
    </div>
    <div class="editor-panes">
        <div class="editor-write">
            <textarea name="bean.text" id="edit_content" rows="18">${fn:escapeXml(bean.text)}</textarea>
        </div>
        <%-- The shell URL rides a data attribute rather than an inline JS
             string: JSTL escapes it for the attribute and dataset hands back
             the exact text, so a weblog handle carrying a quote (the allowed
             character set is deployer-configurable) cannot break out of a
             string literal. Same reasoning as the shortcode snippets above. --%>
        <div class="editor-preview-pane" id="editorPreviewPane" hidden aria-live="polite"
             data-shell-url="<c:out value='${previewShellURL}'/>"></div>
    </div>
    <div class="editor-status" id="editorStatus"></div>
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

            <textarea name="bean.summary" id="edit_summary" rows="10" class="col-sm-12">${fn:escapeXml(bean.summary)}</textarea>

        </div>
    </div>
</div>

<%-- ********************************************************************* --%>


<%-- Media File Insert for plain textarea editor --%>

<div id="mediafile_edit_lightbox" class="modal" role="dialog" tabindex="-1" aria-modal="true" aria-labelledby="mediafile-edit-lightbox-title">

    <div class="modal-dialog modal-lg">

        <div class="modal-content">

            <div class="modal-header">
                <h4 id="mediafile-edit-lightbox-title" class="modal-title"><spring:message code="weblogEdit.insertMediaFile"/></h4>
                <button type="button" class="btn-close" data-bs-dismiss="modal" aria-label="<spring:message code='generic.close'/>"></button>
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
                <button type="button" class="btn btn-secondary" data-bs-dismiss="modal"><spring:message code="generic.close"/></button>
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

    <%-- RollerEditor.create takes ONE onChange, and three separate things on
         this page need to know the text moved: the leave-warning dirty flag,
         local draft recovery, and (from Task A5) the live preview. The page
         owns the fan-out rather than each of them reaching into the editor,
         which is what keeps the editor swappable. --%>
    var rollerEditorChangeListeners = [];

    <%-- Toolbar commands, assigned once the editor exists. Declared out here
         because the document-level Ctrl+/ handler reads it too. --%>
    var commands = {};

    <%-- Per-install, so two Rollers on one origin do not share a mode. --%>
    var ROLLER_EDITOR_MODE_KEY = 'roller.editor.mode.v1:${pageContext.request.contextPath}';

    <%-- Click the real buttons rather than submitting the form: the buttons
         carry the formaction that decides draft-vs-publish, and a bare
         form.submit() would post to the form's own action and silently pick
         the wrong one. --%>
    function rollerSaveDraft() {
        var button = document.querySelector("#entry button[formaction$='saveDraft.rol']");
        if (button) {
            button.click();
        }
    }

    function rollerPublish() {
        var button = document.querySelector("#entry button[formaction$='publish.rol']");
        if (button) {
            button.click();
        }
    }

    $(document).ready(function () {
        <%-- The shortcuts for focus OUTSIDE the editor: the title field, the
             rail, the SEO drawer. Inside the editor they are bound on the
             editor itself instead (onSave/onPublish/onHelp below), because
             CodeMirror's own default keymap claims two of the three --
             Mod-Enter inserts a blank line and Mod-slash toggles a comment --
             and this handler cannot take a key back off it; see the bail
             below. The two halves must stay in step: a shortcut added here
             and not there works everywhere except the writing surface. --%>
        document.addEventListener('keydown', function (event) {
            <%-- A binding that DID handle the key calls preventDefault without
                 stopPropagation, so the event still reaches document -- and
                 this handler would fire a second click, i.e. two saves, or a
                 save and a publish, per Ctrl-S. Bailing on an already-handled
                 event is what keeps any such pair from overlapping. --%>
            if (event.defaultPrevented) {
                return;
            }
            if (!(event.ctrlKey || event.metaKey)) {
                return;
            }
            if (event.key === 's' || event.key === 'S') {
                event.preventDefault();
                rollerSaveDraft();
            } else if (event.key === 'Enter') {
                event.preventDefault();
                rollerPublish();
            } else if (event.key === '/' && commands.help) {
                event.preventDefault();
                commands.help();
            }
        });

        <%-- Assigned before the editor is created, because create() is
             handed commands.help as its Ctrl+/ binding. The bodies run
             later, so referring to rollerEditor here is fine. --%>
        commands = {
            bold: function () { rollerEditor.wrapSelection('**', '**', '<spring:message code="editor.boldPlaceholder" javaScriptEscape="true"/>'); },
            italic: function () { rollerEditor.wrapSelection('*', '*', '<spring:message code="editor.italicPlaceholder" javaScriptEscape="true"/>'); },
            heading: function () { rollerEditor.toggleLinePrefix('## '); },
            quote: function () { rollerEditor.toggleLinePrefix('> '); },
            ul: function () { rollerEditor.toggleLinePrefix('- '); },
            ol: function () { rollerEditor.toggleLinePrefix('1. '); },
            link: function () { rollerEditor.wrapSelection('[', '](https://)', '<spring:message code="editor.linkPlaceholder" javaScriptEscape="true"/>'); },
            code: function () { rollerEditor.wrapSelection('`', '`', 'code'); },
            table: function () { rollerEditor.insert('\n| <spring:message code="editor.tableHeader" javaScriptEscape="true"/> | <spring:message code="editor.tableHeader" javaScriptEscape="true"/> |\n| --- | --- |\n|  |  |\n'); },
            image: function () { onClickMediaFileInsert(); },
            help: function () { if (window.rollerOpenGuide) { window.rollerOpenGuide(); } }
        };
        <%-- The insert menu is the registry's own list, read back off the
             DOM: the editor's completion source and the menu therefore
             cannot disagree about what a shortcode is called. --%>
        var shortcodes = [];
        document.querySelectorAll('#shortcodeInsertMenu .shortcode-card').forEach(function (b) {
            shortcodes.push({ name: b.dataset.shortcode, snippet: b.dataset.snippet, label: b.textContent.trim() });
        });
        rollerEditor = RollerEditor.create({
            textarea: document.getElementById('edit_content'),
            placeholder: '<spring:message code="editor.placeholder" javaScriptEscape="true"/>',
            shortcodes: shortcodes,
            <%-- Bound on the editor, not just on document: CodeMirror's
                 default keymap owns Mod-Enter and Mod-slash, so the
                 document-level handler never sees them unhandled. --%>
            onSave: rollerSaveDraft,
            onPublish: rollerPublish,
            onHelp: commands.help,
            onChange: function () {
                <%-- A listener that throws must not stop the ones after it:
                     losing the dirty flag because draft recovery failed would
                     be a silent loss of the leave warning. --%>
                rollerEditorChangeListeners.forEach(function (listener) {
                    try {
                        listener();
                    } catch (e) {
                        /* one bad listener is not the others' problem */
                    }
                });
            }
        });

        <%-- The preview follows the text. Registered on the page's own fan-out
             array, not on the editor, so an editor swap carries it along. --%>
        rollerEditorChangeListeners.push(rollerSchedulePreview);

        <%-- Scroll sync, editor -> preview only. Proportional rather than
             line-mapped: a rendered gallery or map is metres taller than the
             two lines of Markdown that produced it, so there is no honest
             line-to-pixel mapping to build. Throttled to one message per
             animation frame -- a scroll event fires far faster than the frame
             can repaint, and every extra postMessage is work nobody sees. --%>
        var rollerScrollQueued = false;
        rollerEditor.view.scrollDOM.addEventListener('scroll', function () {
            if (rollerScrollQueued) {
                return;
            }
            rollerScrollQueued = true;
            window.requestAnimationFrame(function () {
                rollerScrollQueued = false;
                if (!rollerPreview.ready) {
                    return;
                }
                rollerPostToPreview({
                    type: 'roller-preview-scroll',
                    scroll: rollerEditor.scrollFraction()
                });
            });
        });

        document.getElementById('editorToolbar').addEventListener('click', function (event) {
            var button = event.target.closest('button[data-cmd]');
            if (button && commands[button.dataset.cmd]) {
                event.preventDefault();
                commands[button.dataset.cmd]();
            }
        });
        document.getElementById('editorMode').addEventListener('click', function (event) {
            var button = event.target.closest('button[data-mode]');
            if (button) {
                rollerSetEditorMode(button.dataset.mode);
            }
        });
        try {
            var storedMode = window.localStorage.getItem(ROLLER_EDITOR_MODE_KEY);
            if (storedMode) {
                rollerSetEditorMode(storedMode);
            }
        } catch (e) {
            /* storage unavailable: write mode */
        }

        <%-- Warn before leaving with unsaved edits, and stand down on submit.
             Bound ONCE, tracking a dirty flag. The previous version registered
             both leave-warning handlers *inside* the change callback, so every
             keystroke added another one of each -- and the one that actually
             mattered, another submit handler on the form about to be posted.

             The warning stays even though drafts now survive a lost tab: a
             local snapshot is a recovery mechanism, not a reason to stop
             telling someone they are walking away from unsaved work. --%>
        var rollerEntryDirty = false;
        rollerEditorChangeListeners.push(function () {
            rollerEntryDirty = true;
        });
        $("#entry").on('input change', function () {
            rollerEntryDirty = true;
        });
        <%-- Namespaced. A bare .off("beforeunload") below would unbind every
             beforeunload handler on the page, including one belonging to
             something else added later -- silently, with no signal. --%>
        $(window).on("beforeunload.rollerLeaveWarning", function (event) {
            if (!rollerEntryDirty) {
                return undefined;
            }
            if (event.originalEvent) {
                event.originalEvent.returnValue = "<spring:message code='weblogEdit.leaveWarning' javaScriptEscape='true'/>";
            }
            return "<spring:message code='weblogEdit.leaveWarning' javaScriptEscape='true'/>";
        });
        $("#entry").on('submit', function () {
            rollerEntryDirty = false;
            $(window).off("beforeunload.rollerLeaveWarning");
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
                <%-- bean.text is the editor's own textarea, already captured
                     through getText/setText. bean.status must be excluded HERE
                     but not on PageEdit: doEntryEditSave mutates the bean and
                     forwards, so the page rendered right after a successful
                     Post carries PUBLISHED while the snapshot taken at submit
                     holds DRAFT -- comparing it would raise a phantom
                     "unsaved changes" bar over a save that just succeeded. --%>
                exclude: ['bean.text', 'bean.status'],
                getText: rollerGetEntryText,
                setText: rollerSetEntryText,
                onEditorChange: function (callback) {
                    rollerEditorChangeListeners.push(callback);
                }
            });
        }

        <%-- Every card goes through insertMediaFile, the editor's one insert
             seam, so the WYSIWYG-for-Markdown surface that may replace this
             one inherits the whole menu by reimplementing a single function. --%>
        $(".shortcode-card").on('click', function (event) {
            event.preventDefault();
            if (this.dataset.chooser === 'true') {
                onClickMediaFileInsert();
            } else {
                insertMediaFile(this.dataset.snippet);
                rollerEditor.focus();
            }
        });
    });

    <%-- The preview is the weblog's own theme in an iframe, not a fragment
         dumped into a div. The shell document (PreviewServlet's ?shell=true
         branch) carries the theme stylesheet and the gallery/map/embed asset
         scripts around one empty #previewArticle; we post the server-rendered
         HTML in and it swaps and re-initialises. That is why an author sees
         their prose set the way it will publish rather than unstyled. --%>
    var rollerPreview = { frame: null, ready: false, timer: null, pending: false, inflight: false };

    function rollerEnsurePreviewFrame() {
        if (rollerPreview.frame) {
            return;
        }
        var pane = document.getElementById('editorPreviewPane');
        var frame = document.createElement('iframe');
        <%-- allow-same-origin is required, not optional: without it the frame
             gets an opaque origin, its postMessage arrives as "null" and the
             origin check on both sides refuses it. The document is ours and
             same-origin already, so this grants nothing new. --%>
        frame.setAttribute('sandbox', 'allow-scripts allow-same-origin');
        frame.setAttribute('title', '<spring:message code="editor.mode.preview" javaScriptEscape="true"/>');
        frame.src = pane.dataset.shellUrl;
        pane.appendChild(frame);
        rollerPreview.frame = frame;
        <%-- Registered once, with the frame. The shell announces itself when
             its document is ready, so the first push waits for that rather
             than guessing -- and because the ensure/push pair below is
             idempotent, re-entering preview mode costs nothing. --%>
        window.addEventListener('message', function (event) {
            if (event.origin !== window.location.origin) {
                return;
            }
            if (event.data && event.data.type === 'roller-preview-ready') {
                rollerPreview.ready = true;
                rollerPushPreview();
            }
        });
    }

    <%-- Debounced: a POST per keystroke would render the whole shortcode
         pipeline on every character. 400ms of idle is below "did it hang?" --%>
    function rollerSchedulePreview() {
        if (document.getElementById('editorSurface').dataset.mode === 'write') {
            return;
        }
        window.clearTimeout(rollerPreview.timer);
        rollerPreview.timer = window.setTimeout(rollerPushPreview, 400);
    }

    <%-- The preview is rendered by the SERVER, not by a Markdown library in
         the browser. Only the server can expand [gallery], [map] and the rest,
         and a preview that disagreed with the published page about those would
         be worse than no preview at all.

         In-flight coalescing rather than cancellation: a slow render must not
         be overtaken by a later one whose response arrives first, which would
         paint stale text over new. At most one request is out; anything that
         happens while it is out collapses into a single follow-up. --%>
    function rollerPushPreview() {
        if (!rollerPreview.ready) {
            return;
        }
        if (rollerPreview.inflight) {
            rollerPreview.pending = true;
            return;
        }
        rollerPreview.inflight = true;
        $.ajax({
            type: 'POST',
            url: '<c:url value="/roller-ui/authoring/entryEdit!preview.rol"/>',
            data: {
                id: $("input[name='bean.id']").val(),
                text: rollerGetEntryText(),
                weblog: $("input[name='weblog']").val(),
                '${_csrf.parameterName}': '${_csrf.token}'
            },
            success: function (html) {
                rollerPostToPreview({ type: 'roller-preview', html: html, scroll: rollerEditor.scrollFraction() });
            },
            error: function () {
                rollerPostToPreview({
                    type: 'roller-preview',
                    html: '<p class="roller-preview-error"><spring:message code="weblogEdit.previewFailed" javaScriptEscape="true"/></p>'
                });
            },
            complete: function () {
                rollerPreview.inflight = false;
                if (rollerPreview.pending) {
                    rollerPreview.pending = false;
                    rollerPushPreview();
                }
            }
        });
    }

    <%-- One place that talks to the frame, so the origin argument cannot
         drift between the three callers. --%>
    function rollerPostToPreview(message) {
        if (!rollerPreview.frame || !rollerPreview.frame.contentWindow) {
            return;
        }
        rollerPreview.frame.contentWindow.postMessage(message, window.location.origin);
    }

    <%-- Mode is a data attribute on the surface; CSS lays the panes out. --%>
    function rollerSetEditorMode(mode) {
        var surface = document.getElementById('editorSurface');
        surface.dataset.mode = mode;
        document.querySelectorAll('#editorMode button[data-mode]').forEach(function (b) {
            b.setAttribute('aria-checked', b.dataset.mode === mode ? 'true' : 'false');
        });
        var pane = document.getElementById('editorPreviewPane');
        pane.hidden = (mode === 'write');
        if (mode !== 'write') {
            rollerEnsurePreviewFrame();
            rollerPushPreview();
        }
        try {
            window.localStorage.setItem(ROLLER_EDITOR_MODE_KEY, mode);
        } catch (e) {
            /* storage unavailable: the choice simply does not persist */
        }
    }

    <%-- The one seam for putting text into the editor, used by the media
         chooser (which inserts an [image id=..] shortcode) and by the browser
         tests. Everything else talks to the editor through here, so swapping
         the editor again does not mean hunting down its callers. --%>
    function insertMediaFile(toInsert) {
        rollerEditor.insert(toInsert);
    }

    function rollerSetEntryText(text) {
        rollerEditor.setValue(text);
    }

    function rollerGetEntryText() {
        return rollerEditor.getValue();
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
