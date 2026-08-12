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
    <spring:message code="websiteSettings.subtitle" arguments="${actionWeblog.handle}"/>
</p>

<%-- The legacy free-text analytics textarea and its entry in the section index
     are the SAME branch, held in one variable so the two can never disagree
     about whether that group is on the page. It renders only when the site
     allows an analytics-code override AND weblog admins are trusted; this fork
     keeps weblogAdminsUntrusted on, so in practice it never renders -- the
     structured, validated bean.analyticsSiteId field below is what a weblog
     owner uses instead. --%>
<c:set var="showAnalyticsCodeOverride"
       value="${rc:getBooleanProp('analytics.code.override.allowed') && !weblogAdminsUntrusted}"/>

<c:url var="weblogRemoveUrl" value="/roller-ui/authoring/weblogRemove.rol">
    <c:param name="weblog" value="${actionWeblog.handle}"/>
</c:url>

<%-- Settings is a long form with a rail (see
     docs/design/forms/settings-with-rail.html, the approved card): the field
     groups on the left, and a 252px rail holding a section index -- the spine
     marks where you are, exactly as the nav rail does -- plus a Save that is
     reachable without scrolling past nine groups to find it.

     The <form> IS the grid container, so the rail sits in its second column
     without a display:contents indirection and the Save button stays a plain
     descendant of the form it submits. Hidden inputs are display:none and so
     never become grid items. --%>
<form action="${pageContext.request.contextPath}/roller-ui/authoring/weblogConfig!save.rol" method="post" class="settings-grid form-stacked">
<input type="hidden" name="weblog" value="${actionWeblog.handle}"/>

