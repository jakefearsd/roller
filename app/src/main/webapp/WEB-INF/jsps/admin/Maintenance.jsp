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

<p class="subtitle"><spring:message code="maintenance.subtitle"/></p>

<form action="${pageContext.request.contextPath}/roller-ui/admin/maintenance.rol" method="post" class="form-vertical">
<sec:csrfInput/>

    <div class="mb-3">
        <label for="maintenanceWeblog"><spring:message code="maintenance.prompt.weblog"/></label>
        <select name="weblogId" id="maintenanceWeblog" class="form-select">
            <%-- A <select> with no explicitly selected <option> defaults to
                 its FIRST option per the HTML spec -- not "nothing chosen".
                 Without this placeholder, an admin who opens the page and
                 clicks an action button without touching the dropdown would
                 silently submit whichever weblog getWeblogs() happens to
                 list first (most-recently-created, per its ORDER BY), with
                 no error and no visual cue a choice was ever made. Disabled
                 so it can never be re-selected once a real weblog is picked;
                 selected only while selectedWeblog is null, so exactly one
                 <option> ever carries "selected". Submitting it posts
                 weblogId="", which resolveWeblog() below already turns into
                 maintenance.error.noSuchWeblog. --%>
            <option value=""
                <c:if test="${selectedWeblog == null}">selected="selected"</c:if>
                disabled="disabled"><spring:message code="maintenance.select.placeholder"/></option>
            <c:forEach var="w" items="${weblogs}">
                <option value="${fn:escapeXml(w.id)}"
                    <c:if test="${selectedWeblog != null && selectedWeblog.id == w.id}">selected="selected"</c:if>
                >${fn:escapeXml(w.name)} (${fn:escapeXml(w.handle)})</option>
            </c:forEach>
        </select>
    </div>

    <p><spring:message code="maintenance.prompt.flush"/></p>
    <button type="submit" class="btn" formaction="${pageContext.request.contextPath}/roller-ui/admin/maintenance!flushCache.rol"><spring:message code="maintenance.button.flush"/></button>

    <c:if test="${rc:getBooleanProp('search.enabled')}">
        <p><spring:message code="maintenance.prompt.index"/></p>
        <button type="submit" class="btn" formaction="${pageContext.request.contextPath}/roller-ui/admin/maintenance!index.rol"><spring:message code="maintenance.button.index"/></button>
    </c:if>

    <p><spring:message code="maintenance.prompt.regenerateRenditions"/></p>
    <button type="submit" class="btn" formaction="${pageContext.request.contextPath}/roller-ui/admin/maintenance!regenerateRenditions.rol"><spring:message code="maintenance.button.regenerateRenditions"/></button>

</form>
