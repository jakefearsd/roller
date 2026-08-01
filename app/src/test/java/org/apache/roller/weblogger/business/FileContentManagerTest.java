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
import org.apache.roller.util.UUIDGenerator;
import org.apache.roller.weblogger.TestUtils;
import org.apache.roller.weblogger.pojos.FileContent;
import org.apache.roller.weblogger.pojos.RuntimeConfigProperty;
import org.apache.roller.weblogger.pojos.User;
import org.apache.roller.weblogger.pojos.Weblog;
import org.apache.roller.weblogger.util.RollerMessages;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test File Management business layer operations.
 */
public class FileContentManagerTest  {

    private static final Log log = LogFactory.getLog(FileContentManagerTest.class);
    User testUser = null;
    Weblog testWeblog = null;

    @BeforeEach
    public void setUp() throws Exception {

        // setup weblogger
        TestUtils.setupWeblogger();

    }

    @AfterEach
    public void tearDown() throws Exception {
        PropertiesManager pmgr = WebloggerFactory.getWeblogger().getPropertiesManager();
        Map<String, RuntimeConfigProperty> config = pmgr.getProperties();
        config.get("uploads.dir.maxsize").setValue("30000");
        config.get("uploads.types.forbid").setValue("");
        config.get("uploads.types.allowed").setValue("");
        config.get("uploads.enabled").setValue("true");
        pmgr.saveProperties(config);
        TestUtils.endSession(true);
    }

    /**
     * Test simple file save/delete.
     */
    @Test
    public void testFileCRUD() throws Exception {

        try {
            testUser = TestUtils.setupUser("FCMTest_userName1");
            testWeblog = TestUtils.setupWeblog("FCMTest_handle1", testUser);
            TestUtils.endSession(true);
        } catch (Exception ex) {
            log.error(ex);
        }

        // update roller properties to prepare for test
        PropertiesManager pmgr = WebloggerFactory.getWeblogger().getPropertiesManager();
        Map<String, RuntimeConfigProperty> config = pmgr.getProperties();
        config.get("uploads.enabled").setValue("true");
        config.get("uploads.types.allowed").setValue("opml");
        config.get("uploads.dir.maxsize").setValue("1.00");
        pmgr.saveProperties(config);
        TestUtils.endSession(true);

        /* NOTE: upload dir for unit tests is set in
        roller/testdata/roller-custom.properties */
        FileContentManager fmgr = WebloggerFactory.getWeblogger().getFileContentManager();

        // File should not exist initially
        try {
            FileContent fileContent = fmgr.getFileContent(testWeblog, "bookmarks-file-id");
            assertTrue(false, "Non-existant file retrieved without any exception");
        } catch (FileNotFoundException e) {
            assertTrue(true, "Exception thrown for non-existant file as expected");
        }

        // store a file
        InputStream is = getClass().getResourceAsStream("/bookmarks.opml");
        fmgr.saveFileContent(testWeblog, "bookmarks-file-id", is);

        // make sure file was stored successfully
        FileContent fileContent1 = fmgr.getFileContent(testWeblog, "bookmarks-file-id");
        assertEquals("bookmarks-file-id", fileContent1.getFileId());


        // delete file
        fmgr.deleteFile(testWeblog, "bookmarks-file-id");

        // File should not exist after delete
        try {
            FileContent fileContent = fmgr.getFileContent(testWeblog, "bookmarks-file-id");
            assertTrue(false, "Non-existant file retrieved without any exception");
        } catch (FileNotFoundException e) {
            assertTrue(true, "Exception thrown for non-existant file as expected");
        }

        TestUtils.endSession(true);
        TestUtils.teardownWeblog(testWeblog.getId());
        TestUtils.teardownUser(testUser.getUserName());
    }