<div>

    <%-- ***** General settings ***** --%>

    <h3 class="section-head" id="settings-general"><spring:message code="websiteSettings.generalSettings"/></h3>

    <div class="row mb-3">
        <label class="col-sm-3 col-form-label"><spring:message code="websiteSettings.websiteTitle"/></label>
        <div class="col-sm-9">
            <input type="text" name="bean.name" value="${bean.name}" size="30" maxlength="40" class="form-control"/>
        </div>
    </div>

    <div class="row mb-3">
        <label class="col-sm-3 col-form-label"><spring:message code="generic.tagline"/></label>
        <div class="col-sm-9">
            <input type="text" name="bean.tagline" value="${bean.tagline}" size="30" maxlength="255" class="form-control"/>
        </div>
    </div>

    <div class="row mb-3">
        <label class="col-sm-3 col-form-label"><spring:message code="websiteSettings.icon"/></label>
        <div class="col-sm-9">
            <input type="text" name="bean.icon" value="${bean.icon}" size="30" maxlength="40" class="form-control"/>
        </div>
    </div>

    <div class="row mb-3">
        <label class="col-sm-3 col-form-label"><spring:message code="websiteSettings.about"/></label>
        <div class="col-sm-9">
            <textarea name="bean.about" rows="3" cols="40" class="form-control">${bean.about}</textarea>
        </div>
    </div>

    <div class="row mb-3">
        <label class="col-sm-3 col-form-label"><spring:message code="websiteSettings.emailAddress"/></label>
        <div class="col-sm-9">
            <input type="text" name="bean.emailAddress" value="${bean.emailAddress}" size="30" maxlength="40" class="form-control"/>
        </div>
    </div>

    <div class="row mb-3">
        <label class="col-sm-3 col-form-label"><spring:message code="websiteSettings.entryDisplayCount"/></label>
        <div class="col-sm-9">
            <input type="number" name="bean.entryDisplayCount" value="${bean.entryDisplayCount}" size="4" class="form-control"/>
        </div>
    </div>

    <div class="row mb-3">
        <div class="offset-sm-3 col-sm-9">
            <div class="form-check">
                <label class="form-check-label"><input type="checkbox" class="form-check-input" name="bean.active" value="true" ${bean.active ? 'checked' : ''}/> <spring:message code="websiteSettings.active"/></label>
            </div>
        </div>
    </div>

    <%-- ***** Language/i18n settings ***** --%>

    <h3 class="section-head" id="settings-language"><spring:message code="websiteSettings.languageSettings"/></h3>

    <div class="row mb-3">
        <label class="col-sm-3 col-form-label"><spring:message code="createWebsite.locale"/></label>
        <div class="col-sm-9">
            <select name="bean.locale" class="form-select">
                <c:forEach items="${localesList}" var="opt">
                    <option value="${opt}" ${opt == bean.locale ? 'selected' : ''}>${opt}</option>
                </c:forEach>
            </select>
        </div>
    </div>

    <div class="row mb-3">
        <label class="col-sm-3 col-form-label"><spring:message code="createWebsite.timezone"/></label>
        <div class="col-sm-9">
            <select name="bean.timeZone" class="form-select">
                <c:forEach items="${timeZonesList}" var="opt">
                    <option value="${opt}" ${opt == bean.timeZone ? 'selected' : ''}>${opt}</option>
                </c:forEach>
            </select>
        </div>
    </div>

    <%-- ***** Web analytics settings ***** --%>

    <c:if test="${showAnalyticsCodeOverride}">
        <h3 class="section-head" id="settings-web-analytics"><spring:message code="configForm.webAnalytics"/></h3>

        <div class="row mb-3">
            <label class="col-sm-3 col-form-label"><spring:message code="websiteSettings.analyticsTrackingCode"/></label>
            <div class="col-sm-9">
                <textarea name="bean.analyticsCode" rows="10" cols="70" class="form-control">${bean.analyticsCode}</textarea>
            </div>
        </div>
    </c:if>

    <%-- ***** Analytics settings ***** --%>

    <h3 class="section-head" id="settings-analytics"><spring:message code="websiteSettings.analyticsSettings"/></h3>

    <div class="row mb-3">
        <label class="col-sm-3 col-form-label"><spring:message code="websiteSettings.analyticsSiteId"/></label>
        <div class="col-sm-9">
            <input type="text" name="bean.analyticsSiteId" value="${fn:escapeXml(bean.analyticsSiteId)}" size="40" maxlength="64" class="form-control"/>
            <div class="form-text"><spring:message code="websiteSettings.analyticsSiteId.tip"/></div>
        </div>
    </div>

    <div class="row mb-3">
        <label class="col-sm-3 col-form-label"><spring:message code="websiteSettings.analyticsShareUrl"/></label>
        <div class="col-sm-9">
            <input type="text" name="bean.analyticsShareUrl" value="${fn:escapeXml(bean.analyticsShareUrl)}" size="40" maxlength="255" class="form-control"/>
            <div class="form-text"><spring:message code="websiteSettings.analyticsShareUrl.tip"/></div>
            <%-- A rejected save re-renders this page with the raw submitted bean, so
                 "not empty" alone would echo an unvalidated value (e.g. javascript:...)
                 as a live link. The prefix check below is the scheme allowlist for the
                 echoed link, case-insensitively mirroring myValidate's own check; "not
                 empty" short-circuits first so fn:toLowerCase never sees a null value. --%>
            <c:if test="${not empty bean.analyticsShareUrl
                    and (fn:startsWith(fn:toLowerCase(bean.analyticsShareUrl), 'https://')
                    or fn:startsWith(fn:toLowerCase(bean.analyticsShareUrl), 'http://'))}">
                <div class="form-text"><a href="${fn:escapeXml(bean.analyticsShareUrl)}" target="_blank" rel="noopener">${fn:escapeXml(bean.analyticsShareUrl)}</a></div>
            </c:if>
        </div>
    </div>

    <%-- ***** Newsletter settings ***** --%>

    <h3 class="section-head" id="settings-newsletter"><spring:message code="websiteSettings.newsletterSettings"/></h3>

    <div class="row mb-3">
        <label class="col-sm-3 col-form-label"><spring:message code="websiteSettings.newsletterListUuid"/></label>
        <div class="col-sm-9">
            <input type="text" name="bean.newsletterListUuid" value="${fn:escapeXml(bean.newsletterListUuid)}" size="40" maxlength="64" class="form-control"/>
            <div class="form-text"><spring:message code="websiteSettings.newsletterListUuid.tip"/></div>
        </div>
    </div>

