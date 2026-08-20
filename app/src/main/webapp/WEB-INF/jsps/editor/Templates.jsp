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

    <%-- rollertable, not just table: it is the class that carries the token
         palette and the caps-label header row every other admin list gets.
         Its header rule keys off the first <tr> of the table, so the header
         row has to stay the first thing inside -- which is why the empty
         branch is a sibling of the table rather than a row in it. --%>
    <table class="rollertable table table-striped"> <%-- of weblog templates --%>

        <c:if test="${not empty templates}">

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
                            <img src='<c:url value="/images/page_white.png"/>' alt=""/>
                        </c:when>
<c:otherwise>
                            <img src='<c:url value="/images/page_white_gear.png"/>' alt=""/>
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
                            <%-- id/name ride in data-* attributes, not an
                                 interpolated onclick string -- fn:escapeXml
                                 renders an apostrophe as &#039;, which the
                                 HTML parser decodes back to ' BEFORE the
                                 onclick attribute compiles as JavaScript, so
                                 a template named e.g. "Maiia's Sidebar" made
                                 this control a permanent SyntaxError.
                                 Delegated handler below (same convention as
                                 MediaFileView.jsp:493). --%>
                            <button type="button" class="btn btn-link p-0 align-baseline border-0 template-delete-btn"
                                    data-template-id="${p.id}" data-template-name="${fn:escapeXml(p.name)}"
                                    aria-label="<spring:message code='generic.delete'/>: ${fn:escapeXml(p.name)}">
                                <span class="bi bi-trash" aria-hidden="true"></span>
                            </button>

                        </c:when>
<c:otherwise>
                            <span class="bi bi-lock" role="img"
                                  aria-label="<spring:message code='pagesForm.cannotDelete'/>"></span>
                        </c:otherwise>
</c:choose></td>

                </tr>
            </c:forEach>

        </c:if>

    </table>

    <c:if test="${empty templates}">
        <div class="empty-state">
            <p class="empty-state-title"><spring:message code="empty.templates.title"/></p>
            <%-- No action button: a template is created by the named form in
                 the sidebar (templates!add.rol), not by a URL this button
                 could point at, and shipping a dead control is worse than
                 shipping none. --%>
            <p class="empty-state-body"><spring:message code="empty.templates.body"/></p>
        </div>
    </c:if>

<sec:csrfInput/>
</form>


<script>
    <%-- Delegated: a row's id/name ride in data-* attributes on the button
         (see the comment above it), never in an inline onclick string. --%>
    $(document).on('click', '.template-delete-btn', function () {
        confirmTemplateDelete(this.dataset.templateId, this.dataset.templateName);
    });

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
