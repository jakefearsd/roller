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

<p class="pagetip">
    <spring:message code="mediaFileEdit.pagetip"/>
</p>

<form id="entry" class="form-stacked" action="${pageContext.request.contextPath}/roller-ui/authoring/mediaFileEdit!save.rol" method="POST" enctype="multipart/form-data">
<input type="hidden" name="weblog" value="${actionWeblog.handle}"/>
    <input type="hidden" name="mediaFileId" value="${mediaFileId}" id="mediaFileId"/>
    <input type="hidden" name="bean.permalink" value="${bean.permalink}"/>

    <c:if test="${bean.isImage}">
        <div class="row mb-3">
            <label class="col-form-label col-sm-3">Thumbnail</label>
            <div class="col-sm-9">
                <a href='${bean.permalink}' target="_blank">
                    <img alt="thumbnail" src='${bean.thumbnailURL}'
                         title='<spring:message code="mediaFileEdit.clickToView"/>'/>
                </a>
            </div>
        </div>

        <%-- ============================================================== --%>
        <%-- Focal point: click-to-set marker, saved with the main form     --%>

        <div class="row mb-3">
            <label class="col-form-label col-sm-3"><spring:message code="mediaFileEdit.focalPoint"/></label>
            <div class="col-sm-9">
                <div id="focalPicker" style="position:relative; display:inline-block; cursor:crosshair; line-height:0">
                    <img id="focalImage" src='${bean.permalink}' alt="focal point target"
                         style="max-width:240px; max-height:240px; display:block"/>
                    <span id="focalMarker"
                          style="position:absolute; width:14px; height:14px; margin:-7px 0 0 -7px;
                                 border:2px solid #fff; border-radius:50%;
                                 background:color-mix(in srgb, var(--bad) 85%, transparent); box-shadow:0 0 3px #000;
                                 pointer-events:none; display:none"></span>
                </div>
                <div class="form-text"><spring:message code="mediaFileEdit.focalPoint.tip"/></div>
                <button type="button" id="clearFocalButton" class="btn btn-sm btn-outline-secondary mt-1">
                    <spring:message code="mediaFileEdit.focalPoint.clear"/>
                </button>
                <input type="hidden" name="bean.focalX" id="focalX" value="${bean.focalX}"/>
                <input type="hidden" name="bean.focalY" id="focalY" value="${bean.focalY}"/>
            </div>
        </div>
    </c:if>

    <%-- ================================================================== --%>
    <%-- Title, category, dates and other metadata --%>

    <div class="row mb-3">
        <label class="col-sm-3 col-form-label"><spring:message code="generic.name"/></label>
        <div class="col-sm-9">
            <input type="text" name="bean.name" value="${bean.name}" size="35" maxlength="100" tabindex="1" class="form-control"/>
        </div>
    </div>

    <div class="row mb-3">
        <label class="col-form-label col-sm-3"><spring:message code="mediaFileEdit.fileInfo"/></label>

        <div class="col-sm-9">

            <spring:message code="mediaFileEdit.fileTypeSize" arguments="${bean.contentType},${bean.length}"/>

            <c:if test="${bean.isImage}">
                <spring:message code="mediaFileEdit.fileDimensions" arguments="${bean.width},${bean.height}"/>
            </c:if>

        </div>
    </div>

    <div class="row mb-3">
        <label class="col-form-label col-sm-3">URL</label>

        <div class="col-sm-9">

            <input type="text" id="clip_text" size="57"
                   value='${bean.permalink}' readonly />

            <c:url var="linkIconURL" value="/roller-ui/images/clippy.svg"/>
            <button class="clipbutton" data-clipboard-target="#clip_text" type="button">
                <img src='${linkIconURL}' alt="Copy to clipboard" style="width:0.9em; height:0.9em">
            </button>

        </div>
    </div>

    <div class="row mb-3">
        <label class="col-sm-3 col-form-label"><spring:message code="generic.description"/></label>
        <div class="col-sm-9">
            <textarea name="bean.description" rows="2" cols="50" tabindex="2" class="form-control">${bean.description}</textarea>
        </div>
    </div>

    <div class="row mb-3">
        <label class="col-sm-3 col-form-label"><spring:message code="mediaFileEdit.tags"/></label>
        <div class="col-sm-9">
            <input type="text" name="bean.tagsAsString" value="${bean.tagsAsString}" size="30" maxlength="100" tabindex="3" class="form-control"/>
        </div>
    </div>

    <div class="row mb-3">
        <label class="col-sm-3 col-form-label"><spring:message code="mediaFileEdit.copyright"/></label>
        <div class="col-sm-9">
            <input type="text" name="bean.copyrightText" value="${bean.copyrightText}" size="30" maxlength="100" tabindex="4" class="form-control"/>
        </div>
    </div>

    <div class="row mb-3">
        <label class="col-sm-3 col-form-label"><spring:message code="mediaFileEdit.directory"/></label>
        <div class="col-sm-9">
            <select name="bean.directoryId" class="form-select" tabindex="5">
                <c:forEach items="${allDirectories}" var="opt">
                    <option value="${opt.id}" ${opt.id == bean.directoryId ? 'selected' : ''}>${opt.name}</option>
                </c:forEach>
            </select>
        </div>
    </div>

    <div class="row mb-3">
        <div class="offset-sm-3 col-sm-9">
            <label><input type="checkbox" name="bean.sharedForGallery" value="true" ${bean.sharedForGallery ? 'checked' : ''} tabindex="6"/> <spring:message code="mediaFileEdit.includeGalleryHelp"/></label>
        </div>
    </div>

    <!-- original path from base URL of ctx/resources/ -->
    <c:if test="${rc:getBooleanProp('mediafile.originalPathEdit.enabled')}">
        <div id="originalPathdiv" class="miscControl">
            <input type="text" name="bean.originalPath" value="${bean.originalPath}" id="originalPath" size="30" maxlength="100" tabindex="3" class="form-control"/>
        </div>
    </c:if>


    <input type="submit" tabindex="7" class="btn btn-success"
           value="<spring:message code="generic.save"/>" name="submit"/>
    <input type="button" tabindex="8" class="btn"
           value="<spring:message code="generic.cancel"/>" onClick="window.parent.onEditCancelled();"/>