</div>

<%-- ====================================================================== --%>
<%-- The rail: where you are, Save, and the quiet way out. --%>

<aside class="settings-rail">

    <div class="rail-box">
        <h3 class="section-head"><spring:message code="websiteSettings.sections"/></h3>
        <nav class="section-index" id="settingsSectionIndex"
             aria-label="<spring:message code="websiteSettings.sections"/>">
            <a href="#settings-general" class="is-current"><spring:message code="websiteSettings.generalSettings"/></a>
            <a href="#settings-language"><spring:message code="websiteSettings.languageSettings"/></a>
            <c:if test="${showAnalyticsCodeOverride}">
                <a href="#settings-web-analytics"><spring:message code="configForm.webAnalytics"/></a>
            </c:if>
            <a href="#settings-analytics"><spring:message code="websiteSettings.analyticsSettings"/></a>
            <a href="#settings-newsletter"><spring:message code="websiteSettings.newsletterSettings"/></a>
        </nav>
    </div>

    <div class="rail-box">
        <button type="submit" class="btn btn-success w-100"><spring:message code="websiteSettings.button.update"/></button>
    </div>

    <%-- Removal is a quiet text link, matching the editor's delete: it opens
         weblogRemove.rol, the confirmation page that carries the irreversible-
         removal warning and the CSRF-protected POST that actually removes the
         weblog. It was a second <form> below this one, POSTing to that same
         address -- which has only a GET mapping, so the button answered 405.
         A link is both what the card asks for and the working form of it.

         The rail's one caps label belongs on the section index, per the
         card; the delete box is a single quiet link and needs none. --%>
    <div class="rail-box">
        <a class="delete-link" href="${weblogRemoveUrl}"><spring:message code="websiteSettings.button.remove"/></a>
    </div>

</aside>

<sec:csrfInput/>
</form>

<%-- The index marks where you are while scrolling. IntersectionObserver only,
     guarded: without it the index is still a working list of anchor links and
     the server-rendered .is-current simply stays on the first group. The links
     are plain anchors, so scrolling is the browser's own -- no smooth-scroll
     behaviour to withhold from a reader who asked for reduced motion. --%>
<script>
(function () {
    var index = document.getElementById('settingsSectionIndex');
    if (!index || !('IntersectionObserver' in window)) {
        return;
    }

    var links = {};
    var heads = [];
    Array.prototype.forEach.call(index.querySelectorAll('a[href^="#"]'), function (link) {
        var head = document.getElementById(link.getAttribute('href').substring(1));
        if (head) {
            links[head.id] = link;
            heads.push(head);
        }
    });
    if (heads.length === 0) {
        return;
    }

    var inBand = {};

    function mark(id) {
        heads.forEach(function (head) {
            links[head.id].classList.toggle('is-current', head.id === id);
        });
    }

    // The band is the top 30% of the viewport: the heading in it is the group
    // you are reading. When a long group has scrolled past its own heading
    // nothing is in the band, and the previous mark deliberately stays put.
    var observer = new IntersectionObserver(function (entries) {
        entries.forEach(function (entry) {
            inBand[entry.target.id] = entry.isIntersecting;
        });
        for (var i = 0; i < heads.length; i++) {
            if (inBand[heads[i].id]) {
                mark(heads[i].id);
                return;
            }
        }
    }, { rootMargin: '0px 0px -70% 0px' });

    heads.forEach(function (head) {
        observer.observe(head);
    });
}());
</script>
