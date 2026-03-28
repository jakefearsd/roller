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

<form id="createPostForm" method="post">
<input type="hidden" name="weblog" value='${actionWeblog.handle}'/>
    <input type="hidden" name="selectedImage" id="selectedImage"/>
    <input type="hidden" name="type" id="type"/>
<sec:csrfInput/>
</form>


<%-- ********************************************************************* --%>

<%-- Subtitle and folder path --%>

<c:choose>
<c:when test='${currentDirectory.name.equals("default")}'>

    <p class="subtitle">
        <spring:message code="mediaFileView.subtitle" arguments="${weblog}"/>
    </p>
    <p class="pagetip">
        <spring:message code="mediaFileView.rootPageTip"/>
    </p>

</c:when>
<c:when test='${pager}'>

    <p class="subtitle">
        <spring:message code="mediaFileView.searchTitle"/>
    </p>
    <p class="pagetip">

            <%-- display summary of the search results and terms --%>

        <c:choose>
<c:when test="${pager.items.size() > 0}">
        <spring:message code="mediaFileView.matchingResults" arguments="${pager.items.size()}"/>
        </c:when>
<c:otherwise>
            <spring:message code="mediaFileView.noResults"/>
        </c:otherwise>
</c:choose><spring:message code="mediaFileView.searchInfo"/>

    <ul>
        <c:if test="${!bean.name.isEmpty()}">
            <li>
                <spring:message code="mediaFileView.filesNamed" arguments="${bean.name}"/>
            </li>
        </c:if>
        <c:if test="${bean.size > 0}">
            <li>
                <spring:message code="mediaFileView.filesOfSize" arguments="${bean.sizeFilterTypeLabel},${bean.size},${bean.sizeUnitLabel}"/>
            </li>
        </c:if>
        <c:if test="${!bean.type.isEmpty()}">
            <li>
                <spring:message code="mediaFileView.filesOfType" arguments="${bean.typeLabel}"/>
            </li>
        </c:if>
        <c:if test="${!bean.tags.isEmpty()}">
            <li>
                <spring:message code="mediaFileView.filesTagged" arguments="${bean.tags}"/>
            </li>
        </c:if>
    </ul>

</c:when>

<c:otherwise>

    <p class="subtitle">
        <spring:message code="mediaFileView.folderName"/>: ${currentDirectory.name}
    </p>
    <p class="pagetip">
        <spring:message code="mediaFileView.dirPageTip"/>
    </p>

</c:otherwise>
</c:choose><c:if test="${childFiles || (pager && pager.items.size() > 0)}">

    <form id="mediaFileViewForm" name="mediaFileViewForm" action="${pageContext.request.contextPath}/roller-ui/authoring/mediaFileView.rol" method="post">
<input type="hidden" name="weblog" value="${weblog}"/>
        <input type="hidden" name="directoryId" value="${directoryId}"/>
        <input type="hidden" name="newDirectoryName" value="${newDirectoryName}"/>
        <input type="hidden" name="mediaFileId" value=""/>

        <div class="image-controls">

            <c:if test="${!allDirectories.isEmpty}">
                <%-- Folder to View combo-box --%>
                <span><spring:message code="mediaFileView.viewFolder"/>:</span>
                <select name="viewDirectoryId" id="viewDirectoryMenu" class="form-control" onchange="onView()">
<c:forEach items="${allDirectories}" var="opt">
<option value="${opt.id}" ${opt.id == viewDirectoryId ? 'selected' : ''}>${opt.name}</option>
</c:forEach>
</select>
            </c:if>

            <span><spring:message code="mediaFileView.sortBy"/>:</span>
            <select name="sortBy" id="sortByMenu" class="form-control" onchange="document.mediaFileViewForm.submit();">
<c:forEach items="${sortOptions}" var="opt">
<option value="${opt.key}" ${opt.key == sortBy ? 'selected' : ''}>${opt.value}</option>
</c:forEach>
</select>

        </div>


        <%-- ***************************************************************** --%>

        <%-- Media file folder contents --%>

        <script>
            function highlight(el, flag) {
                if (flag) {
                    $(el).addClass("highlight");
                } else {
                    $(el).removeClass("highlight");
                }
            }
        </script>

        <div id="imageGrid" class="panel panel-default">
            <div class="panel-body">

                <ul>

                    <c:choose>
<c:when test="${!pager}">

                        <%-- ----------------------------------------------------- --%>

                        <%-- NOT SEARCH RESULTS --%>

                        <c:if test="${childFiles.size() ==0}">
                            <spring:message code="mediaFileView.noFiles"/>
                        </c:if>

                        <%-- List media files --%>

                        <c:forEach items="${childFiles}" var="mediaFile">

                            <li class="align-images"
                                onmouseover="highlight(this, true)" onmouseout="highlight(this, false)">

                                <div class="mediaObject" onclick="onClickEdit(
                                        '${mediaFile.id}',
                                        '${mediaFile.name}' )">

                                    <c:choose>
