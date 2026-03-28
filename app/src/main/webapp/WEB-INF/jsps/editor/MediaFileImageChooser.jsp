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

<style>

</style>

<script>
    function onSelectDirectory(id) {
        window.location = "?directoryId=" + id + "&weblog=" + '${actionWeblog.handle}';
    }
</script>


<%-- ********************************************************************* --%>

<%-- Subtitle and folder path --%>

<c:if test="${childFiles || allDirectories}">

    <form id="mediaFileChooserForm" name="mediaFileChooserForm" action="${pageContext.request.contextPath}/roller-ui/authoring/mediaFileImageChooser.rol" method="post" class="form-vertical">
<input type="hidden" name="weblog" value="${weblog}"/>
        <input type="hidden" name="mediaFileId" value=""/>

        <p class="pagetip"><spring:message code="mediaFileImageChooser.pageTip"/></p>

        <%-- ***************************************************************** --%>
        <%-- Maybe show media directory selector --%>

        <c:if test="${!allDirectories.isEmpty}">
            <select name="directoryId" class="form-control" onchange="onView()">
<option value=""></option>
<c:forEach items="${allDirectories}" var="opt">
<option value="${opt.id}" ${opt.id == directoryId ? 'selected' : ''}>${opt.name}</option>
</c:forEach>
</select>
        </c:if>

        <%-- ***************************************************************** --%>
        <%-- Media files grid --%>

        <div id="imageGrid" class="panel panel-default">
            <div class="panel-body">

                <ul>

                    <c:if test="${childFiles.size() == 0}">
                        <p style="text-align: center"><spring:message code="mediaFileView.noFiles"/></p>
                    </c:if>

                    <c:if test="${childFiles.size() > 0}">

                        <c:forEach items="${childFiles}" var="mediaFile">

                            <c:set var="mediaFileURL" value="${mediaFile.permalink}"/>
                            <c:url var="mediaFileThumbnailURL" value="${mediaFile.thumbnailURL}"/>

                            <li class="align-images"
                                onmouseover="highlight(this, true)" onmouseout="highlight(this, false)">

                                <div class="mediaObject"
                                     onclick="onSelectMediaFile('${mediaFile.name}',
                                             '${mediaFileURL}',
                                             '${mediaFile.isImageFile()}')">

                                    <c:choose>
<c:when test="${mediaFile.imageFile}">
                                        <img border="0" src='${mediaFileThumbnailURL}'
                                             width='${mediaFile.thumbnailWidth}'
                                             height='${mediaFile.thumbnailHeight}'
                                             alt='${mediaFile.name}'/>
                                    </c:when>
<c:otherwise>
                                        <span class="glyphicon glyphicon-file"></span>
                                    </c:otherwise>
                                    </c:choose>
                                </div>

                                <div class="mediaObjectInfo">
                                    <str:truncateNicely upper="60">
                                        ${mediaFile.name}
                                    </str:truncateNicely>
                                </div>

                            </li>

                        </c:forEach>
                    </c:if>

                </ul>
            </div>
        </div>

        <div style="clear:left;"></div>

    <sec:csrfInput/>
</form>

</c:if>


<script>

    function onSelectMediaFile(name, url, isImage) {
        window.parent.onSelectMediaFile(name, url, isImage);
    }

    function highlight(el, flag) {
        if (flag) {
            $(el).addClass("highlight");
        } else {
            $(el).removeClass("highlight");
        }
    }

    function onView() {
        document.mediaFileChooserForm.submit();
    }

</script>
