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

<p style="margin-bottom:2em"><spring:message code="index.prompt"/> </p>

<%--
      Index page on Roller startup; tell the user how to complete their Roller install,
      with helpful notes and links to the appropriate places in the Roller UI.
--%>


<%-- STEP 1: Create a user if you don't already have one --%>

<div class="panel panel-default">
    <div class="panel-heading">
        <h3 class="panel-title">
            <spring:message code="index.createUser"/>
            <c:if test="${userCount > 0}"> -
                <spring:message code="index.createUserDone" arguments="${userCount}"/>
            </c:if>
        </h3>
    </div>

    <div class="panel-body">

        <p><spring:message code="index.createUserHelp"/></p>
        <p><c:if test="${userCount == 0}">
            <spring:message code="index.createUserBy"/>
            <a id="a_createUser" href='<c:url value="/roller-ui/register.rol"/>'>
                <spring:message code="index.createUserPage"/></a>.
        </c:if></p>

    </div>
</div>

<%-- STEP 2: Create a weblog if you don't already have one --%>

<div class="panel panel-default">
    <div class="panel-heading">
        <h3 class="panel-title">
            <spring:message code="index.createWeblog"/>
            <c:if test="${blogCount > 0}"> -
                <spring:message code="index.createWeblogDone" arguments="${blogCount}"/>
            </c:if>
        </h3>
    </div>

    <div class="panel-body">

        <spring:message code="index.createWeblogHelp"/><br/><br/>
        <c:if test="${userCount > 0 && blogCount == 0}">
            <spring:message code="index.createWeblogBy"/>
            <a id="a_createBlog" href='<c:url value="/roller-ui/createWeblog.rol"/>'>
                <spring:message code="index.createWeblogPage"/></a>.
        </c:if>

    </div>
</div>


<%-- STEP 3: Designate a weblog to be the frontpage weblog --%>

<div class="panel panel-default">
    <div class="panel-heading">
        <h3 class="panel-title">
            <spring:message code="index.setFrontpage"/>
        </h3>
    </div>

    <div class="panel-body">

        <p><spring:message code="index.setFrontpageHelp"/></p>

        <c:if test="${blogCount > 0}">

            <form action="${pageContext.request.contextPath}/roller-ui/setup!save.rol" method="post" class="form-horizontal">
                <sec:csrfInput/>

                <div class="form-group">
                    <spring:message code="frontpageConfig.frontpageBlogName" var="frontpageBlogLabel"/>
                    <label class="col-sm-3 control-label">${frontpageBlogLabel}</label>
                    <div class="col-sm-9 controls">
                        <select name="frontpageBlog" class="form-control">
                            <c:forEach items="${weblogs}" var="w">
                                <option value="${fn:escapeXml(w.handle)}">${fn:escapeXml(w.name)}</option>
                            </c:forEach>
                        </select>
                    </div>
                </div>

                <div class="form-group">
                    <spring:message code="frontpageConfig.frontpageAggregated" var="aggregatedLabel"/>
                    <label class="col-sm-3 control-label">${aggregatedLabel}</label>
                    <div class="col-sm-9 controls">
                        <input type="checkbox" name="aggregated" value="true"/>
                    </div>
                </div>

                <button type="submit" class="btn btn-default"><spring:message code="generic.save"/></button>

            </form>

        </c:if>
    </div>
</div>
