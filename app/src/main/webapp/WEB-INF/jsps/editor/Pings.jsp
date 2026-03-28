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
   <spring:message code="pings.subtitle" arguments="${actionWeblog.handle}"/>
</p>  
<p class="pagetip"> <spring:message code="pings.explanation"/> </p>

<p> <h2><spring:message code="pings.commonPingTargets"/></h2> </p>

<p> <spring:message code="pings.commonPingTargetsExplanation"/> </p>


<table class="rollertable table table-striped">
<%-- Headings --%>
<tr class="rollertable">
    <th class="rollertable" width="20%"><spring:message code="generic.name"/></th>
    <th class="rollertable" width="40%"><spring:message code="pingTarget.pingUrl"/></th>
    <th class="rollertable" width="20%" colspan=2><spring:message code="pingTarget.auto"/></th>
    <th class="rollertable" width="20%"><spring:message code="pingTarget.manual"/></th>
</tr>

<%-- Table of current common targets with actions --%>
<c:forEach items="${commonPingTargets}" var="pingTarget" varStatus="rowstatus">
    <c:choose>
<c:when test="${rowstatus.odd == true}">
        <tr class="rollertable_odd">
    </c:when>
<c:otherwise>
        <tr class="rollertable_even">
    </c:otherwise>
</c:choose><td class="rollertable">
        <str:truncateNicely lower="15" upper="20" >${pingTarget.name}</str:truncateNicely>
    </td>
    
    <td class="rollertable">
        <str:truncateNicely lower="70" upper="75" >${pingTarget.pingUrl}</str:truncateNicely>
    </td>
    
    <!-- TODO: Use icons here -->
    <td class="rollertable" align="center" >
        <c:choose>
<c:when test="${pingStatus[pingTarget.id]}">
            <span style="color: #00aa00; font-weight: bold;"><spring:message code="pingTarget.enabled"/></span>&nbsp;
        </c:when>
<c:otherwise>
            <span style="color: #aaaaaa; font-weight: bold;"><spring:message code="pingTarget.disabled"/></span>&nbsp;
        </c:otherwise>
</c:choose></td>
    
    <!-- TODO: Use icons here -->
    <td class="rollertable" align="center" >
        <c:choose>
<c:when test="${pingStatus[pingTarget.id]}">
            <c:url var="disableUrl" value="/roller-ui/authoring/pings!disable.rol">
                <c:param name="weblog" value="${actionWeblog.handle}"/>
                <c:param name="pingTargetId" value="${pingTarget.id}"/>
            </c:url>
            <a href="${disableUrl}"><spring:message code="pingTarget.disable"/></a>
        </c:when>
<c:otherwise>
            <c:url var="enableUrl" value="/roller-ui/authoring/pings!enable.rol">
                <c:param name="weblog" value="${actionWeblog.handle}"/>
                <c:param name="pingTargetId" value="${pingTarget.id}"/>
            </c:url>
            <a href="${enableUrl}"><spring:message code="pingTarget.enable"/></a>
        </c:otherwise>
</c:choose></td>
    
    <td class="rollertable">
        <c:url var="pingNowUrl" value="/roller-ui/authoring/pings!pingNow.rol">
            <c:param name="weblog" value="${actionWeblog.handle}"/>
            <c:param name="pingTargetId" value="${pingTarget.id}"/>
        </c:url>
        <a href="${pingNowUrl}"><spring:message code="pingTarget.sendPingNow"/></a>
    </td>
    
    </tr>
</c:forEach>
</table>

<br />
