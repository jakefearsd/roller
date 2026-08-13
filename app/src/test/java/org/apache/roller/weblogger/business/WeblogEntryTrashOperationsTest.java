/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  The ASF licenses this file to You
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
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import org.apache.roller.weblogger.TestUtils;
import org.apache.roller.weblogger.pojos.RollerEvent;
import org.apache.roller.weblogger.pojos.User;
import org.apache.roller.weblogger.pojos.Weblog;
import org.apache.roller.weblogger.pojos.WeblogEntry;
import org.apache.roller.weblogger.pojos.WeblogEntry.PubStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code trashWeblogEntry}/{@code restoreWeblogEntry}/{@code getTrashedEntries}/
 * {@code purgeTrash} against the real database.
 *
 * <p>Two things this class exists specifically to prove rather than assume,
 * because both are easy to get subtly wrong by reasoning alone: trashing (a
 * pure status change) must not deposit an entry revision, since revisions
 * exist to record CONTENT changes; and trashing a published entry must not
 * emit a second {@code roller_event} row, since {@code ENTRY_PUBLISHED} is
 * gated on the save's new status being PUBLISHED, which TRASHED never is.
 */
class WeblogEntryTrashOperationsTest {

    private User user;
    private Weblog blog;

    @BeforeEach
    void setUp() throws Exception {
        TestUtils.setupWeblogger();
        user = TestUtils.setupUser("trashOpsUser");
        blog = TestUtils.setupWeblog("trashopsblog", user);
        TestUtils.endSession(true);
    }

    @AfterEach
    void tearDown() throws Exception {
        TestUtils.teardownWeblog(blog.getId());
        TestUtils.teardownUser(user.getUserName());
        TestUtils.endSession(true);
    }

    // ----------------------------------------------------------------- trash

    @Test
    void trashingSetsStatusAndStampAndDoesNotHardDelete() throws Exception {
        WeblogEntry created = TestUtils.setupWeblogEntry("to-trash", blog, user, PubStatus.DRAFT);
        TestUtils.endSession(true);

        WeblogEntry managed = entries().getWeblogEntry(created.getId());
        entries().trashWeblogEntry(managed);
        WebloggerFactory.getWeblogger().flush();
        TestUtils.endSession(true);

        WeblogEntry reloaded = entries().getWeblogEntry(created.getId());
        assertNotNull(reloaded, "trashing must not hard-delete the row");
        assertEquals(PubStatus.TRASHED, reloaded.getStatus());
        assertNotNull(reloaded.getTrashedAt(), "the trash stamp must be set");
    }

    // --------------------------------------------------------------- restore

    @Test
    void restoringSetsDraftAndClearsTheStamp() throws Exception {
        WeblogEntry created = TestUtils.setupWeblogEntry("to-restore", blog, user, PubStatus.DRAFT);
        TestUtils.endSession(true);

        WeblogEntry managed = entries().getWeblogEntry(created.getId());
        entries().trashWeblogEntry(managed);
        WebloggerFactory.getWeblogger().flush();
        TestUtils.endSession(true);

        WeblogEntry trashed = entries().getWeblogEntry(created.getId());
        entries().restoreWeblogEntry(trashed);
        WebloggerFactory.getWeblogger().flush();
        TestUtils.endSession(true);

        WeblogEntry restored = entries().getWeblogEntry(created.getId());
        assertEquals(PubStatus.DRAFT, restored.getStatus());
        assertNull(restored.getTrashedAt(), "restoring must clear the trash stamp");
    }

    /**
     * The hazard named in the brief: nothing records what an entry's status
     * was before it was trashed, on purpose. Restoring a formerly-PUBLISHED
     * entry straight back to PUBLISHED would silently republish it to feeds,
     * the sitemap and every subscriber -- worse than making the author click
     * Publish again -- so restore always lands on DRAFT regardless of what
     * was trashed.
     */
    @Test
    void restoringAPublishedEntryStillYieldsDraftNotPublished() throws Exception {
        WeblogEntry created = TestUtils.setupWeblogEntry("published-then-trashed", blog, user);
        TestUtils.endSession(true);
        assertEquals(PubStatus.PUBLISHED, created.getStatus(), "fixture sanity check");

        WeblogEntry managed = entries().getWeblogEntry(created.getId());
        entries().trashWeblogEntry(managed);
        WebloggerFactory.getWeblogger().flush();
        TestUtils.endSession(true);

        WeblogEntry trashed = entries().getWeblogEntry(created.getId());
        entries().restoreWeblogEntry(trashed);
        WebloggerFactory.getWeblogger().flush();
        TestUtils.endSession(true);

        assertEquals(PubStatus.DRAFT, entries().getWeblogEntry(created.getId()).getStatus(),
                "restoring must never silently republish -- DRAFT only, never PUBLISHED");
    }

    // ------------------------------------------------------------- listing

    @Test
    void getTrashedEntriesListsNewestTrashedFirst() throws Exception {
        WeblogEntry first = TestUtils.setupWeblogEntry("trashed-first", blog, user, PubStatus.DRAFT);
        WeblogEntry second = TestUtils.setupWeblogEntry("trashed-second", blog, user, PubStatus.DRAFT);
        TestUtils.endSession(true);

        trashWithStamp(first, Instant.now().minus(2, ChronoUnit.DAYS));
        trashWithStamp(second, Instant.now().minus(1, ChronoUnit.DAYS));

        List<String> anchors = entries().getTrashedEntries(TestUtils.getManagedWebsite(blog))
                .stream().map(WeblogEntry::getAnchor).toList();

        assertEquals(List.of("trashed-second", "trashed-first"), anchors,
                "newest-trashed first: " + anchors);
    }

