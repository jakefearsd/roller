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
   <spring:message code="oauthKeys.description" arguments="${authenticatedUser.userName}" />
</p>

<p class="pagetip">
   <spring:message code="oauthKeys.tip" />
</p>


<h2><spring:message code="oauthKeys.userKeys" /></h2>

<p><spring:message code="oauthKeys.userKeysTip" /></p>

    <p style="margin-left:2em"><b><spring:message code="oauthKeys.consumerKey" /></b>:
        ${fn:escapeXml(userConsumer.consumerKey)}</p>

    <p style="margin-left:2em"><b><spring:message code="oauthKeys.consumerSecret" /></b>:
        ${fn:escapeXml(userConsumer.consumerSecret)}</p>


<c:if test="${not empty siteWideConsumer}">

<h2><spring:message code="oauthKeys.siteWideKeys" /></h2>

<p><spring:message code="oauthKeys.siteWideKeysTip" /></p>

    <p style="margin-left:2em"><b><spring:message code="oauthKeys.consumerKey" /></b>:
        ${fn:escapeXml(siteWideConsumer.consumerKey)}</p>

    <p style="margin-left:2em"><b><spring:message code="oauthKeys.consumerSecret" /></b>:
        ${fn:escapeXml(siteWideConsumer.consumerSecret)}</p>

</c:if>


<h2><spring:message code="oauthKeys.urls" /></h2>

<p><spring:message code="oauthKeys.urlsTip" /></p>

    <p style="margin-left:2em"><b><spring:message code="oauthKeys.requestTokenURL" /></b>:
        ${fn:escapeXml(requestTokenURL)}</p>

    <p style="margin-left:2em"><b><spring:message code="oauthKeys.authorizationURL" /></b>:
        ${fn:escapeXml(authorizationURL)}</p>

    <p style="margin-left:2em"><b><spring:message code="oauthKeys.accessTokenURL" /></b>:
        ${fn:escapeXml(accessTokenURL)}</p>

<br />

<input type="button" value="<spring:message code='generic.cancel'/>" onclick="window.location='<c:url value='/roller-ui/menu.rol'/>'" />
