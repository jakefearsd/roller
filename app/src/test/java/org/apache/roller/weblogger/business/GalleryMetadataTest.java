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

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.roller.weblogger.TestUtils;
import org.apache.roller.weblogger.pojos.MediaFile;
import org.apache.roller.weblogger.pojos.MediaFileDirectory;
import org.apache.roller.weblogger.pojos.User;
import org.apache.roller.weblogger.pojos.Weblog;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Round trips for the Wave 2 gallery columns: sort_order and the focal point
 * on media files, cover image and privacy on directories. Existing content is
 * unaffected: everything defaults to null / false.
 */
public class GalleryMetadataTest {

    public static Log log = LogFactory.getLog(GalleryMetadataTest.class);

    User testUser = null;
    Weblog testWeblog = null;

    @BeforeEach
    public void setUp() throws Exception {
        TestUtils.setupWeblogger();
        try {
            testUser = TestUtils.setupUser("galleryMetaTestUser");
            testWeblog = TestUtils.setupWeblog("galleryMetaTestWeblog", testUser);
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

    @Test
    public void testMediaFileGalleryFieldsRoundTrip() throws Exception {

        MediaFileManager mgr = WebloggerFactory.getWeblogger().getMediaFileManager();

        MediaFile mediaFile = TestUtils.setupImageMediaFile(testWeblog, "gallery-meta.jpg");
        TestUtils.endSession(true);

        // fresh uploads carry no gallery metadata
        MediaFile fetched = mgr.getMediaFile(mediaFile.getId());
        assertNull(fetched.getSortOrder());
        assertNull(fetched.getFocalX());
        assertNull(fetched.getFocalY());

        fetched.setSortOrder(3);
        fetched.setFocalX(0.25);
        fetched.setFocalY(0.75);
        mgr.updateMediaFile(TestUtils.getManagedWebsite(testWeblog), fetched);
        TestUtils.endSession(true);

        fetched = mgr.getMediaFile(mediaFile.getId());
        assertEquals(Integer.valueOf(3), fetched.getSortOrder());
        assertEquals(0.25, fetched.getFocalX(), 0.0001);
        assertEquals(0.75, fetched.getFocalY(), 0.0001);

        // clearing puts the file back on the "never curated" path
        fetched.setSortOrder(null);
        fetched.setFocalX(null);
        fetched.setFocalY(null);
        mgr.updateMediaFile(TestUtils.getManagedWebsite(testWeblog), fetched);
        TestUtils.endSession(true);

        fetched = mgr.getMediaFile(mediaFile.getId());
        assertNull(fetched.getSortOrder());
        assertNull(fetched.getFocalX());
        assertNull(fetched.getFocalY());
    }

    @Test
    public void testDirectoryCoverAndPrivacyRoundTrip() throws Exception {

        MediaFileManager mgr = WebloggerFactory.getWeblogger().getMediaFileManager();

        MediaFile cover = TestUtils.setupImageMediaFile(testWeblog, "cover.jpg");
        TestUtils.endSession(true);

        MediaFileDirectory directory = mgr.getDefaultMediaFileDirectory(
                TestUtils.getManagedWebsite(testWeblog));
        assertNotNull(directory);
        assertNull(directory.getCoverMediaFileId(), "New directories have no cover");
        assertFalse(directory.isPrivate(), "Directories are public unless flagged");

        directory.setCoverMediaFileId(cover.getId());
        directory.setPrivate(true);
        TestUtils.endSession(true);

        MediaFileDirectory fetched = mgr.getMediaFileDirectory(directory.getId());
        assertEquals(cover.getId(), fetched.getCoverMediaFileId());
        assertTrue(fetched.isPrivate());

        fetched.setPrivate(false);
        TestUtils.endSession(true);

        fetched = mgr.getMediaFileDirectory(directory.getId());
        assertFalse(fetched.isPrivate());
        assertEquals(cover.getId(), fetched.getCoverMediaFileId(),
                "Cover survives independent privacy toggles");
    }

    /**
     * The site-wide media feed renders each file's name, description, tags,
     * uploader and permalink, so a file that reaches it has leaked its
     * metadata even though the bytes themselves stay behind the private-
     * directory gate. The two flags can disagree in either order -- the
     * gallery flag is settable on a file that is already in a private folder,
     * and a folder can be privatised long after its files were shared -- so
     * the directory flag has to win at query time rather than at flag-set
     * time.
     */
    @Test
    public void testPrivateDirectoryFilesAreExcludedFromThePublicFileFeed() throws Exception {

        MediaFileManager mgr = WebloggerFactory.getWeblogger().getMediaFileManager();

        MediaFile openFile = TestUtils.setupImageMediaFile(testWeblog, "feed-open.jpg", "feedopen");
        MediaFile hiddenFile = TestUtils.setupImageMediaFile(testWeblog, "feed-hidden.jpg", "feedhidden");
        TestUtils.endSession(true);

        shareForGallery(mgr, openFile.getId());
        shareForGallery(mgr, hiddenFile.getId());
        TestUtils.endSession(true);

        // both directories are still public, so both files are in the feed
        List<String> ids = feedIds(mgr);
        assertTrue(ids.contains(openFile.getId()));
        assertTrue(ids.contains(hiddenFile.getId()),
                "precondition: sharing puts a file in the feed while its folder is public");

        MediaFileDirectory hiddenDir = mgr.getMediaFileDirectoryByName(
                TestUtils.getManagedWebsite(testWeblog), "feedhidden");
        hiddenDir.setPrivate(true);
        TestUtils.endSession(true);

        ids = feedIds(mgr);
        assertTrue(ids.contains(openFile.getId()),
                "a shared file in a public folder must stay in the feed");
        assertFalse(ids.contains(hiddenFile.getId()),
                "a private folder's file must not leak its metadata into the public media feed");
    }

    private void shareForGallery(MediaFileManager mgr, String mediaFileId) throws Exception {
        MediaFile fetched = mgr.getMediaFile(mediaFileId);
        fetched.setSharedForGallery(Boolean.TRUE);
        mgr.updateMediaFile(TestUtils.getManagedWebsite(testWeblog), fetched);
    }

    private List<String> feedIds(MediaFileManager mgr) throws Exception {
        List<String> ids = new ArrayList<>();
        for (MediaFile file : mgr.fetchRecentPublicMediaFiles(100)) {
            ids.add(file.getId());
        }
        return ids;
    }
}
