package org.apache.roller.weblogger.business;

import org.apache.roller.weblogger.TestUtils;
import org.apache.roller.weblogger.pojos.User;
import org.apache.roller.weblogger.pojos.Weblog;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The hostname-to-weblog lookup virtual hosting resolves every request through. */
class WeblogCustomDomainTest {

    private User user;
    private Weblog weblog;

    @BeforeEach
    void setUp() throws Exception {
        TestUtils.setupWeblogger();
        user = TestUtils.setupUser("vhostuser");
        weblog = TestUtils.setupWeblog("vhostblog", user);
        TestUtils.endSession(true);
    }

    @AfterEach
    void tearDown() throws Exception {
        TestUtils.teardownWeblog(weblog.getId());
        TestUtils.teardownUser(user.getUserName());
        TestUtils.endSession(true);
    }

    @Test
    void aWeblogIsFoundByItsCustomDomain() throws Exception {
        WeblogManager mgr = WebloggerFactory.getWeblogger().getWeblogManager();
        Weblog stored = mgr.getWeblogByHandle("vhostblog");
        stored.setCustomDomain("vhost.example.com");
        mgr.saveWeblog(stored);
        TestUtils.endSession(true);

        Weblog found = mgr.getWeblogByCustomDomain("vhost.example.com");
        assertEquals("vhostblog", found.getHandle());
    }

    @Test
    void anUnclaimedHostFindsNothing() throws Exception {
        WeblogManager mgr = WebloggerFactory.getWeblogger().getWeblogManager();
        assertNull(mgr.getWeblogByCustomDomain("nobody.example.com"));
    }

    /**
     * A null host must not become a query that matches the many weblogs whose
     * custom_domain is NULL -- that would make every unclaimed hostname resolve
     * to an arbitrary weblog.
     */
    @Test
    void aNullHostFindsNothing() throws Exception {
        WeblogManager mgr = WebloggerFactory.getWeblogger().getWeblogManager();
        assertNull(mgr.getWeblogByCustomDomain(null));
    }

    /**
     * {@code VirtualHostRegistry.map()} builds its hostname-to-handle cache by
     * calling {@code getWeblogs(null, null, null, null, 0, -1)} -- every
     * filter left unset, because it wants every weblog regardless of enabled/
     * active status. With all four filters null the WHERE clause has no
     * conditions in it, and {@code JPAWeblogManagerImpl.getWeblogs} used to
     * build the query as {@code "SELECT w FROM Weblog w WHERE " + whereClause}
     * with " ORDER BY ..." appended to whereClause UNCONDITIONALLY -- so an
     * empty whereClause produced the invalid JPQL
     * "SELECT w FROM Weblog w WHERE  ORDER BY w.dateCreated DESC", which
     * EclipseLink rejects at query-creation time with an
     * IllegalArgumentException. VirtualHostRegistry.map() catches that
     * exception and (deliberately, to tolerate pre-bootstrap calls) returns an
     * empty map WITHOUT caching it -- so every virtual-host lookup silently
     * failed, forever, both in tests and in production.
     */
    @Test
    void getWeblogsWithNoFiltersDoesNotThrow() throws Exception {
        WeblogManager mgr = WebloggerFactory.getWeblogger().getWeblogManager();
        assertTrue(mgr.getWeblogs(null, null, null, null, 0, -1).stream()
                .anyMatch(w -> "vhostblog".equals(w.getHandle())));
    }
}
