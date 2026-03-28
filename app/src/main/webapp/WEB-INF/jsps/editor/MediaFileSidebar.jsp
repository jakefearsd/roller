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

<div class="sidebarFade">
    <div class="menu-tr">
        <div class="menu-tl">
            <div class="sidebarInner">

                <h3><spring:message code="mediaFileSidebar.actions"/></h3>

                <div style="clear:right">
                    <span class="glyphicon glyphicon-picture"></span>
                    <c:url var="mediaFileAddURL" value="/roller-ui/authoring/mediaFileAdd.rol">
                        <c:param name="weblog" value="${actionWeblog.handle}"/>
                        <c:param name="directoryName" value="${directoryName}"/>
                    </c:url>
                    <a href='<c:out value="${mediaFileAddURL}" escapeXml="false"/>'
                            <c:if test="${actionName.equals('mediaFileAdd')}"> style='font-weight:bold;'</c:if> >
                        <spring:message code="mediaFileSidebar.add"/>
                    </a>
                </div>

                <c:if test="${!pager}">
                    <%-- Only show Create New Directory control when NOT showing search results --%>

                    <div style="clear:right; margin-top: 1em">

                        <span class="glyphicon glyphicon-folder-open"></span>
                        <spring:message code="mediaFileView.addDirectory"/> <br />

                        <label for="newDirectoryName">
                            <spring:message code="mediaFileView.directoryName"/>
                        </label>
                        <input type="text" id="newDirectoryName" name="newDirectoryName" size="8" maxlength="25"/>

                        <input type="button" id="newDirectoryButton" class="btn btn-primary" style="clear:left"
                               value='<spring:message code="mediaFileView.create"/>' onclick="onCreateDirectory()"/>

                    </div>
                </c:if>

                <hr size="1" noshade="noshade"/>

                <h3><spring:message code="mediaFileView.search"/></h3>

                <form id="mediaFileSearchForm" name="mediaFileSearchForm" action="${pageContext.request.contextPath}/roller-ui/authoring/mediaFileView!search.rol" method="post" class="form-vertical">
<input type="hidden" name="weblog" value="${weblog}"/>
                    <input type="hidden" name="mediaFileId" value=""/>

                    <input type="text" name="bean.name" value="${bean.name}" id="beanName" size="20" maxlength="255" class="form-control"/>

                    <select name="bean.type" id="beanType" class="form-control">
<c:forEach items="${fileTypes}" var="opt">
<option value="${opt.key}" ${opt.key == bean.type ? 'selected' : ''}>${opt.value}</option>
</c:forEach>
</select>

                    <select name="bean.sizeFilterType" id="sizeFilterTypeCombo" class="form-control">
<c:forEach items="${sizeFilterTypes}" var="opt">
<option value="${opt.key}" ${opt.key == bean.sizeFilterType ? 'selected' : ''}>${opt.value}</option>
</c:forEach>
</select>

                    <input type="text" name="bean.size" value="${bean.size}" id="beanSize" size="3" maxlength="10" class="form-control"/>

                    <select name="bean.sizeUnit" class="form-control">
<c:forEach items="${sizeUnits}" var="opt">
<option value="${opt.key}" ${opt.key == bean.sizeUnit ? 'selected' : ''}>${opt.value}</option>
</c:forEach>
</select>

                    <input type="text" name="bean.tags" value="${bean.tags}" id="beanTags" size="20" maxlength="50" class="form-control"/>

                    <button type="submit" id="searchButton" class="btn btn-primary" style="margin:5px 0;"><spring:message code="mediaFileView.search"/></button>

                    <c:if test="${pager}">
                        <input id="resetButton" style="margin:5px 0;" type="button" class="btn"
                               name="reset" value='<spring:message code="mediaFileView.reset"/>'/>
                    </c:if>

                <sec:csrfInput/>
</form>

            </div>
        </div>
    </div>
</div>


<script>

    function onCreateDirectory() {
        document.mediaFileViewForm.newDirectoryName.value = $("#newDirectoryName").get(0).value;
        document.mediaFileViewForm.action = '<c:url value="/roller-ui/authoring/mediaFileView!createNewDirectory.rol"/>';
        document.mediaFileViewForm.submit();
    }

    $("#newDirectoryButton").ready(function () {
        $("#newDirectoryName").bind("keyup", maintainDirectoryButtonState);
        $("#newDirectoryButton").attr("disabled", true);
    });

    function maintainDirectoryButtonState(e) {
        if (jQuery.trim($("#newDirectoryName").get(0).value).length === 0) {
            $("#newDirectoryButton").attr("disabled", true);
        } else {
            $("#newDirectoryButton").attr("disabled", false);
        }
    }

    $("#searchButton").ready(function () {

        maintainSearchButtonState();
        $("input").bind("keyup", maintainSearchButtonState);
        $("select").bind("change", maintainSearchButtonState);

        $("#resetButton").bind("click", function () {
            <c:url var="mediaFileViewURL" value="/roller-ui/authoring/mediaFileView.rol">
            <c:param name="weblog" value="${actionWeblog.handle}"/>
            </c:url>
            window.location = '${mediaFileViewURL}';
        });
    });

    function maintainSearchButtonState(e) {
        var beanSize = $("#beanSize").get(0).value;
        var beanType = $("#beanType").get(0).value;

        if (jQuery.trim($("#beanName").get(0).value).length === 0
            && jQuery.trim($("#beanTags").get(0).value).length === 0
            && (jQuery.trim(beanSize).length === 0 || beanSize === 0)
            && (beanType.length === 0 || beanType === "mediaFileView.any")) {
            $("#searchButton").attr("disabled", true);
        } else {
            $("#searchButton").attr("disabled", false);
        }
    }

</script>
