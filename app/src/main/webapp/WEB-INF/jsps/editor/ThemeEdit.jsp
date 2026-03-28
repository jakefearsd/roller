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

<p class="subtitle">
    <spring:message code="themeEditor.subtitle" arguments="${actionWeblog.handle}"/>
</p>

<form action="${pageContext.request.contextPath}/roller-ui/authoring/themeEdit!save.rol" method="post" class="form-vertical">
<input type="hidden" name="weblog" value="${weblog}"/>

    <%-- Two choices side-by-side: choose Shared or Custom Theme --%>

    <div class="row row-display-flex">

        <div class="col-xs-6">
            <div class="panel panel-default">
                <div class="panel-body" id="sharedChooser">
                    <h3>
                        <input id="sharedRadio" type="radio" name="themeType" value="shared"
                            <c:choose>
<c:when test="${!customTheme}">checked</c:if> onclick="proposeThemeTypeChange($(this))"/>&nbsp;
                        <spring:message code="themeEditor.sharedTheme"/>
                    </h3>
                    <spring:message code="themeEditor.sharedThemeDescription"/>
                </div>
            </div>
        </div>

        <div class="col-xs-6">
            <div class="panel panel-default">
                <div class="panel-body" id="customChooser">
                    <h3>
                        <input id="customRadio" type="radio" name="themeType" value="custom"
                            <c:if test="${customTheme}">checked</c:if> onclick="proposeThemeTypeChange($(this))"/>&nbsp;
                        <spring:message code="themeEditor.customTheme"/>
                    </h3>
                    <spring:message code="themeEditor.customThemeDescription"/>
                </div>
            </div>
        </div>

    </div>

    <%-- ================================================= --%>

    <div id="sharedNoChange" style="display:none;">

        <%-- you have shared theme X --%>
        <p class="lead">
            <spring:message code="themeEditor.yourCurrentTheme"/>
            <b>${actionWeblog.theme.name}</b>
            <c:if test="${${sharedThemeCustomStylesheet}}">
                <spring:message code="themeEditor.yourCustomStylesheet"/>
            </c:when>
<c:otherwise>
                <spring:message code="themeEditor.yourThemeStyleSheet"/>
            </c:otherwise>
</c:choose></p>

    </div>

    <%-- ================================================= --%>

    <div id="themeChooser" style="display:none;">

        <%-- theme selector with preview image --%>
        <p class="lead"><spring:message code="themeEditor.selectTheme"/></p>
        <p>
            <select name="selectedThemeId" id="themeSelector" class="form-control" style="width:20em" size="1" onchange="proposeSharedThemeChange(this[selectedIndex].value)">
<c:forEach items="${themes}" var="opt">
<option value="${opt.id}" ${opt.id == selectedThemeId ? 'selected' : ''}>${opt.name}</option>
</c:forEach>
</select>
        </p>
        <p><spring:message code="themeEditor.thisTheme"/> <p id="themeDescription"></p>
        <p><img id="themeThumbnail" src="" class="img-responsive img-thumbnail" style="max-width: 30em" /></p>

    </div>

    <%-- ================================================= --%>

    <div id="sharedChangeToShared" style="display:none;">

        <div class="alert-warning" style="margin-top:3em; margin-bottom:2em; padding: 1em">
            <spring:message code="themeEditor.proposedSharedThemeChange"/>
        </div>

        <%-- Preview and Update buttons --%>
        <p> <spring:message code="themeEditor.previewDescription"/> </p>
        <input type="button" name="themePreview" class="btn"
            value="<spring:message code="themeEditor.preview"/>"
            onclick="fullPreview($('#themeSelector').get(0))"/>

        <button type="submit" class="btn btn-default"><spring:message code="themeEditor.save"/></button>

        <input type="button" class="btn" onclick="cancelChanges()" value="<spring:message code="generic.cancel"/>" />

    </div>

    <%-- ================================================= --%>

    <div id="sharedChangeToCustom" style="display:none;">

        <div class="alert-warning" style="margin-top:3em; margin-bottom:2em; padding: 1em">
            <spring:message code="themeEditor.proposedSharedChangeToCustom"/>
        </div>

        <c:choose>
<c:when test="${firstCustomization}">
            <p>
                <spring:message code="themeEditor.importRequired"/>
                <input type="hidden" name="importTheme" value="${true}"/>
            </p>
        </c:when>
<c:otherwise>
            <p><spring:message code="themeEditor.existingTemplatesWarning"/></p>
            <input type="checkbox" name="importTheme" value="true" ${importTheme ? 'checked' : ''}/>
        </c:otherwise>
