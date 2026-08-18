package org.apache.roller.weblogger.business;

import org.apache.roller.weblogger.TestUtils;
import org.apache.roller.weblogger.pojos.User;
import org.apache.roller.weblogger.pojos.Weblog;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * {@code VirtualHostRegistry} against a live database. Unlike
 * {@code WeblogRequestMapperTest}, which calls {@code invalidate()} by hand
 * after every domain change, these tests never do -- the point is to prove
 * that {@code JPAWeblogManagerImpl.saveWeblog}/{@code removeWeblog}
 * themselves keep the registry in sync. A test that calls
 * {@code invalidate()} manually proves nothing about whether those call
 * sites exist or are correctly placed.
 */
class VirtualHostRegistryDbTest {

    private User user;
    private Weblog weblog;

    @BeforeEach
    void setUp() throws Exception {
        TestUtils.setupWeblogger();
        user = TestUtils.setupUser("vhostdbuser");
        weblog = TestUtils.setupWeblog("vhostdbblog", user);
        TestUtils.endSession(true);
    }

    @AfterEach
    void tearDown() throws Exception {
        TestUtils.teardownWeblog(weblog.getId());
        TestUtils.teardownUser(user.getUserName());
        TestUtils.endSession(true);
        // VirtualHostRegistry is a JVM-wide static cache -- a custom domain
        // set by one test must not leak into the next.
        VirtualHostRegistry.invalidate();
    }

    /**
     * This is the test that would have failed against the JPQL bug fixed in
     * 8d019a23c: {@code map()}'s {@code getWeblogs(null, null, null, null, 0,
     * -1)} call threw, was swallowed, and every custom domain silently never
     * resolved. No manual {@code invalidate()} between the save and the
     * lookup -- {@code saveWeblog} must trigger the rebuild itself.
     */
    @Test
    void savingACustomDomainMakesItResolveWithNoManualInvalidate() throws Exception {
        WeblogManager mgr = WebloggerFactory.getWeblogger().getWeblogManager();
        Weblog stored = mgr.getWeblogByHandle("vhostdbblog");
        stored.setCustomDomain("dbtest.example.com");
        mgr.saveWeblog(stored);
        TestUtils.endSession(true);

        assertEquals("vhostdbblog", VirtualHostRegistry.handleFor("dbtest.example.com"));
    }

    @Test
    void hostForAgreesWithHandleFor() throws Exception {
        WeblogManager mgr = WebloggerFactory.getWeblogger().getWeblogManager();
        Weblog stored = mgr.getWeblogByHandle("vhostdbblog");
        stored.setCustomDomain("dbtest2.example.com");
        mgr.saveWeblog(stored);
        TestUtils.endSession(true);

        assertEquals("dbtest2.example.com", VirtualHostRegistry.hostFor("vhostdbblog"));
    }

    /**
     * Proves {@code saveWeblog}'s {@code invalidate()} call, not just
     * {@code invalidate()} itself: the first lookup forces the map to cache
     * under the OLD domain, then the domain changes and the OLD host must
     * stop resolving while the NEW one starts -- with no manual
     * {@code invalidate()} between the second save and the second lookup. A
     * misplaced or removed {@code invalidate()} call in {@code saveWeblog}
     * leaves the OLD mapping cached and the NEW one invisible.
     */
    @Test
    void changingTheDomainMovesTheMappingWithoutManualInvalidate() throws Exception {
        WeblogManager mgr = WebloggerFactory.getWeblogger().getWeblogManager();

        Weblog stored = mgr.getWeblogByHandle("vhostdbblog");
        stored.setCustomDomain("old.example.com");
        mgr.saveWeblog(stored);
        TestUtils.endSession(true);
        // Force the map to build and cache under the OLD domain.
        assertEquals("vhostdbblog", VirtualHostRegistry.handleFor("old.example.com"));

        stored = mgr.getWeblogByHandle("vhostdbblog");
        stored.setCustomDomain("new.example.com");
        mgr.saveWeblog(stored);
        TestUtils.endSession(true);

        assertNull(VirtualHostRegistry.handleFor("old.example.com"));
        assertEquals("vhostdbblog", VirtualHostRegistry.handleFor("new.example.com"));
    }

    /**
     * Covers the {@code removeWeblog} call site: a removed weblog's domain
     * must stop resolving. Uses its own weblog/user, torn down inside the
     * test, rather than the shared fixture -- the class-level
     * {@code tearDown} already removes the shared weblog and must not be
     * asked to remove an already-removed row.
     */
    @Test
    void removingAWeblogStopsItsDomainFromResolving() throws Exception {
        User removalUser = TestUtils.setupUser("vhostdbremoveuser");
        Weblog removalWeblog = TestUtils.setupWeblog("vhostdbremoveblog", removalUser);
        TestUtils.endSession(true);

        WeblogManager mgr = WebloggerFactory.getWeblogger().getWeblogManager();
        Weblog stored = mgr.getWeblogByHandle("vhostdbremoveblog");
        stored.setCustomDomain("removeme.example.com");
        mgr.saveWeblog(stored);
        TestUtils.endSession(true);
        // Force the map to build and cache the mapping before removal.
        assertEquals("vhostdbremoveblog", VirtualHostRegistry.handleFor("removeme.example.com"));

        mgr.removeWeblog(mgr.getWeblog(removalWeblog.getId()));
        TestUtils.endSession(true);

        assertNull(VirtualHostRegistry.handleFor("removeme.example.com"));

        TestUtils.teardownUser(removalUser.getUserName());
        TestUtils.endSession(true);
    }
}
