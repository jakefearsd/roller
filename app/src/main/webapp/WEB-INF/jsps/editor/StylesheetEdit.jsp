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

<p class="subtitle"><spring:message code="stylesheetEdit.subtitle"/></p>


<c:choose>
<c:when test="${template != null}">

    <spring:message code="stylesheetEdit.youCanCustomize"/>

    <form action="${pageContext.request.contextPath}/roller-ui/authoring/stylesheetEdit!save.rol" method="post" class="form-vertical">
<input type="hidden" name="weblog" value="${actionWeblog.handle}"/>

        <%-- Tabs for each of the two content areas: Standard and Mobile --%>
        <ul id="template-code-tabs" class="nav nav-tabs" role="tablist" style="margin-bottom: 1em">

            <li role="presentation" class="active">
                <a href="#tabStandard" aria-controls="home" role="tab" data-toggle="tab">
                    <em><spring:message code="stylesheetEdit.standard"/></em>
                </a>
            </li>

            <c:if test="${contentsMobile != null}">
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
                <textarea name="contentsStandard" rows="30" cols="80" style="width:100%">${contentsStandard}</textarea>
            </div>

            <c:if test="${contentsMobile != null}">
                <div role="tabpanel" class="tab-pane" id="tabMobile">
                    <textarea name="contentsMobile" rows="30" cols="80" style="width:100%">${contentsMobile}</textarea>
                </div>
            </c:if>

        </div>

        <%-- Save, Close and Resize text area buttons--%>
        <button type="submit" class="btn btn-success"><spring:message code="generic.save"/></button>

        <c:if test="${!customTheme}">
            <button type="submit" class="btn" onclick="revertStylesheet();return false;"><spring:message code="stylesheetEdit.revert"/></button>
        </c:if>

        <%-- Only delete if we have no custom templates ie website.customStylesheetPath=null --%>
        <c:if test="${sharedThemeStylesheet}">
            <button type="submit" class="btn btn-danger" onclick="deleteStylesheet();return false;"><spring:message code="stylesheetEdit.delete"/></button>
        </c:if>

    <sec:csrfInput/>
</form>

</c:when>
<c:when test="${sharedTheme}">

    <c:choose>
<c:when test="${sharedThemeStylesheet}">

        <spring:message code="stylesheetEdit.sharedThemeWithStylesheet"/>

        <form action="${pageContext.request.contextPath}/roller-ui/authoring/stylesheetEdit!copyStylesheet.rol" method="post" class="form-vertical">
<input type="hidden" name="weblog" value="${actionWeblog.handle}"/>
            <button type="submit" class="btn btn-success"><spring:message code="stylesheetEdit.copyStylesheet"/></button>
        <sec:csrfInput/>
</form>

    </c:when>
<c:otherwise>
        <p><spring:message code="stylesheetEdit.sharedThemeNoStylesheetSupport"/></p>
    </c:otherwise>
</c:choose>
</c:when>
<c:otherwise>
    <spring:message code="stylesheetEdit.customThemeNoStylesheet"/>
</c:otherwise>
</c:choose>

<script type="text/javascript">

    function revertStylesheet() {
        if (window.confirm('<spring:message code="stylesheetEdit.confirmRevert"/>')) {
            document.stylesheetEdit.action = "<c:url value='/roller-ui/authoring/stylesheetEdit!revert.rol'/>";
            document.stylesheetEdit.submit();
        }
    };
    <c:if test="${sharedThemeStylesheet}">
        function deleteStylesheet() {
            if (window.confirm('<spring:message code="stylesheetEdit.confirmDelete"/>')) {
                document.stylesheetEdit.action = "<c:url value='/roller-ui/authoring/stylesheetEdit!delete.rol'/>";
                document.stylesheetEdit.submit();
            }
        };
    </c:if>

</script>