<sec:csrfInput/>
</form>

<%-- ================================================================== --%>
<%-- Crop: Cropper.js v2 custom elements over the original image;       --%>
<%-- destructive server-side re-encode behind a confirm dialog          --%>

<c:if test="${bean.croppable}">
    <hr/>
    <h5 id="cropSectionTitle"><spring:message code="mediaFileEdit.crop.title"/></h5>
    <p class="pagetip"><spring:message code="mediaFileEdit.crop.tip"/></p>

    <cropper-canvas id="cropCanvas" background style="width:100%; height:360px">
        <cropper-image id="cropImage" src='${bean.permalink}' alt="crop target"></cropper-image>
        <cropper-shade hidden></cropper-shade>
        <cropper-handle action="select" plain></cropper-handle>
        <cropper-selection id="cropSelection" initial-coverage="0.9" movable resizable>
            <cropper-grid role="grid" covered></cropper-grid>
            <cropper-crosshair centered></cropper-crosshair>
            <cropper-handle action="move" theme-color="rgba(255, 255, 255, 0.35)"></cropper-handle>
            <cropper-handle action="n-resize"></cropper-handle>
            <cropper-handle action="e-resize"></cropper-handle>
            <cropper-handle action="s-resize"></cropper-handle>
            <cropper-handle action="w-resize"></cropper-handle>
            <cropper-handle action="ne-resize"></cropper-handle>
            <cropper-handle action="nw-resize"></cropper-handle>
            <cropper-handle action="se-resize"></cropper-handle>
            <cropper-handle action="sw-resize"></cropper-handle>
        </cropper-selection>
    </cropper-canvas>

    <form id="cropForm" class="mt-2" action="${pageContext.request.contextPath}/roller-ui/authoring/mediaFileEdit!crop.rol" method="POST">
        <input type="hidden" name="weblog" value="${actionWeblog.handle}"/>
        <input type="hidden" name="mediaFileId" value="${mediaFileId}"/>
        <input type="hidden" name="cropX" id="cropX" value="0"/>
        <input type="hidden" name="cropY" id="cropY" value="0"/>
        <input type="hidden" name="cropWidth" id="cropWidth" value="0"/>
        <input type="hidden" name="cropHeight" id="cropHeight" value="0"/>
        <button type="submit" id="cropButton" class="btn btn-danger">
            <spring:message code="mediaFileEdit.crop.apply"/>
        </button>
        <sec:csrfInput/>
    </form>

    <c:url var="cropperJsURL" value="/webjars/cropperjs/2.1.0/dist/cropper.min.js"/>
    <script src="${cropperJsURL}"></script>
    <script>
        (function () {
            // The stored dimensions already describe the DISPLAYED
            // (orientation-corrected) image, which is also what the browser
            // renders, so selection math and server-side crop math agree.
            var naturalWidth = Number('${bean.width}');
            var naturalHeight = Number('${bean.height}');

            // This page is loaded into an iframe inside a Bootstrap modal, and
            // MediaFileView.jsp sets that iframe's src BEFORE the modal has
            // finished fading in. Whenever this document wins that race the
            // cropper custom elements upgrade while the iframe still has no
            // layout box at all: <cropper-selection> sizes its
            // initial-coverage selection from <cropper-canvas>.offsetWidth,
            // which is 0 then, and Cropper v2 never recomputes it (it observes
            // no resize; $initSelection runs only from connectedCallback). The
            // user is then shown a cropper with an invisible 0x0 selection and
            // a Crop button that silently does nothing. Seed the selection
            // ourselves the moment the canvas actually gains a size - and only
            // while it is still empty, so we never fight a selection the user
            // has already drawn.
            function seedSelectionOnceLaidOut() {
                var canvas = document.getElementById('cropCanvas');
                var selection = document.getElementById('cropSelection');
                if (!canvas || !selection || typeof selection.$change !== 'function') {
                    return;
                }
                if (selection.width > 0 && selection.height > 0) {
                    return;
                }
                var coverage = selection.initialCoverage;
                if (!(coverage > 0) || coverage > 1) {
                    coverage = 0.9;
                }
                var canvasWidth = canvas.offsetWidth;
                var canvasHeight = canvas.offsetHeight;
                var width = canvasWidth * coverage;
                var height = canvasHeight * coverage;
                if (!(width > 0) || !(height > 0)) {
                    return;
                }
                selection.$change((canvasWidth - width) / 2, (canvasHeight - height) / 2,
                        width, height);
            }

            if (window.ResizeObserver) {
                new ResizeObserver(seedSelectionOnceLaidOut)
                        .observe(document.getElementById('cropCanvas'));
            } else {
                seedSelectionOnceLaidOut();
            }

            function cropRectInNaturalPixels() {
                var canvas = document.getElementById('cropCanvas');
                var image = document.getElementById('cropImage');
                var selection = document.getElementById('cropSelection');
                var canvasRect = canvas.getBoundingClientRect();
                var imageRect = image.getBoundingClientRect();
                if (!(imageRect.width > 0) || !(imageRect.height > 0)) {
                    return null;
                }
                if (!(naturalWidth > 0) || !(naturalHeight > 0)) {
                    // pre-ladder upload without stored dimensions: fall back
                    // to the decoded image itself
                    var img = image.$image;
                    naturalWidth = img ? img.naturalWidth : 0;
                    naturalHeight = img ? img.naturalHeight : 0;
                    if (!(naturalWidth > 0)) {
                        return null;
                    }
                }
                var scaleX = naturalWidth / imageRect.width;
                var scaleY = naturalHeight / imageRect.height;
                return {
                    x: Math.round((selection.x + canvasRect.left - imageRect.left) * scaleX),
                    y: Math.round((selection.y + canvasRect.top - imageRect.top) * scaleY),
                    width: Math.round(selection.width * scaleX),
                    height: Math.round(selection.height * scaleY)
                };
            }

            document.getElementById('cropForm').addEventListener('submit', function (event) {
                var rect = cropRectInNaturalPixels();
                if (rect === null || !(rect.width > 0) || !(rect.height > 0)) {
                    event.preventDefault();
                    return;
                }
                if (!confirm('<spring:message code="mediaFileEdit.crop.confirm" javaScriptEscape="true"/>')) {
                    event.preventDefault();
                    return;
                }
                document.getElementById('cropX').value = rect.x;
                document.getElementById('cropY').value = rect.y;
                document.getElementById('cropWidth').value = rect.width;
                document.getElementById('cropHeight').value = rect.height;
            });
        })();
    </script>
