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
<input type="hidden" name="weblog" value="${actionWeblog.handle}"/>

    <%-- Two choices side-by-side: choose Shared or Custom Theme --%>

    <div class="row row-display-flex">

        <div class="col-6">
            <div class="card">
                <div class="card-body" id="sharedChooser">
                    <h3>
                        <input id="sharedRadio" type="radio" name="themeType" value="shared"
                            <c:if test="${!customTheme}">checked</c:if> onclick="proposeThemeTypeChange($(this))"/>&nbsp;
                        <spring:message code="themeEditor.sharedTheme"/>
                    </h3>
                    <spring:message code="themeEditor.sharedThemeDescription"/>
                </div>
            </div>
        </div>

        <%-- Offered only when the installation allows custom themes. The
             controller refuses the switch either way; this stops the page
             advertising a choice that would be rejected. A weblog already on a
             custom theme keeps the control, matching the controller's
             grandfathering rule. --%>
        <c:if test="${customThemeAllowed}">
        <div class="col-6">
            <div class="card">
                <div class="card-body" id="customChooser">
                    <h3>
                        <input id="customRadio" type="radio" name="themeType" value="custom"
                            <c:if test="${customTheme}">checked</c:if> onclick="proposeThemeTypeChange($(this))"/>&nbsp;
                        <spring:message code="themeEditor.customTheme"/>
                    </h3>
                    <spring:message code="themeEditor.customThemeDescription"/>
                </div>
            </div>
        </div>
        </c:if>

    </div>

    <%-- ================================================= --%>

    <div id="sharedNoChange" style="display:none;">

        <%-- you have shared theme X --%>
        <p class="lead">
            <spring:message code="themeEditor.yourCurrentTheme"/>&nbsp;<b>${actionWeblog.theme.name}</b>
            <c:choose>
<c:when test="${sharedThemeCustomStylesheet}">
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
        <p><img id="themeThumbnail" src="" class="img-fluid img-thumbnail" style="max-width: 30em" /></p>

    </div>

    <%-- ================================================= --%>

    <%-- Below: four mutually-exclusive "something is about to change" blocks,
         each with its own copy of the Preview/Update/Cancel action row (kept
         per-block, not consolidated, so existing IT selectors like
         "#sharedChangeToShared button[type='submit']" keep working). Exactly
         one of these six state blocks (this four, plus #sharedNoChange /
         #customNoChange above) may be visible at a time -- updateView() below
         now calls hideAll() unconditionally on every call, first, before
         deciding what to show, rather than relying on each branch's own
         hide()/show() pairs to have covered every other block. That
         unconditional reset is the actual fix for "three Update Theme
         buttons visible at once": some transitions (moving from a *NoChange
         state straight into a *ChangeTo* one) previously showed the new
         block's button without ever hiding the old block's, so more than one
         stayed in the DOM, visible, simultaneously. --%>

    <div id="sharedChangeToShared" style="display:none;">

        <div class="alert alert-warning" style="margin-top:3em; margin-bottom:2em; padding: 1em">
            <spring:message code="themeEditor.proposedSharedThemeChange"/>
        </div>

        <%-- Preview and Update buttons --%>
        <p> <spring:message code="themeEditor.previewDescription"/> </p>
        <input type="button" name="themePreview" class="btn"
            value="<spring:message code="themeEditor.preview"/>"
            onclick="fullPreview($('#themeSelector').get(0))"/>

        <button type="submit" class="btn btn-secondary"><spring:message code="themeEditor.save"/></button>

        <input type="button" class="btn" onclick="cancelChanges()" value="<spring:message code="generic.cancel"/>" />

    </div>

    <div id="sharedChangeToCustom" style="display:none;">

        <div class="alert alert-warning" style="margin-top:3em; margin-bottom:2em; padding: 1em">
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
            <label><input type="checkbox" name="importTheme" value="true" ${importTheme ? 'checked' : ''}/> <spring:message code="themeEditor.importAndOverwriteTemplates"/></label>
        </c:otherwise>
</c:choose><%-- Update button --%>
        <button type="submit" class="btn btn-secondary"><spring:message code="themeEditor.save"/></button>

        <input type="button" class="btn" onclick="cancelChanges()" value="<spring:message code="generic.cancel"/>" />

    </div>

    <div id="customNoChange" style="display:none;">
        <p class="lead"><spring:message code="themeEditor.youAreUsingACustomTheme"/></p>
    </div>

    <div id="customChangeToShared" style="display:none;">

        <div class="alert alert-warning" style="margin-top:3em; margin-bottom:2em; padding: 1em">
            <spring:message code="themeEditor.proposedChangeToShared"/>
        </div>

        <%-- Preview and Update buttons --%>
        <p> <spring:message code="themeEditor.previewDescription"/> </p>
        <input type="button" name="themePreview" class="btn"
            value="<spring:message code="themeEditor.preview"/>"
            onclick="fullPreview($('#themeSelector').get(0))"/>

        <button type="submit" class="btn btn-secondary"><spring:message code="themeEditor.save"/></button>

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

    <%-- Shows exactly one of the six state blocks for the given radio
         selection. hideAll() runs first on every call -- unconditionally --
         so this never has to rely on the *previous* call having hidden the
         right things; that was the bug (see the comment above the state
         blocks in the markup): some transitions showed the new block without
         hiding the old one, leaving more than one Update Theme button
         visible at once. --%>
    function updateView(selected) {

        changed =
               (proposedThemeId    !== "" && proposedThemeId    !== originalThemeId)
            || (proposedChangeType !== "" && proposedChangeType !== originalType )

        hideAll();

        if (selected[0].value === 'shared') {

            $('#sharedChooser').addClass("chooser-selected")
            $('#customChooser').removeClass("chooser-selected")

            $('#themeChooser').show();

            if ( !changed ) {
                $('#sharedNoChange').show();
            } else if ( originalType === "shared" ) {
                $('#sharedChangeToShared').show();
            } else {
                $('#customChangeToShared').show();
            }

        } else {

            $('#sharedChooser').removeClass("chooser-selected")
            $('#customChooser').addClass("chooser-selected")

            if ( !changed ) {
                $('#customNoChange').show();
            } else {
                $('#sharedChangeToCustom').show();
            }

        }
    }

</script>
