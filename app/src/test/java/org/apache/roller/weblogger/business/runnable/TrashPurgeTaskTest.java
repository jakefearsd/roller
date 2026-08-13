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
package org.apache.roller.weblogger.business.runnable;

import java.util.List;

import org.apache.roller.weblogger.WebloggerException;
import org.apache.roller.weblogger.business.PropertiesManager;
import org.apache.roller.weblogger.business.WeblogEntryManager;
import org.apache.roller.weblogger.business.WeblogManager;
import org.apache.roller.weblogger.business.Weblogger;
import org.apache.roller.weblogger.business.WebloggerFactory;
import org.apache.roller.weblogger.pojos.RuntimeConfigProperty;
import org.apache.roller.weblogger.pojos.Weblog;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link TrashPurgeTask}'s own logic -- retention property re-read,
 * per-weblog looping, and per-weblog failure isolation -- exercised against
 * a fully mocked {@link Weblogger} facade rather than the real database.
 *
 * <p>{@code purgeTrash}'s own semantics (what "past retention" means, the
 * strict boundary comparison, -1 meaning "keep forever") are already covered
 * end to end against a real database by {@code WeblogEntryTrashOperationsTest}
 * -- this class exists to prove what the TASK does with that method, not to
 * re-prove what the method does.
 */
class TrashPurgeTaskTest {

    private static final String RETENTION_PROPERTY = "entry.trash.retention.days";

    private Weblogger weblogger;
    private WeblogManager weblogManager;
    private WeblogEntryManager entryManager;
    private PropertiesManager propertiesManager;
    private MockedStatic<WebloggerFactory> factory;

    @BeforeEach
    void setUp() throws Exception {
        weblogger = mock(Weblogger.class);
        weblogManager = mock(WeblogManager.class);
        entryManager = mock(WeblogEntryManager.class);
        propertiesManager = mock(PropertiesManager.class);

        when(weblogger.getWeblogManager()).thenReturn(weblogManager);
        when(weblogger.getWeblogEntryManager()).thenReturn(entryManager);
        when(weblogger.getPropertiesManager()).thenReturn(propertiesManager);

        factory = mockStatic(WebloggerFactory.class);
        factory.when(WebloggerFactory::getWeblogger).thenReturn(weblogger);
    }

    @AfterEach
    void tearDown() {
        factory.close();
    }

    @Test
    void retentionOfMinusOneIsPassedThroughUnchanged() throws Exception {
        Weblog blog = weblog("onlyblog");
        givenWeblogs(blog);
        givenRetention(-1);

        new TrashPurgeTask().runTask();

        verify(entryManager).purgeTrash(blog, -1);
    }

    @Test
    void retentionOfZeroIsPassedThroughUnchanged() throws Exception {
        Weblog blog = weblog("onlyblog");
        givenWeblogs(blog);
        givenRetention(0);

        new TrashPurgeTask().runTask();

        verify(entryManager).purgeTrash(blog, 0);
    }

    @Test
    void aPositiveRetentionIsPassedThroughUnchanged() throws Exception {
        Weblog blog = weblog("onlyblog");
        givenWeblogs(blog);
        givenRetention(30);

        new TrashPurgeTask().runTask();

        verify(entryManager).purgeTrash(blog, 30);
    }

    /**
     * The property is re-read on every call to {@code runTask()}, not latched
     * once -- the trap CLAUDE.md's Configuration scope section names for a
     * promoted-looking runtime property. Two sweeps with two different values
     * on the SAME task instance must use the value current at each sweep.
     */
    @Test
    void theRetentionPropertyIsReReadOnEverySweepNotLatched() throws Exception {
        Weblog blog = weblog("onlyblog");
        givenWeblogs(blog);
        TrashPurgeTask task = new TrashPurgeTask();

        givenRetention(30);
        task.runTask();
        verify(entryManager).purgeTrash(blog, 30);

        givenRetention(7);
        task.runTask();
        verify(entryManager).purgeTrash(blog, 7);
    }

    /**
     * The failure granularity this task chose: per weblog. One weblog's
     * {@code purgeTrash} throwing must not stop the sweep for the others, and
     * must not propagate out of {@code runTask()} -- this runs unattended off
     * the scheduler (see {@link RollerTaskWithLeasing#run()}), the same
     * contract {@code ScheduledEntriesTask} honours for the sweep as a whole.
     */
    @Test
    void oneWeblogsPurgeThrowingDoesNotStopTheOthers() throws Exception {
        Weblog failing = weblog("failingblog");
        Weblog first = weblog("firstblog");
        Weblog second = weblog("secondblog");
        givenWeblogs(first, failing, second);
        givenRetention(30);

        doThrow(new WebloggerException("boom")).when(entryManager).purgeTrash(eq(failing), anyInt());

        new TrashPurgeTask().runTask();

        verify(entryManager).purgeTrash(first, 30);
        verify(entryManager).purgeTrash(failing, 30);
        verify(entryManager).purgeTrash(second, 30);
    }

    /**
     * Same isolation contract for an unchecked exception, not just the
     * declared {@code WebloggerException} -- {@code runTask()} itself has no
     * "unexpected exception" escape hatch of its own the way
     * {@code ScheduledEntriesTask.runTask()} does for its top-level body, so
     * the per-weblog catch must be the thing that actually stops a runtime
     * exception from taking the rest of the sweep down.
     */
    @Test
    void oneWeblogsPurgeThrowingAnUncheckedExceptionDoesNotStopTheOthers() throws Exception {
        Weblog failing = weblog("failingblog");
        Weblog other = weblog("otherblog");
        givenWeblogs(failing, other);
        givenRetention(30);

        doThrow(new RuntimeException("kaboom")).when(entryManager).purgeTrash(eq(failing), anyInt());

        new TrashPurgeTask().runTask();

        verify(entryManager).purgeTrash(other, 30);
    }

    /**
     * The lease/release contract every {@code RollerTaskWithLeasing} subclass
     * must keep: {@code WebloggerFactory.getWeblogger().release()} always
     * runs, even when a purge blew up.
     */
    @Test
    void theWebloggerIsAlwaysReleasedEvenWhenAPurgeThrows() throws Exception {
        Weblog failing = weblog("failingblog");
        givenWeblogs(failing);
        givenRetention(30);

        doThrow(new WebloggerException("boom")).when(entryManager).purgeTrash(eq(failing), anyInt());

        new TrashPurgeTask().runTask();

        verify(weblogger).release();
    }

    /**
     * No weblog to purge and no property configured yet (a fresh install
     * before the row is seeded) must not throw out of {@code runTask()}.
     */
    @Test
    void noWeblogsIsANoOp() throws Exception {
        givenWeblogs();
        givenRetention(30);

        new TrashPurgeTask().runTask();

        verify(entryManager, never()).purgeTrash(any(), anyInt());
        verify(weblogger).release();
    }

    // ---------------------------------------------------------------- fixtures

    private static Weblog weblog(String handle) {
        Weblog weblog = new Weblog();
        weblog.setHandle(handle);
        return weblog;
    }

    private void givenWeblogs(Weblog... weblogs) throws WebloggerException {
        when(weblogManager.getWeblogs(eq(true), isNull(), isNull(), isNull(), eq(0), eq(-1)))
                .thenReturn(List.of(weblogs));
    }

    private void givenRetention(int days) throws WebloggerException {
        when(propertiesManager.getProperty(RETENTION_PROPERTY))
                .thenReturn(new RuntimeConfigProperty(RETENTION_PROPERTY, Integer.toString(days)));
    }
}