</c:if>

<script>
    $(document).ready(function () {
        new ClipboardJS('.clipbutton');

        // ------------------------------------------------- focal point
        var picker = document.getElementById('focalPicker');
        if (picker) {
            var marker = document.getElementById('focalMarker');
            var focalX = document.getElementById('focalX');
            var focalY = document.getElementById('focalY');

            function showMarker(fx, fy) {
                marker.style.left = (fx * 100) + '%';
                marker.style.top = (fy * 100) + '%';
                marker.style.display = 'block';
            }

            if (focalX.value !== '' && focalY.value !== '') {
                showMarker(parseFloat(focalX.value), parseFloat(focalY.value));
            }

            picker.addEventListener('click', function (event) {
                var image = document.getElementById('focalImage');
                var rect = image.getBoundingClientRect();
                if (!(rect.width > 0) || !(rect.height > 0)) {
                    return;
                }
                var fx = Math.min(1, Math.max(0, (event.clientX - rect.left) / rect.width));
                var fy = Math.min(1, Math.max(0, (event.clientY - rect.top) / rect.height));
                focalX.value = fx.toFixed(3);
                focalY.value = fy.toFixed(3);
                showMarker(fx, fy);
            });

            document.getElementById('clearFocalButton').addEventListener('click', function () {
                focalX.value = '';
                focalY.value = '';
                marker.style.display = 'none';
            });
        }
    });
</script>
