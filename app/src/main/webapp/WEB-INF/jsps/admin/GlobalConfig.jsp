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

<p class="subtitle"><spring:message code="configForm.subtitle"/></p>
<p><spring:message code="configForm.prompt"/></p>


<form method="post" action="<c:url value='/roller-ui/admin/globalConfig!save.rol'/>" class="form-horizontal">
    <sec:csrfInput/>

    <c:forEach var="dg" items="${globalConfigDef.displayGroups}">

        <h3><spring:message code="${dg.key}"/></h3>

        <c:forEach var="pd" items="${dg.propertyDefs}">

            <%-- special case for comment plugins --%>
            <c:if test="${pd.name == 'users.comments.plugins'}">
                <div class="form-group">
                    <label class="col-sm-3 control-label"><spring:message code="${pd.key}"/></label>
                    <div class="col-sm-9 controls">
                        <c:forEach var="plugin" items="${pluginsList}">
                            <label class="checkbox-inline">
                                <input type="checkbox" name="commentPlugins" value="${fn:escapeXml(plugin.id)}"
                                    <c:forEach var="cp" items="${commentPlugins}">
                                        <c:if test="${cp == plugin.id}">checked="checked"</c:if>
                                    </c:forEach>
                                /> ${fn:escapeXml(plugin.name)}
                            </label>
                        </c:forEach>
                    </div>
                </div>
            </c:if>

            <%-- special case for front page blog --%>
            <c:if test="${pd.name == 'site.frontpage.weblog.handle'}">
                <div class="form-group">
                    <label class="col-sm-3 control-label"><spring:message code="${pd.key}"/></label>
                    <div class="col-sm-9 controls">
                        <select name="${fn:escapeXml(pd.name)}" class="form-control">
                            <c:forEach var="weblog" items="${weblogs}">
                                <option value="${fn:escapeXml(weblog.handle)}"
                                    <c:if test="${properties[pd.name].value == weblog.handle}">selected="selected"</c:if>
                                >${fn:escapeXml(weblog.handle)}</option>
                            </c:forEach>
                        </select>
                    </div>
                </div>
            </c:if>

            <%-- "string" type means use a simple textbox --%>
            <c:if test="${pd.name != 'users.comments.plugins' && pd.name != 'site.frontpage.weblog.handle' && pd.type == 'string'}">
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
            <c:if test="${pd.name != 'users.comments.plugins' && pd.name != 'site.frontpage.weblog.handle' && pd.type == 'text'}">
                <div class="form-group">
                    <label class="col-sm-3 control-label"><spring:message code="${pd.key}"/></label>
                    <div class="col-sm-9 controls">
                        <textarea name="${fn:escapeXml(pd.name)}" rows="${pd.rows}" cols="${pd.cols}"
                                  class="form-control">${fn:escapeXml(properties[pd.name].value)}</textarea>
                    </div>
                </div>
            </c:if>

            <%-- "boolean" type means use a checkbox --%>
            <c:if test="${pd.name != 'users.comments.plugins' && pd.name != 'site.frontpage.weblog.handle' && pd.type == 'boolean'}">
                <div class="form-group">
                    <label class="col-sm-3 control-label"><spring:message code="${pd.key}"/></label>
                    <div class="col-sm-9 controls">
                        <input type="checkbox" name="${fn:escapeXml(pd.name)}" value="true"
                            <c:if test="${properties[pd.name].value == 'true'}">checked="checked"</c:if>
                               onchange="formChanged()" class="boolean"/>
                    </div>
                </div>
            </c:if>

            <%-- "integer" use input type number --%>
            <c:if test="${pd.name != 'users.comments.plugins' && pd.name != 'site.frontpage.weblog.handle' && pd.type == 'integer'}">
                <div class="form-group ">
                    <label class="col-sm-3 control-label"
                           for='globalConfig_${pd.nameWithUnderbars}'>
                        <spring:message code="${pd.key}"/>
                    </label>
                    <div class="col-sm-9 controls">
                        <input type="number" name='${fn:escapeXml(pd.name)}' size="35"
                               value='${fn:escapeXml(properties[pd.name].value)}'
                               id='globalConfig_${pd.nameWithUnderbars}'
                               class="form-control integer" onkeyup="formChanged()"/>
                    </div>
                </div>
            </c:if>

            <%-- "float" use input type number --%>
            <c:if test="${pd.name != 'users.comments.plugins' && pd.name != 'site.frontpage.weblog.handle' && pd.type == 'float'}">
                <div class="form-group ">
                    <label class="col-sm-3 control-label"
                           for='globalConfig_${pd.nameWithUnderbars}'>
                        <spring:message code="${pd.key}"/>
                    </label>
                    <div class="col-sm-9 controls">
                        <input type="number" name='${fn:escapeXml(pd.name)}' size="5"
                               value='${fn:escapeXml(properties[pd.name].value)}'
                               id='globalConfig_${pd.nameWithUnderbars}'
                               class="form-control float" onkeyup="formChanged()"/>
                    </div>
                </div>
            </c:if>

            <%-- if it's something we don't understand then use textbox --%>
            <c:if test="${pd.name != 'users.comments.plugins' && pd.name != 'site.frontpage.weblog.handle' && pd.type != 'string' && pd.type != 'text' && pd.type != 'boolean' && pd.type != 'integer' && pd.type != 'float'}">
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

        <img src="<c:url value='/roller-ui/images/spacer.png' />" alt="spacer" style="min-height: 1em"/>

    </c:forEach>

    <input id="saveButton" class="btn btn-default" type="submit" value="<spring:message code="generic.save"/>"/>

</form>


<script type="text/javascript">

    function formChanged() {
        var saveBookmarkButton = $('#saveButton:first');
        var error = false;

        $("input").each(function () {
            var isInteger = $(this).hasClass("integer");
            var isFloat = $(this).hasClass("float");
            var isBoolean = $(this).hasClass("boolean");

            if (isInteger || isFloat) {

                if (isNaN(this.valueAsNumber)) {
                    $(this).css("background", "#FBB")
                    error = true;

                } else if (isInteger && !Number.isInteger(this.valueAsNumber)) {
                    $(this).css("background", "#FBB")
                    error = true;

                } else {
                    $(this).css("background", "white")
                }

            } else if (isFloat) {

                if (isNaN(this.valueAsNumber)) {
                    $(this).css("background", "#FBB")
                    error = true;

                } else {
                    $(this).css("background", "white")
                }

            } else if (isBoolean) {
                // not sure why this is necessary, value does not track checked state?
                $(this).prop("value", !(!$(this).prop("checked")));
            }

        });

        saveBookmarkButton.attr("disabled", error);
    }

</script>

