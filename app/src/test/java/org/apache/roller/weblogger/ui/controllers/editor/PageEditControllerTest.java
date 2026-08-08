/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  The ASF licenses this file to You
 * under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.  For additional information regarding
 * copyright in this work, please see the NOTICE file in the top level
 * directory of this distribution.
 */
package org.apache.roller.weblogger.ui.controllers.editor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import jakarta.servlet.http.HttpServletRequest;

import org.apache.roller.weblogger.WebloggerException;
import org.apache.roller.weblogger.pojos.Weblog;
import org.apache.roller.weblogger.pojos.WeblogPage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.ui.Model;
import org.springframework.web.bind.ServletRequestDataBinder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link PageEditController} and {@link PageBean}.
 *
 * <p>The critical behaviour is the same hazard as {@code lookupCategory} /
 * {@code lookupEntry} / {@code lookupTemplate}: a page id is client input and
 * {@code getPage} is a global by-id lookup, so without an ownership check an
 * editor on one weblog could open -- and on save, overwrite -- another
 * weblog's page. See {@link #aPageBelongingToAnotherWeblogIsNotFound()} and
 * {@link #savingAPageBelongingToAnotherWeblogIsRefused()}.
 */
class PageEditControllerTest extends EditorControllerTestSupport {

    /** Where a denied lookup goes: back to the list, same as TemplateEditController. */
    private static final String DENIED_VIEW = ".Pages";

    private Weblog weblogA;
    private Weblog weblogB;

    private PageEditController controller;
    private Model model;

    @BeforeEach
    void setUp() {
        controller = prepare(new PageEditController());
        model = newModel();

        weblogA = weblog;
        weblogB = new Weblog();
        weblogB.setId("weblog-2");
        weblogB.setHandle("someoneelse");
    }

    // --- ownership ---

    @Test
    void aPageBelongingToAnotherWeblogIsNotFound() throws Exception {
        // actionWeblog is weblogA; the id names a page on weblogB
        WeblogPage foreign = pageOn(weblogB, "their-about");

        String view = controller.edit(foreign.getId(), requestFor(weblogA), model);

        assertEquals(DENIED_VIEW, view,
                "the permission interceptor vouches only for the action weblog, "
                        + "so a global by-id lookup would let any editor edit any "
                        + "weblog's pages");
    }

    @Test
    void aBlankIdIsTreatedAsAbsentRatherThanLookedUp() throws Exception {
        assertNull(controller.lookupPage("   ", requestFor(weblogA)));
    }

    @Test
    void savingAPageBelongingToAnotherWeblogIsRefused() throws Exception {
        WeblogPage foreign = pageOn(weblogB, "their-about");
        PageBean bean = new PageBean();
        bean.setId(foreign.getId());
        bean.setTitle("Hijacked");
        bean.setSlug("hijacked");

        String view = controller.save(requestFor(weblogA), model, bean);

        assertEquals(DENIED_VIEW, view);
        assertEquals("their-about", foreign.getSlug(),
                "a foreign page must come through the request unchanged");
        verify(weblogger.getWeblogPageManager(), never()).savePage(any());
    }

    // --- opening the editor ---

    @Test
    void openingAnExistingPageLoadsItIntoTheForm() throws Exception {
        WeblogPage page = pageOn(weblogA, "about");
        page.setTitle("About Us");
        page.setContent("## Hello");

        String view = controller.edit(page.getId(), requestFor(weblogA), model);

        assertEquals(".PageEdit", view);
        PageBean bean = (PageBean) model.getAttribute("bean");
        assertEquals("About Us", bean.getTitle());
        assertEquals("## Hello", bean.getContent());
        assertEquals("about", bean.getSlug());
    }

    @Test
    void openingWithNoIdShowsAnEmptyFormDefaultingToDraft() throws Exception {
        String view = controller.edit(null, requestFor(weblogA), model);

        assertEquals(".PageEdit", view);
        PageBean bean = (PageBean) model.getAttribute("bean");
        assertEquals("DRAFT", bean.getStatus());
        assertNull(bean.getId());
    }

    // --- saving ---

    @Test
    void savingANewPageCreatesItAsADraftByDefault() throws Exception {
        PageBean bean = new PageBean();
        bean.setSlug("about");
        bean.setTitle("About");
        bean.setContent("Some content");

        String view = controller.save(requestFor(weblogA), model, bean);

        assertEquals(".PageEdit", view);

        org.mockito.ArgumentCaptor<WeblogPage> saved =
                org.mockito.ArgumentCaptor.forClass(WeblogPage.class);
        verify(weblogger.getWeblogPageManager()).savePage(saved.capture());
        assertEquals("about", saved.getValue().getSlug());
        assertEquals("About", saved.getValue().getTitle());
        assertEquals(WeblogPage.PubStatus.DRAFT, saved.getValue().getStatus());
        assertEquals(weblogA, saved.getValue().getWeblog(),
                "a new page must be attached to the action weblog, not left dangling");
        assertEquals(1, weblogger.flushCount());
    }

    @Test
    void savingAnExistingPageUpdatesTheLookedUpPage() throws Exception {
        WeblogPage existing = pageOn(weblogA, "about");
        existing.setTitle("Old Title");

        PageBean bean = new PageBean();
        bean.setId(existing.getId());
        bean.setSlug("about");
        bean.setTitle("New Title");
        bean.setStatus("PUBLISHED");

        String view = controller.save(requestFor(weblogA), model, bean);

        assertEquals(".PageEdit", view);
        assertEquals("New Title", existing.getTitle(),
                "the edit must be applied to the page that was looked up");
        assertEquals(WeblogPage.PubStatus.PUBLISHED, existing.getStatus());
        verify(weblogger.getWeblogPageManager()).savePage(existing);
    }

    @Test
    void savingWithAReservedSlugIsAFieldErrorNotA500() throws Exception {
        doThrow(new WebloggerException("page slug is reserved: feed"))
                .when(weblogger.getWeblogPageManager()).savePage(any());

        PageBean bean = new PageBean();
        bean.setSlug("feed");
        bean.setTitle("Feed");

        String view = controller.save(requestFor(weblogA), model, bean);

        assertEquals(".PageEdit", view, "a rejected slug must redisplay the form, not error out");
        assertTrue(errors(model).contains("pageEdit.error.slugReserved"),
                "Expected a slugReserved error, got: " + errors(model));
    }

    @Test
    void savingWithABlankSlugIsAFieldErrorNotA500() throws Exception {
        doThrow(new WebloggerException("page slug is required"))
                .when(weblogger.getWeblogPageManager()).savePage(any());

        PageBean bean = new PageBean();
        bean.setSlug("");
        bean.setTitle("No Slug");

        String view = controller.save(requestFor(weblogA), model, bean);

        assertEquals(".PageEdit", view);
        assertTrue(errors(model).contains("pageEdit.error.slugInvalid"),
                "Expected a slugInvalid error, got: " + errors(model));
    }

    @Test
    void savingWithASlashInTheSlugIsAFieldErrorNotA500() throws Exception {
        doThrow(new WebloggerException("page slug may not contain '/': a/b"))
                .when(weblogger.getWeblogPageManager()).savePage(any());

        PageBean bean = new PageBean();
        bean.setSlug("a/b");
        bean.setTitle("Slashy");

        String view = controller.save(requestFor(weblogA), model, bean);

        assertEquals(".PageEdit", view);
        assertTrue(errors(model).contains("pageEdit.error.slugInvalid"),
                "Expected a slugInvalid error, got: " + errors(model));
    }

    @Test
    void savingWithAnUnrecognizedStatusSavesAsDraftRatherThanThrowing() throws Exception {
        // A crafted POST can carry any string in bean.status -- the dropdown
        // only ever offers DRAFT/PUBLISHED, but nothing stops a direct POST
        // of bean.status=BOGUS. PubStatus.valueOf throws IllegalArgumentException
        // on anything it does not recognize, and that must not reach the
        // caller as a 500.
        PageBean bean = new PageBean();
        bean.setSlug("about");
        bean.setTitle("About");
        bean.setStatus("BOGUS");

        String view = controller.save(requestFor(weblogA), model, bean);

        assertEquals(".PageEdit", view);
        assertTrue(errors(model).isEmpty(), "Expected no error, got: " + errors(model));

        org.mockito.ArgumentCaptor<WeblogPage> saved =
                org.mockito.ArgumentCaptor.forClass(WeblogPage.class);
        verify(weblogger.getWeblogPageManager()).savePage(saved.capture());
        assertEquals(WeblogPage.PubStatus.DRAFT, saved.getValue().getStatus());
    }

    @Test
    void anUnrelatedSaveFailureIsReportedGenerically() throws Exception {
        doThrow(new WebloggerException("database down"))
                .when(weblogger.getWeblogPageManager()).savePage(any());

        PageBean bean = new PageBean();
        bean.setSlug("about");
        bean.setTitle("About");

        controller.save(requestFor(weblogA), model, bean);

        assertTrue(errors(model).contains("generic.error.check.logs"),
                "Expected a generic failure, got: " + errors(model));
        assertTrue(messages(model).isEmpty(), "A failed save must not also report success");
    }

    // --- checkbox field-marker binding (real Spring WebDataBinder) ---
    //
    // Every test above calls controller.save(...) directly with a hand-built
    // PageBean, bypassing Spring's WebDataBinder entirely -- so none of them
    // would have caught a bug in the marker convention itself. These two
    // build the exact ServletRequestDataBinder Spring MVC would construct
    // for a POST to pageEdit!save.rol (running it through
    // BaseController#initBeanBinder, the real @InitBinder) and bind a
    // request carrying PageEdit.jsp's actual marker input name -- read
    // straight out of the JSP so a regression to the wrong name fails here,
    // not only in the browser suite.

    @Test
    void uncheckingShowInNavBindsToFalseThroughTheRealBinder() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/roller-ui/authoring/pageEdit!save.rol");
        request.setParameter("bean.slug", "about");
        request.setParameter("bean.title", "About Us");
        // The box was unchecked: the browser omits "bean.showInNav" entirely,
        // submitting only the marker PageEdit.jsp always renders.
        request.setParameter(showInNavMarkerName(), "on");

        PageBean bean = new PageBean();
        ServletRequestDataBinder binder = new ServletRequestDataBinder(bean, "bean");
        controller.initBeanBinder(binder);
        binder.bind(request);

        assertEquals(false, bean.getShowInNav(),
                "an unchecked box must bind to false -- PageBean defaults to "
                        + "true, so a marker name that does not round-trip "
                        + "through BaseController's \"bean.\" field-default "
                        + "prefix leaves nav on forever (see "
                        + "BaseController#initBeanBinder)");
    }

    @Test
    void checkingShowInNavBindsToTrueThroughTheRealBinder() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/roller-ui/authoring/pageEdit!save.rol");
        request.setParameter("bean.slug", "about");
        request.setParameter("bean.title", "About Us");
        request.setParameter("bean.showInNav", "true");
        request.setParameter(showInNavMarkerName(), "on");

        PageBean bean = new PageBean();
        bean.setShowInNav(false);
        ServletRequestDataBinder binder = new ServletRequestDataBinder(bean, "bean");
        controller.initBeanBinder(binder);
        binder.bind(request);

        assertEquals(true, bean.getShowInNav());
    }

    /**
     * The {@code name} of PageEdit.jsp's showInNav field-marker hidden
     * input, read from the JSP itself rather than hardcoded, so these tests
     * fail if the marker is ever renamed back to the broken
     * {@code "_bean.showInNav"} (see BaseController#initBeanBinder).
     */
    private static String showInNavMarkerName() throws IOException {
        Path jsp = Paths.get("src/main/webapp/WEB-INF/jsps/editor/PageEdit.jsp");
        String content = Files.readString(jsp, StandardCharsets.UTF_8);
        Matcher matcher = Pattern.compile("name=\"(_[^\"]*[Ss]howInNav[^\"]*)\"").matcher(content);
        assertTrue(matcher.find(), "PageEdit.jsp must carry a showInNav field-marker hidden input");
        return matcher.group(1);
    }

    // --- PageBean ---

    @Test
    void beanCopiesRoundTrip() {
        WeblogPage page = new WeblogPage();
        page.setId("page-1");
        page.setWeblog(weblogA);
        page.setSlug("about");
        page.setTitle("About");
        page.setContent("body text");
        page.setStatus(WeblogPage.PubStatus.PUBLISHED);
        page.setShowInNav(false);
        page.setNavOrder(3);
        page.setMetaTitle("Meta Title");
        page.setSearchDescription("desc");
        page.setCanonicalUrl("https://example.com/about");
        page.setNoindex(true);
        page.setOgImageId("media-1");

        PageBean bean = new PageBean();
        bean.copyFrom(page);

        assertEquals("page-1", bean.getId());
        assertEquals("about", bean.getSlug());
        assertEquals("About", bean.getTitle());
        assertEquals("body text", bean.getContent());
        assertEquals("PUBLISHED", bean.getStatus());
        assertEquals(false, bean.getShowInNav());
        assertEquals(3, bean.getNavOrder());
        assertEquals("Meta Title", bean.getMetaTitle());
        assertEquals("desc", bean.getSearchDescription());
        assertEquals("https://example.com/about", bean.getCanonicalUrl());
        assertEquals(true, bean.getNoindex());
        assertEquals("media-1", bean.getOgImageId());

        WeblogPage target = new WeblogPage();
        bean.copyTo(target);

        assertEquals("about", target.getSlug());
        assertEquals("About", target.getTitle());
        assertEquals("body text", target.getContent());
        assertEquals(WeblogPage.PubStatus.PUBLISHED, target.getStatus());
        assertEquals(false, target.getShowInNav());
        assertEquals(3, target.getNavOrder());
        assertEquals("Meta Title", target.getMetaTitle());
        assertEquals("desc", target.getSearchDescription());
        assertEquals("https://example.com/about", target.getCanonicalUrl());
        assertEquals(true, target.getNoindex());
        assertEquals("media-1", target.getOgImageId());
    }

    @Test
    void copyToDefaultsABlankStatusToDraftRatherThanThrowing() {
        PageBean bean = new PageBean();
        bean.setStatus(null);
        WeblogPage page = new WeblogPage();

        bean.copyTo(page);

        assertEquals(WeblogPage.PubStatus.DRAFT, page.getStatus());
    }

    @Test
    void copyToDefaultsAnUnrecognizedStatusToDraftRatherThanThrowing() {
        // The dropdown only ever offers DRAFT/PUBLISHED, but the bean is
        // bound from raw POST data, so PubStatus.valueOf's
        // IllegalArgumentException on anything else must be caught here, not
        // just the blank case.
        PageBean bean = new PageBean();
        bean.setStatus("BOGUS");
        WeblogPage page = new WeblogPage();

        bean.copyTo(page);

        assertEquals(WeblogPage.PubStatus.DRAFT, page.getStatus());
    }

    // --- fixtures ---

    private HttpServletRequest requestFor(Weblog weblog) {
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getLocale()).thenReturn(Locale.US);
        when(req.getAttribute("authenticatedUser")).thenReturn(user);
        when(req.getAttribute("actionWeblog")).thenReturn(weblog);
        return req;
    }

    /** A page owned by {@code weblog}, resolvable by its global id. */
    private WeblogPage pageOn(Weblog weblog, String slug) throws WebloggerException {
        WeblogPage page = new WeblogPage();
        page.setId("page-" + slug);
        page.setWeblog(weblog);
        page.setSlug(slug);
        when(weblogger.getWeblogPageManager().getPage(page.getId())).thenReturn(page);
        return page;
    }
}