<c:when test="${mediaFile.imageFile}">
                                        <img border="0" src='${mediaFile.thumbnailURL}'
                                             width='${mediaFile.thumbnailWidth}'
                                             height='${mediaFile.thumbnailHeight}'
                                             title='${mediaFile.name}'
                                             alt='${mediaFile.name}'
                                            <%-- onclick="onClickEdit('${mediaFile.id}')" --%> />
                                    </c:when>
<c:otherwise>
                                        <c:url var="mediaFileURL" value="/images/page.png"/>
                                        <img border="0" src='${mediaFileURL}'
                                             style="padding:40px 50px;"
                                             alt='${mediaFile.name}'
                                            <%-- onclick="onClickEdit('${mediaFile.id}')" --%> />
                                    </c:otherwise>
                                    </c:choose>
                                </div>

                                <div class="mediaObjectInfo">

                                    <input type="checkbox"
                                           name="selectedMediaFiles"
                                           value="${mediaFile.id}"/>
                                    <input type="hidden" id="mediafileidentity"
                                           value="${mediaFile.id}"/>

                                    <str:truncateNicely lower="47" upper="47">
                                        ${mediaFile.name}
                                    </str:truncateNicely>

                                </div>

                            </li>

                        </c:forEach>

                    </c:when>
<c:otherwise>

                        <%-- ----------------------------------------------------- --%>

                        <%-- SEARCH RESULTS --%>

                        <c:forEach items="${pager.items}" var="mediaFile">

                            <li class="align-images"
                                onmouseover="highlight(this, true)" onmouseout="highlight(this, false)">

                                <div class="mediaObject" onclick="onClickEdit(
                                        '${mediaFile.id}',
                                        '${mediaFile.name}' )">

                                    <c:choose>
<c:when test="${mediaFile.imageFile}">
                                        <img border="0" src='${mediaFile.thumbnailURL}'
                                             width='${mediaFile.thumbnailWidth}'
                                             height='${mediaFile.thumbnailHeight}'
                                             title='${mediaFile.name}'
                                             alt='${mediaFile.name}'/>
                                    </c:when>
<c:otherwise>
                                        <c:url var="mediaFileURL" value="/images/page.png"/>
                                        <img border="0" src='${mediaFileURL}'
                                             style="padding:40px 50px;" alt='${mediaFile.name}'/>
                                    </c:otherwise>
</c:choose></div>

                                <div class="mediaObjectInfo">

                                    <input type="checkbox"
                                           name="selectedMediaFiles"
                                           value="${mediaFile.id}"/>
                                    <input type="hidden" id="mediafileidentity"
                                           value="${mediaFile.id}">

                                    <str:truncateNicely lower="40" upper="50">
                                        ${mediaFile.name}
                                    </str:truncateNicely>

                                    <span class="button" id="addbutton-${mediaFile.id}">
                                    <img id="addbutton-img${mediaFile.id}"
                                         src="<c:url value="/images/add.png"/>"/>
                                </span>

                                </div>

                            </li>

                        </c:forEach>

                    </c:otherwise>
</c:choose></ul>

            </div>
        </div>

        <div style="clear:left;"></div>

        <c:if test="${(!pager && childFiles.size() > 0) || (pager && pager.items.size() > 0) || (currentDirectory.name != 'default' && !pager)}">

            <div class="image-controls">

                <c:if test="${(!pager && childFiles.size() > 0) || (pager && pager.items.size() > 0)}">
                    <input id="toggleButton" type="button" class="btn" style="display: inline"
                           value='<spring:message code="generic.toggle"/>' onclick="onToggle()"/>

                    <input id="deleteButton" type="button" class="btn btn-danger" style="display: inline"
                           value='<spring:message code="mediaFileView.deleteSelected"/>' onclick="onDeleteSelected()"/>

                    <input id="moveButton" type="button" class="btn btn-primary" style="display: inline"
                           value='<spring:message code="mediaFileView.moveSelected"/>' onclick="onMoveSelected()"/>
                </c:if>

                <select name="selectedDirectory" id="moveTargetMenu" class="form-control" style="display: inline; width: 15em">
