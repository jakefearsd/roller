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

<div class="sidebarFade">
    <div class="menu-tr">
        <div class="menu-tl">
            
            <div class="sidebarInner">

                <%-- comments on this entry --%>
                
                <h3><spring:message code="weblogEdit.comments"/></h3>

                <c:choose>
<c:when test="${bean.commentCount > 0}">
                    <c:url var="commentsURL" value="/roller-ui/authoring/comments.rol">
                       <c:param name="bean.entryId" value="${bean.id}"/>
                       <c:param name="weblog" value="${actionWeblog.handle}"/>
                    </c:url>
                    <spring:message code="weblogEdit.hasComments" arguments="${commentsURL},${bean.commentCount}"/>
                </c:when>
<c:otherwise>
                    <span><spring:message code="generic.none"/></span>
                </c:otherwise>
</c:choose><%-- pending entries --%>
                    
                <hr size="1" noshade="noshade" />  
                <h3><spring:message code="weblogEdit.pendingEntries"/></h3>
                
                <c:set var="pendingEntries" value="${recentPendingEntries}"/>
                <c:if test="${empty pendingEntries}">
                    <span><spring:message code="generic.none"/></span>
                </c:if>
                <c:forEach items="${pendingEntries}" var="post">
                    <span class="entryEditSidebarLink">
                        <c:url var="editUrl" value="/roller-ui/authoring/entryEdit.rol">
                            <c:param name="weblog" value="${actionWeblog.handle}"/>
                            <c:param name="bean.id" value="${post.id}"/>
                        </c:url>
                        <span class="glyphicon glyphicon-lock" aria-hidden="true"> </span> 
                        <a href="${editUrl}"><str:truncateNicely lower="40">
                             ${post.title}</str:truncateNicely></a>
                    </span><br />
                </c:forEach>

                <%-- draft entries --%>
                
                <hr size="1" noshade="noshade" />            
                <h3><spring:message code="weblogEdit.draftEntries"/></h3>
                
                <c:set var="draftEntries" value="${recentDraftEntries}"/>
                <c:if test="${empty draftEntries}">
                    <span><spring:message code="generic.none"/></span>
                </c:if>
                <c:forEach items="${draftEntries}" var="post">
                    <span class="entryEditSidebarLink">
                        <c:url var="editUrl" value="/roller-ui/authoring/entryEdit.rol">
                            <c:param name="weblog" value="${actionWeblog.handle}"/>
                            <c:param name="bean.id" value="${post.id}"/>
                        </c:url>
                        <span class="glyphicon glyphicon-edit" aria-hidden="true"> </span> 
                        <a href="${editUrl}"><str:truncateNicely lower="40">
                             ${post.title}</str:truncateNicely></a>
                    </span><br />
                </c:forEach>
                
                
                <c:if test="${userAnAuthor}">

                    <%-- published entries --%>

                    <hr size="1" noshade="noshade" />
                    <h3><spring:message code="weblogEdit.publishedEntries"/></h3>
                    
                    <c:set var="pubEntries" value="${recentPublishedEntries}"/>
                    <c:if test="${empty pubEntries}">
                        <span><spring:message code="generic.none"/></span>
                    </c:if>
                    <c:forEach items="${pubEntries}" var="post">
                        <span class="entryEditSidebarLink">
                            <c:url var="editUrl" value="/roller-ui/authoring/entryEdit.rol">
                                <c:param name="weblog" value="${actionWeblog.handle}"/>
                                <c:param name="bean.id" value="${post.id}"/>
                            </c:url>
                            <span class="glyphicon glyphicon-book" aria-hidden="true"> </span> 
                            <a href="${editUrl}"><str:truncateNicely lower="40">
                                ${post.title}</str:truncateNicely></a>
                        </span><br />
                    </c:forEach>


                    <%-- scheduled entries --%>

                    <hr size="1" noshade="noshade" />            
                    <h3><spring:message code="weblogEdit.scheduledEntries"/></h3>
                    
                    <c:set var="schedEntries" value="${recentScheduledEntries}"/>
                    <c:if test="${empty schedEntries}">
                        <span><spring:message code="generic.none"/></span>
                    </c:if>
                    <c:forEach items="${schedEntries}" var="post">
                        <span class="entryEditSidebarLink">
                            <c:url var="editUrl" value="/roller-ui/authoring/entryEdit.rol">
                                <c:param name="weblog" value="${actionWeblog.handle}"/>
                                <c:param name="bean.id" value="${post.id}"/>
                            </c:url>
                            <span class="glyphicon glyphicon-time" aria-hidden="true"> </span>
                            <a href="${editUrl}"><str:truncateNicely lower="40">
                                ${post.title}</str:truncateNicely></a>
                        </span><br />
                    </c:forEach>
                    
                </c:if>
                
                <br />
                <br />
            </div>
            
        </div>
    </div>
</div>
