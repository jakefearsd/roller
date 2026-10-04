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

import java.io.File;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import org.apache.roller.weblogger.business.UserManager;
import org.apache.roller.weblogger.business.WeblogEntryManager;
import org.apache.roller.weblogger.business.Weblogger;
import org.apache.roller.weblogger.business.runnable.ThreadManager;
import org.apache.roller.weblogger.business.search.SearchResultList;
import org.apache.roller.weblogger.pojos.Weblog;
import org.apache.roller.weblogger.pojos.WeblogCategory;
import org.apache.roller.weblogger.pojos.WeblogEntry;
import org.apache.roller.weblogger.pojos.WeblogEntry.PubStatus;
import org.apache.roller.weblogger.pojos.wrapper.WeblogEntryWrapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A search that starts the moment an index write finishes must see that
 * write.
 *
 * <p>Searches share one cached {@link org.apache.lucene.index.IndexReader},
 * and a write invalidates it with {@code resetSharedReader()}. That reset
 * used to run <em>after</em> the write lock was released, leaving a window in
 * which a search queued behind the write could take the read lock, find the
 * pre-write reader still cached, and answer from it -- a just-indexed entry
 * not found. That window is what made {@code SearchIndexQueryTest} flaky: its
 * {@code setUp} returns with the indexing write still in flight, so its first
 * search queues behind that write and, on a busy machine, could win the race
 * against the writer's reset (and the cached reader it found was the previous
 * test's).
 *
 * <p>The window is held open deterministically here: the writer pauses just
 * before resetting until the search has either finished or is seen waiting on
 * the index lock. With the reset outside the lock the search finishes first,
 * against the stale reader; with it inside, the search can only wait.
 */
class SharedReaderHandoffTest {

    private static final String WORD = "handoffword";

    @TempDir
    Path indexDir;

    private WeblogEntryManager entries;
    private PausingManager manager;

    @BeforeEach
    void setUp() throws Exception {
        Weblogger roller = mock(Weblogger.class);
        entries = mock(WeblogEntryManager.class);
        when(roller.getWeblogEntryManager()).thenReturn(entries);
        when(roller.getUserManager()).thenReturn(mock(UserManager.class));
        ThreadManager inline = mock(ThreadManager.class);
        doAnswer(inv -> {
            ((Runnable) inv.getArgument(0)).run();
            return null;
        }).when(inline).executeInBackground(any(Runnable.class));
        doAnswer(inv -> {
            ((Runnable) inv.getArgument(0)).run();
            return null;
        }).when(inline).executeInForeground(any(Runnable.class));
        when(roller.getThreadManager()).thenReturn(inline);

        manager = new PausingManager(roller);
        setField(manager, "indexDir", indexDir.toString());
        setField(manager, "indexConsistencyMarker", new File(indexDir.toFile(), ".index-inconsistent"));
        manager.initialize();
    }

    @AfterEach
    void tearDown() {
        manager.shutdown();
    }

    @Test
    void aSearchQueuedBehindAWriteSeesThatWrite() throws Exception {
        Weblog blog = new Weblog();
        blog.setHandle("handoffblog");
        blog.setName("handoffblog");
        WeblogEntry first = entry("first", blog);
        WeblogEntry second = entry("second", blog);

        index(first);
        assertEquals(List.of("first"), titles(search()),
                "precondition: the first entry is indexed, and this search caches a reader");

        AtomicReference<List<String>> seen = new AtomicReference<>();
        Thread searcher = new Thread(() -> {
            try {
                seen.set(titles(search()));
            } catch (Exception e) {
                seen.set(List.of("search failed: " + e));
            }
        }, "handoff-searcher");
        manager.pauseNextResetUntilSettled(searcher);

        AtomicReference<Throwable> writerFailure = new AtomicReference<>();
        Thread writer = new Thread(() -> {
            try {
                index(second);
            } catch (Exception e) {
                writerFailure.set(e);
            }
        }, "handoff-writer");
        writer.start();
        assertTrue(manager.resetReached.await(10, TimeUnit.SECONDS),
                "the write never reached its reader reset");

        searcher.start();
        searcher.join(Duration.ofSeconds(10));
        writer.join(Duration.ofSeconds(10));

        assertNull(writerFailure.get(), "the writer thread failed: " + writerFailure.get());
        assertTrue(seen.get() != null && seen.get().contains("second"),
                "a search that began as the write finished answered from the reader cached "
                        + "before it: " + seen.get());
    }

    /**
     * Holds the next reader reset until the searcher has either finished
     * (the stale-reader outcome) or is parked on the index lock (it cannot
     * read until the write lets go). Either way the test then proceeds; the
     * assertion is what tells them apart.
     */
    private static final class PausingManager extends LuceneIndexManager {

        final CountDownLatch resetReached = new CountDownLatch(1);
        private volatile Thread awaited;

        PausingManager(Weblogger roller) {
            super(roller);
        }

        void pauseNextResetUntilSettled(Thread searcher) {
            awaited = searcher;
        }

        @Override
        public void resetSharedReader() {
            Thread searcher = awaited;
            if (searcher != null) {
                awaited = null;
                resetReached.countDown();
                ReentrantReadWriteLock lock = (ReentrantReadWriteLock) getReadWriteLock();
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                while (System.nanoTime() < deadline
                        && searcher.getState() != Thread.State.TERMINATED
                        && !lock.hasQueuedThread(searcher)) {
                    Thread.onSpinWait();
                }
            }
            super.resetSharedReader();
        }
    }

    private void index(WeblogEntry entry) throws Exception {
        manager.addEntryIndexOperation(entry);
    }

    private SearchResultList search() throws Exception {
        return manager.search(WORD, "handoffblog", null, null, 0, 10, null);
    }

    private static List<String> titles(SearchResultList results) {
        return results.getResults().stream().map(WeblogEntryWrapper::getTitle).toList();
    }

    private WeblogEntry entry(String id, Weblog weblog) throws Exception {
        WeblogCategory cat = new WeblogCategory();
        cat.setName("General");
        WeblogEntry entry = new WeblogEntry();
        entry.setId(id);
        entry.setWebsite(weblog);
        entry.setCategory(cat);
        entry.setTitle(id);
        entry.setText(WORD + " is in the body of " + id);
        entry.setLocale("en");
        entry.setCreatorUserName("nobody");
        entry.setStatus(PubStatus.PUBLISHED);
        Timestamp past = new Timestamp(System.currentTimeMillis() - 60_000);
        entry.setPubTime(past);
        entry.setUpdateTime(past);
        when(entries.getWeblogEntry(id)).thenReturn(entry);
        return entry;
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field field = LuceneIndexManager.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
