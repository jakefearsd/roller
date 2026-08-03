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

import org.apache.roller.weblogger.TestUtils;
import org.apache.roller.weblogger.pojos.RuntimeConfigProperty;
import org.apache.roller.weblogger.pojos.User;
import org.apache.roller.weblogger.pojos.Weblog;
import org.apache.roller.weblogger.pojos.WeblogEntry;
import org.apache.roller.weblogger.pojos.WeblogEntry.PubStatus;
import org.apache.roller.weblogger.pojos.WeblogEntryRevision;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Entry revisions against the real database.
 *
 * <p>What has to hold: a revision records the content a save DISPLACED (not
 * the content it wrote), a save that changes nothing records nothing, the
 * retention property is honoured in both directions, and neither deleting an
 * entry nor deleting a whole weblog trips over the foreign key.
 */
public class WeblogEntryRevisionTest {

    private static final String RETENTION = "entry.revisions.retention";

    private User testUser;
    private Weblog testWeblog;

    @BeforeEach
    public void setUp() throws Exception {
        TestUtils.setupWeblogger();
        testUser = TestUtils.setupUser("revisionTestUser");
        testWeblog = TestUtils.setupWeblog("revisionTestWeblog", testUser);
        TestUtils.endSession(true);
    }

    @AfterEach
    public void tearDown() throws Exception {
        setRetention("-1");
        if (testWeblog != null) {
            TestUtils.teardownWeblog(testWeblog.getId());
        }
        TestUtils.teardownUser(testUser.getUserName());
        TestUtils.endSession(true);
    }

    @Test
    public void aSaveRecordsTheContentItReplaced() throws Exception {
        String id = saveEntry("First title", "First body").getId();
        TestUtils.endSession(true);

        WeblogEntry entry = entryManager().getWeblogEntry(id);
        entry.setTitle("Second title");
        entry.setText("Second body");
        entryManager().saveWeblogEntry(entry);
        TestUtils.endSession(true);

        List<WeblogEntryRevision> revisions = entryManager()
                .getRevisions(entryManager().getWeblogEntry(id));
        assertEquals(1, revisions.size());

        // The revision holds what was displaced, not what was written -- the
        // distinction that makes "restore" mean anything.
        assertEquals("First title", revisions.get(0).getTitle());
        assertEquals("First body", revisions.get(0).getText());
        assertNotNull(revisions.get(0).getCreated());
    }

    @Test
    public void threeContentChangingSavesLeaveTwoRevisions() throws Exception {
        String id = saveEntry("V1", "Body 1").getId();
        TestUtils.endSession(true);

        editText(id, "Body 2");
        editText(id, "Body 3");

        List<WeblogEntryRevision> revisions = entryManager()
                .getRevisions(entryManager().getWeblogEntry(id));
        assertEquals(2, revisions.size());
        // Newest first: the most recent save displaced "Body 2".
        assertEquals("Body 2", revisions.get(0).getText());
        assertEquals("Body 1", revisions.get(1).getText());
    }

    /**
     * The safeguard that matters most now that nothing is pruned by default:
     * re-saving to change only the publication date must not deposit a row
     * identical to the one before it.
     */
    @Test
    public void aSaveThatChangesNoContentRecordsNothing() throws Exception {
        String id = saveEntry("Unchanged", "Body").getId();
        TestUtils.endSession(true);

        WeblogEntry entry = entryManager().getWeblogEntry(id);
        entry.setPubTime(new Timestamp(System.currentTimeMillis() - 86_400_000L));
        entryManager().saveWeblogEntry(entry);
        TestUtils.endSession(true);

        assertTrue(entryManager().getRevisions(entryManager().getWeblogEntry(id)).isEmpty(),
                "changing only the publication date must not record a revision");
    }

    /** The shipped default: -1 keeps everything. */
    @Test
    public void theDefaultRetentionKeepsEveryRevision() throws Exception {
        setRetention("-1");
        String id = saveEntry("Kept", "Body 0").getId();
        TestUtils.endSession(true);

        for (int i = 1; i <= 25; i++) {
            editText(id, "Body " + i);
        }

        List<WeblogEntryRevision> revisions = entryManager()
                .getRevisions(entryManager().getWeblogEntry(id));
        assertEquals(25, revisions.size(),
                "the default must not prune; twenty-five edits leave twenty-five revisions");
    }

