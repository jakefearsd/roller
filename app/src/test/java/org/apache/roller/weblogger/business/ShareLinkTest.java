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
package org.apache.roller.weblogger.business;

import java.sql.Timestamp;
import java.util.List;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.roller.weblogger.TestUtils;
import org.apache.roller.weblogger.WebloggerException;
import org.apache.roller.weblogger.pojos.ShareLink;
import org.apache.roller.weblogger.pojos.User;
import org.apache.roller.weblogger.pojos.Weblog;
import org.apache.roller.weblogger.util.TokenGenerator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ShareLinkManager round trips against the real database.
 */
public class ShareLinkTest {

    public static Log log = LogFactory.getLog(ShareLinkTest.class);

    User testUser = null;
    Weblog testWeblog = null;

    @BeforeEach
    public void setUp() throws Exception {
        TestUtils.setupWeblogger();
        try {
            testUser = TestUtils.setupUser("shareLinkTestUser");
            testWeblog = TestUtils.setupWeblog("shareLinkTestWeblog", testUser);
            TestUtils.endSession(true);
        } catch (Exception ex) {
            log.error(ex);
            throw new Exception("Test setup failed", ex);
        }
    }

    @AfterEach
    public void tearDown() throws Exception {
        try {
            TestUtils.teardownWeblog(testWeblog.getId());
            TestUtils.teardownUser(testUser.getUserName());
            TestUtils.endSession(true);
        } catch (Exception ex) {
            log.error(ex);
            throw new Exception("Test teardown failed", ex);
        }
    }

    private ShareLink buildLink(String targetType, String targetId) throws Exception {
        ShareLink link = new ShareLink();
        link.setWeblog(TestUtils.getManagedWebsite(testWeblog));
        link.setTargetType(targetType);
        link.setTargetId(targetId);
        link.setToken(TokenGenerator.newToken());
        return link;
    }

    @Test
    public void testShareLinkCRUD() throws Exception {

        ShareLinkManager mgr = WebloggerFactory.getWeblogger().getShareLinkManager();

        ShareLink link = buildLink(ShareLink.TYPE_ENTRY, "entry-id-1");
        link.setPasswordHash("{bcrypt}$2a$10$notreallyahashbutstoredasis");
        Timestamp expires = new Timestamp(System.currentTimeMillis() + 86_400_000L);
        link.setExpires(expires);
        String token = link.getToken();

        // create
        mgr.createShareLink(link);
        TestUtils.endSession(true);

        // read back by token; the manager stores the hash verbatim --
        // hashing happens in the caller, never in this tier
        ShareLink fetched = mgr.getShareLinkByToken(token);
        assertNotNull(fetched);
        assertEquals(link.getId(), fetched.getId());
        assertEquals(ShareLink.TYPE_ENTRY, fetched.getTargetType());
        assertEquals("entry-id-1", fetched.getTargetId());
        assertEquals("{bcrypt}$2a$10$notreallyahashbutstoredasis", fetched.getPasswordHash());
        assertEquals(expires, fetched.getExpires());
        assertNotNull(fetched.getCreated());
        assertEquals(testWeblog.getId(), fetched.getWeblog().getId());

        // update
        fetched.setPasswordHash(null);
        mgr.createShareLink(fetched);
        TestUtils.endSession(true);

        fetched = mgr.getShareLinkByToken(token);
        assertNotNull(fetched);
        assertNull(fetched.getPasswordHash(), "Clearing the password must stick");

        // delete
        mgr.removeShareLink(fetched);
        TestUtils.endSession(true);

        assertNull(mgr.getShareLinkByToken(token));
    }

    @Test
    public void testLookupByWeblogAndTarget() throws Exception {

        ShareLinkManager mgr = WebloggerFactory.getWeblogger().getShareLinkManager();

        ShareLink entryLink = buildLink(ShareLink.TYPE_ENTRY, "entry-id-2");
        ShareLink dirLink = buildLink(ShareLink.TYPE_DIRECTORY, "dir-id-2");
        mgr.createShareLink(entryLink);
        mgr.createShareLink(dirLink);
        TestUtils.endSession(true);

        List<ShareLink> forWeblog =
                mgr.getShareLinksForWeblog(TestUtils.getManagedWebsite(testWeblog));
        assertEquals(2, forWeblog.size());

        ShareLink byTarget = mgr.getShareLinkForTarget(ShareLink.TYPE_DIRECTORY, "dir-id-2");
        assertNotNull(byTarget);
        assertEquals(dirLink.getToken(), byTarget.getToken());

        // same id under the other type must not match
        assertNull(mgr.getShareLinkForTarget(ShareLink.TYPE_ENTRY, "dir-id-2"));
        assertNull(mgr.getShareLinkForTarget(ShareLink.TYPE_ENTRY, "never-shared"));
        assertNull(mgr.getShareLinkByToken("no-such-token"));

        mgr.removeShareLink(mgr.getShareLinkByToken(entryLink.getToken()));
        mgr.removeShareLink(mgr.getShareLinkByToken(dirLink.getToken()));
        TestUtils.endSession(true);

        assertTrue(mgr.getShareLinksForWeblog(
                TestUtils.getManagedWebsite(testWeblog)).isEmpty());
    }

