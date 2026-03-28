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


<p class="subtitle"><spring:message code="mediaFileSuccess.subtitle"/></p>
<p class="pagetip"><spring:message code="mediaFileSuccess.pageTip"/></p>

<form id="entry" method="post" class="form-horizontal">
<input type="hidden" name="weblog" value="${actionWeblog.handle}"/>
    <input type="hidden" name="bean.enclosureURL" value="${bean.enclosureURL}" id="enclosureURL"/>

    <c:if test="${newImages.size() > 0}">
        <h4><spring:message code="mediaFileSuccess.selectImagesTitle"/></h4>
        <p><spring:message code="mediaFileSuccess.selectImages"/></p>

        <%-- select images via checkboxes --%>

        <c:forEach items="${newImages}" var="newImage">

            <div class="panel panel-default">
                <div class="panel-body">

                    <div class="row">

                        <div class="col-md-1">
                            <input type="checkbox" name="selectedImages" value="${newImage.id}"/>
                        </div>

                        <div class="col-md-2">
                            <img align="center" class="mediaFileImage"
                                 src='${newImage.thumbnailURL}' alt="thumbnail"/>
                        </div>

                        <div class="col-md-9">
                            <p>
                                <b><spring:message code="mediaFileSuccess.name"/></b>
                                ${newImage.name}
                            </p>

                            <p>
                                <b><spring:message code="mediaFileSuccess.type"/></b>
                                ${newImage.contentType}
                            </p>

                            <p>
                                <b><spring:message code="mediaFileSuccess.size"/></b>
                                ${newImage.length} <spring:message code="mediaFileSuccess.bytes"/>,
                                ${newImage.width} x
                                ${newImage.height} <spring:message code="mediaFileSuccess.pixels"/>
                            </p>

                            <p>
                                <b><spring:message code="mediaFileSuccess.link"/></b>
                                ${newImage.permalink}
                            </p>
                        </div>

                    </div>

                </div>
            </div>

        </c:forEach>

    </c:if>

    <c:if test="${newFiles.size() > 0}">

        <%-- select enclosure file via radio boxes --%>

        <h4><spring:message code="mediaFileSuccess.selectEnclosureTitle"/></h4>
        <p><spring:message code="mediaFileSuccess.selectEnclosure"/></p>

        <c:forEach items="${newFiles}" var="newFile">
            <div class="panel panel-default">
                <div class="panel-body">

                    <div class="row">

                        <div class="col-md-1">
                            <input type="radio" name="enclosure"
                                   onchange="setEnclosure('${newFile.permalink}')"/>
                        </div>

                        <div class="col-md-11">
                            <p>
                                <b><spring:message code="mediaFileSuccess.name"/></b>
                                ${newFile.name}
                            </p>

                            <p>
                                <b><spring:message code="mediaFileSuccess.type"/></b>
                                ${newFile.contentType},&nbsp;

                                <b><spring:message code="mediaFileSuccess.size"/></b>
                                ${newFile.length} <spring:message code="mediaFileSuccess.bytes"/>,
                                ${newFile.width} x
                                ${newFile.height} <spring:message code="mediaFileSuccess.pixels"/>
                            </p>

                            <p>
                                <b><spring:message code="mediaFileSuccess.link"/></b>
                                ${newFile.permalink}
                            </p>
                        </div>

                    </div>

                </div>
            </div>
        </c:forEach>

        <div class="panel panel-default">
            <div class="panel-body">
                <div class="row">

                    <div class="col-md-1">
                        <input type="radio" name="enclosure" onchange="setEnclosure('')" />
                    </div>

                    <div class="col-md-10">
                        <spring:message code="mediaFileSuccess.noEnclosure"/>
                    </div>

                </div>
            </div>
        </div>

    </c:if>

    <%-- buttons for create new weblog, cancel and upload more --%>

    <div>
        <c:url var="mediaFileAddURL" value="/roller-ui/authoring/mediaFileAdd.rol">
            <c:param name="weblog" value="${actionWeblog.handle}"/>
            <c:param name="directoryName" value="${directoryName}"/>
        </c:url>

        <c:url var="mediaFileViewURL" value="/roller-ui/authoring/mediaFileView.rol">
            <c:param name="weblog" value="${actionWeblog.handle}"/>
            <c:param name="directoryId" value="${bean.directoryId}"/>
        </c:url>

        <button type="submit" id="submit" class="btn btn-success" formaction="${pageContext.request.contextPath}/roller-ui/authoring/entryAddWithMediaFile.rol"><spring:message code="mediaFileSuccess.createPost"/></button>

        <button class="btn btn-default" onclick='window.load("${mediaFileAddURL}")'>
            <spring:message code="mediaFileSuccess.uploadMore"/>
        </button>

        <button class="btn" onclick='window.load("${mediaFileViewURL}")'>
            <spring:message code="generic.cancel"/>
        </button>
    </div>

<sec:csrfInput/>
</form>


<%-- ================================================================================= --%>

<script>

    var submitButton = $("#submit");

    $(document).ready(function () {
        $("#submit").attr("disabled", true);

        $("input[type='checkbox']").change(function () {
            if ($("#enclosureURL").get(0).getAttribute("value") !== '') {
                $("#submit").attr("disabled", false);
                return;
            }
            submitButton.attr("disabled", !isImageChecked());
        });
    });

    function isImageChecked() {
        var boxes = $("input[type='checkbox']");
        for (var i = 0; i < boxes.length; i++) {
            if (boxes.get(i).checked) {
                return true;
            }
        }
        return false;
    }

    function setEnclosure(url) {
        $("#enclosureURL").get(0).value = url;
        if (isImageChecked()) {
            $("#submit").attr("disabled", false);
            return;
        }
        submitButton.attr("disabled", url === '');
    }

</script>