    @Test
    public void aPositiveRetentionKeepsTheNewestAndDropsTheOldest() throws Exception {
        setRetention("3");
        String id = saveEntry("Capped", "Body 0").getId();
        TestUtils.endSession(true);

        for (int i = 1; i <= 6; i++) {
            editText(id, "Body " + i);
        }

        List<WeblogEntryRevision> revisions = entryManager()
                .getRevisions(entryManager().getWeblogEntry(id));
        assertEquals(3, revisions.size());
        assertEquals("Body 5", revisions.get(0).getText());
        assertEquals("Body 4", revisions.get(1).getText());
        assertEquals("Body 3", revisions.get(2).getText());
    }

    @Test
    public void aRetentionOfZeroRecordsNothingAtAll() throws Exception {
        setRetention("0");
        String id = saveEntry("Unrecorded", "Body 0").getId();
        TestUtils.endSession(true);

        editText(id, "Body 1");
        editText(id, "Body 2");

        assertTrue(entryManager().getRevisions(entryManager().getWeblogEntry(id)).isEmpty());
    }

    /**
     * Revisions hold a foreign key to the entry, so deleting an entry that has
     * them must clear them first or the delete simply fails.
     */
    @Test
    public void deletingAnEntryTakesItsRevisionsWithIt() throws Exception {
        String id = saveEntry("Doomed", "Body 0").getId();
        TestUtils.endSession(true);
        editText(id, "Body 1");

        WeblogEntry entry = entryManager().getWeblogEntry(id);
        assertEquals(1, entryManager().getRevisions(entry).size());

        entryManager().removeWeblogEntry(entry);
        TestUtils.endSession(true);

        assertTrue(entryManager().getWeblogEntry(id) == null);
    }

    /** The same foreign key, reached the other way: through weblog deletion. */
    @Test
    public void deletingAWeblogWithRevisionsSucceeds() throws Exception {
        String id = saveEntry("Doomed with the blog", "Body 0").getId();
        TestUtils.endSession(true);
        editText(id, "Body 1");

        String weblogId = testWeblog.getId();
        TestUtils.teardownWeblog(weblogId);
        TestUtils.endSession(true);
        testWeblog = null;   // tearDown must not try again

        assertTrue(WebloggerFactory.getWeblogger().getWeblogManager()
                .getWeblog(weblogId) == null);
    }

    // ------------------------------------------------------------- fixtures

    private WeblogEntryManager entryManager() {
        return WebloggerFactory.getWeblogger().getWeblogEntryManager();
    }

    /** Loads the entry, changes its text, saves, and closes the session. */
    private void editText(String id, String newText) throws Exception {
        WeblogEntry entry = entryManager().getWeblogEntry(id);
        entry.setText(newText);
        entryManager().saveWeblogEntry(entry);
        TestUtils.endSession(true);
    }

    private void setRetention(String value) throws Exception {
        PropertiesManager pmgr = WebloggerFactory.getWeblogger().getPropertiesManager();
        RuntimeConfigProperty property = pmgr.getProperties().get(RETENTION);
        assertNotNull(property, RETENTION + " is not a declared runtime property");
        property.setValue(value);
        pmgr.saveProperties(pmgr.getProperties());
        TestUtils.endSession(true);
    }

    private WeblogEntry saveEntry(String title, String text) throws Exception {
        Weblog managedWeblog = TestUtils.getManagedWebsite(testWeblog);

        WeblogEntry entry = new WeblogEntry();
        entry.setTitle(title);
        entry.setText(text);
        entry.setAnchor("revision-" + System.nanoTime());
        entry.setWebsite(managedWeblog);
        entry.setCreatorUserName(TestUtils.getManagedUser(testUser).getUserName());
        entry.setCategory(managedWeblog.getWeblogCategory("General"));
        entry.setStatus(PubStatus.DRAFT);
        entry.setUpdateTime(new Timestamp(System.currentTimeMillis()));
        entryManager().saveWeblogEntry(entry);
        return entry;
    }
}
