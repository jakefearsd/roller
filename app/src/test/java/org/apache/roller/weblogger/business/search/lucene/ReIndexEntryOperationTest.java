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
package org.apache.roller.weblogger.business.search.lucene;

import java.sql.Timestamp;
import java.util.List;

import org.apache.roller.weblogger.TestUtils;
import org.apache.roller.weblogger.business.WebloggerFactory;
import org.apache.roller.weblogger.business.search.IndexManager;
import org.apache.roller.weblogger.business.search.SearchResultList;
import org.apache.roller.weblogger.pojos.User;
import org.apache.roller.weblogger.pojos.Weblog;
import org.apache.roller.weblogger.pojos.WeblogEntry;
import org.apache.roller.weblogger.pojos.WeblogEntry.PubStatus;
import org.apache.roller.weblogger.pojos.wrapper.WeblogEntryWrapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

/**
 * {@link ReIndexEntryOperation} against a real Lucene index -- the fix that
 * stops a trashed entry from being re-added to search.
 *
 * <p>{@code doRun()} always re-fetches the entry by id before touching the
 * index (see its javadoc: the object handed to the operation may be detached,
 * stale, or -- since this queues onto its own thread -- describing an entry
 * that has since moved on entirely), so every case here goes through
 * {@link IndexManager#addEntryReIndexOperation} exactly the way
 * {@code ScheduledEntriesTask} and the editor controllers do, rather than
 * constructing the operation directly.
 *
 * <p>Indexing is asynchronous, so every assertion here polls rather than
 * checks immediately -- same idiom as {@code SearchIndexQueryTest}. Every
 * entry's body carries the same search word, {@code reindexword}, so one
 * {@code awaitTitles} helper can poll all of them.
 */
class ReIndexEntryOperationTest {

    private static final String WORD = "reindexword";

    private User user;
    private Weblog blog;

    @BeforeEach
    void setUp() throws Exception {
        TestUtils.setupWeblogger();
        user = TestUtils.setupUser("reindexOpUser");
        blog = TestUtils.setupWeblog("reindexopblog", user);
        TestUtils.endSession(true);
    }

    @AfterEach
    void tearDown() throws Exception {
        TestUtils.teardownWeblog(blog.getId());
        TestUtils.teardownUser(user.getUserName());
        TestUtils.endSession(true);
    }

    /**
     * The unremarkable case: a re-index of a still-PUBLISHED entry puts it
     * back in the index, findable by search.
     */
    @Test
    void reindexingAPublishedEntryMakesItFindable() throws Exception {
        WeblogEntry entry = savedEntry("reindex-published", "Reindexed and published");

        indexManager().addEntryReIndexOperation(TestUtils.getManagedWeblogEntry(entry));

        awaitTitles(titles -> titles.contains("Reindexed and published"),
                "a re-indexed PUBLISHED entry must become findable");
    }

    /**
     * The regression this wave fixed: an entry that was findable while
     * PUBLISHED must drop out of the index once it is TRASHED and a re-index
     * runs against it -- e.g. a queued re-index that lands after the entry
     * was trashed. Before the fix, {@code doRun()} unconditionally re-added a
     * document for whatever it re-fetched, so a trashed entry stayed (or
     * came back) in site search with a link that 404s.
     */
    @Test
    void reindexingATrashedEntryRemovesItFromTheIndexInsteadOfReAddingIt() throws Exception {
        WeblogEntry entry = savedEntry("reindex-trashed", "Reindexed but trashed");
        indexManager().addEntryIndexOperation(TestUtils.getManagedWeblogEntry(entry));
        awaitTitles(titles -> titles.contains("Reindexed but trashed"),
                "the entry must be findable before it is trashed");

        WeblogEntry managed = TestUtils.getManagedWeblogEntry(entry);
        managed.setStatus(PubStatus.TRASHED);
        managed.setTrashedAt(new Timestamp(System.currentTimeMillis()));
        WebloggerFactory.getWeblogger().getWeblogEntryManager().saveWeblogEntry(managed);
        WebloggerFactory.getWeblogger().flush();
        TestUtils.endSession(true);

        indexManager().addEntryReIndexOperation(TestUtils.getManagedWeblogEntry(entry));

        awaitTitles(titles -> !titles.contains("Reindexed but trashed"),
                "a re-index of a TRASHED entry must remove it from the index, not re-add it");
    }

