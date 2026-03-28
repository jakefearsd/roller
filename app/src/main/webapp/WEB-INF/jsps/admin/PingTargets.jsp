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

<p class="subtitle"><spring:message code="commonPingTargets.subtitle"/></p>

<p><spring:message code="commonPingTargets.explanation"/></p>


<table class="rollertable table table-striped">

    <%-- Headings --%>
    <tr class="rollertable">
        <th class="rollertable" width="20%%"><spring:message code="generic.name"/></th>
        <th class="rollertable" width="55%"><spring:message code="pingTarget.pingUrl"/></th>
        <th class="rollertable" width="15%" colspan="2"><spring:message code="pingTarget.autoEnabled"/></th>
        <th class="rollertable" width="5%"><spring:message code="generic.edit"/></th>
        <th class="rollertable" width="5%"><spring:message code="pingTarget.remove"/></th>
    </tr>

    <%-- Listing of current common targets --%>
    <c:forEach var="pingTarget" items="${pingTargets}" varStatus="rowstatus">

        <tr class="rollertable_odd">

            <td class="rollertable">${fn:escapeXml(pingTarget.name)}</td>

            <td class="rollertable">${fn:escapeXml(pingTarget.pingUrl)}</td>

            <td class="rollertable" align="center">
                <c:if test="${pingTarget.autoEnabled}">
                    <span style="color: #00aa00; font-weight: bold;"><spring:message code="pingTarget.enabled"/></span>&nbsp;
                </c:if>
                <c:if test="${!pingTarget.autoEnabled}">
                    <span style="color: #aaaaaa; font-weight: bold;"><spring:message code="pingTarget.disabled"/></span>&nbsp;
                </c:if>
            </td>

            <td class="rollertable" align="center">
                <c:if test="${pingTarget.autoEnabled}">
                    <c:url var="disablePing" value="/roller-ui/admin/commonPingTargets!disable.rol">
                        <c:param name="pingTargetId" value="${pingTarget.id}"/>
                    </c:url>
                    <a href="${disablePing}">
                        <spring:message code="pingTarget.disable"/>
                    </a>
                </c:if>
                <c:if test="${!pingTarget.autoEnabled}">
                    <c:url var="enablePing" value="/roller-ui/admin/commonPingTargets!enable.rol">
                        <c:param name="pingTargetId" value="${pingTarget.id}"/>
                    </c:url>
                    <a href="${enablePing}">
                        <spring:message code="pingTarget.enable"/></a>
                </c:if>
            </td>

            <td class="rollertable" align="center">
                <a href="#" onclick="showAddEditModal('${fn:escapeXml(pingTarget.id)}',
                        '${fn:escapeXml(pingTarget.name)}',
                        '${fn:escapeXml(pingTarget.pingUrl)}'
                        )">
                    <span class="glyphicon glyphicon-edit" aria-hidden="true"> </span>
                </a>
            </td>

            <td class="rollertable" align="center">
                <a href="#" onclick="showDeleteModal('${fn:escapeXml(pingTarget.id)}')">
                    <span class="glyphicon glyphicon-trash" aria-hidden="true"> </span>
                </a>
            </td>

        </tr>
    </c:forEach>

</table>

<div style="padding: 4px; font-weight: bold;">
    <a href="#" onclick="showAddEditModal()">
        <span class="glyphicon glyphicon-plus-sign" aria-hidden="true"> </span>
        <spring:message code="pingTarget.addTarget"/>
    </a>
</div>


<%-- ================================================================================================ --%>

<div id="delete-ping-target-modal" class="modal fade ping-target-modal" tabindex="-1" role="dialog">

    <div class="modal-dialog modal-lg">

        <div class="modal-content">

            <form method="post" action="<c:url value='/roller-ui/admin/commonPingTargets!delete.rol'/>" class="form-horizontal">
                <sec:csrfInput/>
                <input type="hidden" id="removeId" name="pingTargetId"/>

                <div class="modal-header">
                    <div class="modal-title">
                        <h3><spring:message code="pingTarget.confirmRemoveTitle"/></h3>
                    </div>
                </div>

                <div class="modal-body">
                    <spring:message code="pingTarget.confirmCommonRemove"/>
                </div>

                <div class="modal-footer">
                    <button type="submit" class="btn btn-danger">
                        <spring:message code="generic.yes"/>
                    </button>
                    <button type="button" class="btn" data-dismiss="modal">
                        <spring:message code="generic.cancel"/>
                    </button>
                </div>

            </form>

        </div>

    </div>

</div>


<%-- ================================================================================================ --%>

<%-- add/edit link form: a modal --%>

