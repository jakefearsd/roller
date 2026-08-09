# Wave B — Audience Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Contact forms and a submissions inbox, newsletter wiring (subscribe endpoint owned by Roller, "Send as newsletter"), and tokened account access (forgot password, admin set-password links) — the audience ring around the publishing engine.

**Architecture:** Three pillars share one migration pair. `roller_event` lands first because everything else writes to it. The contact form and the subscribe form are both *injected client-side* by a theme assets macro — the sanitizer strips `<form>` from authored content, so shortcodes emit placeholder `<div>`s exactly as `[map]` and `[video]` do, and the JS builds the form; no sanitizer weakening, no CSP change (every theme already sends `connect-src 'self'` and both endpoints are same-origin). Account access reuses the ShareLink architecture (tokened public controller + `GenericThrottle`) but stores only a SHA-256 digest of the token, because a reset token grants account access and a database read must never yield working links.

**Tech Stack:** Java 25, Spring Boot 4.1, Spring MVC, EclipseLink JPA, PostgreSQL 16, Velocity (public rendering), JSP/JSTL (admin UI), `java.net.http.HttpClient` (Listmonk forwarding), JUnit 5 + Mockito + Testcontainers, Selenide (browser ITs).

**Spec:** `docs/superpowers/specs/2026-08-08-pages-audience-analytics-design.md` (Wave B section + Cross-cutting)