    /**
     * The null-re-fetch case: an entry deleted out from under a queued
     * re-index (the id no longer resolves to anything) must not throw --
     * {@code doRun()} runs on its own worker thread with nothing to catch an
     * escaping exception, so an NPE there could silently kill that worker and
     * strand whatever else was queued behind it. A stale id is used directly
     * rather than racing a real delete against the async queue, which cannot
     * be made deterministic; a second, unrelated entry queued immediately
     * after is how this proves the worker kept running rather than just that
     * scheduling didn't throw synchronously.
     */
    @Test
    void reindexingAnEntryThatNoLongerExistsDoesNotThrowOrStallLaterOperations() throws Exception {
        WeblogEntry entry = savedEntry("reindex-deleted", "Reindex ghost entry");
        String staleId = TestUtils.getManagedWeblogEntry(entry).getId();
        WebloggerFactory.getWeblogger().getWeblogEntryManager()
                .removeWeblogEntry(TestUtils.getManagedWeblogEntry(entry));
        WebloggerFactory.getWeblogger().flush();
        TestUtils.endSession(true);

        WeblogEntry detachedWithStaleId = new WeblogEntry();
        detachedWithStaleId.setId(staleId);
        assertDoesNotThrow(() -> indexManager().addEntryReIndexOperation(detachedWithStaleId),
                "scheduling a re-index for an id that no longer exists must not throw");

        WeblogEntry survivor = savedEntry("reindex-survivor", "Reindex queue survivor");
        indexManager().addEntryIndexOperation(TestUtils.getManagedWeblogEntry(survivor));

        awaitTitles(titles -> titles.contains("Reindex queue survivor"),
                "an operation queued after the stale re-index must still run");
    }

    /**
     * {@code roller} is dereferenced unconditionally in {@code doRun()}
     * (getWeblogEntryManager, then release() in the finally), so the guard
     * belongs before either happens. Constructed directly rather than
     * through {@link IndexManager}, since every real caller always supplies
     * a non-null Weblogger -- this exercises a shape no production caller
     * can currently produce, the same way the class-level javadoc notes for
     * {@link AddEntryOperation}.
     */
    @Test
    void doRunWithoutAWebloggerLogsAndReturnsInsteadOfThrowing() {
        WeblogEntry data = new WeblogEntry();
        data.setId("does-not-matter");
        // The second null is the LuceneIndexManager, deliberately unmocked: doRun()
        // returns on the null-roller path before it is ever touched.
        ReIndexEntryOperation op = new ReIndexEntryOperation(null, null, data);

        assertDoesNotThrow(op::doRun);
    }

    // ---------------------------------------------------------------- helpers

    private WeblogEntry savedEntry(String anchor, String title) throws Exception {
        WeblogEntry entry = TestUtils.setupWeblogEntry(anchor, blog, user, PubStatus.PUBLISHED);
        entry.setTitle(title);
        entry.setText(WORD + " lives in this entry's body");
        WebloggerFactory.getWeblogger().getWeblogEntryManager().saveWeblogEntry(entry);
        WebloggerFactory.getWeblogger().flush();
        TestUtils.endSession(true);
        return entry;
    }

    private static IndexManager indexManager() {
        return WebloggerFactory.getWeblogger().getIndexManager();
    }

    private static List<String> titlesOf(SearchResultList results) {
        return results.getResults().stream()
                .map(WeblogEntryWrapper::getTitle)
                .toList();
    }

    /**
     * Polls the index for this weblog until the titles it returns for the
     * shared search word satisfy the condition, or the deadline passes.
     * Async index operations race an immediate assertion otherwise.
     */
    private void awaitTitles(java.util.function.Predicate<List<String>> condition, String message)
            throws Exception {
        long deadline = System.nanoTime() + java.time.Duration.ofSeconds(10).toNanos();
        List<String> titles = List.of();
        while (System.nanoTime() < deadline) {
            titles = titlesOf(indexManager().search(WORD, blog.getHandle(), null, null,
                    0, 10, WebloggerFactory.getWeblogger().getUrlStrategy()));
            if (condition.test(titles)) {
                return;
            }
            Thread.sleep(50);
        }
        throw new AssertionError(message + " -- still " + titles + " after 10s");
    }
}