<div id="addedit-pingtarget-modal" class="modal fade addedit-pingtarget-modal" tabindex="-1" role="dialog">

    <div class="modal-dialog modal-lg">

        <div class="modal-content">

            <div class="modal-header">

                <c:choose>
                    <c:when test="${actionName == 'commonPingTargetEdit'}">
                        <c:set var="subtitleKey" value="pingTargetEdit.subtitle"/>
                    </c:when>
                    <c:otherwise>
                        <c:set var="subtitleKey" value="pingTargetAdd.subtitle"/>
                    </c:otherwise>
                </c:choose>

                <div class="modal-title">
                    <h3> <spring:message code="${subtitleKey}"/> </h3>
                </div>

            </div> <%-- modal header --%>

            <div class="modal-body">

                <form id="pingTargetEditForm" method="post" class="form-horizontal">
                    <sec:csrfInput/>
                    <input type="hidden" name="bean.id" id="pingTargetEditForm_bean_id"/>
                    <input type="hidden" name="actionName" id="pingTargetEditForm_actionName"/>

                    <div class="form-group">
                        <label class="col-sm-3 control-label"><spring:message code="generic.name"/></label>
                        <div class="col-sm-9 controls">
                            <input type="text" name="bean.name" id="pingTargetEditForm_bean_name"
                                   size="30" maxlength="30" style="width:50%"
                                   onchange="validate()" onkeyup="validate()"
                                   class="form-control"/>
                        </div>
                    </div>

                    <div class="form-group">
                        <label class="col-sm-3 control-label"><spring:message code="pingTarget.pingUrl"/></label>
                        <div class="col-sm-9 controls">
                            <input type="text" name="bean.pingUrl" id="pingTargetEditForm_bean_pingUrl"
                                   size="100" maxlength="255" style="width:50%"
                                   onchange="validate()" onkeyup="validate()"
                                   class="form-control"/>
                        </div>
                    </div>
                </form>

            </div> <%-- modal body --%>

            <div class="modal-footer">

                <p id="feedback-area-edit"></p>

                <button type="button" id="save_ping_target" onclick="savePingTarget()" class="btn btn-success">
                    <spring:message code="generic.save"/>
                </button>

                <button type="button" class="btn" data-dismiss="modal">
                    <spring:message code="generic.cancel"/>
                </button>

            </div> <%-- modal footer --%>

        </div> <%-- modal content --%>

    </div> <%-- modal dialog --%>

</div> <%-- modal --%>


<%-- page reload mechanism --%>
<form id="commonPingTargets" method="GET" action="<c:url value='/roller-ui/admin/commonPingTargets.rol'/>">
</form>


<%-- ================================================================================================ --%>

<script>

    function showDeleteModal( removeId ) {
        $('#removeId').val(removeId);
        $('#delete-ping-target-modal').modal({show: true});
    }

    function showAddEditModal(pingTargetId, name, url) {
        if ( pingTargetId ) {
            $('#pingTargetEditForm_actionName').val("commonPingTargetEdit");
            $('#pingTargetEditForm_bean_id').val(pingTargetId);
            $('#pingTargetEditForm_bean_name').val(name);
            $('#pingTargetEditForm_bean_pingUrl').val(url);
        } else {
            $('#pingTargetEditForm_actionName').val("commonPingTargetAdd");
            $('#pingTargetEditForm_bean_name').val("");
            $('#pingTargetEditForm_bean_pingUrl').val("");
        }
        $('#addedit-pingtarget-modal').modal({show: true});
    }

    function validate() {
        var savePingTargetButton = $('#save-button:first');
        var name = $('#pingTargetEditForm_bean_name').val().trim();
        var url = $('#pingTargetEditForm_bean_pingUrl').val().trim();
        if ( name.length > 0 && url.length > 0 && isValidUrl(url) ) {
            savePingTargetButton.attr("disabled", false);
        } else {
            savePingTargetButton.attr("disabled", true);
        }
    }

    function isValidUrl(url) {
        if (/^(http|https|ftp):\/\/[a-z0-9]+([\-\.]{1}[a-z0-9]+)*\.[a-z]{2,5}(:[0-9]{1,5})?(\/.*)?$/i.test(url)) {
            return true;
        } else {
            return false;
        }
    }

    $( document ).ready(function() {
        var savePingTargetButton = $('#save-button:first');
        savePingTargetButton.attr("disabled", true);
    });

    function viewChanged() {
        var form = $("#commonPingTargets")[0];
        form.submit();
    }

    function savePingTarget() {

        var feedbackAreaEdit = $("#feedback-area-edit");

        var actionName = $('#pingTargetEditForm_actionName').val();

        // post ping target via AJAX
        $.ajax({
            method: 'post',
            url: actionName + ".rol#save",
            data: $("#pingTargetEditForm").serialize(),
            context: document.body

        }).done(function (data) {

            // kludge: scrape response status from HTML returned by Struts
            var alertEnd = data.indexOf("ALERT_END");
            var notUnique = data.indexOf("<spring:message code='pingTarget.nameNotUnique' />");
            if (notUnique > 0 && notUnique < alertEnd) {
                feedbackAreaEdit.css("color", "red");
                feedbackAreaEdit.html('<spring:message code="pingTarget.nameNotUnique" />');

            } else {
                feedbackAreaEdit.css("color", "green");
                feedbackAreaEdit.html('<spring:message code="generic.success" />');
                $('#addedit-pingtarget-modal').modal("hide");

                // cause page to be reloaded so that edit appears
                viewChanged();
            }

        }).error(function (data) {
            feedbackAreaEdit.html('<spring:message code="generic.error.check.logs" />');
            feedbackAreaEdit.css("color", "red");
        });
    }

</script>
