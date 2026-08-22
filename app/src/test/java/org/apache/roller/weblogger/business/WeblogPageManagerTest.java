package org.apache.roller.weblogger.business;

import java.util.Date;
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
        return TestUtils.weblogger().getWeblogPageManager();
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
        TestUtils.weblogger().flush();
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
        TestUtils.weblogger().flush();
        TestUtils.endSession(true);

        List<WeblogPage> pages = manager().getPages(TestUtils.getManagedWebsite(weblog));

        assertEquals(List.of("about", "services"),
                pages.stream().map(WeblogPage::getSlug).toList());
    }

    @Test
    void aRemovedPageIsGone() throws Exception {
        WeblogPage page = save(weblog, "about", WeblogPage.PubStatus.PUBLISHED);

        manager().removePage(manager().getPage(page.getId()));
        TestUtils.weblogger().flush();
        TestUtils.endSession(true);

        assertNull(manager().getPageBySlug(TestUtils.getManagedWebsite(weblog), "about"));
    }

    /**
     * WeblogPageCache has no CacheHandler -- a rendered page expires lazily
     * by comparing itself against weblog.lastModified (the same mechanism
     * saveTemplate and saveComment rely on). If savePage stopped bumping it,
     * publishing or editing a page would never reach a reader holding a
     * cached copy of the weblog.
     */
    @Test
    void savingAPageBumpsTheWeblogsLastModified() throws Exception {
        Date before = freshLastModifiedBaseline();
        // Guarantee a distinguishable millisecond boundary even on a very
        // fast clock/database so the "must have moved" assertion below
        // can't pass by coincidence (see MediaFileTest for the same pattern).
        Thread.sleep(5);

        save(weblog, "about", WeblogPage.PubStatus.PUBLISHED);

        Date after = TestUtils.getManagedWebsite(weblog).getLastModified();
        assertTrue(after.after(before),
                "savePage must bump weblog.lastModified or a cached copy of the weblog's "
                        + "pages never expires when a page is published or edited");
    }

    /**
     * The same requirement as above, for the other write path: a reader must
     * not keep seeing a page that was just deleted.
     */
    @Test
    void removingAPageBumpsTheWeblogsLastModified() throws Exception {
        WeblogPage page = save(weblog, "about", WeblogPage.PubStatus.PUBLISHED);
        Date before = freshLastModifiedBaseline();
        Thread.sleep(5);

        manager().removePage(manager().getPage(page.getId()));
        TestUtils.weblogger().flush();
        TestUtils.endSession(true);

        Date after = TestUtils.getManagedWebsite(weblog).getLastModified();
        assertTrue(after.after(before),
                "removePage must bump weblog.lastModified or a cached copy of the removed "
                        + "page keeps being served");
    }

    /**
     * A known, non-null starting point for the before/after comparisons
     * above. saveWeblog always stamps lastModified with the current time
     * (it does not honour a value set on the pojo beforehand), so this is a
     * real save, not a fabricated past date; it exists so "before" is never
     * null -- addWeblog does not set lastModified, and Date.after(null)
     * throws.
     */
    private Date freshLastModifiedBaseline() throws Exception {
        Weblog managed = TestUtils.getManagedWebsite(weblog);
        TestUtils.weblogger().getWeblogManager().saveWeblog(managed);
        TestUtils.weblogger().flush();
        TestUtils.endSession(true);
        return TestUtils.getManagedWebsite(weblog).getLastModified();
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
        TestUtils.weblogger().flush();
        TestUtils.endSession(true);

        assertTrue(manager().getPages(TestUtils.getManagedWebsite(weblog)).isEmpty());
        assertEquals(1, manager().getPages(TestUtils.getManagedWebsite(otherWeblog)).size(),
                "the other weblog's pages must survive");
    }
}
