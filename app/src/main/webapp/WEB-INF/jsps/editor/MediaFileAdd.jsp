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


<p class="subtitle"> <spring:message code="mediaFileAdd.title"/> </p>
<p class="pagetip"> <spring:message code="mediaFileAdd.pageTip"/> </p>

<form id="entry" action="${pageContext.request.contextPath}/roller-ui/authoring/mediaFileAdd!save.rol" method="POST" enctype="multipart/form-data" class="form-horizontal">
<input type="hidden" name="weblog" value="${actionWeblog.handle}"/>
    <input type="hidden" name="directoryName" value="${directoryName}"/>

    <div class="form-group">
        <label class="col-sm-3 control-label"><spring:message code="generic.name"/></label>
        <div class="col-sm-9">
            <input type="text" id="entry_bean_name" name="bean.name" value="${bean.name}" maxlength="255" class="form-control"/>
        </div>
    </div>

    <div class="form-group">
        <label class="col-sm-3 control-label"><spring:message code="generic.description"/></label>
        <div class="col-sm-9">
            <textarea name="bean.description" rows="3" class="form-control">${bean.description}</textarea>
        </div>
    </div>

    <div class="form-group">
        <label class="col-sm-3 control-label"><spring:message code="mediaFileAdd.copyright"/></label>
        <div class="col-sm-9">
            <textarea name="bean.copyrightText" rows="3" class="form-control">${bean.copyrightText}</textarea>
        </div>
    </div>

    <div class="form-group">
        <label class="col-sm-3 control-label"><spring:message code="mediaFileAdd.tags"/></label>
        <div class="col-sm-9">
            <input type="text" name="bean.tagsAsString" value="${bean.tagsAsString}" maxlength="255" class="form-control"/>
        </div>
    </div>

    <div class="form-group">
        <label class="col-sm-3 control-label"><spring:message code="mediaFileAdd.directory"/></label>
        <div class="col-sm-9">
            <select name="bean.directoryId" class="form-control">
                <c:forEach items="${allDirectories}" var="opt">
                    <option value="${opt.id}" ${opt.id == bean.directoryId ? 'selected' : ''}>${opt.name}</option>
                </c:forEach>
            </select>
        </div>
    </div>

    <div class="form-group">
        <div class="col-sm-offset-3 col-sm-9">
            <label><input type="checkbox" name="bean.sharedForGallery" value="true" ${bean.sharedForGallery ? 'checked' : ''}/> <spring:message code="mediaFileAdd.includeGallery"/></label>
        </div>
    </div>

    <div class="panel panel-default">
        <div class="panel-heading">
            <h4 class="panel-title">
                <spring:message code="mediaFileAdd.fileLocation"/>
            </h4>
        </div>
        <div class="panel-body">
            <input type="file" name="uploadedFiles" id="fileControl0" size="30"/>
            <input type="file" name="uploadedFiles" id="fileControl1" size="30"/>
            <input type="file" name="uploadedFiles" id="fileControl2" size="30"/>
            <input type="file" name="uploadedFiles" id="fileControl3" size="30"/>
            <input type="file" name="uploadedFiles" id="fileControl4" size="30"/>
        </div>
    </div>

    <button type="submit" id="uploadButton" class="btn btn-default" formaction="${pageContext.request.contextPath}/roller-ui/authoring/mediaFileAdd!save.rol"><spring:message code="mediaFileAdd.upload"/></button>
    <button type="submit" class="btn" formaction="${pageContext.request.contextPath}/roller-ui/authoring/mediaFileAdd!cancel.rol"><spring:message code="generic.cancel"/></button>

<sec:csrfInput/>
</form>


<%-- ================================================================== --%>

<script>

    $(document).ready(function () {

        $("input[type='file']").change(function () {

            var name = '';
            var count = 0;
            var fileControls = $("input[type='file']");

            for (var i = 0; i < fileControls.length; i++) {
                if (jQuery.trim(fileControls.get(i).value).length > 0) {
                    count++;
                    name = fileControls.get(i).value;
                }
            }

            var entryBean = $("#entry_bean_name");
            if (count === 1) {
                entryBean.get(0).disabled = false;
                entryBean.get(0).value = getFileName(name);

            } else if (count > 1) {
                entryBean.css("font-style", "italic");
                entryBean.css("color", "grey");
                entryBean.get(0).value = "<spring:message code="mediaFileAdd.multipleNames"/>";
                entryBean.get(0).disabled = true;
            }

            if (count > 0) {
                $("#uploadButton:first").attr("disabled", false)
            }
        });

        $("#uploadButton:first").attr("disabled", true)
    });

    function getFileName(fullName) {
        var backslashIndex = fullName.lastIndexOf('/');
        var fwdslashIndex = fullName.lastIndexOf('\\');
        var fileName;
        if (backslashIndex >= 0) {
            fileName = fullName.substring(backslashIndex + 1);
        } else if (fwdslashIndex >= 0) {
            fileName = fullName.substring(fwdslashIndex + 1);
        }
        else {
            fileName = fullName;
        }
        return fileName;
    }

</script>