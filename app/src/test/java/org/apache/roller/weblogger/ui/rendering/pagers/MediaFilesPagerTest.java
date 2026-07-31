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

package org.apache.roller.weblogger.ui.rendering.pagers;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import org.apache.roller.weblogger.WebloggerException;
import org.apache.roller.weblogger.business.MediaFileManager;
import org.apache.roller.weblogger.business.URLStrategy;
import org.apache.roller.weblogger.business.Weblogger;
import org.apache.roller.weblogger.business.WebloggerFactory;
import org.apache.roller.weblogger.pojos.MediaFile;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link MediaFilesPager}, which backs the site-wide recent
 * media feed.
 *
 * <p>Unlike the other pagers this one is deliberately single-page: it fetches
 * the most recent N files and never offers a next link. Pinning that keeps a
 * well-meaning "add paging for symmetry" change from producing next links that
 * lead to a repeat of the same first page, since the query ignores the offset.
 */
class MediaFilesPagerTest {

    private static final String BASE_URL = "http://localhost/roller/mediafiles";
    private static final int LENGTH = 5;

    private final URLStrategy urlStrategy = mock(URLStrategy.class);

    private static List<MediaFile> filesUpdatedAt(long... epochMillis) {
        List<MediaFile> list = new ArrayList<>();
        for (long millis : epochMillis) {
            MediaFile file = new MediaFile();
            file.setLastUpdated(new Timestamp(millis));
            list.add(file);
        }
        return list;
    }

    private static void withMediaFileManager(MediaFileManager manager, Runnable body) {
        try (MockedStatic<WebloggerFactory> factory = mockStatic(WebloggerFactory.class)) {
            Weblogger weblogger = mock(Weblogger.class);
            when(weblogger.getMediaFileManager()).thenReturn(manager);
            factory.when(WebloggerFactory::getWeblogger).thenReturn(weblogger);
            body.run();
        }
    }

    @Test
    void itemsComeFromTheRecentPublicFilesQueryBoundedByThePageSize() throws Exception {
        MediaFileManager manager = mock(MediaFileManager.class);
        when(manager.fetchRecentPublicMediaFiles(anyInt()))
                .thenReturn(filesUpdatedAt(1L, 2L, 3L));

        withMediaFileManager(manager, () -> {
            MediaFilesPager pager = new MediaFilesPager(urlStrategy, BASE_URL, 0, LENGTH);

            assertEquals(3, pager.getItems().size());
        });

        verify(manager).fetchRecentPublicMediaFiles(LENGTH);
    }

    @Test
    void thisPagerNeverOffersAFurtherPage() {
        // The query takes no offset, so any next link would re-serve page 0.
        MediaFileManager manager = mock(MediaFileManager.class);

        withMediaFileManager(manager, () -> {
            MediaFilesPager pager = new MediaFilesPager(urlStrategy, BASE_URL, 2, LENGTH);

            assertFalse(pager.hasMoreItems(),
                    "The recent-media query ignores the offset, so there is no "
                            + "meaningful second page to advertise");
            assertNull(pager.getNextLink(),
                    "A next link here would lead back to the same set of files");
        });
    }

    @Test
    void lastUpdatedIsTheNewestFileEvenWhenItIsNotTheFirstItem() throws Exception {
        MediaFileManager manager = mock(MediaFileManager.class);
        when(manager.fetchRecentPublicMediaFiles(anyInt()))
                .thenReturn(filesUpdatedAt(1_000L, 9_000L, 4_000L));

        withMediaFileManager(manager, () -> {
            MediaFilesPager pager = new MediaFilesPager(urlStrategy, BASE_URL, 0, LENGTH);

            assertEquals(new Date(9_000L), pager.getLastUpdated(),
                    "lastUpdated must scan the whole page, not trust the first item");
        });
    }

    @Test
    void lastUpdatedIsComputedOnceAndReused() throws Exception {
        MediaFileManager manager = mock(MediaFileManager.class);
        when(manager.fetchRecentPublicMediaFiles(anyInt())).thenReturn(filesUpdatedAt(1_000L));

        withMediaFileManager(manager, () -> {
            MediaFilesPager pager = new MediaFilesPager(urlStrategy, BASE_URL, 0, LENGTH);

            assertEquals(pager.getLastUpdated(), pager.getLastUpdated(),
                    "A feed must not report a different timestamp each time it is read");
        });
    }

    @Test
    void anEmptyPageReportsNowSoTheFeedStillValidates() throws Exception {
        MediaFileManager manager = mock(MediaFileManager.class);
        when(manager.fetchRecentPublicMediaFiles(anyInt())).thenReturn(List.of());

        withMediaFileManager(manager, () -> {
            Date before = new Date();
            MediaFilesPager pager = new MediaFilesPager(urlStrategy, BASE_URL, 0, LENGTH);
            Date lastUpdated = pager.getLastUpdated();

            assertNotNull(lastUpdated, "An empty feed still needs a timestamp");
            assertFalse(lastUpdated.before(before),
                    "With no files the timestamp must be 'now', not the epoch");
        });
    }

    @Test
    void aFailingQueryLeavesAnEmptyPagerRatherThanBreakingTheFeed() throws Exception {
        MediaFileManager manager = mock(MediaFileManager.class);
        when(manager.fetchRecentPublicMediaFiles(anyInt()))
                .thenThrow(new WebloggerException("database is down"));

        withMediaFileManager(manager, () -> {
            MediaFilesPager pager = new MediaFilesPager(urlStrategy, BASE_URL, 0, LENGTH);

            assertTrue(pager.getItems().isEmpty(),
                    "A failed lookup must yield an empty list, never null");
        });
    }
}
