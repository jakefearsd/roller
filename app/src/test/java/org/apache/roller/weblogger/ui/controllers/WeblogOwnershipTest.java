package org.apache.roller.weblogger.ui.controllers;

import org.apache.roller.weblogger.TestUtils;
import org.apache.roller.weblogger.business.Weblogger;
import org.apache.roller.weblogger.business.WebloggerFactory;
import org.apache.roller.weblogger.pojos.User;
import org.apache.roller.weblogger.pojos.Weblog;
import org.apache.roller.weblogger.pojos.WeblogEntry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Characterisation test: pins behaviour that already existed in
 * {@code BaseController}'s four {@code lookupX} methods before Task 7 moved
 * their bodies into {@link WeblogOwnership}. It is not a specification of new
 * behaviour -- the permission interceptor vouches for the ACTION weblog only,
 * so every by-id lookup is a global lookup, and without this check any editor
 * could read or rewrite any other weblog's rows by guessing an id.
 */
class WeblogOwnershipTest {

    private User user;
    private Weblog mine;
    private Weblog theirs;
    private WeblogEntry entry;
    private Weblogger weblogger;

    @BeforeEach
    void setUp() throws Exception {
        TestUtils.setupWeblogger();
        weblogger = WebloggerFactory.getWeblogger();
        user = TestUtils.setupUser("ownershiptestuser");
        mine = TestUtils.setupWeblog("ownershipmine", user);
        theirs = TestUtils.setupWeblog("ownershiptheirs", user);
        entry = TestUtils.setupWeblogEntry("ownership-entry", mine, user);
        TestUtils.endSession(true);
    }

    @AfterEach
    void tearDown() throws Exception {
        TestUtils.teardownWeblog(mine.getId());
        TestUtils.teardownWeblog(theirs.getId());
        TestUtils.teardownUser(user.getUserName());
        TestUtils.endSession(true);
    }

    @Test
    void anEntryOfTheActionWeblogIsFound() {
        assertNotNull(WeblogOwnership.entry(weblogger, entry.getId(), mine));
    }

    @Test
    void anEntryOfAnotherWeblogIsNotFound() {
        assertNull(WeblogOwnership.entry(weblogger, entry.getId(), theirs),
                "a foreign id must read as absent, not as someone else's entry");
    }

    @Test
    void aBlankIdIsAbsentRatherThanSomethingToLookUp() {
        assertNull(WeblogOwnership.entry(weblogger, null, mine));
        assertNull(WeblogOwnership.entry(weblogger, "", mine));
        assertNull(WeblogOwnership.entry(weblogger, "   ", mine));
    }

    @Test
    void anUnknownIdIsAbsent() {
        assertNull(WeblogOwnership.entry(weblogger, "no-such-id", mine));
    }
}
