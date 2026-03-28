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

<form id="entry" action="${pageContext.request.contextPath}/roller-ui/authoring/mediaFileEdit!save.rol" method="POST" enctype="multipart/form-data" class="form-horizontal">
<input type="hidden" name="weblog" value="${weblog}"/>
    <input type="hidden" name="mediaFileId" value="${mediaFileId}" id="mediaFileId"/>
    <input type="hidden" name="bean.permalink" value="${bean.permalink}"/>

    <c:if test="${bean.isImage}">
        <div class="form-group">
            <label class="control-label col-sm-3">Thumbnail</label>
            <div class="controls col-sm-9">
                <a href='${bean.permalink}' target="_blank">
                    <img alt="thumbnail" src='${bean.thumbnailURL}'
                         title='<spring:message code="mediaFileEdit.clickToView"/>'/>
                </a>
            </div>
        </div>
    </c:if>

    <%-- ================================================================== --%>
    <%-- Title, category, dates and other metadata --%>

    <input type="text" name="bean.name" value="${bean.name}" size="35" maxlength="100" tabindex="1" class="form-control"/>

    <div class="form-group">
        <label class="control-label col-sm-3"><spring:message code="mediaFileEdit.fileInfo"/></label>

        <div class="controls col-sm-9">

            <spring:message code="mediaFileEdit.fileTypeSize" arguments="${bean.contentType},${bean.length}"/>

            <c:if test="${bean.isImage}">
                <spring:message code="mediaFileEdit.fileDimensions" arguments="${bean.width},${bean.height}"/>
            </c:if>

        </div>
    </div>

    <div class="form-group">
        <label class="control-label col-sm-3">URL</label>

        <div class="controls col-sm-9">

            <input type="text" id="clip_text" size="57" 
                   value='${bean.permalink}' readonly />

            <c:url var="linkIconURL" value="/roller-ui/images/clippy.svg"/>
            <button class="clipbutton" data-clipboard-target="#clip_text" type="button">
                <img src='${linkIconURL}' alt="Copy to clipboard" style="width:0.9em; height:0.9em">
            </button>

        </div>
    </div>

    <textarea name="bean.description" rows="2" cols="50" tabindex="2">${bean.description}</textarea>

    <input type="text" name="bean.tagsAsString" value="${bean.tagsAsString}" size="30" maxlength="100" tabindex="3" class="form-control"/>

    <input type="text" name="bean.copyrightText" value="${bean.copyrightText}" size="30" maxlength="100" tabindex="4" class="form-control"/>

    <select name="bean.directoryId" class="form-control" tabindex="5">
<c:forEach items="${allDirectories}" var="opt">
<option value="${opt.id}" ${opt.id == bean.directoryId ? 'selected' : ''}>${opt.name}</option>
</c:forEach>
</select>

    <input type="checkbox" name="bean.sharedForGallery" value="true" ${bean.sharedForGallery ? 'checked' : ''} tabindex="6"/>

    <!-- original path from base URL of ctx/resources/ -->
    <c:if test="${getBooleanProp('mediafile.originalPathEdit.enabled')}">
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


<script>
    $(document).ready(function () {
        new ClipboardJS('.clipbutton');
    });
</script>
