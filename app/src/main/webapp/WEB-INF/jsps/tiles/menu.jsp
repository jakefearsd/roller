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

<c:set var="tabMenu" value="${menu}"/>
<c:if test="${tabMenu != null}">

    <%--
    <nav class="navbar navbar-default">
        <div class="container-fluid">
            <div id="navbar" class="navbar-collapse collapse">
                <ul class="nav navbar-nav">

                    <c:forEach items="${tabMenu.tabs}" var="tab">
                        <li class="dropdown">
                            <a href="#" class="dropdown-toggle" data-toggle="dropdown" role="button" 
                                aria-haspopup="true" aria-expanded="false">
                                <spring:message code="${tab.key}"/> <span class="caret"></span>
                            </a>
                            <ul class="dropdown-menu">
                                <c:forEach items="${tab.items}" var="tabItem" varStatus="stat">
                                    <li>
                                        <a href="<c:url value="/roller-ui/authoring/${tabItem.action}.rol"><c:param name="weblog" value="${actionWeblog.handle}"/></c:url>">
                                            <spring:message code="${tabItem.key}"/>
                                        </a>
                                    </li>
                                </c:forEach>
                            </ul>
                        </li>
                    </c:forEach>
                    
                </ul>
            </div> <!--/.nav-collapse -->
        </div> <!--/.container-fluid -->
    </nav>

    <c:forEach items="${tabMenu.tabs}" var="tab">

        <h3><spring:message code="${tab.key}"/></h3>

        <div class="list-group">
            <c:forEach items="${tab.items}" var="tabItem" varStatus="stat">
                <a class="list-group-item" href="<c:url value="/roller-ui/authoring/${tabItem.action}.rol"><c:param name="weblog" value="${actionWeblog.handle}"/></c:url>">
                    <spring:message code="${tabItem.key}"/></a>
            </c:forEach>
        </div>

    </c:forEach>
    --%>
        
</c:if>