**Wave base commit:** `37c2c9171` (Wave A's final commit). Diff coverage in Task 14 runs against this ref.

## Deviations from the spec, called out for review

1. **"Send as newsletter" runs synchronously in the POST action, not "via the background-task framework with retry."** The background framework (`ThreadManagerImpl`/`TaskScheduler`) is cron-style recurring only; `ThreadManager.executeInBackground` is fire-and-forget with no retry, and building a durable job queue for one manual button is out of proportion. A synchronous call surfaces failure to the human who just clicked — who *is* the retry mechanism. `newsletter_sent_at` is stamped only on success, and the button is hidden once stamped, so the cannot-double-send property survives.
2. **The subscribe endpoint forwards only list UUIDs that match some weblog's `newsletter_list_uuid`.** Forwarding arbitrary UUIDs would make Roller an open relay to any Listmonk list. The spec doesn't say this; it's an added restriction.
3. **`roller_event.metadata` is created as `jsonb` per the spec but left unmapped in the JPA entity.** Nothing in Wave B writes metadata, and mapping a Java `String` onto `jsonb` through EclipseLink needs a cast layer nothing yet needs. The column exists for Wave C's views; the entity maps it when a writer appears.

## Global Constraints

Every task inherits these. Copied from the spec.

- **`weblogAdminsUntrusted` stays `true`.** No role-keyed sanitizer bypass, and no raw HTML from a weblog admin reaches `<head>` or entry output.
- **No theme-CSP changes in this wave.** Wave A's `frame-src` addition was the single authorised widening. Contact and subscribe work same-origin precisely so the pinned CSP strings (`MapAssetsRenderingTest`, `PortfolioThemeRenderingTest`, `TravelThemeRenderingTest`) never change. No CAPTCHA, ever — that is *why* the defences are honeypot/timing/throttle/caps.
- **No server-side fetch of an author-supplied URL.** The only outbound HTTP in this wave targets the Listmonk base URL, which is deployer configuration (`roller.properties`), never author or reader input.
- **No GPL-licensed dependencies.** (This wave adds none at all: JSON via the Jackson already on the Boot classpath, HTTP via `java.net.http`.)
- **Every schema change adds a numbered idempotent migration** under `bin/db/migrations/`; never edit an applied one. This wave adds `V015` and `V016`.
- **Controllers name every `@RequestParam`/`@PathVariable` explicitly** — the build does not pass `-parameters`; `ControllerMetadataTest.everyRequestBoundParameterIsNamedExplicitly` scans the whole `ui.controllers` tree.
- **Ownership-check every id** through the `BaseController.lookup*` family. Submissions rows named by client ids are verified against the action weblog before deletion.
- **Roller stores no subscriber data.** The subscribe endpoint forwards; it never persists an email address. `roller_event` rows for subscribes carry no address.
- **Persist, then notify.** A contact inquiry is committed before the notification email is attempted; SMTP failure must never lose a lead.
- **Reset tokens are stored hashed** (SHA-256 digest is the lookup key). The raw token exists only in the emailed link. Identical confirmation whether or not an address exists — the form must not enumerate accounts.
- **Every new `@GetMapping` goes into `it-selenium/.../support/Routes.java`** — `RouteCoverageTest` fails the build otherwise. Every new message key must be referenced or `MessageKeyTest`'s unused-key ratchet fails.
- **Coverage gates:** ~90% diff coverage on changed lines (`bin/check-diff-coverage.sh 37c2c9171`); JaCoCo floors only rise; a browser IT for every new public surface and every new admin screen.
- **Tests clean up after themselves:** fixtures via `TestUtils.setupX(...)`, removed in `@AfterEach` (`teardownWeblog`/`teardownUser` + `endSession(true)`); rendering-path tests call `CacheManager.clear()`/`RenderingTestSupport.clearRenderCaches()` in `@BeforeEach`.
- **Commit on `master`.** Solo-developer repo; no feature branch.

## File Structure

**Events & submissions (Tasks 1-3)**

| File | Responsibility |
| --- | --- |
| `bin/db/migrations/V015__form_submissions_and_tokens.sql` *(new)* | `roller_event`, `roller_form_submission`, `roller_user_token` |
| `pojos/RollerEvent.java` + `RollerEvent.orm.xml` *(new)* | First-party outcome rows |
| `business/EventManager.java` + `business/jpa/JPAEventManagerImpl.java` *(new)* | record / query / cleanup |
| `pojos/FormSubmission.java` + `FormSubmission.orm.xml` *(new)* | A stored inquiry |
| `business/FormSubmissionManager.java` + `business/jpa/JPAFormSubmissionManagerImpl.java` *(new)* | save / list / delete / cleanup |
| `business/jpa/JPAWeblogEntryManagerImpl.java` | Records `entry_published` on the DRAFT→PUBLISHED transition |
| `business/jpa/JPAWeblogManagerImpl.java` | `removeWeblogContents` cleans events + submissions |

**Contact (Tasks 4-6)**

| File | Responsibility |
| --- | --- |
| `business/shortcodes/ContactShortcode.java` *(new)* | `[contact]` → placeholder `<div class="contact-form-slot">` |
| `util/HTMLSanitizer.java` | Grants `data-weblog`, `data-list-uuid` on `div` (same single `.onElements("div")` call) |
| `WEB-INF/velocity/weblog.vm` | `#showAudienceAssets` — injects contact + subscribe forms client-side |
| `ui/controllers/core/ContactController.java` *(new)* | Public POST `/roller-ui/rendering/contact.rol` |
| `util/MailUtil.java` | Reply-To capable overload |
| `boot/SecurityConfig.java` | Narrow CSRF exemptions for the two public POSTs |
| `ui/controllers/editor/FormSubmissionsController.java` + `Submissions.jsp` *(new)* | Per-weblog inbox |

**Newsletter (Tasks 7-10)**

| File | Responsibility |
| --- | --- |
| `bin/db/migrations/V016__newsletter_wiring.sql` *(new)* | `weblog.newsletter_list_uuid`, `weblogentry.newsletter_sent_at` |
| `pojos/Weblog.java` / `Weblog.orm.xml` / `wrapper/WeblogWrapper.java` | `newsletterListUuid` |
| `pojos/WeblogEntry.java` / `WeblogEntry.orm.xml` | `newsletterSentAt` |
| `ui/controllers/editor/WeblogConfigBean.java` + `WeblogConfig.jsp` | Settings field, UUID-validated |
| `business/ListmonkClient.java` *(new)* | Subscribe forward + campaign create/send; injectable HTTP |
| `ui/controllers/core/NewsletterController.java` *(new)* | `POST /newsletter/subscribe` |
| `deploy/caddy/Caddyfile` | **Delete** the `/newsletter/subscribe` handle block |
| `boot/ServletRegistrationConfig.java` | `/newsletter/*` dispatcher mapping |
| `WEB-INF/velocity/weblog.vm` | `#showSubscribeForm` reworked; themes call it |
| `business/shortcodes/SubscribeShortcode.java` *(new)* | `[subscribe]` placeholder |
| `ui/controllers/editor/EntryEditController.java` + `EntryEdit.jsp` | "Send as newsletter" |

**Account access (Tasks 11-12)**

| File | Responsibility |
| --- | --- |
| `pojos/UserToken.java` + `UserToken.orm.xml` *(new)* | Hashed single-use token |
| `business/UserTokenManager.java` + `business/jpa/JPAUserTokenManagerImpl.java` *(new)* | issue / redeem / cleanup |
| `util/TokenGenerator.java` | adds `sha256Hex` |
| `ui/controllers/core/PasswordResetController.java` *(new)* + `ForgotPassword.jsp`, `ResetPassword.jsp` | Public flows |
| `ui/controllers/admin/UserEditController.java` + `UserEdit.jsp` | "Send set-password link" |
| `WEB-INF/jsps/core/Login.jsp` | "Forgot password?" link |

---

# Task 1: `V015` migration, `RollerEvent`, `EventManager`

`roller_event` comes first because Tasks 2, 5 and 8 all write to it. The other two V015 tables ride along in the same migration (one migration per wave-pillar-pair, per the spec's cross-cutting table) but get their entities in their own tasks.

**Files:**
- Create: `bin/db/migrations/V015__form_submissions_and_tokens.sql`
- Create: `app/src/main/java/org/apache/roller/weblogger/pojos/RollerEvent.java`
- Create: `app/src/main/resources/org/apache/roller/weblogger/pojos/RollerEvent.orm.xml`
- Create: `app/src/main/java/org/apache/roller/weblogger/business/EventManager.java`
- Create: `app/src/main/java/org/apache/roller/weblogger/business/jpa/JPAEventManagerImpl.java`
- Modify: `app/src/main/resources/META-INF/persistence.xml` (add mapping file)
- Modify: `app/src/main/java/org/apache/roller/weblogger/business/Weblogger.java`, `WebloggerImpl.java`, `business/jpa/WebloggerBeanConfig.java` (facade wiring, `shareLinkManager` pattern)
- Modify: `app/src/main/java/org/apache/roller/weblogger/business/jpa/JPAWeblogManagerImpl.java` (`removeWeblogContents` cleanup — the FK has no cascade; Wave A's Task 6 learned this the hard way)
- Test: `app/src/test/java/org/apache/roller/weblogger/business/EventManagerTest.java`

**Interfaces:**
- Produces: `RollerEvent` with `getId/setId`, `getWeblog/setWeblog` (many-to-one), `getEventType/setEventType` (enum `EventType { FORM_SUBMITTED, NEWSLETTER_SUBSCRIBED, ENTRY_PUBLISHED }`), `getEntryAnchor/setEntryAnchor`, `getPageSlug/setPageSlug`, `getOccurredAt/setOccurredAt`.
- Produces: `EventManager` with `void record(RollerEvent event) throws WebloggerException`, `List<RollerEvent> getEvents(Weblog weblog, int max) throws WebloggerException` (newest first), `void removeEvents(Weblog weblog) throws WebloggerException`. `Weblogger.getEventManager()`.
- Callers must treat `record` as best-effort bookkeeping: wrap in try/catch and log — an event insert must never fail the business operation that produced it. (The manager itself stays simple and throws; the *call sites* decide that policy, and every call site in this plan shows it.)

- [ ] **Step 1: Write the migration**

Create `bin/db/migrations/V015__form_submissions_and_tokens.sql` with the ASF header copied from `V013`, then:

```sql
-- Migration: audience wiring -- inquiries, account tokens, first-party events
--
-- Three tables for Wave B (Audience):
--
-- 1. roller_event: outcomes the analytics tier cannot see from traffic alone
--    (form submitted, newsletter subscribed, entry published). Wave B writes
--    these rows; Wave C only adds SQL views over them for Grafana. metadata
--    is jsonb per the analytics contract but is deliberately not mapped in
--    JPA yet -- nothing writes it, and the cast layer can wait for a writer.
--
-- 2. roller_form_submission: contact-form inquiries, persisted BEFORE any
--    notification email is attempted. If SMTP is down the lead survives,
--    which for a business running on leads is the failure that matters.
--
-- 3. roller_user_token: single-use, expiring account tokens serving both
--    forgot-password and the admin "send set-password link". Only a SHA-256
--    digest of the token is stored: a database read must not yield working
--    reset links. The raw token exists only in the emailed URL.
--
-- Prerequisites: V002__baseline_schema.

CREATE TABLE IF NOT EXISTS roller_event (
    id           varchar(48)  NOT NULL PRIMARY KEY,
    weblogid     varchar(48)  NOT NULL CONSTRAINT rev_weblog_fk REFERENCES weblog(id),
    event_type   varchar(32)  NOT NULL,
    entry_anchor varchar(255),
    page_slug    varchar(255),
    occurred_at  timestamp(3) with time zone NOT NULL,
    metadata     jsonb
);

-- Wave C's views group by weblog, type and day.
CREATE INDEX IF NOT EXISTS rev_weblog_type_idx
    ON roller_event(weblogid, event_type, occurred_at);

CREATE TABLE IF NOT EXISTS roller_form_submission (
    id           varchar(48)  NOT NULL PRIMARY KEY,
    weblogid     varchar(48)  NOT NULL CONSTRAINT rfs_weblog_fk REFERENCES weblog(id),
    name         varchar(255) NOT NULL,
    email        varchar(255) NOT NULL,
    subject      varchar(255),
    message      text         NOT NULL,
    page_slug    varchar(255),
    entry_anchor varchar(255),
    client_ip    varchar(64),
    created      timestamp(3) with time zone NOT NULL
);

-- The inbox lists one weblog's submissions newest-first.
CREATE INDEX IF NOT EXISTS rfs_weblog_created_idx
    ON roller_form_submission(weblogid, created);

CREATE TABLE IF NOT EXISTS roller_user_token (
    id           varchar(48)  NOT NULL PRIMARY KEY,
    userid       varchar(48)  NOT NULL CONSTRAINT rut_user_fk REFERENCES roller_user(id),
    token_sha256 varchar(64)  NOT NULL CONSTRAINT rut_token_uq UNIQUE,
    purpose      varchar(16)  NOT NULL,
    created      timestamp(3) with time zone NOT NULL,
    expires      timestamp(3) with time zone NOT NULL,
    used_at      timestamp(3) with time zone
);

CREATE INDEX IF NOT EXISTS rut_user_idx ON roller_user_token(userid);
```

- [ ] **Step 2: Verify the migration applies and is idempotent**

Run: `mvn -pl app test -Dtest=SchemaMigrationTest`
Expected: PASS.

- [ ] **Step 3: Write the failing manager test**

Create `app/src/test/java/org/apache/roller/weblogger/business/EventManagerTest.java`:

```java
package org.apache.roller.weblogger.business;

import java.sql.Timestamp;
import java.util.List;

import org.apache.roller.weblogger.TestUtils;
import org.apache.roller.weblogger.pojos.RollerEvent;
import org.apache.roller.weblogger.pojos.User;
import org.apache.roller.weblogger.pojos.Weblog;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EventManagerTest {

    private User user;
    private Weblog weblog;
    private Weblog otherWeblog;

    @BeforeEach
    void setUp() throws Exception {
        TestUtils.setupWeblogger();
        user = TestUtils.setupUser("eventuser");
        weblog = TestUtils.setupWeblog("eventblog", user);
        otherWeblog = TestUtils.setupWeblog("othereventblog", user);
        TestUtils.endSession(true);
    }

    @AfterEach
    void tearDown() throws Exception {
        TestUtils.teardownWeblog(weblog.getId());
        TestUtils.teardownWeblog(otherWeblog.getId());
        TestUtils.teardownUser(user.getUserName());
        TestUtils.endSession(true);
    }

    private static EventManager manager() {
        return WebloggerFactory.getWeblogger().getEventManager();
    }

    private RollerEvent record(Weblog target, RollerEvent.EventType type, String anchor)
            throws Exception {
        RollerEvent event = new RollerEvent();
        event.setWeblog(TestUtils.getManagedWebsite(target));
        event.setEventType(type);
        event.setEntryAnchor(anchor);
        event.setOccurredAt(new Timestamp(System.currentTimeMillis()));
        manager().record(event);
        WebloggerFactory.getWeblogger().flush();
        TestUtils.endSession(true);
        return event;
    }

    @Test
    void aRecordedEventComesBackForItsWeblog() throws Exception {
        record(weblog, RollerEvent.EventType.FORM_SUBMITTED, null);

        List<RollerEvent> events = manager().getEvents(TestUtils.getManagedWebsite(weblog), 10);

        assertEquals(1, events.size());
        assertEquals(RollerEvent.EventType.FORM_SUBMITTED, events.get(0).getEventType());
        assertNotNull(events.get(0).getOccurredAt());
    }

    @Test
    void eventsAreScopedToTheirWeblog() throws Exception {
        record(weblog, RollerEvent.EventType.ENTRY_PUBLISHED, "some-post");

        assertTrue(manager().getEvents(TestUtils.getManagedWebsite(otherWeblog), 10).isEmpty(),
                "another weblog's events must not answer this weblog's query");
    }

    @Test
    void newestEventsComeFirstAndMaxIsHonoured() throws Exception {
        record(weblog, RollerEvent.EventType.ENTRY_PUBLISHED, "first");
        Thread.sleep(5);
        record(weblog, RollerEvent.EventType.ENTRY_PUBLISHED, "second");

        List<RollerEvent> events = manager().getEvents(TestUtils.getManagedWebsite(weblog), 1);

        assertEquals(1, events.size(), "max must cap the result");
        assertEquals("second", events.get(0).getEntryAnchor(), "newest first");
    }

    @Test
    void removingAWeblogsEventsLeavesAnothersAlone() throws Exception {
        record(weblog, RollerEvent.EventType.FORM_SUBMITTED, null);
        record(otherWeblog, RollerEvent.EventType.FORM_SUBMITTED, null);

        manager().removeEvents(TestUtils.getManagedWebsite(weblog));
        WebloggerFactory.getWeblogger().flush();
        TestUtils.endSession(true);

        assertTrue(manager().getEvents(TestUtils.getManagedWebsite(weblog), 10).isEmpty());
        assertEquals(1, manager().getEvents(TestUtils.getManagedWebsite(otherWeblog), 10).size());
    }
}
```

- [ ] **Step 4: Run it and watch it fail**

Run: `mvn -pl app test -Dtest=EventManagerTest`
Expected: FAIL — `getEventManager` does not exist.

- [ ] **Step 5: Write the entity and mapping**

Create `RollerEvent.java` with the ASF header: fields `id` (default `UUIDGenerator.generateUUID()`), `weblog`, `eventType` (nested `public enum EventType { FORM_SUBMITTED, NEWSLETTER_SUBSCRIBED, ENTRY_PUBLISHED }`), `entryAnchor`, `pageSlug`, `occurredAt` (`java.sql.Timestamp`); standard getters/setters; `equals`/`hashCode` on `id` and a `toString` naming weblog handle and type, matching `WeblogPage`'s style. **No `metadata` field** (deviation 3 above).

Create `RollerEvent.orm.xml` modelled exactly on `WeblogPage.orm.xml` (metadata-complete, PROPERTY access, table `roller_event`), attributes: `id`; `eventType` → column `event_type`, `<enumerated>STRING</enumerated>`, nullable false; `entryAnchor` → `entry_anchor` nullable; `pageSlug` → `page_slug` nullable; `occurredAt` → `occurred_at` nullable false; many-to-one `weblog` → join-column `weblogid` nullable false. Named queries:

```xml
<named-query name="RollerEvent.getByWeblog">
    <query>SELECT e FROM RollerEvent e WHERE e.weblog = ?1 ORDER BY e.occurredAt DESC</query>
</named-query>
<named-query name="RollerEvent.removeByWeblog">
    <query>DELETE FROM RollerEvent e WHERE e.weblog = ?1</query>
</named-query>
```

**No XML comment containing `--` anywhere in the file** — it kills the whole persistence unit with an error that does not name the file.

Register in `persistence.xml` after the `WeblogPage.orm.xml` line:

```xml
    <mapping-file>org/apache/roller/weblogger/pojos/RollerEvent.orm.xml</mapping-file>
```

- [ ] **Step 6: Write the manager and wire the facade**

`EventManager` interface (ASF header, javadoc noting the call-site best-effort policy from **Interfaces** above). `JPAEventManagerImpl` modelled on `JPAWeblogPageManagerImpl`: constructor takes `JPAPersistenceStrategy`; `record` stores via `strategy.store(event)` after defaulting `occurredAt` to now when null; `getEvents` uses the named query with `.setMaxResults(max)`; `removeEvents` uses the delete query.

Wire exactly as `weblogPageManager` is wired: `Weblogger.getEventManager()`, `WebloggerImpl` field + constructor param + getter, `JPAWebloggerImpl` constructor pass-through, `WebloggerBeanConfig` bean + `weblogger(...)` param.

In `JPAWeblogManagerImpl.removeWeblogContents`, immediately after the `WeblogPage.removeByWeblog` cleanup, add the same pattern for events (submissions follow in Task 3):

```java
        // roller_event rows FK the weblog with no cascade
        strategy.getNamedUpdate("RollerEvent.removeByWeblog")
                .setParameter(1, weblog).executeUpdate();
```

- [ ] **Step 7: Run the tests**

Run: `mvn -pl app test -Dtest='EventManagerTest,SchemaMigrationTest,WeblogEntryManagerQueryTest'`
Expected: PASS. (The third class proves the persistence unit still parses.)

- [ ] **Step 8: Check the ratchets**

Run: `mvn -pl app test -Dtest='EqualsContractTest,SmallWrapperDelegationTest,*ManagerTest'`
Expected: PASS after adding a `RollerEvent` specimen to `EqualsContractTest` (id-keyed, mirroring the `WeblogPage` specimen — Wave A's regression lesson: this ratchet catches every new hand-written `equals`).

- [ ] **Step 9: Commit**

```bash
git add bin/db/migrations/V015__form_submissions_and_tokens.sql \
        app/src/main/java/org/apache/roller/weblogger/pojos/RollerEvent.java \
        app/src/main/resources/org/apache/roller/weblogger/pojos/RollerEvent.orm.xml \
        app/src/main/resources/META-INF/persistence.xml \
        app/src/main/java/org/apache/roller/weblogger/business/ \
        app/src/test/java/org/apache/roller/weblogger/business/EventManagerTest.java \
        app/src/test/java/org/apache/roller/weblogger/pojos/
git commit -m "Add V015 (events, submissions, tokens) and the EventManager"
```

---

# Task 2: Record `entry_published` events

**Files:**
- Modify: `app/src/main/java/org/apache/roller/weblogger/business/jpa/JPAWeblogEntryManagerImpl.java` (`saveWeblogEntry`)
- Test: `app/src/test/java/org/apache/roller/weblogger/business/EntryPublishedEventTest.java`

**Interfaces:**
- Consumes: `EventManager.record` (Task 1).
- Produces: a `RollerEvent` row (`ENTRY_PUBLISHED`, `entryAnchor` set) exactly once per entry's first arrival at PUBLISHED.

- [ ] **Step 1: Write the failing test**

Create `EntryPublishedEventTest.java`:

```java
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
```

If `TestUtils.setupWeblogEntry` has no status-taking overload, add one delegating to the existing method then setting the status before save — follow the existing overload pattern in `TestUtils`.

- [ ] **Step 2: Run it and watch it fail**

Run: `mvn -pl app test -Dtest=EntryPublishedEventTest`
Expected: FAIL — zero events recorded.

- [ ] **Step 3: Detect the transition in `saveWeblogEntry`**

`WeblogEntry` already snapshots its loaded state for revisions (`snapshotLoadedContent`, a JPA `post-load` callback). Follow that precedent: the entity knows what its status was when loaded. Add alongside the existing snapshot field in `WeblogEntry.java`:

```java
    /** Status as loaded from the database; null for a new entry. Set by the
     *  same post-load callback that snapshots content for revisions. */
    private transient PubStatus loadedStatus = null;

    public PubStatus getLoadedStatus() {
        return loadedStatus;
    }
```

and set `loadedStatus = getStatus();` inside the existing `snapshotLoadedContent()` post-load method.

In `JPAWeblogEntryManagerImpl.saveWeblogEntry`, after the entry is stored (beside where revisions are handled), add:

```java
        // First arrival at PUBLISHED is a first-party outcome the analytics
        // tier joins against traffic. Best-effort: an event insert must never
        // fail the save that produced it.
        if (entry.getStatus() == PubStatus.PUBLISHED
                && entry.getLoadedStatus() != PubStatus.PUBLISHED) {
            try {
                RollerEvent event = new RollerEvent();
                event.setWeblog(entry.getWebsite());
                event.setEventType(RollerEvent.EventType.ENTRY_PUBLISHED);
                event.setEntryAnchor(entry.getAnchor());
                event.setOccurredAt(new Timestamp(System.currentTimeMillis()));
                roller.getEventManager().record(event);
            } catch (Exception ex) {
                log.warn("Could not record entry_published event for "
                        + entry.getAnchor(), ex);
            }
        }
```

(`roller` is the `Weblogger` reference this impl already holds; if it holds only the strategy, thread a `@Lazy Weblogger` through the bean config exactly as `JPAWeblogPageManagerImpl` does.) This single seam covers the editor's publish actions AND `ScheduledEntriesTask` promotion, because both go through `saveWeblogEntry`.

- [ ] **Step 4: Run the tests**

Run: `mvn -pl app test -Dtest='EntryPublishedEventTest,WeblogEntryTest,*RevisionTest,WeblogEntryManagerQueryTest'`
Expected: PASS with no changes to existing assertions.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/org/apache/roller/weblogger/pojos/WeblogEntry.java \
        app/src/main/java/org/apache/roller/weblogger/business/jpa/ \
        app/src/test/java/org/apache/roller/weblogger/business/EntryPublishedEventTest.java \
        app/src/test/java/org/apache/roller/weblogger/TestUtils.java
git commit -m "Record an entry_published event on the transition into PUBLISHED"
```

---

# Task 3: `FormSubmission` entity and manager

**Files:**
- Create: `app/src/main/java/org/apache/roller/weblogger/pojos/FormSubmission.java`
- Create: `app/src/main/resources/org/apache/roller/weblogger/pojos/FormSubmission.orm.xml`
- Create: `app/src/main/java/org/apache/roller/weblogger/business/FormSubmissionManager.java`
- Create: `app/src/main/java/org/apache/roller/weblogger/business/jpa/JPAFormSubmissionManagerImpl.java`
- Modify: `persistence.xml`, `Weblogger.java`, `WebloggerImpl.java`, `JPAWebloggerImpl.java`, `WebloggerBeanConfig.java` (same wiring as Task 1)
- Modify: `JPAWeblogManagerImpl.removeWeblogContents` (cleanup, beside the event cleanup)
- Test: `app/src/test/java/org/apache/roller/weblogger/business/FormSubmissionManagerTest.java`

**Interfaces:**
- Produces: `FormSubmission` with `getId/setId`, `getWeblog/setWeblog`, `getName/setName`, `getEmail/setEmail`, `getSubject/setSubject`, `getMessage/setMessage`, `getPageSlug/setPageSlug`, `getEntryAnchor/setEntryAnchor`, `getClientIp/setClientIp`, `getCreated/setCreated`.
- Produces: `FormSubmissionManager` with `void save(FormSubmission s) throws WebloggerException` (stamps `created` when null), `FormSubmission get(String id) throws WebloggerException`, `List<FormSubmission> getSubmissions(Weblog weblog, int offset, int max) throws WebloggerException` (newest first), `int getCount(Weblog weblog) throws WebloggerException`, `void remove(FormSubmission s) throws WebloggerException`, `void removeSubmissions(Weblog weblog) throws WebloggerException`. `Weblogger.getFormSubmissionManager()`.
- Field length constants on the interface, shared with the controller's caps: `int MAX_NAME = 255, MAX_EMAIL = 255, MAX_SUBJECT = 255, MAX_MESSAGE = 4000`.

- [ ] **Step 1: Write the failing test**

Model `FormSubmissionManagerTest` on `EventManagerTest` (same fixture shape, two weblogs, teardown). Cover: a saved submission comes back with all fields and a stamped `created`; listing is scoped to its weblog; newest first with offset/max honoured; `getCount` matches; `remove` deletes only the named row; `removeSubmissions` leaves the other weblog's rows; saving with a message longer than `MAX_MESSAGE` throws `WebloggerException` (the manager is the last line of the length caps — the controller checks first, but a cap that only lives in the controller is not a cap).

The over-length test:

```java
    @Test
    void anOverlongMessageIsRefusedAtTheManagerToo() {
        FormSubmission s = new FormSubmission();
        s.setWeblog(weblog);
        s.setName("n");
        s.setEmail("e@example.com");
        s.setMessage("x".repeat(FormSubmissionManager.MAX_MESSAGE + 1));
        assertThrows(WebloggerException.class,
                () -> manager().save(s),
                "length caps must hold even if a future caller forgets them");
    }
```

- [ ] **Step 2: Run it and watch it fail**

Run: `mvn -pl app test -Dtest=FormSubmissionManagerTest`
Expected: FAIL — `getFormSubmissionManager` does not exist.

- [ ] **Step 3: Entity, mapping, manager, wiring**

`FormSubmission.java`: same conventions as `RollerEvent` (UUID id default, equals/hashCode on id). `FormSubmission.orm.xml` modelled on `RollerEvent.orm.xml`, table `roller_form_submission`, named queries:

```xml
<named-query name="FormSubmission.getByWeblog">
    <query>SELECT s FROM FormSubmission s WHERE s.weblog = ?1 ORDER BY s.created DESC</query>
</named-query>
<named-query name="FormSubmission.countByWeblog">
    <query>SELECT COUNT(s) FROM FormSubmission s WHERE s.weblog = ?1</query>
</named-query>
<named-query name="FormSubmission.removeByWeblog">
    <query>DELETE FROM FormSubmission s WHERE s.weblog = ?1</query>
</named-query>
```

`JPAFormSubmissionManagerImpl.save` validates before storing:

```java
    @Override
    public void save(FormSubmission submission) throws WebloggerException {
        if (submission.getWeblog() == null) {
            throw new WebloggerException("submission requires a weblog");
        }
        requireLength("name", submission.getName(), MAX_NAME, true);
        requireLength("email", submission.getEmail(), MAX_EMAIL, true);
        requireLength("subject", submission.getSubject(), MAX_SUBJECT, false);
        requireLength("message", submission.getMessage(), MAX_MESSAGE, true);
        if (submission.getCreated() == null) {
            submission.setCreated(new Timestamp(System.currentTimeMillis()));
        }
        strategy.store(submission);
    }

    private static void requireLength(String field, String value, int max,
            boolean required) throws WebloggerException {
        if (value == null || value.isBlank()) {
            if (required) {
                throw new WebloggerException(field + " is required");
            }
            return;
        }
        if (value.length() > max) {
            throw new WebloggerException(field + " exceeds " + max + " characters");
        }
    }
```

Facade wiring identical to Task 1's. `removeWeblogContents` gets `FormSubmission.removeByWeblog` beside the event cleanup.

- [ ] **Step 4: Run the tests**

Run: `mvn -pl app test -Dtest='FormSubmissionManagerTest,EventManagerTest,SchemaMigrationTest,EqualsContractTest'`
Expected: PASS (add the `FormSubmission` specimen to `EqualsContractTest`).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/org/apache/roller/weblogger/pojos/FormSubmission.java \
        app/src/main/resources/org/apache/roller/weblogger/pojos/FormSubmission.orm.xml \
        app/src/main/resources/META-INF/persistence.xml \
        app/src/main/java/org/apache/roller/weblogger/business/ \
        app/src/test/java/org/apache/roller/weblogger/business/FormSubmissionManagerTest.java \
        app/src/test/java/org/apache/roller/weblogger/pojos/
git commit -m "Add FormSubmissionManager with length caps enforced at the manager"
```

---

# Task 4: `[contact]` and `[subscribe]` shortcodes, the sanitizer grant, `#showAudienceAssets`

Both forms are injected client-side: the sanitizer strips `<form>` from authored content, and granting form elements would hand any editor a phishing kit. Placeholder `<div>` + assets-macro injection is the `[map]`/`[video]` pattern, third use.

**Files:**
- Create: `app/src/main/java/org/apache/roller/weblogger/business/shortcodes/ContactShortcode.java`
- Create: `app/src/main/java/org/apache/roller/weblogger/business/shortcodes/SubscribeShortcode.java`
- Modify: `app/src/main/java/org/apache/roller/weblogger/business/shortcodes/ShortcodeExpander.java` (register both in `DEFAULT`)
- Modify: `app/src/main/java/org/apache/roller/weblogger/util/HTMLSanitizer.java` (extend the one `div` grant)
- Modify: `app/src/main/webapp/WEB-INF/velocity/weblog.vm` (`#showAudienceAssets`)
- Modify: `app/src/main/webapp/themes/{basic,fauxcoly,gaurav,portfolio,travel}` (call `#showAudienceAssets()` beside `#showEmbedAssets()` — same include points Task 4 of Wave A used; fauxcoly/gaurav via their shared `std_head.vm`)
- Modify: `app/src/main/webapp/WEB-INF/velocity/templates/weblog/page.vm` (add `#showAudienceAssets()` beside `#showEmbedAssets()`)
- Modify: `app/src/main/resources/ApplicationResources.properties` (`shortcode.contact.label=Contact form`, `shortcode.subscribe.label=Subscribe form`)
- Test: `app/src/test/java/org/apache/roller/weblogger/business/shortcodes/ContactShortcodeTest.java`, `SubscribeShortcodeTest.java`, `app/src/test/java/org/apache/roller/weblogger/util/AudienceSanitizationTest.java`

**Interfaces:**
- Consumes: `ShortcodeHandler`/`ShortcodeCard`/`ShortcodeContext` (Wave A), `Weblog.getNewsletterListUuid()` (Task 7 — until then `SubscribeShortcode` reads it reflectively-free: see Step 3; the field lands in Task 7 and this task's subscribe emission is null-safe).
- Produces: `[contact]` → `<div class="contact-form-slot" data-weblog="<handle>"></div>`; `[subscribe]` → `<div class="subscribe-form-slot" data-list-uuid="<uuid>"></div>` (or `null` — author text stays visible — when the weblog has no list uuid); `#showAudienceAssets` injecting both forms.

- [ ] **Step 1: Write the failing tests**

`ContactShortcodeTest`:

```java
package org.apache.roller.weblogger.business.shortcodes;

import java.util.Map;

import org.apache.roller.weblogger.pojos.Weblog;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * [contact] emits a placeholder div, never a form: the sanitizer strips
 * form elements from authored content by design, and #showAudienceAssets
 * builds the real form client-side. Same pattern as [map] and [video].
 */
class ContactShortcodeTest {

    private final ContactShortcode shortcode = new ContactShortcode();

    private static ShortcodeContext context(String handle) {
        Weblog weblog = handle == null ? null : new Weblog();
        if (weblog != null) {
            weblog.setHandle(handle);
        }
        Weblog finalWeblog = weblog;
        return new ShortcodeContext() {
            @Override public Weblog getWeblog() { return finalWeblog; }
            @Override public String getSlug() { return "contact"; }
            @Override public String getRawText() { return "[contact]"; }
        };
    }

    @Test
    void emitsAPlaceholderCarryingTheWeblogHandle() {
        String html = shortcode.render(Map.of(), null, context("travelblog"));

        assertTrue(html.contains("class=\"contact-form-slot\""), html);
        assertTrue(html.contains("data-weblog=\"travelblog\""), html);
        assertFalse(html.contains("<form"), "the macro injects the form, not the shortcode");
        assertFalse(html.contains("<input"), html);
    }

    @Test
    void withoutAWeblogItLeavesTheAuthorsTextVisible() {
        assertNull(shortcode.render(Map.of(), null, context(null)));
    }

    @Test
    void theCardIsDiscoverable() {
        ShortcodeCard card = shortcode.getCard();
        assertEquals("contact", card.name());
        assertTrue(card.snippet().startsWith("[contact"), card.snippet());
        assertFalse(card.snippet().contains("<"));
    }

    @Test
    void bothHandlersAreRegisteredInTheDefaultExpander() {
        assertTrue(ShortcodeExpander.defaultExpander().cards().stream()
                .anyMatch(c -> "contact".equals(c.name())));
        assertTrue(ShortcodeExpander.defaultExpander().cards().stream()
                .anyMatch(c -> "subscribe".equals(c.name())));
    }
}
```

`SubscribeShortcodeTest` (same shape): with a weblog whose `newsletterListUuid` is set (use a real `Weblog` and call the setter — Task 7 adds it; until Task 7 lands this test file is written but the two uuid-dependent tests carry `@Disabled("enabled in Task 7 when Weblog.newsletterListUuid lands")` — **Task 7 Step 6 removes the annotations**), assert the placeholder carries `data-list-uuid`; with a blank/null uuid, `render` returns null; the card is discoverable. Plus the uuid is emitted only when it matches `[0-9a-fA-F-]{36}` — a malformed stored value renders nothing rather than junk.

`AudienceSanitizationTest` (models `VideoSanitizationTest`):

```java
package org.apache.roller.weblogger.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AudienceSanitizationTest {

    @Test
    void thePlaceholderDataAttributesSurvive() {
        String clean = HTMLSanitizer.conditionallySanitize(
                "<div class=\"contact-form-slot\" data-weblog=\"myblog\"></div>"
                + "<div class=\"subscribe-form-slot\" data-list-uuid=\"2f0f1b0c-1111-2222-3333-444455556666\"></div>");

        assertTrue(clean.contains("data-weblog=\"myblog\""), clean);
        assertTrue(clean.contains("data-list-uuid="), clean);
    }

    @Test
    void formsAreStillStrippedFromAuthoredContent() {
        String clean = HTMLSanitizer.conditionallySanitize(
                "<form action=\"https://evil.example\"><input name=\"password\"></form>");

        assertFalse(clean.contains("<form"), "authored forms are a phishing kit: " + clean);
        assertFalse(clean.contains("<input"), clean);
    }

    @Test
    void theAttributesAreNotGrantedGlobally() {
        String clean = HTMLSanitizer.conditionallySanitize(
                "<a data-weblog=\"x\">link</a>");

        assertFalse(clean.contains("data-weblog"), clean);
    }
}
```

- [ ] **Step 2: Run them and watch them fail**

Run: `mvn -pl app test -Dtest='ContactShortcodeTest,SubscribeShortcodeTest,AudienceSanitizationTest'`
Expected: FAIL — the classes do not exist / the attributes are stripped.

- [ ] **Step 3: Write the handlers and the grant**

`ContactShortcode` (ASF header, modelled on `VideoShortcode`'s file style):

```java
public class ContactShortcode implements ShortcodeHandler {

    @Override
    public String getName() {
        return "contact";
    }

    @Override
    public ShortcodeCard getCard() {
        return ShortcodeCard.snippet("contact", "shortcode.contact.label", "[contact]");
    }

    @Override
    public String render(Map<String, String> attributes, String body,
            ShortcodeContext content) {
        if (content == null || content.getWeblog() == null
                || content.getWeblog().getHandle() == null) {
            return null;
        }
        return "<div class=\"contact-form-slot\" data-weblog=\""
                + StringEscapeUtils.escapeHtml4(content.getWeblog().getHandle())
                + "\"></div>";
    }
}
```

`SubscribeShortcode`: same shape; reads `content.getWeblog().getNewsletterListUuid()`; returns null when the weblog, the uuid, or the uuid's shape (`^[0-9a-fA-F-]{36}$`) is missing/wrong; emits `<div class="subscribe-form-slot" data-list-uuid="...">`. Until Task 7 adds the accessor this file will not compile — **implement `ContactShortcode` fully here, and write `SubscribeShortcode` against the accessor name `getNewsletterListUuid()` but keep it and its test excluded from this task's commit if Task 7 has not landed**. In subagent execution, tasks run in order, so the practical rule is: this task commits `ContactShortcode` + sanitizer + macro + themes; `SubscribeShortcode` + its registration + its test move to **Task 9** where the accessor exists. (The `DEFAULT` list in this task registers only `ContactShortcode`; the `bothHandlersAreRegistered` assertion above is written for the final state and lives in the test file Task 9 completes — in THIS task assert only `contact`.)

`HTMLSanitizer`: extend the existing single `div` grant (the one call that already lists `data-pins ... data-provider, data-video-id`) with `"data-weblog", "data-list-uuid"`. Keep it one `.onElements("div")` call.

Also update `ShortcodeCardTest`'s hand-maintained expectation list with `"contact"` (its own documented convention — Wave A's regression lesson), and `it-selenium/.../ShortcodeCardIT` if it enumerates the Insert menu.

- [ ] **Step 4: Add `#showAudienceAssets`**

In `weblog.vm`, immediately after `#showEmbedAssets`'s `#end`:

```velocity
#**
Client-side injection for the [contact] and [subscribe] placeholders -- the
audience twin of #showEmbedAssets.

The sanitizer strips <form> from authored content on purpose (an authored
form is a phishing kit), so the shortcodes emit inert divs and this macro
builds the real forms. Both endpoints are same-origin, which is why no theme
CSP changes: connect-src 'self' already allows the fetches.

The hidden "website" field is a honeypot: bots fill every field, humans
never see it. elapsedMs is a naive-bot timer: the server refuses a submit
faster than a human could type. Neither is secret; neither needs to be.
*#
#macro(showAudienceAssets)
<style>
.contact-form label, .newsletter-subscribe label { display: block; margin-top: .75em; }
.contact-form input, .contact-form textarea, .newsletter-subscribe input[type=email] {
  width: 100%; max-width: 32em; padding: .4em; box-sizing: border-box; }
.contact-form textarea { min-height: 8em; }
.contact-form button, .newsletter-subscribe button { margin-top: .75em; padding: .4em 1.2em; }
.audience-hp { position: absolute; left: -9999px; top: -9999px; }
.audience-message { margin-top: .75em; }
</style>
<script>
document.addEventListener('DOMContentLoaded', function () {
  var loadedAt = Date.now();
  var base = document.querySelector('base') ? null : null; // context path derived below

  function contextPath() {
    // The page lives at <ctx>/<handle>/... ; every theme is served under the
    // application context. Derive it from a known marker: the stylesheet link
    // that every bundled theme emits points under the context path.
    var link = document.querySelector('link[rel=stylesheet][href]');
    if (!link) { return ''; }
    var href = link.getAttribute('href');
    var idx = href.indexOf('/roller-ui/');
    return idx > 0 ? href.substring(0, idx) : '';
  }

  function showMessage(container, text) {
    var p = container.querySelector('.audience-message');
    p.hidden = false;
    p.textContent = text;
  }

  Array.prototype.forEach.call(document.querySelectorAll('.contact-form-slot'), function (slot) {
    var handle = slot.getAttribute('data-weblog');
    if (!handle) { return; }
    var form = document.createElement('form');
    form.className = 'contact-form';
    form.innerHTML =
        '<label>Name <input name="name" required maxlength="255"></label>'
      + '<label>Email <input type="email" name="email" required maxlength="255" autocomplete="email"></label>'
      + '<label>Subject <input name="subject" maxlength="255"></label>'
      + '<label>Message <textarea name="message" required maxlength="4000"></textarea></label>'
      + '<label class="audience-hp" aria-hidden="true">Website <input name="website" tabindex="-1" autocomplete="off"></label>'
      + '<button type="submit">Send</button>'
      + '<p class="audience-message" hidden></p>';
    form.addEventListener('submit', function (e) {
      e.preventDefault();
      showMessage(form, 'Sending…');
      fetch(contextPath() + '/roller-ui/rendering/contact.rol', {
        method: 'POST',
        headers: {'Content-Type': 'application/json'},
        body: JSON.stringify({
          weblog: handle,
          name: form.name.value,
          email: form.email.value,
          subject: form.subject.value,
          message: form.message.value,
          website: form.website.value,
          elapsedMs: Date.now() - loadedAt,
          source: window.location.pathname
        })
      }).then(function (response) {
        showMessage(form, response.ok
            ? 'Thanks — your message has been sent.'
            : 'Sorry, that did not work. Please try again later.');
        if (response.ok) { form.querySelector('button').disabled = true; }
      }).catch(function () {
        showMessage(form, 'Sorry, that did not work. Please try again later.');
      });
    });
    slot.appendChild(form);
  });

  Array.prototype.forEach.call(document.querySelectorAll('.subscribe-form-slot'), function (slot) {
    var uuid = slot.getAttribute('data-list-uuid');
    if (!uuid) { return; }
    var form = document.createElement('form');
    form.className = 'newsletter-subscribe';
    form.innerHTML =
        '<label>Email <input type="email" name="email" required maxlength="255" autocomplete="email" placeholder="you@example.com"></label>'
      + '<label class="audience-hp" aria-hidden="true">Website <input name="website" tabindex="-1" autocomplete="off"></label>'
      + '<button type="submit">Subscribe</button>'
      + '<p class="audience-message" hidden></p>';
    form.addEventListener('submit', function (e) {
      e.preventDefault();
      showMessage(form, 'Subscribing…');
      fetch('/newsletter/subscribe', {
        method: 'POST',
        headers: {'Content-Type': 'application/json'},
        body: JSON.stringify({
          email: form.email.value,
          list_uuids: [uuid],
          website: form.website.value,
          elapsedMs: Date.now() - loadedAt
        })
      }).then(function (response) {
        showMessage(form, (response.ok || response.status === 409)
            ? 'Thanks — please check your email to confirm.'
            : 'Sorry, that did not work. Please try again later.');
      }).catch(function () {
        showMessage(form, 'Sorry, that did not work. Please try again later.');
      });
    });
    slot.appendChild(form);
  });
});
</script>
#end
```

Note the 200-and-409-look-identical behaviour is kept from the old macro — a different message would let anyone test whether an address is subscribed.

- [ ] **Step 5: Wire the macro into the themes and the page template**

Add `#showAudienceAssets()` beside every existing `#showEmbedAssets()` call: `basic` (weblog.vm, permalink.vm, searchresults.vm — wherever Wave A placed the embed macro), `fauxcoly/std_head.vm`, `gaurav/std_head.vm`, `portfolio` and `travel` (their weblog.vm/permalink.vm/searchresults.vm), and `WEB-INF/velocity/templates/weblog/page.vm`. Find them all:

```bash
grep -rln "showEmbedAssets" app/src/main/webapp/
```

Every file that calls `#showEmbedAssets()` gets `#showAudienceAssets()` on the next line. Missing one theme means the form silently never renders there — the rendering test in Step 6 loops all five themes to prevent exactly that.

- [ ] **Step 6: Rendering test**

Add to `ContactShortcodeTest` a companion rendering test class `AudienceAssetsRenderingTest` in `ui/rendering` (model on `PageNavRenderingTest`'s five-theme loop): a published page whose content is `[contact]` renders, on every one of the five themes, output containing BOTH `contact-form-slot` AND the `#showAudienceAssets` marker (`audience-hp`) — proving placeholder and injector ship together. Use `RenderingTestSupport` + a page fixture (Wave A's `PageRoutingTest` shows the savePage pattern).

Run: `mvn -pl app test -Dtest='ContactShortcodeTest,AudienceSanitizationTest,AudienceAssetsRenderingTest,*Rendering*Test,ShortcodeCardTest'`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/org/apache/roller/weblogger/business/shortcodes/ \
        app/src/main/java/org/apache/roller/weblogger/util/HTMLSanitizer.java \
        app/src/main/webapp/ \
        app/src/main/resources/ApplicationResources.properties \
        app/src/test/java/
git commit -m "Add [contact]: placeholder div + client-injected form, sanitizer grant, #showAudienceAssets"
```

---

# Task 5: The contact endpoint

**Files:**
- Create: `app/src/main/java/org/apache/roller/weblogger/ui/controllers/core/ContactController.java`
- Modify: `app/src/main/java/org/apache/roller/weblogger/util/MailUtil.java` (Reply-To overload)
- Modify: `app/src/main/java/org/apache/roller/weblogger/boot/SecurityConfig.java` (CSRF exemption)
- Modify: `app/src/main/resources/org/apache/roller/weblogger/config/roller.properties` (throttle + timing properties)
- Test: `app/src/test/java/org/apache/roller/weblogger/ui/controllers/core/ContactControllerTest.java`

**Interfaces:**
- Consumes: `FormSubmissionManager` (Task 3), `EventManager` (Task 1), `GenericThrottle`, `MailUtil`.
- Produces: `POST /roller-ui/rendering/contact.rol` accepting the JSON body Task 4's JS sends; 204 on accept (including silent honeypot drops), 400 on validation failure, 404 on unknown weblog, 429 on throttle.
- Produces: `MailUtil.sendTextMessage(String from, String replyTo, String[] to, String subject, String content)` — sets the `Reply-To` header when `replyTo` is non-null; existing overloads delegate with null.

Why this URL: `*.rol` is already dispatcher-mapped as a suffix pattern, so the full path is the lookup path and there is no ambiguity with `/share/*`'s prefix-stripped patterns. `/roller-ui/rendering/*` is where the public comment servlet already lives, and Spring Security's matcher list leaves it `permitAll` via the catch-all.

- [ ] **Step 1: Write the failing test**

`ContactControllerTest`, using the `core` package's `ControllerTestFixture` + `MockWeblogger` conventions (see `LoginControllerTest`). The controller takes its collaborators via the fixture's lazy `weblogger`. Cover, with one test each:

- a valid submission persists (capture via mocked `FormSubmissionManager.save` `ArgumentCaptor`: name/email/message/clientIp/pageSlug present), records a `FORM_SUBMITTED` event, attempts the notification email, and returns 204;
- **persist-first**: when `MailUtil` send throws, the submission is STILL saved and the response is still 204 (mock the mail seam — see Step 3's `MailSender` indirection — the lead survives SMTP failure);
- a filled honeypot (`website` non-blank) returns 204 but persists nothing and records nothing — indistinguishable from success so the bot learns nothing;
- `elapsedMs` below the configured minimum returns 204, persists nothing (same silent drop);
- a missing/blank required field (name, email, message) returns 400 with no persist;
- an email without `@` returns 400;
- an over-length field returns 400 (controller-level cap, before the manager's);
- an unknown weblog handle returns 404;
- when the throttle reports abusive, 429 and nothing persists;
- the event-record failing does NOT fail the request (mock `EventManager.record` to throw; submission persists, 204).

- [ ] **Step 2: Run it and watch it fail**

Run: `mvn -pl app test -Dtest=ContactControllerTest`
Expected: FAIL — the controller does not exist.

- [ ] **Step 3: Write the controller**

`ContactController` extends `BaseController`; `@Controller @RequestMapping("/roller-ui/rendering")`; overrides `isUserRequired()` → false, `isWeblogRequired()` → false, `requiredGlobalPermissionActions()` → `Collections.emptyList()` (the `ShareController` overrides). Lazy per-IP `GenericThrottle` exactly on `ShareController`'s double-checked pattern, sized from `contact.throttle.threshold|interval|maxentries` (defaults 10 / 60s / 250), enabled via startup property `contact.throttle.enabled` default true. Timing minimum from `contact.form.min.seconds` default 3.

```java
    @PostMapping(value = "/contact.rol", consumes = "application/json")
    public ResponseEntity<Void> submit(@RequestBody ContactPayload payload,
            HttpServletRequest request) {

        if (throttlingEnabled() && throttle().isAbusive(request.getRemoteAddr())) {
            return ResponseEntity.status(429).build();
        }

        Weblog weblog = lookupWeblogByHandle(payload.weblog());
        if (weblog == null) {
            return ResponseEntity.notFound().build();
        }

        // Honeypot and timing: answer exactly like success so automation
        // cannot tell it was detected. Nothing is stored.
        if (StringUtils.isNotBlank(payload.website())
                || payload.elapsedMs() < minElapsedMs()) {
            if (throttlingEnabled()) {
                throttle().processHit(request.getRemoteAddr());
            }
            return ResponseEntity.noContent().build();
        }

        String error = validate(payload);
        if (error != null) {
            return ResponseEntity.badRequest().build();
        }

        // Persist FIRST. If SMTP is down the inquiry survives; for a
        // business running on leads that is the failure that matters.
        FormSubmission submission = toSubmission(payload, weblog, request.getRemoteAddr());
        try {
            weblogger.getFormSubmissionManager().save(submission);
            weblogger.flush();
        } catch (WebloggerException ex) {
            log.error("Could not persist contact submission", ex);
            return ResponseEntity.internalServerError().build();
        }

        recordEventBestEffort(weblog, submission);
        notifyBestEffort(weblog, submission);

        if (throttlingEnabled()) {
            throttle().processHit(request.getRemoteAddr());
        }
        return ResponseEntity.noContent().build();
    }
```

`ContactPayload` is a record `(String weblog, String name, String email, String subject, String message, String website, long elapsedMs, String source)`. `validate` enforces required fields, the `MAX_*` caps from `FormSubmissionManager`, and `email.contains("@")`. `toSubmission` fills `pageSlug`/`entryAnchor` by parsing `source`'s last path segment when present (best-effort labelling, never trusted for anything but display). `lookupWeblogByHandle` uses `weblogger.getWeblogManager().getWeblogByHandle(handle)` guarded try/catch → null. `recordEventBestEffort` builds a `FORM_SUBMITTED` `RollerEvent` inside try/catch-log. `notifyBestEffort`:

```java
    private void notifyBestEffort(Weblog weblog, FormSubmission s) {
        if (!MailUtil.isMailConfigured()
                || StringUtils.isBlank(weblog.getEmailAddress())) {
            return;
        }
        try {
            String subject = "[" + weblog.getHandle() + "] contact: "
                    + StringUtils.defaultIfBlank(s.getSubject(), "(no subject)");
            String body = "From: " + s.getName() + " <" + s.getEmail() + ">\n\n"
                    + s.getMessage();
            MailUtil.sendTextMessage(weblog.getEmailAddress(), s.getEmail(),
                    new String[] { weblog.getEmailAddress() }, subject, body);
        } catch (Exception ex) {
            log.error("Contact notification email failed; the submission is stored", ex);
        }
    }
```

For testability of the mail seam without static-mocking `MailUtil`, route the call through a package-private overridable method (`void sendNotification(...)`) the test can override — matching how other controller tests in `core` stub collaborators. (If `core`'s `ControllerTestFixture` already provides a mail interception hook, use that instead — follow the fixture.)

`MailUtil`: add the Reply-To overload; it duplicates `sendTextMessage`'s delegation into `sendMessage` but passes `replyTo` down to a widened private `sendMessage` that calls `msg.setReplyTo(new InternetAddress[]{ new InternetAddress(replyTo) })` when non-null. Existing public signatures keep exact behaviour.

`SecurityConfig`: extend the CSRF exemption with a second narrow predicate and REWRITE the block comment (it currently claims comments are the only exemption and that nothing under `/roller-ui/` is exempt — both stop being true):

```java
.csrf(csrf -> csrf.ignoringRequestMatchers(
        SecurityConfig::isPublicCommentPost,
        SecurityConfig::isPublicAudiencePost))
```

```java
    /**
     * The audience endpoints: an anonymous reader submitting the contact
     * form or the subscribe form. Same reasoning as the comment exemption
     * above -- the forms are injected onto cached pages that cannot carry a
     * per-session token, the requests carry no ambient authority, and the
     * real defences are the honeypot, the timing check and the throttle.
     */
    private static boolean isPublicAudiencePost(HttpServletRequest request) {
        if (!"POST".equalsIgnoreCase(request.getMethod())) {
            return false;
        }
        String path = request.getRequestURI().substring(request.getContextPath().length());
        return path.equals("/roller-ui/rendering/contact.rol")
                || path.equals("/newsletter/subscribe");
    }
```

(`/newsletter/subscribe` is registered in Task 8; exempting it here is inert until then and keeps the security change in one reviewed commit.)

`roller.properties` additions beside the comment-throttle block:

```properties
# Contact form defences. The enabled flag and sizing are startup-scoped,
# like the share password throttle. min.seconds is the naive-bot timer:
# submissions reporting less elapsed time are silently dropped.
contact.throttle.enabled=true
contact.throttle.threshold=10
contact.throttle.interval=60
contact.throttle.maxentries=250
contact.form.min.seconds=3
```

- [ ] **Step 4: Run the tests**

Run: `mvn -pl app test -Dtest='ContactControllerTest,ControllerMetadataTest'`
Expected: PASS (every `@RequestParam`-free JSON body avoids the naming trap; `ControllerMetadataTest`'s tree-wide parameter scan still runs).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/org/apache/roller/weblogger/ui/controllers/core/ContactController.java \
        app/src/main/java/org/apache/roller/weblogger/util/MailUtil.java \
        app/src/main/java/org/apache/roller/weblogger/boot/SecurityConfig.java \
        app/src/main/resources/org/apache/roller/weblogger/config/roller.properties \
        app/src/test/java/org/apache/roller/weblogger/ui/controllers/core/ContactControllerTest.java
git commit -m "Add the contact endpoint: persist-first, layered defences, Reply-To notification"
```

---

# Task 6: The submissions inbox

**Files:**
- Create: `app/src/main/java/org/apache/roller/weblogger/ui/controllers/editor/FormSubmissionsController.java`
- Create: `app/src/main/webapp/WEB-INF/jsps/editor/Submissions.jsp`
- Modify: `app/src/main/resources/org/apache/roller/weblogger/ui/menu/editor-menu.xml` (after `comments`)
- Modify: `app/src/main/resources/ApplicationResources.properties`
- Modify: `app/src/main/java/org/apache/roller/weblogger/ui/controllers/RollerViewResolver.java` (`.Submissions` view)
- Modify: `it-selenium/src/test/java/org/apache/roller/it/support/Routes.java` (the new GET route)
- Test: `app/src/test/java/org/apache/roller/weblogger/ui/controllers/editor/FormSubmissionsControllerTest.java`

**Interfaces:**
- Consumes: `FormSubmissionManager` (Task 3).
- Produces: `GET /roller-ui/authoring/submissions.rol` (list, newest first, paged 30), `POST /roller-ui/authoring/submissions!delete.rol` (selected ids, each ownership-checked).

- [ ] **Step 1: Write the failing test**

Model on `PagesControllerTest`/`CommentsController` conventions. Cover: the list shows only the action weblog's submissions; delete removes only rows belonging to the action weblog (a foreign id in the selection is skipped — the `lookupCategory` hazard, asserted explicitly); menu/action metadata (`getActionName()` = `"submissions"`, `getDesiredMenu()` = `"editor"`, `requiredWeblogPermissionActions()` = `List.of(WeblogPermission.POST)`); paging passes offset/max through to the manager.

The ownership test:

```java
    @Test
    void aForeignSubmissionIdInTheDeleteSelectionIsSkipped() throws Exception {
        FormSubmission mine = submissionOn(weblogA);
        FormSubmission foreign = submissionOn(weblogB);

        controller.delete(new String[] { mine.getId(), foreign.getId() },
                requestFor(weblogA), model);

        verify(submissionManager).remove(mine);
        verify(submissionManager, never()).remove(foreign);
    }
```

- [ ] **Step 2: Run it and watch it fail**

Run: `mvn -pl app test -Dtest=FormSubmissionsControllerTest`
Expected: FAIL.

- [ ] **Step 3: Controller, JSP, menu, messages, view, routes**

Controller: `@Controller @RequestMapping("/roller-ui/authoring")`, extends `BaseController`. `@GetMapping("/submissions.rol")` loads page `p` (`@RequestParam(name = "page", required = false, defaultValue = "0") int page`) → `getSubmissions(weblog, page * 30, 30)` + `getCount` into the model, returns `.Submissions`. `@PostMapping("/submissions!delete.rol")` takes `@RequestParam(name = "deleteIds", required = false) String[] deleteIds`, loops: `get(id)` → skip unless `submission.getWeblog().equals(getActionWeblog(request))` → `remove`; flush; message `submissions.deleted` with the count.

`Submissions.jsp`: ONE form around the table (the `Entries.jsp` pattern — no nested forms), columns: created, name, email (as `mailto:` link), subject, message (truncated with full text in a `<details>`), source page, delete checkbox. **Escape every author-controlled field with `<c:out>`/`fn:escapeXml`** — name, email, subject, message, pageSlug are all attacker-supplied by anonymous visitors; this screen is the highest-XSS-risk surface in the wave (Wave A shipped this bug in Pages.jsp; do not repeat it). `<sec:csrfInput/>` in the form. Prev/next paging links.

Menu (`editor-menu.xml`, after the `comments` item):

```xml
        <menu-item action="submissions"
                   name="tabbedmenu.submissions"
                   weblogPerms="post"/>
```

Messages:

```properties
tabbedmenu.submissions=Inquiries
submissions.title=Contact inquiries
submissions.subtitle=Messages sent through the contact form on {0}
submissions.column.received=Received
submissions.column.from=From
submissions.column.subject=Subject
submissions.column.message=Message
submissions.column.source=Page
submissions.deleted=Deleted {0} submission(s).
submissions.none=No inquiries yet.
generic.delete.selected=Delete selected
```

(Reuse `generic.delete` keys if they already exist — check before adding; `MessageKeyTest`'s unused-key ratchet fails on orphans. `tabbedmenu.submissions` is menu-XML-referenced only, so bump `KNOWN_DYNAMIC_KEY_COUNT` by one with the same in-test comment convention the `tabbedmenu.pages` bump used.)

`RollerViewResolver`: `.Submissions` extends `.tiles-simplepage` with `content=/WEB-INF/jsps/editor/Submissions.jsp`. `Routes.java`: add `/roller-ui/authoring/submissions.rol` per its conventions. `MenuDefinitionTest`/`MenuHelperTest`: extend expectations if they enumerate items (Wave A's Task 8 did this — follow the same edits).

- [ ] **Step 4: Run the tests**

Run: `mvn -pl app test -Dtest='FormSubmissionsControllerTest,ControllerMetadataTest,MessageKeyTest,MenuDefinitionTest,MenuHelperTest,RouteCoverageTest'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/org/apache/roller/weblogger/ui/controllers/ \
        app/src/main/webapp/WEB-INF/jsps/editor/Submissions.jsp \
        app/src/main/resources/ \
        app/src/test/java/ \
        it-selenium/src/test/java/org/apache/roller/it/support/Routes.java
git commit -m "Add the per-weblog inquiries inbox"
```

---

# Task 7: `V016` migration, `newsletterListUuid`, `newsletterSentAt`, the Settings field

**Files:**
- Create: `bin/db/migrations/V016__newsletter_wiring.sql`
- Modify: `app/src/main/java/org/apache/roller/weblogger/pojos/Weblog.java`, `app/src/main/resources/org/apache/roller/weblogger/pojos/Weblog.orm.xml`, `app/src/main/java/org/apache/roller/weblogger/pojos/wrapper/WeblogWrapper.java`
- Modify: `app/src/main/java/org/apache/roller/weblogger/pojos/WeblogEntry.java`, `app/src/main/resources/org/apache/roller/weblogger/pojos/WeblogEntry.orm.xml`
- Modify: `app/src/main/java/org/apache/roller/weblogger/ui/controllers/editor/WeblogConfigBean.java`, `WeblogConfigController.java` (validation), `app/src/main/webapp/WEB-INF/jsps/editor/WeblogConfig.jsp`
- Modify: `app/src/main/resources/ApplicationResources.properties`
- Test: `app/src/test/java/org/apache/roller/weblogger/ui/controllers/editor/WeblogConfigNewsletterTest.java`

**Interfaces:**
- Produces: `Weblog.getNewsletterListUuid()/setNewsletterListUuid(String)` (nullable), mirrored read-only on `WeblogWrapper`; `WeblogEntry.getNewsletterSentAt()/setNewsletterSentAt(Timestamp)` (nullable).
- Produces: Weblog Settings field that accepts blank or a 36-char UUID and rejects anything else with field error `websiteSettings.newsletterListUuid.invalid`.

- [ ] **Step 1: Write the migration**

`V016__newsletter_wiring.sql` (ASF header + prose per convention):

```sql
-- Migration: newsletter wiring
--
-- weblog.newsletter_list_uuid: which Listmonk list this weblog's subscribe
-- form feeds. Roller stores no subscriber data -- Listmonk owns addresses,
-- double opt-in and unsubscribe; this column is the only newsletter state
-- the blog itself holds, and it is configuration, not subscriber data.
--
-- weblogentry.newsletter_sent_at: stamped when "Send as newsletter"
-- succeeds, so an entry cannot be mailed twice. Null means never sent.
--
-- Prerequisites: V002__baseline_schema.

ALTER TABLE weblog
    ADD COLUMN IF NOT EXISTS newsletter_list_uuid varchar(64);

ALTER TABLE weblogentry
    ADD COLUMN IF NOT EXISTS newsletter_sent_at timestamp(3) with time zone;
```

Run: `mvn -pl app test -Dtest=SchemaMigrationTest` — PASS.

- [ ] **Step 2: Write the failing test**

`WeblogConfigNewsletterTest` (conventions from the existing `WeblogConfigControllerTest` if present, else the editor `ControllerTestFixture`): saving a valid UUID persists it via `copyTo`; saving blank clears it (null stored); saving `"not-a-uuid"` produces field error `websiteSettings.newsletterListUuid.invalid` and does not persist; the round trip `copyFrom` shows the stored value.

- [ ] **Step 3: Wire the field through all seven layers**

The exact `requireAuthenticatedComments` template (V013), adapted:
1. `Weblog.java`: `private String newsletterListUuid;` + accessors, beside `analyticsCode`.
2. `Weblog.orm.xml`: `<basic name="newsletterListUuid"><column name="newsletter_list_uuid" insertable="true" updatable="true" unique="false"/></basic>` after `analyticsCode`.
3. `WeblogWrapper`: read-only `getNewsletterListUuid()` delegating to the pojo (templates and `SubscribeShortcode` read through the wrapper/pojo).
4. `WeblogConfigBean`: `private String newsletterListUuid;` + accessors + `copyFrom`/`copyTo` lines (trim-to-null in `copyTo`).
5. `WeblogConfigController.myValidate`: blank OK; else must match `^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$` or add the field error.
6. `WeblogConfig.jsp`: text input row modelled on the existing text fields, bound `name="bean.newsletterListUuid"`, with tip text.
7. Messages: `websiteSettings.newsletterListUuid=Newsletter list UUID`, `websiteSettings.newsletterListUuid.tip=From Listmonk: Lists → your list → UUID. Blank disables the subscribe form for this weblog.`, `websiteSettings.newsletterListUuid.invalid=That is not a list UUID. Copy it exactly from Listmonk.`

`WeblogEntry`: `private Timestamp newsletterSentAt = null;` + accessors; orm mapping `<basic name="newsletterSentAt"><column name="newsletter_sent_at" insertable="true" updatable="true" unique="false" nullable="true"/></basic>` beside `eventStart`/`eventEnd`.

- [ ] **Step 4: Enable the deferred SubscribeShortcode tests**

If Task 4 deferred `SubscribeShortcode` (see its Step 3 note), it lands **here or in Task 9** — the plan puts it in Task 9 with the macro rework; this task only makes the accessor exist. Remove any `@Disabled` markers Task 9 needs only when Task 9 registers the handler.

- [ ] **Step 5: Run the tests**

Run: `mvn -pl app test -Dtest='WeblogConfigNewsletterTest,SchemaMigrationTest,WeblogEntryManagerQueryTest,SmallWrapperDelegationTest'`
Expected: PASS (`SmallWrapperDelegationTest` may enumerate wrapper methods — extend per its conventions).

- [ ] **Step 6: Commit**

```bash
git add bin/db/migrations/V016__newsletter_wiring.sql \
        app/src/main/java/org/apache/roller/weblogger/pojos/ \
        app/src/main/resources/ \
        app/src/main/java/org/apache/roller/weblogger/ui/controllers/editor/ \
        app/src/main/webapp/WEB-INF/jsps/editor/WeblogConfig.jsp \
        app/src/test/java/
git commit -m "Add V016: per-weblog newsletter list uuid and entry sent-at stamp"
```

---

# Task 8: Roller owns `/newsletter/subscribe`; the Caddy rewrite dies

**Files:**
- Create: `app/src/main/java/org/apache/roller/weblogger/business/ListmonkClient.java`
- Create: `app/src/main/java/org/apache/roller/weblogger/ui/controllers/core/NewsletterController.java`
- Modify: `app/src/main/java/org/apache/roller/weblogger/boot/ServletRegistrationConfig.java` (`/newsletter/*` mapping)
- Modify: `deploy/caddy/Caddyfile` (**delete** the handle block)
- Modify: `app/src/main/resources/org/apache/roller/weblogger/config/roller.properties`
- Test: `app/src/test/java/org/apache/roller/weblogger/business/ListmonkClientTest.java`, `app/src/test/java/org/apache/roller/weblogger/ui/controllers/core/NewsletterControllerTest.java`

**Interfaces:**
- Consumes: `Weblog.getNewsletterListUuid` (Task 7), `EventManager` (Task 1), `GenericThrottle`.
- Produces: `ListmonkClient` built from startup properties (`newsletter.listmonk.baseurl`, blank = unconfigured) with `int subscribe(String email, String listUuid) throws IOException` returning Listmonk's status code (200/409/other). Constructor takes a `java.net.http.HttpClient` for tests; a no-arg factory builds the real one with a 5s timeout.
- Produces: `POST /newsletter/subscribe` — 200/409 passed through; 400 invalid; 404 unknown list; 429 throttled; 503 when unconfigured; 502 when Listmonk errors.

**Why the Caddy block must be deleted in this same commit:** Caddy evaluates mutually-exclusive `handle` blocks by path specificity, so its `handle /newsletter/subscribe` beats the catch-all `handle { reverse_proxy app:8080 }` — in production the rewrite wins and this controller would never run: no throttle, no honeypot, no `roller_event`, silently. The deletion IS the deploy of this feature.

- [ ] **Step 1: Write the failing client test**

`ListmonkClientTest` uses the JDK's `com.sun.net.httpserver.HttpServer` on an ephemeral port as a fake Listmonk (no new dependency):

```java
package org.apache.roller.weblogger.business;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ListmonkClientTest {

    private HttpServer server;
    private final AtomicReference<String> lastBody = new AtomicReference<>();
    private volatile int respondWith = 200;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/public/subscription", exchange -> {
            lastBody.set(new String(exchange.getRequestBody().readAllBytes(),
                    StandardCharsets.UTF_8));
            byte[] out = "{}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(respondWith, out.length);
            try (OutputStream os = exchange.getResponseBody()) { os.write(out); }
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private ListmonkClient client() {
        return new ListmonkClient("http://127.0.0.1:" + server.getAddress().getPort(),
                HttpClient.newHttpClient());
    }

    @Test
    void subscribePostsTheListmonkPublicShapeAndReturnsTheStatus() throws Exception {
        int status = client().subscribe("reader@example.com",
                "2f0f1b0c-1111-2222-3333-444455556666");

        assertEquals(200, status);
        assertTrue(lastBody.get().contains("\"email\":\"reader@example.com\""), lastBody.get());
        assertTrue(lastBody.get().contains("\"list_uuids\":[\"2f0f1b0c-1111-2222-3333-444455556666\"]"),
                lastBody.get());
    }

    @Test
    void aConflictPassesThroughAsItsOwnStatus() throws Exception {
        respondWith = 409;
        assertEquals(409, client().subscribe("reader@example.com",
                "2f0f1b0c-1111-2222-3333-444455556666"));
    }

    @Test
    void anUnconfiguredClientReportsItself() {
        assertTrue(new ListmonkClient("", HttpClient.newHttpClient()).isUnconfigured());
        assertTrue(new ListmonkClient(null, HttpClient.newHttpClient()).isUnconfigured());
    }
}
```

- [ ] **Step 2: Run it and watch it fail**

Run: `mvn -pl app test -Dtest=ListmonkClientTest`
Expected: FAIL — `ListmonkClient` does not exist.

- [ ] **Step 3: Write the client**

```java
package org.apache.roller.weblogger.business;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import org.apache.commons.lang3.StringUtils;
import org.apache.roller.weblogger.config.WebloggerConfig;

/**
 * The only outbound HTTP in the audience wave, and it points at deployer
 * configuration, never at author or reader input: the Listmonk base URL
 * comes from roller.properties, and the request body carries an email plus
 * a list uuid that has already been matched against a weblog's configured
 * list. There is nothing here a reader can aim at an internal address.
 */
public class ListmonkClient {

    private final String baseUrl;
    private final HttpClient http;

    public ListmonkClient(String baseUrl, HttpClient http) {
        this.baseUrl = StringUtils.stripEnd(StringUtils.trimToNull(baseUrl), "/");
        this.http = http;
    }

    public static ListmonkClient fromConfig() {
        return new ListmonkClient(
                WebloggerConfig.getProperty("newsletter.listmonk.baseurl"),
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build());
    }

    public boolean isUnconfigured() {
        return baseUrl == null;
    }

    /** Forwards a subscription; returns Listmonk's status code (200, 409, ...). */
    public int subscribe(String email, String listUuid) throws IOException {
        String body = "{\"email\":" + jsonString(email)
                + ",\"list_uuids\":[" + jsonString(listUuid) + "]}";
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/public/subscription"))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        try {
            return http.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted forwarding subscription", ex);
        }
    }

    private static String jsonString(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
```

(Campaign methods arrive in Task 10 on this same class.)

- [ ] **Step 4: Write the failing controller test, then the controller**

`NewsletterControllerTest` (core fixture conventions; `ListmonkClient` injected via a package-private setter or constructor overload the test uses): valid email + uuid matching a weblog → forwards, returns Listmonk's 200, records `NEWSLETTER_SUBSCRIBED`; Listmonk answering 409 passes through as 409 and records NOTHING (an already-subscribed address is not a new conversion); uuid matching no weblog → 404, no forward (the open-relay guard); bad email → 400; filled honeypot or too-fast `elapsedMs` → 200 with no forward (indistinguishable from success); unconfigured client → 503; `IOException` from the forward → 502; abusive IP → 429; event-record failure does not fail the request.

`NewsletterController`: `@Controller`, no class-level mapping; registered via a new prefix pattern. In `ServletRegistrationConfig`, beside `SHARE_URL_PATTERNS`:

```java
    static final String[] NEWSLETTER_URL_PATTERNS = {"/newsletter/*"};
```

added with `servletRegistration.addMapping(NEWSLETTER_URL_PATTERNS);` in the same `configure` override. The controller's mapping is prefix-relative: `@PostMapping(value = "/subscribe", consumes = "application/json")`. (Spring prefers the exact literal `/subscribe` over `ShareController`'s `/{token:...}` template for the stripped lookup path, and methods differ anyway; note this in the controller's javadoc.) Overrides: `isUserRequired()` false, `isWeblogRequired()` false, `requiredGlobalPermissionActions()` empty. Payload record `(String email, java.util.List<String> list_uuids, String website, long elapsedMs)`. Weblog match: `WeblogManager` has no by-uuid finder — add `getWeblogByNewsletterListUuid(String)` to `WeblogManager`/`JPAWeblogManagerImpl` with a named query on `Weblog` (`SELECT w FROM Weblog w WHERE w.newsletterListUuid = ?1`), covered in `NewsletterControllerTest` via the mock and in `WeblogPageManagerTest`-style real-DB assertion added to an existing `*ManagerTest` for weblogs. Throttle: lazy per-IP, `newsletter.subscribe.throttle.*` properties (defaults 10/60/250, enabled default true). Success path records the event best-effort (200 only).

`roller.properties`:

```properties
# Newsletter forwarding. Blank baseurl disables the endpoint (503), which is
# the dev default -- listmonk only exists in the production compose file.
newsletter.listmonk.baseurl=
newsletter.subscribe.throttle.enabled=true
newsletter.subscribe.throttle.threshold=10
newsletter.subscribe.throttle.interval=60
newsletter.subscribe.throttle.maxentries=250
```

- [ ] **Step 5: Delete the Caddy block**

In `deploy/caddy/Caddyfile`, delete the entire

```
	handle /newsletter/subscribe {
		rewrite * /api/public/subscription
		reverse_proxy listmonk:9000
	}
```

block, leaving the catch-all `handle { reverse_proxy app:8080 }` to route the path to the app. Add a one-line comment where it was: `# /newsletter/subscribe is served by the app itself (throttle + events); do not re-add a rewrite here.` Update the stale claim in `weblog.vm`'s subscribe-macro doc comment ("which Caddy forwards to listmonk") — Task 9 rewrites that comment wholesale; if Task 9 is executed after this task nothing else is needed here. Also update `docker-compose.prod.yml`'s `app` service environment if it has no mechanism to pass `newsletter.listmonk.baseurl` — the app reads `roller-custom.properties`; add a documented line to the production properties template (`deploy/` — find where prod roller-custom.properties is templated; `docker_deployment.md` documents it) setting `newsletter.listmonk.baseurl=http://listmonk:9000`. Documentation lands in Task 14; the property template line lands here.

- [ ] **Step 6: Run the tests**

Run: `mvn -pl app test -Dtest='ListmonkClientTest,NewsletterControllerTest,ControllerMetadataTest,RouteCoverageTest'`
Expected: PASS (no new GET routes, so `Routes.java` is untouched).

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/org/apache/roller/weblogger/business/ListmonkClient.java \
        app/src/main/java/org/apache/roller/weblogger/ui/controllers/core/NewsletterController.java \
        app/src/main/java/org/apache/roller/weblogger/boot/ServletRegistrationConfig.java \
        app/src/main/java/org/apache/roller/weblogger/business/ \
        deploy/ \
        app/src/main/resources/org/apache/roller/weblogger/config/roller.properties \
        app/src/test/java/
git commit -m "Roller owns /newsletter/subscribe; delete the Caddy rewrite

The Caddy handle block was more specific than the catch-all, so in
production it won and the app-side endpoint would never have run: no
throttle, no honeypot, no roller_event. The deletion ships in the same
commit as the endpoint so the two cannot exist in a half state."
```

---

# Task 9: `#showSubscribeForm` rework, `[subscribe]`, theme footers

**Files:**
- Modify: `app/src/main/webapp/WEB-INF/velocity/weblog.vm` (rework the macro at ~line 1674)
- Create/complete: `app/src/main/java/org/apache/roller/weblogger/business/shortcodes/SubscribeShortcode.java` + registration + test (deferred from Task 4)
- Modify: theme footers: `basic/weblog.vm` (new footer block before `</body>`), `fauxcoly/std_footer.vm`, `gaurav/std_footer.vm`, `portfolio/{weblog,permalink}.vm` (`.pf-footer`), `travel/{weblog,permalink}.vm` (`.tg-footer`)
- Modify: `app/src/main/resources/ApplicationResources.properties` (`shortcode.subscribe.label`)
- Test: `app/src/test/java/org/apache/roller/weblogger/ui/rendering/SubscribeFormRenderingTest.java`

**Interfaces:**
- Consumes: `WeblogWrapper.getNewsletterListUuid` (Task 7), `#showAudienceAssets` (Task 4).
- Produces: `#showSubscribeForm($weblog $prompt)` — emits the subscribe SLOT div (`<div class="subscribe-form-slot" data-list-uuid="…">`) plus a visible prompt, or NOTHING when the weblog has no list uuid; themes call it unconditionally.

- [ ] **Step 1: Write the failing rendering test**

`SubscribeFormRenderingTest` (five-theme loop, `PageNavRenderingTest` pattern): with `newsletterListUuid` set on the fixture weblog, every theme's home page contains `subscribe-form-slot` AND `data-list-uuid`; with it null, no theme emits the slot; the prompt text is HTML-escaped (set a prompt-bearing... the prompt is theme-authored, not user data — instead assert the uuid attribute is exactly the stored value). Also assert `audience-hp` present (the injector shipped) wherever the slot is.

- [ ] **Step 2: Run it and watch it fail**

Run: `mvn -pl app test -Dtest=SubscribeFormRenderingTest`
Expected: FAIL — no theme calls the macro yet.

- [ ] **Step 3: Rework the macro**

Replace the old `#showSubscribeForm($listUuid $prompt)` macro AND its doc comment (the comment claims Caddy forwards the POST — no longer true) with:

```velocity
#**
A newsletter subscribe form, for themes that want one.

Call it with the weblog and a prompt:
    #showSubscribeForm($model.weblog "Get new guides by email")

Renders NOTHING when the weblog has no newsletter list configured (Weblog
Settings -> Newsletter list UUID), so themes call it unconditionally.

Emits only the placeholder slot; #showAudienceAssets injects the actual
form client-side and posts same-origin to /newsletter/subscribe, which THIS
application serves (throttled, honeypotted, recorded as a first-party
event) and forwards to Listmonk. Roller stores no subscriber data --
Listmonk records the address, sends the double opt-in email and owns the
unsubscribe link.
*#
#macro(showSubscribeForm $weblog $prompt)
#if($weblog.newsletterListUuid && $weblog.newsletterListUuid != "")
<div class="newsletter-subscribe-block">
    <p class="newsletter-prompt">$utils.escapeHTML($prompt)</p>
    <div class="subscribe-form-slot" data-list-uuid="$utils.escapeHTML($weblog.newsletterListUuid)"></div>
</div>
#end
#end
```

- [ ] **Step 4: Complete `SubscribeShortcode`**

Implement as specified in Task 4's Step 3 (placeholder div, uuid shape check, null when absent), register in `ShortcodeExpander.DEFAULT`, add `shortcode.subscribe.label=Subscribe form`, complete `SubscribeShortcodeTest` (no `@Disabled` left), extend `ShortcodeCardTest`'s list with `"subscribe"` and the IT enumeration if applicable.

- [ ] **Step 5: Theme footers**

Each theme gets `#showSubscribeForm($model.weblog "<theme-appropriate prompt>")` in its footer, styled with the theme's own container conventions (Wave A's Task 9 lesson: THEMES supply containers):
- `basic/weblog.vm`: introduce `<footer>` before `</body>` (line ~46) containing the call.
- `fauxcoly/std_footer.vm`: append after the `#poweredby` line.
- `gaurav/std_footer.vm`: a new column div inside the existing `row`, Bootstrap classes matching its siblings.
- `portfolio/weblog.vm` + `permalink.vm`: inside `<footer class="pf-footer">` after the search form.
- `travel/weblog.vm` + `permalink.vm`: inside `<footer class="tg-footer">` after the search form.
Prompts: basic/fauxcoly/gaurav "Subscribe by email"; portfolio "New work, by email"; travel "Get new guides by email".

- [ ] **Step 6: Run the tests**

Run: `mvn -pl app test -Dtest='SubscribeFormRenderingTest,SubscribeShortcodeTest,ShortcodeCardTest,*Rendering*Test'`
Expected: PASS — including the pinned CSP tests, untouched, proving no CSP drift.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/webapp/ \
        app/src/main/java/org/apache/roller/weblogger/business/shortcodes/ \
        app/src/main/resources/ApplicationResources.properties \
        app/src/test/java/ \
        it-selenium/
git commit -m "Wire the subscribe form into every theme footer and add [subscribe]"
```

---

# Task 10: "Send as newsletter"

**Files:**
- Modify: `app/src/main/java/org/apache/roller/weblogger/business/ListmonkClient.java` (campaign methods)
- Modify: `app/src/main/java/org/apache/roller/weblogger/ui/controllers/editor/EntryEditController.java` (action)
- Modify: `app/src/main/webapp/WEB-INF/jsps/editor/EntryEdit.jsp` (card + confirm modal)
- Modify: `app/src/main/resources/org/apache/roller/weblogger/config/roller.properties` (admin API credentials)
- Modify: `app/src/main/resources/ApplicationResources.properties`
- Test: extend `ListmonkClientTest`; `app/src/test/java/org/apache/roller/weblogger/ui/controllers/editor/EntryEditNewsletterTest.java`

**Interfaces:**
- Consumes: `WeblogEntry.getNewsletterSentAt/setNewsletterSentAt` (Task 7), `WeblogEntry.getTransformedText()` (the theme-independent render seam feeds already use), `BaseController.lookupEntry`.
- Produces: `ListmonkClient.sendCampaign(String listUuid, String subject, String html) throws IOException` — resolves the list id from the uuid (`GET /api/lists?per_page=all`), creates a campaign (`POST /api/campaigns`), starts it (`PUT /api/campaigns/{id}/status` body `{"status":"running"}`), all with HTTP basic auth from `newsletter.listmonk.apiuser`/`newsletter.listmonk.apitoken`; throws `IOException` naming the failing step otherwise.
- Produces: `POST /roller-ui/authoring/entryEdit!sendNewsletter.rol`.

- [ ] **Step 1: Extend the client test**

Add to `ListmonkClientTest`: fake-server contexts for `/api/lists` (returns `{"data":{"results":[{"id":7,"uuid":"<the-uuid>"}]}}`), `/api/campaigns` (captures body, returns `{"data":{"id":42}}`), `/api/campaigns/42/status` (captures body, returns 200). Assert: `sendCampaign` hits all three in order with basic-auth headers; the campaign body carries the subject, the html, `"lists":[7]`, `"type":"regular"`, `"content_type":"html"`; the status body is `{"status":"running"}`; a lists response NOT containing the uuid throws `IOException` whose message names the uuid; a 500 from any step throws naming the step.

- [ ] **Step 2: Run it and watch it fail**

Run: `mvn -pl app test -Dtest=ListmonkClientTest`
Expected: FAIL — `sendCampaign` does not exist.

- [ ] **Step 3: Implement the client methods**

Parse JSON with Jackson (`com.fasterxml.jackson.databind.ObjectMapper` — already on the Boot classpath; build request bodies with it too, replacing hand-rolled concatenation for the campaign payload). Basic auth: `Authorization: Basic base64(apiuser + ":" + apitoken)`; `isCampaignConfigured()` = baseurl + apiuser + apitoken all present. Properties:

```properties
# Listmonk admin API, for "Send as newsletter". Create an API user in
# Listmonk (Admin -> Users) and put its credentials here. Blank disables
# the button.
newsletter.listmonk.apiuser=
newsletter.listmonk.apitoken=
```

- [ ] **Step 4: Write the failing controller test, then the action**

`EntryEditNewsletterTest` (editor fixture conventions, mocked client injected via package-private seam): sending for a PUBLISHED, never-sent entry with a weblog list uuid → `sendCampaign` called with subject = entry title and html containing `getTransformedText()` output and the permalink; `newsletterSentAt` stamped and saved; success message. Already-stamped entry → refused with message `newsletter.alreadySent`, no client call (the cannot-double-send property, asserted). DRAFT entry → refused. Weblog without list uuid → refused with `newsletter.noList`. Client `IOException` → error message shown, `newsletterSentAt` NOT stamped (retry stays possible). Foreign entry id → denied (lookupEntry family, asserted).

Controller action (in `EntryEditController`, beside `entryEditCreateShareLink`):

```java
    @PostMapping("/entryEdit!sendNewsletter.rol")
    public String entryEditSendNewsletter(
            @RequestParam(name = "bean.id") String entryId,
            HttpServletRequest request, Model model) { ... }
```

Flow: `populateCommonModel`; `lookupEntry(entryId, request)` → denied view when null; guards in order (status PUBLISHED, `newsletterSentAt == null`, weblog uuid present, `client.isCampaignConfigured()`), each with its own message key; build html:

```java
        String html = "<h1>" + StringEscapeUtils.escapeHtml4(entry.getTitle()) + "</h1>\n"
                + entry.getTransformedText()
                + "\n<p><a href=\"" + entry.getPermalink() + "\">Read on the site</a></p>";
```

call `sendCampaign(uuid, entry.getTitle(), html)`; on success stamp `setNewsletterSentAt(now)`, `saveWeblogEntry`, `flush`, success message `newsletter.sent`; on `IOException` add error `newsletter.sendFailed` with the exception message; re-render the edit view either way.

- [ ] **Step 5: The JSP card**

In `EntryEdit.jsp`, beside the share-link card (inside the same `actionName == 'entryEdit'` guard): a "Newsletter" card shown only for PUBLISHED entries. If `entry.newsletterSentAt` non-null: static text `newsletter.sentAt` with the timestamp. Else if the weblog has no list uuid: tip text pointing at Weblog Settings. Else: a button opening a Bootstrap confirm modal (the `#delete-entry-modal` pattern) whose form posts `entryEdit!sendNewsletter.rol` with `<sec:csrfInput/>` and the entry id; modal copy warns it emails every subscriber and cannot be unsent. Messages:

```properties
newsletter.cardTitle=Newsletter
newsletter.send=Send as newsletter
newsletter.confirmTitle=Send this entry to the list?
newsletter.confirmBody=This emails every subscriber. A mailed typo is permanent; a published one is a 30-second fix. Check the entry once more.
newsletter.sent=Campaign created and sending.
newsletter.sentAt=Sent as newsletter {0}.
newsletter.alreadySent=This entry has already been sent.
newsletter.noList=Set a newsletter list UUID in Weblog Settings first.
newsletter.notConfigured=Listmonk API credentials are not configured.
newsletter.sendFailed=Sending failed: {0}. Nothing was stamped; you can retry.
```

- [ ] **Step 6: Run the tests**

Run: `mvn -pl app test -Dtest='ListmonkClientTest,EntryEditNewsletterTest,ControllerMetadataTest,MessageKeyTest'`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/org/apache/roller/weblogger/business/ListmonkClient.java \
        app/src/main/java/org/apache/roller/weblogger/ui/controllers/editor/EntryEditController.java \
        app/src/main/webapp/WEB-INF/jsps/editor/EntryEdit.jsp \
        app/src/main/resources/ \
        app/src/test/java/
git commit -m "Add Send as newsletter: manual, stamped, cannot double-send"
```

---

# Task 11: `UserToken`, forgot password

**Files:**
- Create: `app/src/main/java/org/apache/roller/weblogger/pojos/UserToken.java`
- Create: `app/src/main/resources/org/apache/roller/weblogger/pojos/UserToken.orm.xml`
- Create: `app/src/main/java/org/apache/roller/weblogger/business/UserTokenManager.java`
- Create: `app/src/main/java/org/apache/roller/weblogger/business/jpa/JPAUserTokenManagerImpl.java`
- Create: `app/src/main/java/org/apache/roller/weblogger/ui/controllers/core/PasswordResetController.java`
- Create: `app/src/main/webapp/WEB-INF/jsps/core/ForgotPassword.jsp`, `ResetPassword.jsp`
- Modify: `app/src/main/java/org/apache/roller/weblogger/util/TokenGenerator.java` (`sha256Hex`)
- Modify: `app/src/main/webapp/WEB-INF/jsps/core/Login.jsp` ("Forgot password?" link)
- Modify: `persistence.xml`, facade wiring (Task 1 pattern), `RollerViewResolver`, `Routes.java`, `ApplicationResources.properties`
- Modify: `app/src/main/java/org/apache/roller/weblogger/business/jpa/JPAUserManagerImpl.java` (`removeUser` cleans this user's tokens — the FK has no cascade)
- Test: `app/src/test/java/org/apache/roller/weblogger/business/UserTokenManagerTest.java`, `app/src/test/java/org/apache/roller/weblogger/ui/controllers/core/PasswordResetControllerTest.java`

**Interfaces:**
- Produces: `TokenGenerator.sha256Hex(String value)` — lowercase hex SHA-256.
- Produces: `UserToken` (`id`, `user` many-to-one, `tokenSha256`, `purpose` enum `Purpose { PASSWORD_RESET, PASSWORD_SET }`, `created`, `expires`, `usedAt`).
- Produces: `UserTokenManager`:
  - `String issueToken(User user, UserToken.Purpose purpose) throws WebloggerException` — returns the RAW token (the only moment it exists in memory); stores only the digest; expiry = now + 1 hour (`TOKEN_TTL_MS` constant).
  - `UserToken validate(String rawToken) throws WebloggerException` — the row if the digest matches AND `usedAt == null` AND unexpired, else null. Read-only (the GET form peek).
  - `User consume(String rawToken) throws WebloggerException` — validate, then stamp `usedAt` and save; returns the token's user, else null. Single-use is enforced here.
  - `void removeTokens(User user) throws WebloggerException`.
  - `Weblogger.getUserTokenManager()`.
- Produces: routes `GET/POST /roller-ui/forgotPassword.rol`, `forgotPassword!send.rol`, `GET resetPassword.rol`, `POST resetPassword!save.rol` — all public via `SecurityConfig`'s existing `anyRequest().permitAll()` catch-all (no matcher changes needed; verified fact from the security research).

- [ ] **Step 1: Write the failing manager test**

`UserTokenManagerTest` (real-DB, `TestUtils` user fixture): issue returns a raw token whose `sha256Hex` matches the stored row and the raw value is NOT in the database (query the row, assert `tokenSha256 != raw` and equals the digest — the spec's stolen-database property, asserted directly); validate accepts a fresh token; consume returns the user and a second consume of the same token returns null (single-use); an expired token (issue, then set `expires` into the past via the managed row) validates null; a garbage token validates null without throwing; `removeTokens` clears a user's rows; `teardownUser` works for a user with tokens (proving the `removeUser` cleanup).

- [ ] **Step 2: Run, watch fail, implement manager**

Run: `mvn -pl app test -Dtest=UserTokenManagerTest` → FAIL.

`TokenGenerator.sha256Hex`:

```java
    /** Lowercase-hex SHA-256, for storing token digests instead of tokens. */
    public static String sha256Hex(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16))
                   .append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("JVM lacks SHA-256", ex);
        }
    }
```

`UserToken.orm.xml`: table `roller_user_token`, named queries `UserToken.getByDigest` (`WHERE t.tokenSha256 = ?1`), `UserToken.removeByUser`. Entity conventions as `RollerEvent`. `JPAUserTokenManagerImpl`:

```java
    @Override
    public String issueToken(User user, UserToken.Purpose purpose) throws WebloggerException {
        String raw = TokenGenerator.newToken();
        UserToken token = new UserToken();
        token.setUser(user);
        token.setTokenSha256(TokenGenerator.sha256Hex(raw));
        token.setPurpose(purpose);
        Timestamp now = new Timestamp(System.currentTimeMillis());
        token.setCreated(now);
        token.setExpires(new Timestamp(now.getTime() + TOKEN_TTL_MS));
        strategy.store(token);
        return raw;
    }
```

`validate` looks up by digest, checks used/expiry. `consume` calls validate, stamps `usedAt`, stores, returns `token.getUser()`. `removeUser` cleanup in `JPAUserManagerImpl` mirrors the weblog-contents pattern (`UserToken.removeByUser` named update before the user row is removed). Facade wiring per Task 1.

Run the manager test → PASS. Also `mvn -pl app test -Dtest='EqualsContractTest'` with a `UserToken` specimen added.

- [ ] **Step 3: Write the failing controller test**

`PasswordResetControllerTest` (core fixture, `installNoopPasswordEncoder` in setup / restore in teardown — required for `resetPassword`): 

- POST forgot with a known username → token issued, email attempted, and the SAME confirmation message as an unknown username (assert message equality between the two runs — the enumeration property as a test);
- unknown username/email → identical message, no token, no email;
- mail unconfigured → the plain `forgotPassword.mailNotConfigured` message instead, no token;
- disabled user → identical generic confirmation, no token;
- per-IP and per-identifier throttling: after N submits the response is the generic confirmation still (never a distinct throttled page — a distinct response would itself leak) but no further tokens are issued;
- GET reset with a valid raw token → the form view; with garbage/expired/used → the generic `resetPassword.invalid` view;
- POST reset with valid token + matching passwords → password changes (`RollerContext.getPasswordEncoder().matches` under noop encoder: compare directly), token consumed (second POST fails), sessions invalidated (`RollerLoginSessionManager` — spy/verify or assert via its API), redirect to login with message;
- mismatched passwords → field error, token NOT consumed;
- password shorter than 8 → field error (match ProfileController's existing rule if different — follow the existing validation, and pin whichever rule it is).

- [ ] **Step 4: Implement the controller and JSPs**

`PasswordResetController`: `@Controller @RequestMapping("/roller-ui")`, `isUserRequired()` false, `isWeblogRequired()` false, empty global perms. Lazy `GenericThrottle` (properties `passwordreset.throttle.threshold|interval|maxentries`, defaults 5/300/250, enabled default true — startup-scoped; add to `roller.properties` with a comment). Two `processHit` keys per submit: the remote addr and `"id:" + identifier.toLowerCase()`.

`@GetMapping("/forgotPassword.rol")` → `.ForgotPassword`. `@PostMapping("/forgotPassword!send.rol")` with `@RequestParam(name = "identifier") String identifier`:

```java
        populateCommonModel(request, model);
        if (!MailUtil.isMailConfigured()) {
            addMessage(model, "forgotPassword.mailNotConfigured", request);
            return ".ForgotPassword";
        }
        boolean throttled = throttlingEnabled()
                && (throttle().isAbusive(request.getRemoteAddr())
                    || throttle().isAbusive(idKey(identifier)));
        if (!throttled) {
            issueAndMailBestEffort(identifier, request);
            if (throttlingEnabled()) {
                throttle().processHit(request.getRemoteAddr());
                throttle().processHit(idKey(identifier));
            }
        }
        // One message for every outcome: an existing address, an unknown
        // one, and a throttled request all read identically, so the form
        // cannot enumerate accounts or reveal that throttling engaged.
        addMessage(model, "forgotPassword.confirmation", request);
        return ".ForgotPassword";
```

`issueAndMailBestEffort`: find the user by username, else by email (`UserManager.getUserByUserName(identifier, Boolean.TRUE)`; for email add `getUserByEmail(String)` to `UserManager`/`JPAUserManagerImpl` with a named query on enabled users — if an equivalent finder already exists, use it and skip the addition); if found and enabled, `issueToken(user, PASSWORD_RESET)` and send a text email (From = the site's `email.address` config the comment mails use; To = the user; body carries `WebloggerRuntimeConfig.getAbsoluteContextURL() + "/roller-ui/resetPassword.rol?token=" + raw` and the 1-hour notice); all inside try/catch-log so no failure changes the response.

`@GetMapping("/resetPassword.rol")` with `@RequestParam(name = "token") String token`: `validate(token)` → `.ResetPassword` with the token echoed into a hidden field, else `.ResetPassword` with the invalid flag (one view, two states — the invalid state shows `resetPassword.invalid` and a link to forgotPassword). Set `Cache-Control: private, no-store` on both reset views (the ShareController precedent for tokened pages).

`@PostMapping("/resetPassword!save.rol")` with named params `token`, `passwordText`, `passwordConfirm`: re-validate + password rules → on failure re-render the form (token still unconsumed, hidden field re-echoed); on success `consume(token)` → null means raced/expired → invalid view; else `user.resetPassword(passwordText)`, `saveUser`, `flush`, `RollerLoginSessionManager.getInstance().invalidate(user.getUserName())`, flash message `resetPassword.done`, redirect `"redirect:/roller-ui/login.rol"`.

JSPs on the `Login.jsp` bootstrap-form conventions; `.ForgotPassword`/`.ResetPassword` view definitions extend `.tiles-loginpage`. `Login.jsp` gets, after the form's closing tag:

```jsp
<p class="mt-3"><a href="<c:url value='/roller-ui/forgotPassword.rol'/>">
    <spring:message code="loginPage.forgotPassword"/></a></p>
```

Messages (all referenced — the ratchet):

```properties
loginPage.forgotPassword=Forgot your password?
forgotPassword.title=Reset your password
forgotPassword.prompt=Enter your username or email address.
forgotPassword.identifier=Username or email
forgotPassword.send=Send reset link
forgotPassword.confirmation=If that account exists, a reset link is on its way. It is valid for one hour.
forgotPassword.mailNotConfigured=This server has no outgoing mail configured, so it cannot send reset links. Contact the administrator.
forgotPassword.email.subject=Password reset link
resetPassword.title=Choose a new password
resetPassword.invalid=That link is invalid or has expired. Request a new one.
resetPassword.newPassword=New password
resetPassword.confirmPassword=Confirm password
resetPassword.save=Set password
resetPassword.done=Your password has been changed. Sign in with it now.
resetPassword.mismatch=The passwords do not match.
```

`Routes.java`: add both GET routes. Run everything:

Run: `mvn -pl app test -Dtest='UserTokenManagerTest,PasswordResetControllerTest,ControllerMetadataTest,MessageKeyTest,RouteCoverageTest'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/org/apache/roller/weblogger/ \
        app/src/main/resources/ \
        app/src/main/webapp/WEB-INF/jsps/core/ \
        app/src/test/java/ \
        it-selenium/src/test/java/org/apache/roller/it/support/Routes.java
git commit -m "Add forgot-password: hashed single-use tokens, enumeration-proof, throttled"
```

---

# Task 12: Admin "send set-password link"

**Files:**
- Modify: `app/src/main/java/org/apache/roller/weblogger/ui/controllers/admin/UserEditController.java`
- Modify: `app/src/main/webapp/WEB-INF/jsps/admin/UserEdit.jsp`
- Modify: `app/src/main/resources/ApplicationResources.properties`
- Test: `app/src/test/java/org/apache/roller/weblogger/ui/controllers/admin/UserEditPasswordLinkTest.java`

**Interfaces:**
- Consumes: `UserTokenManager.issueToken(user, PASSWORD_SET)` (Task 11), `MailUtil`.
- Produces: `POST /roller-ui/admin/userEdit!sendPasswordLink.rol`; on user creation with mail configured, the password field becomes optional — blank means "random password + emailed set-password link".

- [ ] **Step 1: Write the failing test**

`UserEditPasswordLinkTest` (admin fixture conventions): sending for an existing user issues a PASSWORD_SET token and mails the reset URL, success message; mail unconfigured → error message, no token; creating a user with a BLANK password while mail is configured succeeds, sets a random unusable password (assert it is non-empty and not the empty string), issues a token and mails the link; creating with blank password while mail is NOT configured still fails validation with the existing `error.add.user.missingPassword` (the old rule survives where it is the only safe rule).

- [ ] **Step 2: Implement**

`UserEditController`:
- New action `@PostMapping("/userEdit!sendPasswordLink.rol")` with `@RequestParam(name = "bean.userName") String userName`: load the user, guard `MailUtil.isMailConfigured()` (error `userAdmin.mailNotConfigured`), `issueToken(user, PASSWORD_SET)`, mail the same reset URL as Task 11 with subject `userAdmin.setPassword.email.subject`, success message `userAdmin.passwordLinkSent`. Reuse Task 11's mail-the-link helper by extracting it to a small package-visible utility if the duplication exceeds a few lines — `PasswordLinkMailer` in `ui.controllers.core` with one static `sendLink(User user, String rawToken, String subjectKey)` used by both controllers.
- In `createUserSave`'s validation: the missing-password error applies only when `!MailUtil.isMailConfigured()`. When mail is configured and the password is blank: `user.resetPassword(TokenGenerator.newToken())` (random, never disclosed), then after the save issue + mail the PASSWORD_SET link, message `userAdmin.userCreatedLinkSent`.

`UserEdit.jsp`: a "Send set-password link" button (posting the new action, `<sec:csrfInput/>`, shown only when editing an existing user — the `actionName`/id-present guard the page already uses for edit-only controls); on the create form, the password field's label gains tip `userAdmin.passwordOptionalWithMail` when mail is configured (expose `mailConfigured` in the model from the controller's execute methods).

Messages:

```properties
userAdmin.sendPasswordLink=Send set-password link
userAdmin.passwordLinkSent=A set-password link has been emailed to {0}.
userAdmin.userCreatedLinkSent=User created. A set-password link has been emailed.
userAdmin.mailNotConfigured=No outgoing mail is configured; set a password directly instead.
userAdmin.passwordOptionalWithMail=Leave blank to email the user a set-password link instead.
userAdmin.setPassword.email.subject=Set your password
```

- [ ] **Step 3: Run the tests**

Run: `mvn -pl app test -Dtest='UserEditPasswordLinkTest,UserAdminControllerTest,ControllerMetadataTest,MessageKeyTest'`
Expected: PASS with no existing `UserEdit` assertion changed except the one the validation change explicitly relaxes (mail-configured + blank password), which the new test owns.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/org/apache/roller/weblogger/ui/controllers/ \
        app/src/main/webapp/WEB-INF/jsps/admin/UserEdit.jsp \
        app/src/main/resources/ApplicationResources.properties \
        app/src/test/java/
git commit -m "Admin can email a set-password link instead of inventing passwords"
```

---

# Task 13: Browser integration tests

**Files:**
- Create: `it-selenium/src/test/java/org/apache/roller/it/ContactFormIT.java`
- Create: `it-selenium/src/test/java/org/apache/roller/it/SubscribeFormIT.java`
- Create: `it-selenium/src/test/java/org/apache/roller/it/ForgotPasswordIT.java`

Follow the established IT conventions: own weblog per class, never switch the seeded IT weblog's theme, `BrowserHealth` assertions on every browser-visited page, raw `HttpClient` (the `PageIT` precedent) for any deliberate non-2xx or JSON check so `assertNoBrokenResources` is not tripped by intended failures.

- [ ] **Step 1: `ContactFormIT`**

1. Sign in; create a page slugged `contact` with body `[contact]`; publish (the PageIT flow).
2. Visit the page anonymously in the browser: assert the injected form exists (`form.contact-form` with the four visible fields and the hidden honeypot), `assertNoBrokenResources` + `assertNoFailedRequests`.
3. Fill name/email/message in the browser, submit, wait for the success message text ("Thanks — your message has been sent."). This proves endpoint, CSRF exemption and CSP cohere end to end.
4. Sign in, open the Inquiries screen: the submission is listed with the submitted name and message; delete it; the list is empty. `BrowserHealth` on the admin page.
5. Via raw `HttpClient`: POST with the honeypot filled → 204; then assert the inbox does NOT gain a row (silent drop verified end to end).

- [ ] **Step 2: `SubscribeFormIT`**

1. Sign in; set the class weblog's newsletter list UUID in Weblog Settings through the real form (any valid UUID literal).
2. Visit the weblog home anonymously: the footer subscribe block exists with the slot + injected form; `assertNoBrokenResources` + `assertNoFailedRequests` (this is what catches a CSP regression against the injector).
3. Via raw `HttpClient` (NOT the browser — the forward legitimately fails without Listmonk and a browser fetch's 503 would trip BrowserHealth): POST a valid body to `/newsletter/subscribe` → assert 503 (unconfigured in the IT environment) — proving the endpoint exists, parses, and fails closed; POST with a uuid matching no weblog → 404.
4. Clear the UUID in Settings, reload home anonymously → the subscribe block is gone.

- [ ] **Step 3: `ForgotPasswordIT`**

1. Anonymously open the login page: the "Forgot password?" link is present; follow it; `BrowserHealth`.
2. Submit the form with any identifier. The IT server has no mail configured, so assert the plain `forgotPassword.mailNotConfigured` message renders (the spec's "say so plainly" branch — the token flow itself is unit-tested in Task 11; a mail-configured browser flow would need an SMTP fixture the suite does not have, and asserting the honest degraded message IS the production-relevant behaviour for an unconfigured server).
3. GET `/roller-ui/resetPassword.rol?token=garbage` in the browser → the invalid-link page renders (not a 500), `BrowserHealth`.

- [ ] **Step 4: Run the browser suite**

Run: `mvn verify -Pit`
Expected: BUILD SUCCESS. Run it FOREGROUND with a 600000ms timeout; if the harness backgrounds it, wait for its completion notification — never wrap it in shell `timeout` or `&` (a Wave A agent silently killed its own build that way).

- [ ] **Step 5: Commit**

```bash
git add it-selenium/
git commit -m "Add browser ITs for contact, subscribe and forgot-password"
```

---

# Task 14: Ratchet the gates and update the docs

**Files:**
- Modify: `pom.xml` (`jacoco.line.minimum`, `jacoco.branch.minimum`)
- Modify: `CLAUDE.md`
- Modify: `docker_deployment.md`

- [ ] **Step 1: Measure**

```bash
mvn clean test && mvn jacoco:report -pl app
bin/check-diff-coverage.sh 37c2c9171
```

`37c2c9171` is Wave B's base (Wave A's final commit). **Do not pass `master`** — the wave is committed onto master, so that diff would be empty and vacuously pass. Expected ~90%+ on changed lines; add tests for anything under before continuing.

- [ ] **Step 2: Raise the floors**

Set both floors to the measured values rounded DOWN to two decimals; floors only rise (leave a floor unchanged if the measured value rounds to the current floor).

- [ ] **Step 3: Document**

`CLAUDE.md` — add an `## Audience` section in the established dense, hazard-first voice, covering: the placeholder-div + `#showAudienceAssets` injection pattern and WHY (sanitizer strips forms; authored forms are a phishing kit; no CSP change because same-origin); persist-first contact handling and the layered defences (honeypot answers 204 exactly like success — silent drop is deliberate); `/newsletter/subscribe` is served by the APP — the Caddy handle block is deleted and must never come back (path-specificity would silently bypass the throttle and events); Roller stores no subscriber data; `roller_event` is written by Wave B (form/subscribe/publish) and read by Wave C's views — `metadata` column exists but is unmapped until something writes it; `roller_user_token` stores SHA-256 digests only, single-use, 1 hour, and the forgot-password form answers identically for every input on purpose; "Send as newsletter" is manual, synchronous, stamped-on-success (the deviation and its reasoning). Extend the `## Shortcodes` list with `[contact]` and `[subscribe]`.

`docker_deployment.md` — update the newsletter section: the Caddy rewrite is gone; `newsletter.listmonk.baseurl=http://listmonk:9000` plus the API user credentials go in the production `roller-custom.properties`; how to create the Listmonk API user; note that existing deployments must redeploy the Caddyfile (`deploy/deploy.sh` reconciles it) or the old rewrite keeps bypassing the app.

- [ ] **Step 4: Full verification**

Run: `mvn clean verify -Pit`
Expected: BUILD SUCCESS, JaCoCo gates passing.

- [ ] **Step 5: Commit**

```bash
git add pom.xml CLAUDE.md docker_deployment.md
git commit -m "Ratchet coverage floors and document the audience wave"
```

---

# Self-review

**Spec coverage.** Contact forms: shortcode → 4, endpoint + defences + persist-first + Reply-To → 5, admin list → 6, `roller_form_submission` → 1. Newsletter: per-weblog uuid → 7, `#showSubscribeForm` wiring + `[subscribe]` → 9, Roller-owned endpoint + Caddy deletion → 8, "Send as newsletter" + `newsletter_sent_at` → 10. Account access: hashed token table → 1+11, forgot password (enumeration-proof, throttled, 1h single-use) → 11, admin set-password link → 12. Cross-cutting: `roller_event` created in B and written by form (5), subscribe (8), publish (2); V015/V016 → 1/7; browser IT per public surface (contact, subscribe, forgot-password) and per admin screen (inquiries — covered inside ContactFormIT) → 13; floors ratchet → 14. `MailUtil.isMailConfigured` branching → 11 (plain message), 12 (guard). No CAPTCHA anywhere; no CSP change anywhere.

**Deliberately deviated** (header section): synchronous send-as-newsletter; known-list-only forwarding; unmapped `metadata`.

**Deferred to Wave C, deliberately:** `analyticsSiteId`, the SQL views over `roller_event`, the Grafana role, hitcount deletion.

**Type consistency.** `EventManager.record(RollerEvent)` used in 2, 5, 8. `RollerEvent.EventType.{FORM_SUBMITTED,NEWSLETTER_SUBSCRIBED,ENTRY_PUBLISHED}` in 1, 2, 5, 8. `FormSubmissionManager` API in 3, 5, 6; `MAX_*` caps in 3, 5. `Weblog.getNewsletterListUuid` in 7, 8, 9, 10. `WeblogEntry.getNewsletterSentAt` in 7, 10. `ListmonkClient.subscribe/sendCampaign/isUnconfigured/isCampaignConfigured` in 8, 10. `UserTokenManager.issueToken/validate/consume` in 11, 12. `TokenGenerator.newToken/sha256Hex` in 11, 12. `#showAudienceAssets` in 4, 9, 13.

**Known risks.**
1. The `/newsletter/*` dispatcher prefix shares Spring's stripped-lookup-path space with `/share/*`; the plan relies on exact-literal-beats-template resolution (`/subscribe` vs `/{token}`) and differing methods. Task 8 Step 6's tests plus `SubscribeFormIT` exercise the real dispatch; if resolution surprises, the fallback is a distinct suffix route (`/newsletter/subscribe.rol`-style) plus a Caddy-compatible redirect — decide only if the tests actually fail.
2. `#showAudienceAssets` derives the context path from the theme's stylesheet link; a theme with no stylesheet `<link>` would post to the wrong path. All five bundled themes emit one; the rendering test in Task 4 pins injector presence per theme, and `ContactFormIT` proves the real POST path end to end.
3. Task 4 defers `SubscribeShortcode` to Task 9 because its accessor lands in Task 7 — the task ordering note is explicit in both tasks so an executor cannot compile-break by following steps literally.
