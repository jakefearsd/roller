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

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Map;

import javax.imageio.ImageIO;

import org.apache.roller.weblogger.TestUtils;
import org.apache.roller.weblogger.WebloggerException;
import org.apache.roller.weblogger.pojos.MediaFile;
import org.apache.roller.weblogger.pojos.MediaFileDirectory;
import org.apache.roller.weblogger.pojos.RuntimeConfigProperty;
import org.apache.roller.weblogger.pojos.User;
import org.apache.roller.weblogger.pojos.Weblog;
import org.apache.roller.weblogger.util.RollerMessages;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end tests for {@link MediaFileManager#cropMediaFile}: the crop must
 * re-encode the stored original and rebuild EVERYTHING derived from its
 * pixels -- stored dimensions, the admin thumbnail, the whole rendition
 * ladder (including deleting rungs the smaller image no longer clears), and
 * the blurhash -- while leaving the stored EXIF metadata fields alone.
 */
public class MediaFileCropTest {

    private User user;
    private Weblog weblog;
    private MediaFileManager mfMgr;
    private FileContentManager fcMgr;
    private MediaFileDirectory rootDirectory;

    @BeforeEach
    public void setUp() throws Exception {
        TestUtils.setupWeblogger();
        // Force the deterministic ImageIO-only path; webp siblings are
        // deleted alongside the raster ones either way.
        CwebpEncoder.setAvailableForTesting(false);

        Map<String, RuntimeConfigProperty> config = WebloggerFactory.getWeblogger()
                .getPropertiesManager().getProperties();
        config.get("uploads.enabled").setValue("true");

        user = TestUtils.setupUser("cropTestUser");
        weblog = TestUtils.setupWeblog("cropTestWeblog", user);
        mfMgr = WebloggerFactory.getWeblogger().getMediaFileManager();
        fcMgr = WebloggerFactory.getWeblogger().getFileContentManager();
        rootDirectory = mfMgr.getDefaultMediaFileDirectory(weblog);
        TestUtils.endSession(true);

        weblog = TestUtils.getManagedWebsite(weblog);
        rootDirectory = mfMgr.getMediaFileDirectory(rootDirectory.getId());
    }

    @AfterEach
    public void tearDown() throws Exception {
        CwebpEncoder.setAvailableForTesting(null);
        TestUtils.endSession(true);
        TestUtils.teardownWeblog(weblog.getId());
        TestUtils.teardownUser(user.getUserName());
        TestUtils.endSession(true);
    }

    // ------------------------------------------------------------- fixtures

    private MediaFile upload(String name, String contentType, java.io.InputStream is)
            throws Exception {
        MediaFile mediaFile = new MediaFile();
        mediaFile.setName(name);
        mediaFile.setDirectory(rootDirectory);
        mediaFile.setWeblog(weblog);
        mediaFile.setContentType(contentType);
        mediaFile.setInputStream(is);
        mfMgr.createMediaFile(weblog, mediaFile, new RollerMessages());
        TestUtils.endSession(true);
        return mediaFile;
    }

    private MediaFile uploadResource(String name, String resource) throws Exception {
        return upload(name, "image/jpeg", getClass().getResourceAsStream(resource));
    }

    private static byte[] generatedJpeg(int width, int height) throws Exception {
        BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(img, "jpg", baos);
        return baos.toByteArray();
    }

    private byte[] fileBytes(String fileId) throws Exception {
        return fcMgr.getFileContent(weblog, fileId).getInputStream().readAllBytes();
    }

    // ----------------------------------------------------------------- tests

    @Test
    public void cropReencodesTheOriginalAndRederivesDimensions() throws Exception {
        // hawk-exif.jpg is 500x373 with a Canon EOS R5 EXIF block.
        MediaFile mediaFile = uploadResource("crop-hawk.jpg", "/hawk-exif.jpg");
        String id = mediaFile.getId();

        byte[] originalBefore = fileBytes(id);
        byte[] thumbnailBefore = fileBytes(id + "_sm");
        byte[] renditionBefore = fileBytes(id + "_480");
        MediaFile before = mfMgr.getMediaFile(id);
        String blurhashBefore = before.getBlurhash();
        assertNotNull(blurhashBefore, "upload must have produced a blurhash");

        // Crop 500x373 down to 488x300 starting at (5,5): still wider than
        // the 480 rung, so that rendition must exist both before and after --
        // which is what lets us prove its BYTES changed, not just its
        // existence.
        weblog = TestUtils.getManagedWebsite(weblog);
        MediaFile managed = mfMgr.getMediaFile(id);
        mfMgr.cropMediaFile(weblog, managed, 5, 5, 488, 300);
        TestUtils.endSession(true);

        MediaFile cropped = mfMgr.getMediaFile(id);
        assertEquals(488, cropped.getWidth(), "stored width must be re-derived from the crop");
        assertEquals(300, cropped.getHeight());

        byte[] originalAfter = fileBytes(id);
        assertFalse(java.util.Arrays.equals(originalBefore, originalAfter),
                "the stored original must have been re-encoded");
        BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(originalAfter));
        assertEquals(488, decoded.getWidth(), "the re-encoded original must have the cropped raster");
        assertEquals(300, decoded.getHeight());
        assertEquals((long) originalAfter.length, cropped.getLength(),
                "the stored length must describe the re-encoded file");

        assertFalse(java.util.Arrays.equals(renditionBefore, fileBytes(id + "_480")),
                "the 480w rendition must be regenerated from the cropped pixels");
        assertFalse(java.util.Arrays.equals(thumbnailBefore, fileBytes(id + "_sm")),
                "the admin thumbnail must be regenerated from the cropped pixels");

        assertNotNull(cropped.getBlurhash(), "the blurhash must be recomputed");
        assertFalse(blurhashBefore.equals(cropped.getBlurhash()),
                "cropping away part of the photo must change the blurhash");

        // The pixels changed but the photograph's provenance did not: the
        // stored EXIF fields survive even though the re-encoded file itself
        // no longer carries an EXIF block.
        assertEquals("Canon EOS R5", cropped.getExifCamera(),
                "stored EXIF metadata must survive the crop");
    }

    @Test
    public void cropDeletesLadderRungsTheSmallerImageNoLongerClears() throws Exception {
        // 3000x2000: every rung (480/960/1600/2400) is generated on upload.
        MediaFile mediaFile = upload("crop-big.jpg", "image/jpeg",
                new ByteArrayInputStream(generatedJpeg(3000, 2000)));
        String id = mediaFile.getId();

        for (int width : RenditionSupport.LADDER_WIDTHS) {
            assertTrue(fileBytes(id + "_" + width).length > 0,
                    "upload of a 3000w image must generate the " + width + " rung");
        }

        weblog = TestUtils.getManagedWebsite(weblog);
        MediaFile managed = mfMgr.getMediaFile(id);
        mfMgr.cropMediaFile(weblog, managed, 0, 0, 800, 600);
        TestUtils.endSession(true);

        // Only the 480 rung is narrower than the 800w crop; the others must
        // be GONE from disk, or their ?w= URLs would keep serving the
        // uncropped pixels forever.
        assertTrue(fileBytes(id + "_480").length > 0);
        for (int width : new int[] {960, 1600, 2400}) {
            final String staleId = id + "_" + width;
            assertThrows(FileNotFoundException.class, () -> fcMgr.getFileContent(weblog, staleId),
                    "the stale " + width + " rung must be deleted, not left serving uncropped pixels");
        }

        MediaFile cropped = mfMgr.getMediaFile(id);
        assertEquals(800, cropped.getWidth());
        assertEquals(600, cropped.getHeight());
    }

    /**
     * The crop rectangle is measured on the DISPLAYED image. portrait-exif.jpg
     * is a 1000x600 raster tagged Orientation=6, i.e. the browser (and the
     * editor's crop screen) shows a 600x1000 upright portrait. A 550x900
     * selection is only valid against the upright image -- against the raw
     * raster it would be clamped to 550x600 -- so the resulting dimensions
     * prove orientation was composed in before the rectangle was interpreted.
     */
    @Test
    public void cropComposesExifOrientationBeforeInterpretingTheRectangle() throws Exception {
        MediaFile mediaFile = uploadResource("crop-portrait.jpg", "/portrait-exif.jpg");
        String id = mediaFile.getId();

        MediaFile stored = mfMgr.getMediaFile(id);
        assertEquals(600, stored.getWidth(), "sanity: upload stores the upright dimensions");
        assertEquals(1000, stored.getHeight());

        weblog = TestUtils.getManagedWebsite(weblog);
        MediaFile managed = mfMgr.getMediaFile(id);
        mfMgr.cropMediaFile(weblog, managed, 0, 0, 550, 900);
        TestUtils.endSession(true);

        MediaFile cropped = mfMgr.getMediaFile(id);
        assertEquals(550, cropped.getWidth());
        assertEquals(900, cropped.getHeight(),
                "a 900-tall crop only exists on the orientation-corrected image");

        // The re-encoded original has the upright pixels baked in and no EXIF
        // block, so it must decode to exactly the stored dimensions -- no
        // browser-side rotation left to apply.
        BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(fileBytes(id)));
        assertEquals(550, decoded.getWidth());
        assertEquals(900, decoded.getHeight());
    }

    @Test
    public void cropClampsAnOvershootingRectangleToTheImage() throws Exception {
        MediaFile mediaFile = uploadResource("crop-overshoot.jpg", "/hawk-exif.jpg");
        String id = mediaFile.getId();

        weblog = TestUtils.getManagedWebsite(weblog);
        MediaFile managed = mfMgr.getMediaFile(id);
        // browser rounding overshoot: a few pixels past every edge
        mfMgr.cropMediaFile(weblog, managed, -3, -2, 510, 380);
        TestUtils.endSession(true);

        MediaFile cropped = mfMgr.getMediaFile(id);
        assertEquals(500, cropped.getWidth(), "overshoot must clamp to the full image");
        assertEquals(373, cropped.getHeight());
    }

    @Test
    public void cropRemapsTheFocalPointIntoTheCroppedCoordinateSpace() throws Exception {
        MediaFile mediaFile = uploadResource("crop-focal.jpg", "/hawk-exif.jpg");
        String id = mediaFile.getId();

        weblog = TestUtils.getManagedWebsite(weblog);
        MediaFile managed = mfMgr.getMediaFile(id);
        managed.setFocalX(0.5);   // 250px of 500
        managed.setFocalY(0.5);   // 186.5px of 373
        mfMgr.updateMediaFile(weblog, managed);
        TestUtils.endSession(true);

        weblog = TestUtils.getManagedWebsite(weblog);
        managed = mfMgr.getMediaFile(id);
        // crop to the left half: x 0..250 -- the focal pixel lands on the
        // right edge of the new frame
        mfMgr.cropMediaFile(weblog, managed, 0, 0, 250, 373);
        TestUtils.endSession(true);

        MediaFile cropped = mfMgr.getMediaFile(id);
        assertEquals(1.0, cropped.getFocalX(), 0.001,
                "a focal pixel at the crop's right edge must remap to x=1.0");
        assertEquals(0.5, cropped.getFocalY(), 0.001,
                "the vertical coordinate was untouched by this crop");
    }

    @Test
    public void cropRejectsFormatsWithoutAReencodePath() throws Exception {
        BufferedImage img = new BufferedImage(300, 200, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(img, "gif", baos);
        MediaFile gif = upload("crop-me.gif", "image/gif",
                new ByteArrayInputStream(baos.toByteArray()));

        weblog = TestUtils.getManagedWebsite(weblog);
        MediaFile managed = mfMgr.getMediaFile(gif.getId());
        assertThrows(WebloggerException.class,
                () -> mfMgr.cropMediaFile(weblog, managed, 0, 0, 100, 100),
                "gif has no ladder/re-encode path and must be refused, not silently mangled");
    }

    @Test
    public void cropRejectsARectangleOutsideTheImage() throws Exception {
        MediaFile mediaFile = uploadResource("crop-outside.jpg", "/hawk-exif.jpg");

        weblog = TestUtils.getManagedWebsite(weblog);
        MediaFile managed = mfMgr.getMediaFile(mediaFile.getId());
        assertThrows(WebloggerException.class,
                () -> mfMgr.cropMediaFile(weblog, managed, 600, 400, 100, 100));

        MediaFile untouched = mfMgr.getMediaFile(mediaFile.getId());
        assertEquals(500, untouched.getWidth(), "a rejected crop must not change anything");
        assertEquals(373, untouched.getHeight());
    }
}
