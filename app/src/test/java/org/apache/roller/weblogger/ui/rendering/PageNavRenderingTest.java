/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 *  contributor license agreements.  The ASF licenses this file to You
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
package org.apache.roller.weblogger.ui.rendering;

import java.util.ArrayList;
import java.util.List;

import org.apache.roller.weblogger.TestUtils;
import org.apache.roller.weblogger.business.WebloggerFactory;
import org.apache.roller.weblogger.pojos.User;
import org.apache.roller.weblogger.pojos.Weblog;
import org.apache.roller.weblogger.pojos.WeblogPage;
import org.apache.roller.weblogger.ui.rendering.servlets.RenderingTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code #showPageLinks} rendering a weblog's published pages into every
 * bundled theme's navigation, on the theme's own home page template.
 *
 * <p>One test looping the five themes rather than one method per theme --
 * same economical fixture pattern as {@code PortfolioThemeRenderingTest} and
 * the browser suite's {@code ThemeMatrixIT}: the pages are created once and
 * only the theme switch (and re-render) repeats per iteration. Failures are
 * collected per theme so one broken theme does not hide the others.
 */
class PageNavRenderingTest {

    private static final List<String> THEMES =
            List.of("basic", "fauxcoly", "gaurav", "portfolio", "travel");

    private static final String HANDLE = "pagenavrenderblog";
    private static final String BASE = "/roller/" + HANDLE;

    private User user;
    private Weblog weblog;

    @BeforeEach
    void setUp() throws Exception {
        RenderingTestSupport.ensureRenderingRuntime();
        RenderingTestSupport.clearRenderCaches();

        user = TestUtils.setupUser("pagenavrenderuser");
        weblog = TestUtils.setupWeblog(HANDLE, user);
        TestUtils.endSession(true);
    }

    @AfterEach
    void tearDown() throws Exception {
        WebloggerFactory.getWeblogger().getWeblogPageManager()
                .removePages(TestUtils.getManagedWebsite(weblog));
        TestUtils.endSession(true);
        TestUtils.teardownWeblog(weblog.getId());
        TestUtils.teardownUser(user.getUserName());
        TestUtils.endSession(true);
    }

    // ---------------------------------------------------------------- helpers

    private void savePage(String slug, String title, WeblogPage.PubStatus status,
            boolean showInNav, int navOrder) throws Exception {
        WeblogPage page = new WeblogPage();
        page.setWeblog(TestUtils.getManagedWebsite(weblog));
        page.setSlug(slug);
        page.setTitle(title);
        page.setContent("Body of " + slug);
        page.setStatus(status);
        page.setShowInNav(showInNav);
        page.setNavOrder(navOrder);
        WebloggerFactory.getWeblogger().getWeblogPageManager().savePage(page);
        WebloggerFactory.getWeblogger().flush();
        TestUtils.endSession(true);
    }

    private void switchTheme(String themeName) throws Exception {
        Weblog managed = TestUtils.getManagedWebsite(weblog);
        managed.setEditorTheme(themeName);
        WebloggerFactory.getWeblogger().getWeblogManager().saveWeblog(managed);
        TestUtils.endSession(true);
    }

    private String renderHomePage() throws Exception {
        MockHttpServletRequest request = RenderingTestSupport
                .anonymousGet("/roller-ui/rendering/page", "/" + HANDLE);
        MockHttpServletResponse response = RenderingTestSupport
                .execute(RenderingTestSupport.pageServlet(), request);
        assertEquals(200, response.getStatus(), "home page must render");
        return response.getContentAsString();
    }

    // ------------------------------------------------------------------ test

    @Test
    void everyBundledThemeShowsPublishedNavPagesOnTheHomePage() throws Exception {
        // navOrder deliberately out of creation order, so the assertion below
        // actually proves ordering rather than insertion order.
        savePage("second-page", "Second Page", WeblogPage.PubStatus.PUBLISHED, true, 20);
        savePage("first-page", "First & Best", WeblogPage.PubStatus.PUBLISHED, true, 10);
        savePage("hidden-page", "Hidden Page", WeblogPage.PubStatus.PUBLISHED, false, 0);
        savePage("draft-page", "Draft Page", WeblogPage.PubStatus.DRAFT, true, 0);
        // a slug with a character URLEncoder must escape, so the rendered
        // href proves #url.staticPage encodes the path segment rather than
        // interpolating the slug raw
        savePage("space page", "Spacey", WeblogPage.PubStatus.PUBLISHED, true, 30);

        List<String> failures = new ArrayList<>();
        for (String theme : THEMES) {
            try {
                RenderingTestSupport.clearRenderCaches();
                switchTheme(theme);
                String body = renderHomePage();
                checkTheme(theme, body);
            } catch (Throwable failure) {
                failures.add(theme + " -> " + failure);
            }
        }

        assertTrue(failures.isEmpty(),
                "themes failed the page-nav check:\n  " + String.join("\n  ", failures));
    }

    private void checkTheme(String theme, String body) {
        String firstHref = BASE + "/first-page";
        String secondHref = BASE + "/second-page";
        String spaceyHref = BASE + "/space+page";

        assertTrue(body.contains("<a href=\"" + firstHref + "\">First &amp; Best</a>"),
                theme + ": expected an escaped nav link to the first page:\n" + body);
        assertTrue(body.contains("<a href=\"" + secondHref + "\">Second Page</a>"),
                theme + ": expected a nav link to the second page:\n" + body);
        assertTrue(body.contains("<a href=\"" + spaceyHref + "\">Spacey</a>"),
                theme + ": a slug with a space must render URL-encoded ('+' for the "
                        + "space), not raw:\n" + body);

        assertFalse(body.contains("Hidden Page"),
                theme + ": a page with showInNav=false must not appear:\n" + body);
        assertFalse(body.contains("Draft Page"),
                theme + ": a DRAFT page must not appear:\n" + body);

        int firstIndex = body.indexOf(firstHref);
        int secondIndex = body.indexOf(secondHref);
        assertTrue(firstIndex >= 0 && secondIndex >= 0 && firstIndex < secondIndex,
                theme + ": nav links must follow navOrder (first-page navOrder=10 before "
                        + "second-page navOrder=20):\n" + body);

        // Each theme supplies its own <ul> container around #showPageLinks'
        // bare <li> items -- a theme that drops the items as siblings after
        // an already-closed </ul> renders invalid HTML that CSS scoped to
        // "ul.some-class li" silently fails to style.
        assertLinkIsInsideAUl(theme, body, firstHref);
        assertLinkIsInsideAUl(theme, body, secondHref);

        // a Velocity error resolving the macro would leak the raw directive
        assertFalse(body.contains("#showPageLinks"), theme + ":\n" + body);
    }

    /**
     * Confirms the nearest {@code <ul} before the link's {@code href} is
     * closer than the nearest {@code </ul>} before it -- i.e. the link
     * renders while a {@code <ul>} is still open, not as a bare {@code <li>}
     * sitting between two already-self-contained lists.
     */
    private static void assertLinkIsInsideAUl(String theme, String body, String href) {
        String needle = "href=\"" + href + "\"";
        int index = body.indexOf(needle);
        assertTrue(index >= 0, theme + ": expected to find a link to " + href + ":\n" + body);

        int ulOpen = body.lastIndexOf("<ul", index);
        int ulClose = body.lastIndexOf("</ul>", index);
        assertTrue(ulOpen >= 0 && ulOpen > ulClose,
                theme + ": the nav link to " + href + " must render inside an open "
                        + "<ul> supplied by the theme, not as a bare <li>:\n" + body);
    }
}