    /**
     * Test FileContentManager.saveFile() checks.
     *
     * This should test all conditions where a save should fail.
     */
    @Test
    public void testCanSave() throws Exception {

        try {
            testUser = TestUtils.setupUser("FCMTest_userName2");
            testWeblog = TestUtils.setupWeblog("FCMTest_handle2", testUser);
            TestUtils.endSession(true);
        } catch (Exception ex) {
            log.error(ex);
        }

        FileContentManager fmgr = WebloggerFactory.getWeblogger().getFileContentManager();
        PropertiesManager pmgr = WebloggerFactory.getWeblogger().getPropertiesManager();
        Map<String, RuntimeConfigProperty> config = pmgr.getProperties();
        config.get("uploads.dir.maxsize").setValue("1.00");
        config.get("uploads.types.forbid").setValue("");
        config.get("uploads.types.allowed").setValue("");
        config.get("uploads.enabled").setValue("true");
        pmgr.saveProperties(config);
        TestUtils.endSession(true);

        config = pmgr.getProperties();
        config.get("uploads.dir.maxsize").setValue("1.00");
        pmgr.saveProperties(config);
        TestUtils.endSession(true);

        RollerMessages msgs = new RollerMessages();
        boolean canSave = fmgr.canSave(testWeblog, "test.gif", "text/plain", 2500000, msgs);
        assertFalse(canSave);

        config = pmgr.getProperties();
        config.get("uploads.types.forbid").setValue("gif");
        pmgr.saveProperties(config);
        TestUtils.endSession(true);

        // forbidden types check should fail
        msgs = new RollerMessages();
        fmgr.canSave(testWeblog, "test.gif", "text/plain", 10, msgs);
        assertFalse(canSave);


        config = pmgr.getProperties();
        config.get("uploads.enabled").setValue("false");
        pmgr.saveProperties(config);
        TestUtils.endSession(true);

        // uploads disabled should fail
        msgs = new RollerMessages();
        fmgr.canSave(testWeblog, "test.gif", "text/plain", 10, msgs);
        assertFalse(canSave);

        TestUtils.endSession(true);
        TestUtils.teardownWeblog(testWeblog.getId());
        TestUtils.teardownUser(testUser.getUserName());
    }

    /**
     * Responsive-image renditions (`<id>_480`, `<id>_480.webp`, ...) must not
     * count against a weblog's upload quota -- only what the user actually
     * uploaded should. Exercises the precise filename match directly against
     * FileContentManager rather than going through the whole image pipeline,
     * and proves the contrast case too: an ordinary uploaded file whose name
     * happens to contain underscores must still count.
     */
    @Test
    public void testRenditionsExcludedFromQuotaButUnderscoredUserFilesStillCount() throws Exception {

        // FileContentManagerImpl.deleteAllFiles() is an unimplemented no-op
        // (dead code -- nothing calls it) and weblog teardown does not clean
        // up on-disk uploads, so a fixed handle would accumulate real files
        // across repeated local `mvn test` runs against the same
        // target/test-classes tree and eventually trip the quota check for
        // reasons unrelated to this test. A per-run handle keeps this test's
        // directory pristine every time; the files it writes are also
        // deleted explicitly below for good hygiene.
        String handle = "FCMTest_handle3_" + UUIDGenerator.generateUUID().substring(0, 8);
        testUser = TestUtils.setupUser("FCMTest_userName3");
        testWeblog = TestUtils.setupWeblog(handle, testUser);
        TestUtils.endSession(true);

        PropertiesManager pmgr = WebloggerFactory.getWeblogger().getPropertiesManager();
        Map<String, RuntimeConfigProperty> config = pmgr.getProperties();
        config.get("uploads.enabled").setValue("true");
        config.get("uploads.types.allowed").setValue("");
        config.get("uploads.types.forbid").setValue("");
        // ~20KB quota: small enough that a single 40KB "rendition" would trip
        // it if (incorrectly) counted, but well above the 1KB "original".
        config.get("uploads.dir.maxsize").setValue("0.02");
        pmgr.saveProperties(config);
        TestUtils.endSession(true);

        FileContentManager fmgr = WebloggerFactory.getWeblogger().getFileContentManager();

        String id = UUIDGenerator.generateUUID();
        String underscoredUserFile = "user_photo_with_underscores.jpg";
        byte[] small = new byte[1000];
        byte[] big = new byte[40000];

        try {
            fmgr.saveFileContent(testWeblog, id, new ByteArrayInputStream(small));
            fmgr.saveFileContent(testWeblog, id + "_480", new ByteArrayInputStream(big));
            fmgr.saveFileContent(testWeblog, id + "_480.webp", new ByteArrayInputStream(big));
            fmgr.saveFileContent(testWeblog, id + "_sm", new ByteArrayInputStream(big));

            assertFalse(fmgr.overQuota(testWeblog),
                    "generated renditions must be excluded from the upload quota");

            // Contrast: a legitimate uploaded file with underscores in its name
            // (not the rendition convention: not a 36-char id prefix) must still
            // count toward quota.
            fmgr.saveFileContent(testWeblog, underscoredUserFile, new ByteArrayInputStream(big));

            assertTrue(fmgr.overQuota(testWeblog),
                    "a legitimately underscored user filename must still count toward quota");
        } finally {
            fmgr.deleteFile(testWeblog, id);
            fmgr.deleteFile(testWeblog, id + "_480");
            fmgr.deleteFile(testWeblog, id + "_480.webp");
            fmgr.deleteFile(testWeblog, id + "_sm");
            fmgr.deleteFile(testWeblog, underscoredUserFile);
        }

        TestUtils.endSession(true);
        TestUtils.teardownWeblog(testWeblog.getId());
        TestUtils.teardownUser(testUser.getUserName());
    }
}