    @Test
    public void testCreateRejectsIncompleteLinks() throws Exception {

        ShareLinkManager mgr = WebloggerFactory.getWeblogger().getShareLinkManager();

        ShareLink badType = buildLink("weblog", "some-id");
        assertThrows(WebloggerException.class, () -> mgr.createShareLink(badType),
                "Only 'entry' and 'directory' targets exist");

        ShareLink noTarget = buildLink(ShareLink.TYPE_ENTRY, null);
        assertThrows(WebloggerException.class, () -> mgr.createShareLink(noTarget));

        ShareLink noToken = buildLink(ShareLink.TYPE_ENTRY, "some-id");
        noToken.setToken(null);
        assertThrows(WebloggerException.class, () -> mgr.createShareLink(noToken));

        ShareLink noWeblog = new ShareLink();
        noWeblog.setTargetType(ShareLink.TYPE_ENTRY);
        noWeblog.setTargetId("some-id");
        noWeblog.setToken(TokenGenerator.newToken());
        assertThrows(WebloggerException.class, () -> mgr.createShareLink(noWeblog));

        TestUtils.endSession(false);
    }

    @Test
    public void testRemovingAWeblogRemovesItsShareLinks() throws Exception {

        ShareLinkManager mgr = WebloggerFactory.getWeblogger().getShareLinkManager();

        // a separate weblog, so tearDown() is not asked to remove it twice
        Weblog doomed = TestUtils.setupWeblog("shareLinkDoomedWeblog", testUser);
        TestUtils.endSession(true);

        ShareLink link = new ShareLink();
        link.setWeblog(TestUtils.getManagedWebsite(doomed));
        link.setTargetType(ShareLink.TYPE_DIRECTORY);
        link.setTargetId("dir-id-3");
        link.setToken(TokenGenerator.newToken());
        mgr.createShareLink(link);
        TestUtils.endSession(true);

        // removing the weblog must not trip over the share link's FK
        TestUtils.teardownWeblog(doomed.getId());
        TestUtils.endSession(true);

        assertNull(mgr.getShareLinkByToken(link.getToken()),
                "Share links must die with their weblog");
    }

    /**
     * Two links for one target, created back to back, must resolve to the same
     * one on every read.
     *
     * <p>{@code getShareLinkForTarget} returns the first row of a query ordered
     * by creation time, so a tie leaves the database to pick -- and the loser
     * is a token that still works while the editor shows the other one, which
     * means an author who revokes "the" share link has not revoked access at
     * all. Creating both inside the same millisecond is exactly what a test (or
     * a double-click) does, so this is the case worth pinning.
     */
    @Test
    public void theNewestLinkForATargetIsChosenDeterministically() throws Exception {
        ShareLinkManager mgr = WebloggerFactory.getWeblogger().getShareLinkManager();

        ShareLink older = buildLink(ShareLink.TYPE_ENTRY, "contested-target");
        mgr.createShareLink(older);
        ShareLink newer = buildLink(ShareLink.TYPE_ENTRY, "contested-target");
        mgr.createShareLink(newer);
        TestUtils.endSession(true);

        String firstAnswer = mgr.getShareLinkForTarget(
                ShareLink.TYPE_ENTRY, "contested-target").getToken();
        TestUtils.endSession(true);

        // Asked again in a new session, so the answer comes from the database
        // rather than from an entity still in the persistence context.
        String secondAnswer = mgr.getShareLinkForTarget(
                ShareLink.TYPE_ENTRY, "contested-target").getToken();
        assertEquals(firstAnswer, secondAnswer,
                "the same target resolved to two different share links");

        // And it is genuinely the newer one, not merely a stable choice.
        assertEquals(newer.getToken(), firstAnswer,
                "the most recently created link must win");

        mgr.removeShareLink(mgr.getShareLinkByToken(older.getToken()));
        mgr.removeShareLink(mgr.getShareLinkByToken(newer.getToken()));
        TestUtils.endSession(true);
    }
}
