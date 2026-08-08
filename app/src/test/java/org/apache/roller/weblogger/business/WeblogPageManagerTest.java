package org.apache.roller.weblogger.business;

import java.util.List;

import org.apache.roller.weblogger.TestUtils;
import org.apache.roller.weblogger.WebloggerException;
import org.apache.roller.weblogger.pojos.User;
import org.apache.roller.weblogger.pojos.Weblog;
import org.apache.roller.weblogger.pojos.WeblogPage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WeblogPageManagerTest {

    private User user;
    private Weblog weblog;
    private Weblog otherWeblog;

    @BeforeEach
    void setUp() throws Exception {
        TestUtils.setupWeblogger();
        user = TestUtils.setupUser("pageuser");
        weblog = TestUtils.setupWeblog("pageblog", user);
        otherWeblog = TestUtils.setupWeblog("otherpageblog", user);
        TestUtils.endSession(true);
    }

    @AfterEach
    void tearDown() throws Exception {
        TestUtils.teardownWeblog(weblog.getId());
        TestUtils.teardownWeblog(otherWeblog.getId());
        TestUtils.teardownUser(user.getUserName());
        TestUtils.endSession(true);
    }

    private static WeblogPageManager manager() {
        return WebloggerFactory.getWeblogger().getWeblogPageManager();
    }

    private WeblogPage save(Weblog target, String slug, WeblogPage.PubStatus status)
            throws Exception {
        WeblogPage page = new WeblogPage();
        page.setWeblog(TestUtils.getManagedWebsite(target));
        page.setSlug(slug);
        page.setTitle("Title for " + slug);
        page.setContent("Body of " + slug);
        page.setStatus(status);
        manager().savePage(page);
        WebloggerFactory.getWeblogger().flush();
        TestUtils.endSession(true);
        return page;
    }

    @Test
    void aSavedPageIsFoundBySlug() throws Exception {
        save(weblog, "about", WeblogPage.PubStatus.PUBLISHED);

        WeblogPage found = manager().getPageBySlug(TestUtils.getManagedWebsite(weblog), "about");

        assertNotNull(found);
        assertEquals("Title for about", found.getTitle());
    }

    /**
     * The isolation that matters. Two weblogs may both have an /about, and a
     * lookup scoped to one must never answer with the other's.
     */
    @Test
    void aSlugLookupIsScopedToItsWeblog() throws Exception {
        save(weblog, "about", WeblogPage.PubStatus.PUBLISHED);

        assertNull(manager().getPageBySlug(TestUtils.getManagedWebsite(otherWeblog), "about"),
                "another weblog's page must not answer this weblog's lookup");
    }

    @Test
    void twoWeblogsMayEachHaveTheSameSlug() throws Exception {
        save(weblog, "about", WeblogPage.PubStatus.PUBLISHED);
        save(otherWeblog, "about", WeblogPage.PubStatus.PUBLISHED);

        assertNotNull(manager().getPageBySlug(TestUtils.getManagedWebsite(weblog), "about"));
        assertNotNull(manager().getPageBySlug(TestUtils.getManagedWebsite(otherWeblog), "about"));
    }

    @Test
    void publishedPagesExcludeDrafts() throws Exception {
        save(weblog, "about", WeblogPage.PubStatus.PUBLISHED);
        save(weblog, "secret", WeblogPage.PubStatus.DRAFT);

        List<WeblogPage> published =
                manager().getPublishedPages(TestUtils.getManagedWebsite(weblog));

        assertEquals(1, published.size(), "got: " + published);
        assertEquals("about", published.get(0).getSlug());
    }

    @Test
    void pagesComeBackInNavOrder() throws Exception {
        WeblogPage second = save(weblog, "services", WeblogPage.PubStatus.PUBLISHED);
        WeblogPage first = save(weblog, "about", WeblogPage.PubStatus.PUBLISHED);

        WeblogPage managedFirst = manager().getPage(first.getId());
        managedFirst.setNavOrder(1);
        manager().savePage(managedFirst);
        WeblogPage managedSecond = manager().getPage(second.getId());
        managedSecond.setNavOrder(2);
        manager().savePage(managedSecond);
        WebloggerFactory.getWeblogger().flush();
        TestUtils.endSession(true);

        List<WeblogPage> pages = manager().getPages(TestUtils.getManagedWebsite(weblog));

        assertEquals(List.of("about", "services"),
                pages.stream().map(WeblogPage::getSlug).toList());
    }

    @Test
    void aRemovedPageIsGone() throws Exception {
        WeblogPage page = save(weblog, "about", WeblogPage.PubStatus.PUBLISHED);

        manager().removePage(manager().getPage(page.getId()));
        WebloggerFactory.getWeblogger().flush();
        TestUtils.endSession(true);

        assertNull(manager().getPageBySlug(TestUtils.getManagedWebsite(weblog), "about"));
    }

    /**
     * A slug that collides with a routing context would make the page
     * unreachable and shadow a real weblog view. Refused at save, which is the
     * only place it can be refused usefully.
     */
    @Test
    void aReservedSlugIsRefused() {
        assertThrows(WebloggerException.class,
                () -> save(weblog, "entry", WeblogPage.PubStatus.PUBLISHED));
        assertThrows(WebloggerException.class,
                () -> save(weblog, "tags", WeblogPage.PubStatus.PUBLISHED));
        assertThrows(WebloggerException.class,
                () -> save(weblog, "feed", WeblogPage.PubStatus.PUBLISHED));
    }

    @Test
    void aReservedSlugIsRefusedRegardlessOfCase() {
        assertThrows(WebloggerException.class,
                () -> save(weblog, "Entry", WeblogPage.PubStatus.PUBLISHED));
    }

    @Test
    void aBlankSlugIsRefused() {
        assertThrows(WebloggerException.class,
                () -> save(weblog, "   ", WeblogPage.PubStatus.PUBLISHED));
    }

    @Test
    void aSlugWithASlashIsRefused() {
        assertThrows(WebloggerException.class,
                () -> save(weblog, "about/us", WeblogPage.PubStatus.PUBLISHED));
    }

    @Test
    void removingAWeblogsPagesLeavesAnothersAlone() throws Exception {
        save(weblog, "about", WeblogPage.PubStatus.PUBLISHED);
        save(otherWeblog, "about", WeblogPage.PubStatus.PUBLISHED);

        manager().removePages(TestUtils.getManagedWebsite(weblog));
        WebloggerFactory.getWeblogger().flush();
        TestUtils.endSession(true);

        assertTrue(manager().getPages(TestUtils.getManagedWebsite(weblog)).isEmpty());
        assertEquals(1, manager().getPages(TestUtils.getManagedWebsite(otherWeblog)).size(),
                "the other weblog's pages must survive");
    }
}
