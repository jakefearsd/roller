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
<%@ taglib uri="http://java.sun.com/jsp/jstl/xml" prefix="x" %>
<%@ include file="/WEB-INF/jsps/taglibs-spring.jsp" %>


<p class="subtitle"><spring:message code="planetConfig.subtitle"/></p>
<p><spring:message code="planetConfig.prompt"/></p>


<form method="post" action="<c:url value='/roller-ui/admin/planetConfig!save.rol'/>" class="form-horizontal">
    <sec:csrfInput/>

    <c:forEach var="dg" items="${globalConfigDef.displayGroups}">

        <h2><spring:message code="${dg.key}"/></h2>

        <c:forEach var="pd" items="${dg.propertyDefs}">

            <%-- "string" type means use a simple textbox --%>
            <c:if test="${pd.type == 'string'}">
                <div class="form-group">
                    <label class="col-sm-3 control-label"><spring:message code="${pd.key}"/></label>
                    <div class="col-sm-9 controls">
                        <input type="text" name="${fn:escapeXml(pd.name)}" size="35"
                               value="${fn:escapeXml(properties[pd.name].value)}"
                               class="form-control"/>
                    </div>
                </div>
            </c:if>

            <%-- "text" type means use a full textarea --%>
            <c:if test="${pd.type == 'text'}">
                <div class="form-group">
                    <label class="col-sm-3 control-label"><spring:message code="${pd.key}"/></label>
                    <div class="col-sm-9 controls">
                        <textarea name="${fn:escapeXml(pd.name)}" rows="${pd.rows}" cols="${pd.cols}"
                                  class="form-control">${fn:escapeXml(properties[pd.name].value)}</textarea>
                    </div>
                </div>
            </c:if>

            <%-- "boolean" type means use a checkbox --%>
            <c:if test="${pd.type == 'boolean'}">
                <div class="form-group">
                    <label class="col-sm-3 control-label"><spring:message code="${pd.key}"/></label>
                    <div class="col-sm-9 controls">
                        <input type="checkbox" name="${fn:escapeXml(pd.name)}" value="true"
                            <c:if test="${properties[pd.name].value == 'true'}">checked="checked"</c:if>
                               onchange="formChanged()" class="boolean"/>
                    </div>
                </div>
            </c:if>

            <%-- if it's something we don't understand then use textbox --%>
            <c:if test="${pd.type != 'string' && pd.type != 'text' && pd.type != 'boolean'}">
                <div class="form-group">
                    <label class="col-sm-3 control-label"><spring:message code="${pd.key}"/></label>
                    <div class="col-sm-9 controls">
                        <input type="text" name="${fn:escapeXml(pd.name)}" size="35"
                               value="${fn:escapeXml(properties[pd.name].value)}"
                               class="form-control"/>
                    </div>
                </div>
            </c:if>

        </c:forEach>

    </c:forEach>

     <input class="btn btn-default" type="submit" value="<spring:message code="generic.save"/>"/>

</form>
