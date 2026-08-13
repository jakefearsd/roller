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

package org.apache.roller.weblogger.ui.rendering.util.cache;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Tests how the (experimental) cache warm-up job reads its inputs.
 *
 * The job renders feeds ahead of anyone asking for them, so it is driven
 * entirely by a Map handed to it by whatever scheduled it. Everything in that
 * Map is optional and untyped, and a job that throws on the way in takes the
 * scheduler thread with it. The rendering itself needs the whole rendering
 * stack and is not exercised here.
 */
public class WeblogCacheWarmupJobTest {

    private WeblogCacheWarmupJob job;

    @BeforeEach
    public void createTheJob() {
        job = new WeblogCacheWarmupJob();
    }

    @Test
    public void aJobWithNoInputDoesNothing() {
        // input() may simply never be called
        job.execute();

        assertNull(job.output(), "This job reports nothing back to its scheduler");
    }

    @Test
    public void aJobWithNoWeblogsDoesNothing() {
        Map<String, Object> inputs = new HashMap<>();
        inputs.put("feed-entries-atom", "true");
        job.input(inputs);

        // no "weblogs" key at all: nothing to warm up, and nothing to throw over
        job.execute();

        assertNull(job.output(), "This job reports nothing back to its scheduler");
    }

    @Test
    public void weblogsWithoutAFormatAreNotRendered() {
        Map<String, Object> inputs = new HashMap<>();
        inputs.put("weblogs", List.of("myblog"));
        job.input(inputs);

        // feed-entries-atom was not asked for, so nothing must be rendered
        // for the weblog that was named. (RSS feeds are gone -- W2 -- so
        // "feed-entries-rss" is no longer a recognized input at all.)
        job.execute();

        assertNull(WeblogFeedCache.getInstance().get("cache.weblogfeed:myblog/entries/atom", 0L),
                "Naming a weblog is not on its own an instruction to render anything");
    }

    @Test
    public void anEmptyWeblogListIsAcceptedForEveryFormat() {
        Map<String, Object> inputs = new HashMap<>();
        inputs.put("weblogs", List.of());
        inputs.put("feed-entries-atom", "true");
        job.input(inputs);

        // atom requested, no weblogs to do it for
        job.execute();

        assertNull(job.output(), "This job reports nothing back to its scheduler");
    }
}
