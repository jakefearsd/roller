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


<%-- ================================================================== --%>
<%-- add new planet feed subscription --%>

<c:if test="${!createNew}">

    <h3><spring:message code="mainPage.actions"/></h3>
    <hr size="1" noshade="noshade"/>

    <spring:message code="planetGroupSubs.addFeed"/>

    <form method="post" action="<c:url value='/roller-ui/admin/planetGroupSubs!saveSubscription.rol'/>"
          class="form-horizontal" style="margin-top:1em">
        <sec:csrfInput/>
        <input type="hidden" name="group.handle" value="${fn:escapeXml(group.handle)}"/>

        <div class="form-group">
            <label class="col-sm-3 control-label"><spring:message code="planetSubscription.feedUrl"/></label>
            <div class="col-sm-9 controls">
                <input type="text" name="subUrl" id="planetGroupSubs_subUrl"
                       size="40" maxlength="255"
                       onchange="validateUrl()" onkeyup="validateUrl()"
                       class="form-control"/>
            </div>
        </div>

        <p id="feedback-area" style="clear:right; width:100%"></p>

        <button type="submit" id="planetGroupSubs_0" class="btn btn-default">
            <spring:message code="generic.save"/>
        </button>

    </form>

    <script>

        function validateUrl() {
            var feedbackArea = $("#feedback-area");
            var url = $("#planetGroupSubs_subUrl").val();
            var saveButton = $('#planetGroupSubs_0');

            if (url && url.trim() !== '') {
                if (!isValidUrl(url)) {
                    saveButton.attr("disabled", true);
                    feedbackArea.html('<spring:message code="planetGroupSubs.badFeedURL" />');
                    feedbackArea.css("color", "red");
                    return;
                }
            }

            feedbackArea.html('');
            saveButton.attr("disabled", false);
        }

        $( document ).ready(function() {
            var saveButton = $('#planetGroupSubs_0');
            saveButton.attr("disabled", true);
        });
    </script>

</c:if>
