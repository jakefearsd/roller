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

<a href="#" onClick="onClickMediaFileInsert();"><spring:message code="weblogEdit.insertMediaFile"/></a><br/>
<img src="<c:url value='/roller-ui/images/spacer.png'/>" alt="spacer" style="min-height: 2em"/>

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

<div id="mediafile_edit_lightbox" class="modal fade" role="dialog">

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

    $(document).ready(function () {
        $('#edit_content').summernote({
                toolbar: [
                    // [groupName, [list of button]]
                    ['style', ['bold', 'italic', 'underline', 'clear']],
                    ['font', ['strikethrough', 'superscript', 'subscript']],
                    ['fontsize', ['fontsize']],
                    ['color', ['color']],
                    ['para', ['ul', 'ol', 'paragraph']],
                    ['height', ['height']],
                    ['misc', ['codeview']],
                    ['insert', ['link']]
                ],
                height: 400
            }
        );
        // Added event listener to confirm once the editor content is changed
        $('#edit_content').on('summernote.change', function(we, contents, $editable) {
            var confirmFunction = function(event) {
                // Chrome requires returnValue to be set and original event is found as originalEvent
                // see https://developer.mozilla.org/en-US/docs/Web/API/WindowEventHandlers/onbeforeunload#Example
                if (event.originalEvent)
                    event.originalEvent.returnValue = "Are you sure you want to leave?";
                return "Are you sure you want to leave?";
            }
            $(window).on("beforeunload", confirmFunction);

            // Remove it if it is form submit
            $(this.form).on('submit', function() {
                $(window).off("beforeunload", confirmFunction);
            });
        });
    });

    function insertMediaFile(toInsert) {
        $('#edit_content').summernote("pasteHTML", toInsert);
    }

    <%-- Common functions --%>

    <%-- Opens the media chooser. With no argument the chosen file is inserted
         into the Summernote editor (the historic behavior); with a picker
         target ('featuredImage' / 'ogImage') the choice is routed to
         onImagePicked in EntryEdit.jsp instead. --%>
    function onClickMediaFileInsert(pickerTarget) {
        window.mediaPickerTarget = pickerTarget || null;
        <c:url var="mediaFileImageChooser" value="/roller-ui/authoring/overlay/mediaFileImageChooser.rol">
        <c:param name="weblog" value="${actionWeblog.handle}"/>
        </c:url>
        $("#mediaFileEditor").attr('src', '${mediaFileImageChooser}');
        bootstrap.Modal.getOrCreateInstance(document.getElementById('mediafile_edit_lightbox')).show();
    }

    function onClose() {
        $("#mediaFileEditor").attr('src', 'about:blank');
    }

    <%-- Callback from MediaFileImageChooser.jsp inside the iframe. The id is a
         later addition; callers that only pass (name, url, isImage) still work. --%>
    function onSelectMediaFile(name, url, isImage, id) {
        bootstrap.Modal.getOrCreateInstance(document.getElementById('mediafile_edit_lightbox')).hide();
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
