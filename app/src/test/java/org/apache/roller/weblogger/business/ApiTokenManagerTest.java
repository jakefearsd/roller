package org.apache.roller.weblogger.business;

import java.sql.Timestamp;
import org.apache.roller.weblogger.TestUtils;
import org.apache.roller.weblogger.pojos.ApiToken;
import org.apache.roller.weblogger.pojos.User;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ApiTokenManagerTest {

    private User user;
    private ApiTokenManager mgr;

    @BeforeEach
    void setUp() throws Exception {
        TestUtils.setupWeblogger();
        user = TestUtils.setupUser("apitokentestuser");
        TestUtils.endSession(true);
        mgr = WebloggerFactory.getWeblogger().getApiTokenManager();
    }

    @AfterEach
    void tearDown() throws Exception {
        TestUtils.teardownUser(user.getUserName());
        TestUtils.endSession(true);
    }

    @Test
    void anIssuedTokenAuthenticatesAndCarriesItsScope() throws Exception {
        ApiTokenManager.Issued issued = mgr.issueToken(user, "seo-agent", "testblog", ApiToken.Role.POST, null);
        TestUtils.endSession(true);

        String raw = issued.rawToken();
        assertTrue(raw.startsWith("rlr_"), "tokens are prefixed so they are recognisable in logs");
        assertEquals("testblog", issued.token().getScopeWeblog(),
                "the returned row is the same one that was persisted");

        ApiToken found = mgr.authenticate(raw);
        assertNotNull(found);
        assertEquals("testblog", found.getScopeWeblog());
        assertEquals(ApiToken.Role.POST, found.getScopeRole());
    }

    /** A database read must never yield a working credential. */
    @Test
    void theRawTokenIsNeverStored() throws Exception {
        String raw = mgr.issueToken(user, "label", null, ApiToken.Role.READ, null).rawToken();
        TestUtils.endSession(true);

        ApiToken stored = mgr.authenticate(raw);
        assertNotNull(stored);
        assertNotEquals(raw, stored.getTokenSha256());
        assertEquals(64, stored.getTokenSha256().length(), "SHA-256 hex is 64 chars");
    }

    @Test
    void anExpiredTokenDoesNotAuthenticate() throws Exception {
        Timestamp past = new Timestamp(System.currentTimeMillis() - 1000L);
        String raw = mgr.issueToken(user, "expired", null, ApiToken.Role.READ, past).rawToken();
        TestUtils.endSession(true);

        assertNull(mgr.authenticate(raw));
    }

    @Test
    void aRevokedTokenDoesNotAuthenticate() throws Exception {
        String raw = mgr.issueToken(user, "doomed", null, ApiToken.Role.ADMIN, null).rawToken();
        TestUtils.endSession(true);

        ApiToken issued = mgr.authenticate(raw);
        assertNotNull(issued);
        assertTrue(mgr.revoke(user, issued.getId()));
        TestUtils.endSession(true);

        assertNull(mgr.authenticate(raw));
    }

    /** Revocation is scoped to the owner: one user must not revoke another's. */
    @Test
    void revokeRefusesATokenOwnedBySomeoneElse() throws Exception {
        User other = TestUtils.setupUser("apitokenotheruser");
        TestUtils.endSession(true);
        String raw = mgr.issueToken(user, "mine", null, ApiToken.Role.READ, null).rawToken();
        TestUtils.endSession(true);
        ApiToken mine = mgr.authenticate(raw);
        assertNotNull(mine);

        assertFalse(mgr.revoke(other, mine.getId()));
        TestUtils.endSession(true);
        assertNotNull(mgr.authenticate(raw), "the token must still work");

        TestUtils.teardownUser(other.getUserName());
        TestUtils.endSession(true);
    }

    @Test
    void anUnknownTokenDoesNotAuthenticate() throws Exception {
        assertNull(mgr.authenticate("rlr_notarealtoken"));
        assertNull(mgr.authenticate(null));
        assertNull(mgr.authenticate("   "));
    }

    /**
     * lastUsedAt is read by an operator deciding whether a token is still in
     * daily use before revoking it -- it must be durable the moment
     * authenticate() stamps it, not depend on whatever request happened to
     * carry it also calling flush(). A read-only endpoint (GET /api/v1/me)
     * never flushes anything of its own, so before this test, a token used
     * only for reads kept lastUsedAt null forever: the write happened in a
     * transaction nobody ever committed, and {@code PersistenceSessionFilter}
     * / {@code TestUtils.endSession(false)}'s end-of-request release() rolls
     * back whatever is still open.
     *
     * <p>Simulates exactly that: authenticate() (which stamps lastUsedAt),
     * then release() with NO flush -- then re-reads the token in a FRESH
     * session (release() clears the thread-local EntityManager, so the next
     * call opens a new one) to prove the stamp is durable in the database,
     * not just visible in the persistence context that wrote it.
     */
    @Test
    void authenticatingATokenMakesLastUsedAtDurableEvenWithoutTheCallerFlushing() throws Exception {
        String raw = mgr.issueToken(user, "watched", null, ApiToken.Role.READ, null).rawToken();
        TestUtils.endSession(true);

        ApiToken found = mgr.authenticate(raw);
        assertNotNull(found);
        assertNotNull(found.getLastUsedAt(), "authenticate() should have stamped lastUsedAt");

        // No flush: this is what a read-only request actually does.
        TestUtils.endSession(false);

        ApiToken reloaded = mgr.getTokens(user).stream()
                .filter(t -> t.getId().equals(found.getId()))
                .findFirst().orElseThrow();
        assertNotNull(reloaded.getLastUsedAt(),
                "lastUsedAt must survive a release() with no flush, or an operator reading it "
                        + "would see null forever for a token used only for reads");
    }

    /**
     * A missing or blank id is client input, same as an unknown one -- it
     * must fail closed (false), not throw. EntityManager.find (behind
     * strategy.load) throws IllegalArgumentException on a null primary key,
     * which revoke must not let escape.
     */
    @Test
    void revokeRefusesAMissingOrBlankTokenId() throws Exception {
        assertFalse(mgr.revoke(user, null));
        assertFalse(mgr.revoke(user, "   "));
    }
}
