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
    <spring:message code="pageForm.subtitle" arguments="${bean.name},${actionWeblog.handle}"/>
</p>

<c:choose>
<c:when test="${template.required}">
    <p class="pagetip"><spring:message code="pageForm.tip.required"/></p>
</c:when>
<c:otherwise>
    <p class="pagetip"><spring:message code="pageForm.tip"/></p>
</c:otherwise>
</c:choose><form id="template" action="${pageContext.request.contextPath}/roller-ui/authoring/templateEdit!save.rol" method="post" class="form-vertical">
<input type="hidden" name="weblog" value="${weblog}"/>
    <input type="hidden" name="bean.id" value="${bean.id}"/>
    <input type="hidden" name="bean.type" value="${bean.type}"/>

    <%-- ================================================================== --%>
    <%-- Name, link and description: disabled when page is a required page --%>

    <c:choose>
<c:when test="${template.required || bean.mobile}">
        <%-- Cannot edit name of a reqired template --%>
        <input type="text" name="bean.name" value="${bean.name}" size="50" readonly class="form-control" style="background: #e5e5e5"/>
    </c:when>
<c:otherwise>
        <input type="text" name="bean.name" value="${bean.name}" size="50" class="form-control"/>
    </c:otherwise>
</c:choose><input type="text" name="bean.action" value="${bean.action}" size="50" readonly class="form-control" style="background: #e5e5e5"/>

    <c:choose>
<c:when test="${!template.required && template.custom}">

        <%-- allow setting the path for a custom template --%>
        <input type="text" name="bean.link" value="${bean.link}" size="50" class="form-control" onkeyup="updatePageURLDisplay()"/>

        <%-- show preview of the full URL that will result from that path --%>

        <div id="no_link" class="alert-danger" style="display: none; margin-top:3em; margin-bottom:2em; padding: 1em">
            <spring:message code="pageForm.noUrl"/>
        </div>

        <div id="good_link" class="alert-success"
             style="display: none; margin-top:3em; margin-bottom:2em; padding: 1em">
            <spring:message code="pageForm.resultingUrlWillBe"/>
            ${actionWeblog.absoluteURL}page/
            <span id="linkPreview" style="color:red">${bean.link}</span>
            <c:if test="${template.link != null}">
                [<a id="launchLink" onClick="launchPage()"><spring:message code="pageForm.launch"/></a>]
            </c:if>
        </div>

    </c:if>

    <c:if test="${template.required}">
        <%-- Required templates have a description--%>
        <textarea name="bean.description" rows="2" cols="50" readonly style="background: #e5e5e5">${bean.description}</textarea>
    </c:when>
<c:otherwise>
        <textarea name="bean.description" rows="2" cols="50">${bean.description}</textarea>
    </c:otherwise>
</c:choose><%-- ================================================================== --%>

    <%-- Tabs for each of the two content areas: Standard and Mobile --%>
    <ul id="template-code-tabs" class="nav nav-tabs" role="tablist" style="margin-bottom: 1em">

        <li role="presentation" class="active">
            <a href="#tabStandard" aria-controls="home" role="tab" data-toggle="tab">
                <em><spring:message code="stylesheetEdit.standard"/></em>
            </a>
        </li>

        <c:if test="${bean.contentsMobile != null}">
            <li role="presentation">
                <a href="#tabMobile" aria-controls="home" role="tab" data-toggle="tab">
                    <em><spring:message code="stylesheetEdit.mobile"/></em>
                </a>
            </li>
        </c:if>

    </ul>

    <%-- Tab content for each of the two content areas: Standard and Mobile --%>
    <div class="tab-content">

        <div role="tabpanel" class="tab-pane active" id="tabStandard">
            <textarea name="bean.contentsStandard" rows="30" cols="80" style="width:100%">${bean.contentsStandard}</textarea>
        </div>

        <c:if test="${bean.contentsMobile != null}">
            <div role="tabpanel" class="tab-pane" id="tabMobile">
                <textarea name="bean.contentsMobile" rows="30" cols="80" style="width:100%">${bean.contentsMobile}</textarea>
            </div>
        </c:if>

    </div>

    <%-- ================================================================== --%>
    <%-- Save, Close and Resize text area buttons--%>

    <button type="submit" class="btn btn-default"><spring:message code="generic.save"/></button>
    <input type="button" value='<spring:message code="generic.done"/>' class="button btn"
           onclick="window.location='<c:url value="/roller-ui/authoring/templates.rol"><c:param name="weblog" value="${weblog}"/></c:url>'"/>

    <%-- ================================================================== --%>
    <%-- Advanced settings inside a control toggle --%>

    <c:if test="${template.custom}">

        <div class="panel-group" id="accordion" style="margin-top:2em">

        <div class="panel panel-default" id="panel-plugins">

            <div class="panel-heading">

                <h4 class="panel-title">
                    <a class="collapsed" data-toggle="collapse" data-target="#collapseAdvanced" href="#">
                        <spring:message code="pageForm.advancedSettings"/>
                    </a>
                </h4>

            </div>

            <div id="collapseAdvanced" class="panel-collapse collapse">
                <div class="panel-body">

                    <select name="bean.templateLanguage" class="form-control" size="1">
<c:forEach items="${templateLanguages}" var="opt">
<option value="${opt}" ${opt == bean.templateLanguage ? 'selected' : ''}>${opt}</option>
</c:forEach>
</select>

                    <input type="checkbox" name="bean.hidden" value="true" ${bean.hidden ? 'checked' : ''}/>

                    <input type="checkbox" name="bean.navbar" value="true" ${bean.navbar ? 'checked' : ''}/>

                    <input type="checkbox" name="bean.autoContentType" value="true" ${bean.autoContentType ? 'checked' : ''}/>

                    <div id="manual-content-type-control-group" style="display:none">
                        <input type="text" name="bean.manualContentType" value="${bean.manualContentType}" class="form-control"/>
                    </div>

                </div>
            </div>
        </div>

    </c:if>

<sec:csrfInput/>
</form>


<script type="text/javascript">

    var weblogURL = '${actionWeblog.absoluteURL}';
    var originalLink = '${bean.link}';
    var type = '${bean.type}';

    $(document).ready(function () {

        $("#template-code-tabs").tabs();

        showContentTypeField();
        $("#template_bean_autoContentType").click(function(e) {
            showContentTypeField();
        });
    });

    // Update page URL when user changes link
    function updatePageURLDisplay() {
        var link = $("#template_bean_link").val();
        if (link !== "") {
            $("#no_link").hide();
            $("#good_link").show();
            $("#linkPreview").html(link);
        } else {
            $("#good_link").hide();
            $("#no_link").show();
        }
    }

    // Don't launch page if user has changed link, it'll be a 404
    function launchPage() {
        if (originalLink != document.getElementById('template_bean_link').value) {
            window.alert("Link changed, not launching page");
        } else {
            window.open(weblogURL + 'page/' + originalLink + '?type=' + type, '_blank');
        }
    }

    function showContentTypeField() {
        var checked = $("#template_bean_autoContentType").prop("checked");
        if ( checked ) {
            $("#manual-content-type-control-group").hide();
        } else {
            $("#manual-content-type-control-group").show();
        }
    }

</script>
