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
        String raw = mgr.issueToken(user, "seo-agent", "testblog", ApiToken.Role.POST, null);
        TestUtils.endSession(true);

        assertTrue(raw.startsWith("rlr_"), "tokens are prefixed so they are recognisable in logs");

        ApiToken found = mgr.authenticate(raw);
        assertNotNull(found);
        assertEquals("testblog", found.getScopeWeblog());
        assertEquals(ApiToken.Role.POST, found.getScopeRole());
    }

    /** A database read must never yield a working credential. */
    @Test
    void theRawTokenIsNeverStored() throws Exception {
        String raw = mgr.issueToken(user, "label", null, ApiToken.Role.READ, null);
        TestUtils.endSession(true);

        ApiToken stored = mgr.authenticate(raw);
        assertNotNull(stored);
        assertNotEquals(raw, stored.getTokenSha256());
        assertEquals(64, stored.getTokenSha256().length(), "SHA-256 hex is 64 chars");
    }

    @Test
    void anExpiredTokenDoesNotAuthenticate() throws Exception {
        Timestamp past = new Timestamp(System.currentTimeMillis() - 1000L);
        String raw = mgr.issueToken(user, "expired", null, ApiToken.Role.READ, past);
        TestUtils.endSession(true);

        assertNull(mgr.authenticate(raw));
    }

    @Test
    void aRevokedTokenDoesNotAuthenticate() throws Exception {
        String raw = mgr.issueToken(user, "doomed", null, ApiToken.Role.ADMIN, null);
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
        String raw = mgr.issueToken(user, "mine", null, ApiToken.Role.READ, null);
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
}
