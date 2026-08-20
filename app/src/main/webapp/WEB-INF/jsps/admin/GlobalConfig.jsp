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


<form method="post" action="<c:url value='/roller-ui/admin/globalConfig!save.rol'/>" class="form-stacked">
    <sec:csrfInput/>

    <c:forEach var="dg" items="${globalConfigDef.displayGroups}">

        <h3 class="section-head"><spring:message code="${dg.key}"/></h3>

        <c:forEach var="pd" items="${dg.propertyDefs}">

            <%-- special case for front page blog --%>
            <c:if test="${pd.name == 'site.frontpage.weblog.handle'}">
                <div class="row mb-3">
                    <label class="col-sm-3 col-form-label"><spring:message code="${pd.key}"/></label>
                    <div class="col-sm-9">
                        <select name="${fn:escapeXml(pd.name)}" class="form-select">
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
            <c:if test="${pd.name != 'site.frontpage.weblog.handle' && pd.type == 'string'}">
                <div class="row mb-3">
                    <label class="col-sm-3 col-form-label"><spring:message code="${pd.key}"/></label>
                    <div class="col-sm-9">
                        <input type="text" name="${fn:escapeXml(pd.name)}" size="35"
                               value="${fn:escapeXml(properties[pd.name].value)}"
                               class="form-control"/>
                    </div>
                </div>
            </c:if>

            <%-- "text" type means use a full textarea --%>
            <c:if test="${pd.name != 'site.frontpage.weblog.handle' && pd.type == 'text'}">
                <div class="row mb-3">
                    <label class="col-sm-3 col-form-label"><spring:message code="${pd.key}"/></label>
                    <div class="col-sm-9">
                        <textarea name="${fn:escapeXml(pd.name)}" rows="${pd.rows}" cols="${pd.cols}"
                                  class="form-control">${fn:escapeXml(properties[pd.name].value)}</textarea>
                    </div>
                </div>
            </c:if>

            <%-- "boolean" type means use a checkbox --%>
            <c:if test="${pd.name != 'site.frontpage.weblog.handle' && pd.type == 'boolean'}">
                <div class="row mb-3">
                    <label class="col-sm-3 col-form-label"><spring:message code="${pd.key}"/></label>
                    <div class="col-sm-9">
                        <input type="checkbox" name="${fn:escapeXml(pd.name)}" value="true"
                            <c:if test="${properties[pd.name].value == 'true'}">checked="checked"</c:if>
                               onchange="formChanged()" class="form-check-input boolean"/>
                    </div>
                </div>
            </c:if>

            <%-- "integer" use input type number --%>
            <c:if test="${pd.name != 'site.frontpage.weblog.handle' && pd.type == 'integer'}">
                <div class="row mb-3">
                    <label class="col-sm-3 col-form-label"
                           for='globalConfig_${pd.nameWithUnderbars}'>
                        <spring:message code="${pd.key}"/>
                    </label>
                    <div class="col-sm-9">
                        <input type="number" name='${fn:escapeXml(pd.name)}' size="35"
                               value='${fn:escapeXml(properties[pd.name].value)}'
                               id='globalConfig_${pd.nameWithUnderbars}'
                               class="form-control integer" onkeyup="formChanged()"/>
                    </div>
                </div>
            </c:if>

            <%-- "float" use input type number --%>
            <c:if test="${pd.name != 'site.frontpage.weblog.handle' && pd.type == 'float'}">
                <div class="row mb-3">
                    <label class="col-sm-3 col-form-label"
                           for='globalConfig_${pd.nameWithUnderbars}'>
                        <spring:message code="${pd.key}"/>
                    </label>
                    <div class="col-sm-9">
                        <input type="number" name='${fn:escapeXml(pd.name)}' size="5"
                               value='${fn:escapeXml(properties[pd.name].value)}'
                               id='globalConfig_${pd.nameWithUnderbars}'
                               class="form-control float" onkeyup="formChanged()"/>
                    </div>
                </div>
            </c:if>

            <%-- if it's something we don't understand then use textbox --%>
            <c:if test="${pd.name != 'site.frontpage.weblog.handle' && pd.type != 'string' && pd.type != 'text' && pd.type != 'boolean' && pd.type != 'integer' && pd.type != 'float'}">
                <div class="row mb-3">
                    <label class="col-sm-3 col-form-label"><spring:message code="${pd.key}"/></label>
                    <div class="col-sm-9">
                        <input type="text" name="${fn:escapeXml(pd.name)}" size="35"
                               value="${fn:escapeXml(properties[pd.name].value)}"
                               class="form-control"/>
                    </div>
                </div>
            </c:if>

        </c:forEach>

        <%-- The gap between one display group and the next used to be a
             spacer.png with an inline min-height. It is margin, and
             .section-head already carries 32px of top margin, so the group
             heading below spaces the groups on its own -- nothing goes here.
             The save button carries its own mt-3 for the same reason: the
             last group has no heading after it to do the work. --%>

    </c:forEach>

    <button id="saveButton" class="btn btn-primary mt-3" type="submit"><spring:message code="generic.save"/></button>

</form>


<script type="text/javascript">

    function formChanged() {
        var saveButton = $('#saveButton:first');
        var error = false;

        $("input").each(function () {
            var isInteger = $(this).hasClass("integer");
            var isFloat = $(this).hasClass("float");
            var isBoolean = $(this).hasClass("boolean");

            if (isInteger || isFloat) {

                if (isNaN(this.valueAsNumber)) {
                    $(this).addClass("field-invalid-highlight")
                    error = true;

                } else if (isInteger && !Number.isInteger(this.valueAsNumber)) {
                    $(this).addClass("field-invalid-highlight")
                    error = true;

                } else {
                    $(this).removeClass("field-invalid-highlight")
                }

            } else if (isFloat) {

                if (isNaN(this.valueAsNumber)) {
                    $(this).addClass("field-invalid-highlight")
                    error = true;

                } else {
                    $(this).removeClass("field-invalid-highlight")
                }

            } else if (isBoolean) {
                // not sure why this is necessary, value does not track checked state?
                $(this).prop("value", !(!$(this).prop("checked")));
            }

        });

        saveButton.prop("disabled", error);
    }

</script>