</c:choose><%-- Update button --%>
        <button type="submit" class="btn btn-default"><spring:message code="themeEditor.save"/></button>

        <input type="button" class="btn" onclick="cancelChanges()" value="<spring:message code="generic.cancel"/>" />

    </div>

    <%-- ================================================= --%>

    <div id="customNoChange" style="display:none;">
        <p class="lead"><spring:message code="themeEditor.youAreUsingACustomTheme"/></p>
    </div>

    <%-- ================================================= --%>

    <div id="customChangeToShared" style="display:none;">

        <div class="alert-warning" style="margin-top:3em; margin-bottom:2em; padding: 1em">
            <spring:message code="themeEditor.proposedChangeToShared"/>
        </div>

        <%-- Preview and Update buttons --%>
        <p> <spring:message code="themeEditor.previewDescription"/> </p>
        <input type="button" name="themePreview" class="btn"
            value="<spring:message code="themeEditor.preview"/>"
            onclick="fullPreview($('#themeSelector').get(0))"/>

        <button type="submit" class="btn btn-default"><spring:message code="themeEditor.save"/></button>

        <input type="button" class="btn" onclick="cancelChanges()" value="<spring:message code="generic.cancel"/>" />

    </div>

<sec:csrfInput/>
</form>

<script type="text/javascript">

    var proposedChangeType = ""
    var proposedThemeId = ""
    var originalThemeId = "${themeId}"
    var originalType = ""

    $.when( $.ready ).then(function() {

        <c:choose>
<c:when test="${customTheme}">
        originalType = "custom"
        updateView($('#customRadio'));
        previewImage('${themes[0].id}');
        </c:when>
<c:otherwise>
        originalType = "shared"
        updateView($('#sharedRadio'));
        previewImage('${themeId}');
        </c:otherwise>
</c:choose>});

    function proposeThemeTypeChange(selected) {

        if (selected[0].value === 'shared') {
            proposedChangeType = "shared"

            themeSelector = $('#themeSelector')[0]
            index = themeSelector.selectedIndex;
            previewImage(themeSelector.options[index].value)

        } else {
            proposedThemeId = originalThemeId
            proposedChangeType = "custom"
        }
        updateView(selected)
    }

    function proposeSharedThemeChange(themeId) {
        proposedThemeId = themeId;
        previewImage(themeId)
        updateView($('#sharedRadio'))
    }

    function cancelChanges() {

        proposedThemeId = originalThemeId;
        proposedChangeType = originalType;

        hideAll();

        if ( originalType === "custom" ) {
            $("#sharedRadio").prop("checked", false);
            $("#customRadio").prop("checked", true);
            updateView($("#customRadio"));

        } else {
            $("#sharedRadio").prop("checked", true);
            $("#customRadio").prop("checked", false);
            updateView($("#sharedRadio"));
            $("#themeSelector").val(originalThemeId).change();
            previewImage(originalThemeId)
        }

    }

    function hideAll() {
        $('#themeChooser').hide();
        $('#customNoChange').hide();
        $('#customChangeToShared').hide();
        $('#sharedChangeToShared').hide();
        $('#sharedNoChange').hide();
        $('#sharedChangeToCustom').hide();
    }

    function previewImage(themeId) {
        $.ajax({
            url: "<c:url value='themedata'/>",
            data: {theme: themeId}, success: function (data) {
                $('#themeDescription').html(data.description);
                thumbnail = $('#themeThumbnail');
                thumbnail.attr('src', '${siteURL}' + data.previewPath);
            }
        });
    }

    function fullPreview(selector) {
        selected = selector.selectedIndex;
        window.open('<c:url value="/roller-ui/authoring/preview/${actionWeblog.handle}"/>?theme='
            + selector.options[selected].value);
    }

    function updateView(selected) {

        changed =
               (proposedThemeId    !== "" && proposedThemeId    !== originalThemeId)
            || (proposedChangeType !== "" && proposedChangeType !== originalType )

        if (selected[0].value === 'shared') {

            $('#sharedChooser').css("background", "#bfb")
            $('#customChooser').css("background", "white")

            $('#themeChooser').show();

            $('#customNoChange').hide();
            $('#customChangeToShared').hide();

            if ( !changed ) {
                $('#sharedNoChange').show();
                $('#sharedChangeToShared').hide();
                $('#sharedChangeToCustom').hide();

            } else {

                if ( originalType === "shared" ) {
                    $('#sharedChangeToShared').show();
                    $('#sharedChangeToCustom').hide();
                }  else {
                    $('#customChangeToShared').show();
                    $('#sharedChangeToShared').hide();
                    $('#sharedChangeToCustom').hide();
                }
            }

        } else {

            $('#sharedChooser').css("background", "white")
            $('#customChooser').css("background", "#bfb")

            $('#themeChooser').hide();

            $('#sharedNoChange').hide();
            $('#sharedChangeToShared').hide();
            $('#sharedChangeToCustom').hide();

            $('#customChangeToShared').hide();

            if ( !changed ) {
                $('#customNoChange').show();
            } else {
                $('#sharedChangeToCustom').show();
                $('#customNoChange').hide();
            }

        }
    }

</script>
