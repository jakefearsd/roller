package org.apache.roller.weblogger.business;

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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The analytics feedback loop needs to know WHEN a post went live, and only
 * traffic-invisible first-party code can know that. One event per entry, on
 * the transition into PUBLISHED -- not on every later edit-and-save of an
 * already-published entry.
 */
class EntryPublishedEventTest {

    private User user;
    private Weblog weblog;

    @BeforeEach
    void setUp() throws Exception {
        TestUtils.setupWeblogger();
        user = TestUtils.setupUser("pubeventuser");
        weblog = TestUtils.setupWeblog("pubeventblog", user);
        TestUtils.endSession(true);
    }

    @AfterEach
    void tearDown() throws Exception {
        TestUtils.teardownWeblog(weblog.getId());
        TestUtils.teardownUser(user.getUserName());
        TestUtils.endSession(true);
    }

    private List<RollerEvent> events() throws Exception {
        return WebloggerFactory.getWeblogger().getEventManager()
                .getEvents(TestUtils.getManagedWebsite(weblog), 10);
    }

    @Test
    void publishingAnEntryRecordsOneEvent() throws Exception {
        WeblogEntry entry = TestUtils.setupWeblogEntry("pub-event-post",
                TestUtils.getManagedWebsite(weblog), user);
        TestUtils.endSession(true);

        List<RollerEvent> events = events();
        assertEquals(1, events.size(), "got: " + events);
        assertEquals(RollerEvent.EventType.ENTRY_PUBLISHED, events.get(0).getEventType());
        assertEquals(entry.getAnchor(), events.get(0).getEntryAnchor());

        TestUtils.teardownWeblogEntry(entry.getId());
        TestUtils.endSession(true);
    }

    @Test
    void reSavingAPublishedEntryDoesNotRecordASecondEvent() throws Exception {
        WeblogEntry entry = TestUtils.setupWeblogEntry("pub-event-resave",
                TestUtils.getManagedWebsite(weblog), user);
        TestUtils.endSession(true);

        WeblogEntryManager mgr = WebloggerFactory.getWeblogger().getWeblogEntryManager();
        WeblogEntry managed = mgr.getWeblogEntry(entry.getId());
        managed.setTitle("edited title");
        mgr.saveWeblogEntry(managed);
        WebloggerFactory.getWeblogger().flush();
        TestUtils.endSession(true);

        assertEquals(1, events().size(),
                "editing an already-published entry is not a second publication");

        TestUtils.teardownWeblogEntry(entry.getId());
        TestUtils.endSession(true);
    }

    @Test
    void savingADraftRecordsNothing() throws Exception {
        WeblogEntry entry = TestUtils.setupWeblogEntry("pub-event-draft",
                TestUtils.getManagedWebsite(weblog), user, PubStatus.DRAFT);
        TestUtils.endSession(true);

        assertTrue(events().isEmpty(), "a draft has not been published");

        TestUtils.teardownWeblogEntry(entry.getId());
        TestUtils.endSession(true);
    }
}