<c:forEach items="${allDirectories}" var="opt">
<option value="${opt.id}" ${opt.id == selectedDirectory ? 'selected' : ''}>${opt.name}</option>
</c:forEach>
</select>

                <c:if test="${currentDirectory.name != 'default' && !pager}">
                    <input id="deleteFolderButton" type="button" class="btn" style="display: inline"
                           value='<spring:message code="mediaFileView.deleteFolder"/>' onclick="onDeleteFolder()"/>
                </c:if>

            </div>

        </c:if>

    <sec:csrfInput/>
</form>

</c:if>


<%-- ================================================================================================ --%>

<%-- view image modal --%>

<div id="mediafile_edit_lightbox" class="modal fade" tabindex="-1" role="dialog">

    <div class="modal-dialog modal-lg">

        <div class="modal-content">

            <div class="modal-header">
                <h3 class="subtitle">
                    <spring:message code="mediaFileEdit.subtitle"/><b><span id="edit-subtitle"></span></b>
                </h3>
            </div>

            <div class="modal-body">
                <iframe id="mediaFileEditor"
                        style="visibility:inherit"
                        height="700" <%-- pixels, sigh, this is suboptimal--%>
                        width="100%"
                        frameborder="no"
                        scrolling="auto">
                </iframe>
            </div>

            <div class="modal-footer"></div>

        </div>
    </div>

</div>


<script>
    toggleState = 'Off';

    function onClickEdit(mediaFileId, mediaFileName) {
        <c:url var="mediaFileEditURL" value="/roller-ui/authoring/mediaFileEdit.rol">
        <c:param name="weblog" value="${actionWeblog.handle}"/>
        </c:url>
        $('#edit-subtitle').html(mediaFileName);
        $('#mediaFileEditor').attr('src', '${mediaFileEditURL}' + '&mediaFileId=' + mediaFileId);
        $('#mediafile_edit_lightbox').modal({show: true});
    }

    function onEditSuccess() {
        onEditCancelled();
        document.mediaFileViewForm.submit();
    }

    function onEditCancelled() {
        $('#mediafile_edit_lightbox').modal('hide');
        $("#mediaFileEditor").attr('src', 'about:blank');
    }

    function onSelectDirectory(id) {
        window.location = "<c:url value="/roller-ui/authoring/mediaFileView.rol"/>?directoryId="
            + id + "&weblog=" + '${actionWeblog.handle}';
    }

    function onToggle() {
        if (toggleState === 'Off') {
            toggleState = 'On';
            toggleFunction(true, 'selectedMediaFiles');
            $("#deleteButton").attr('disabled', false);
            $("#moveButton").attr('disabled', false);
            $("#moveTargetMenu").attr('disabled', false);
        } else {
            toggleState = 'Off';
            toggleFunction(false, 'selectedMediaFiles');
            $("#deleteButton").attr('disabled', true);
            $("#moveButton").attr('disabled', true);
            $("#moveTargetMenu").attr('disabled', true);
        }
    }

    function onDeleteSelected() {
        if (confirm("<spring:message code="mediaFile.delete.confirm"/>")) {
            document.mediaFileViewForm.action = '<c:url value="/roller-ui/authoring/mediaFileView!deleteSelected.rol"/>';
            document.mediaFileViewForm.submit();
        }
    }

    function onDeleteFolder() {
        if (confirm("<spring:message code="mediaFile.deleteFolder.confirm"/>")) {
            document.mediaFileViewForm.action = '<c:url value="/roller-ui/authoring/mediaFileView!deleteFolder.rol"/>';
            document.mediaFileViewForm.submit();
        }
    }

    function onMoveSelected() {
        if (confirm("<spring:message code="mediaFile.move.confirm"/>")) {
            document.mediaFileViewForm.action = '<c:url value="/roller-ui/authoring/mediaFileView!moveSelected.rol"/>';
            document.mediaFileViewForm.submit();
        }
    }

    function onView() {
        document.mediaFileViewForm.action = "<c:url value='/roller-ui/authoring/mediaFileView!view.rol'/>";
        document.mediaFileViewForm.submit();
    }

    <%-- code to toggle buttons on/off as media file/directory selections change --%>

    $(document).ready(function () {
        $("#deleteButton").attr('disabled', true);
        $("#moveButton").attr('disabled', true);
        $("#moveTargetMenu").attr('disabled', true);

        $("input[type=checkbox]").change(function () {
            var count = 0;
            $("input[type=checkbox]").each(function (index, element) {
                if (element.checked) count++;
            });
            if (count === 0) {
                $("#deleteButton").attr('disabled', true);
                $("#moveButton").attr('disabled', true);
                $("#moveTargetMenu").attr('disabled', true);
            } else {
                $("#deleteButton").attr('disabled', false);
                $("#moveButton").attr('disabled', false);
                $("#moveTargetMenu").attr('disabled', false);
            }
        });
    });

</script>