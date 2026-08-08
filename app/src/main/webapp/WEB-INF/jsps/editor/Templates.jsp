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
    <spring:message code="pagesForm.subtitle" arguments="${actionWeblog.handle}"/>
</p>
<p class="pagetip">
    <spring:message code="pagesForm.tip"/>
</p>

<c:if test="${actionWeblog.editorTheme != 'custom'}">
    <p><spring:message code="pagesForm.themesReminder" arguments="${actionWeblog.editorTheme}"/></p>
</c:if>

<form id="templateRemoveForm" action="${pageContext.request.contextPath}/roller-ui/authoring/templates!remove.rol" method="post">
<input type="hidden" name="weblog" value="${actionWeblog.handle}"/>
    <input type="hidden" name="removeId" value="${removeId}" id="removeId"/>

    <table class="table table-striped"> <%-- of weblog templates --%>

        <c:choose>
            <c:when test="${not empty templates}">

            <tr>
                <th width="30%"><spring:message code="generic.name"/></th>
                <th width="10"><spring:message code="pagesForm.action"/></th>
                <th width="55%"><spring:message code="generic.description"/></th>
                <th width="10"><spring:message code="pagesForm.remove"/></th>
            </tr>

            <c:forEach items="${templates}" var="p" varStatus="rowstatus">
                <tr>

                    <td style="vertical-align:middle">
                        <c:choose>
<c:when test="${! p.hidden}">
                            <img src='<c:url value="/images/page_white.png"/>' border="0" alt="icon"/>
                        </c:when>
<c:otherwise>
                            <img src='<c:url value="/images/page_white_gear.png"/>' border="0" alt="icon"/>
                        </c:otherwise>
</c:choose><c:url var="edit" value="/roller-ui/authoring/templateEdit.rol">
                            <c:param name="weblog" value="${actionWeblog.handle}"/>
                            <c:param name="bean.id" value="${p.id}"/>
                        </c:url>
                        <a href="${edit}"><c:out value="${p.name}"/></a>
                    </td>

                    <td style="vertical-align:middle">${p.action.readableName}</td>

                    <td style="vertical-align:middle">${p.description}</td>

                    <td class="center" style="vertical-align:middle">
                        <c:choose>
<c:when test="${!p.required || !customTheme}">
                            <c:url var="removeUrl" value="/roller-ui/authoring/templateRemove.rol">
                                <c:param name="weblog" value="${actionWeblog.handle}"/>
                                <c:param name="removeId" value="${p.id}"/>
                            </c:url>
                            <a href="#" onclick=
                                    "confirmTemplateDelete('${p.id}', '${fn:escapeXml(p.name)}' )">
                                <span class="bi bi-trash"></span>
                            </a>

                        </c:when>
<c:otherwise>
                            <span class="bi bi-lock"></span>
                        </c:otherwise>
</c:choose></td>

                </tr>
            </c:forEach>

        </c:when>
<c:otherwise>
            <tr class="rollertable_odd">
                <td style="vertical-align:middle" colspan="5">
                    <spring:message code="pageForm.notemplates"/>
                </td>
            </tr>
        </c:otherwise>
</c:choose></table>

<sec:csrfInput/>
</form>


<script>
    function confirmTemplateDelete(templateId, templateName) {
        // The form is submitted by id. It used to be getElementById("templates")
        // -- an id Struts generated from the action name and the JSP never
        // reproduced -- so this threw "Cannot read properties of null (reading
        // 'submit')" after the confirm dialog, and no template could be deleted
        // through the UI at all.
        $('#removeId').val(templateId);
        if (window.confirm('<spring:message code="pageRemove.confirm"/>: \'' + templateName + '\'?')) {
            document.getElementById("templateRemoveForm").submit();
        }
    }
</script>