    // ---------------------------------------------------------------- purge

    @Test
    void purgeTrashHardDeletesPastRetentionAndKeepsWithinIt() throws Exception {
        WeblogEntry stale = TestUtils.setupWeblogEntry("stale-trash", blog, user, PubStatus.DRAFT);
        WeblogEntry fresh = TestUtils.setupWeblogEntry("fresh-trash", blog, user, PubStatus.DRAFT);
        TestUtils.endSession(true);

        trashWithStamp(stale, Instant.now().minus(10, ChronoUnit.DAYS));
        trashWithStamp(fresh, Instant.now().minus(1, ChronoUnit.DAYS));

        int purged = entries().purgeTrash(TestUtils.getManagedWebsite(blog), 5);
        WebloggerFactory.getWeblogger().flush();
        TestUtils.endSession(true);

        assertEquals(1, purged, "only the entry trashed past the 5-day retention should purge");
        assertNull(entries().getWeblogEntry(stale.getId()),
                "the stale trashed entry must be hard-deleted");
        assertNotNull(entries().getWeblogEntry(fresh.getId()),
                "the entry inside the retention window must survive");
        assertEquals(PubStatus.TRASHED, entries().getWeblogEntry(fresh.getId()).getStatus());
    }

    @Test
    void aRetentionOfMinusOnePurgesNothing() throws Exception {
        WeblogEntry ancient = TestUtils.setupWeblogEntry("ancient-trash", blog, user, PubStatus.DRAFT);
        TestUtils.endSession(true);

        trashWithStamp(ancient, Instant.now().minus(365, ChronoUnit.DAYS));

        int purged = entries().purgeTrash(TestUtils.getManagedWebsite(blog), -1);
        WebloggerFactory.getWeblogger().flush();
        TestUtils.endSession(true);

        assertEquals(0, purged, "-1 means keep trash forever");
        assertNotNull(entries().getWeblogEntry(ancient.getId()),
                "nothing may be purged when retention is -1");
    }

    // -------------------------------------------------------------- revisions

    /**
     * A status change is not a content change. {@code recordRevision} compares
     * only title/text/summary, so trashing -- which touches neither -- must
     * leave {@code weblogentry_revision} untouched. Verified against the table
     * through {@code getRevisions} rather than assumed from reading the save
     * path, per the brief.
     */
    @Test
    void trashingDoesNotCreateARevision() throws Exception {
        WeblogEntry created = TestUtils.setupWeblogEntry("no-revision-on-trash", blog, user);
        TestUtils.endSession(true);

        WeblogEntry managed = entries().getWeblogEntry(created.getId());
        entries().trashWeblogEntry(managed);
        WebloggerFactory.getWeblogger().flush();
        TestUtils.endSession(true);

        assertTrue(entries().getRevisions(entries().getWeblogEntry(created.getId())).isEmpty(),
                "trashing is a status change, not a content change -- no revision should be recorded");
    }

    // ------------------------------------------------------------------ events

    /**
     * {@code ENTRY_PUBLISHED} fires only when a save's NEW status is
     * PUBLISHED. Trashing a published entry moves it to TRASHED, so no event
     * fires; restoring it afterwards moves it to DRAFT, so still no event.
     * Verified against {@code roller_event} through the event manager, not
     * assumed from the gate's code.
     */
    @Test
    void trashingAPublishedEntryEmitsNoRollerEvent() throws Exception {
        WeblogEntry created = TestUtils.setupWeblogEntry("published-no-double-event", blog, user);
        TestUtils.endSession(true);

        List<RollerEvent> afterPublish = events();
        assertEquals(1, afterPublish.size(),
                "the initial publish itself records exactly one event: " + afterPublish);

        WeblogEntry managed = entries().getWeblogEntry(created.getId());
        entries().trashWeblogEntry(managed);
        WebloggerFactory.getWeblogger().flush();
        TestUtils.endSession(true);

        assertEquals(1, events().size(),
                "trashing a published entry must not itself count as a publish event");

        WeblogEntry trashed = entries().getWeblogEntry(created.getId());
        entries().restoreWeblogEntry(trashed);
        WebloggerFactory.getWeblogger().flush();
        TestUtils.endSession(true);

        assertEquals(1, events().size(),
                "restoring to DRAFT must not record a publish event either");
    }

    // ---------------------------------------------------------------- helpers

    private static WeblogEntryManager entries() {
        return WebloggerFactory.getWeblogger().getWeblogEntryManager();
    }

    private List<RollerEvent> events() throws Exception {
        return WebloggerFactory.getWeblogger().getEventManager()
                .getEvents(TestUtils.getManagedWebsite(blog), 10);
    }

    /**
     * Trashes the entry with a caller-chosen stamp rather than "now", so
     * ordering and retention-window tests can be deterministic instead of
     * racing the clock.
     */
    private void trashWithStamp(WeblogEntry entry, Instant trashedAt) throws Exception {
        WeblogEntry managed = entries().getWeblogEntry(entry.getId());
        managed.setStatus(PubStatus.TRASHED);
        managed.setTrashedAt(Timestamp.from(trashedAt));
        entries().saveWeblogEntry(managed);
        WebloggerFactory.getWeblogger().flush();
        TestUtils.endSession(true);
    }
}
