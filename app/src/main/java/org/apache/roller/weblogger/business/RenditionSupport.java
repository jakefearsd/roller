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
import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import javax.imageio.ImageIO;

import net.coobird.thumbnailator.Thumbnails;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.roller.weblogger.pojos.MediaFile;
import org.apache.roller.weblogger.pojos.Weblog;

/**
 * Generates and enumerates the responsive-image "width ladder" renditions
 * for an uploaded image, using the same sibling-suffix filename convention
 * as the existing {@code <id>_sm} admin thumbnail: {@code <id>_480},
 * {@code <id>_960}, {@code <id>_1600}, {@code <id>_2400}, each optionally
 * paired with a {@code .webp} sibling when {@code cwebp} is available.
 *
 * <p>Hooked from the same two seams that already produce {@code <id>_sm}
 * (JPAMediaFileManagerImpl#persistNewMediaFile and #updateMediaFile with an
 * input stream) so every upload/replace path gets renditions "for free".
 * Unlike the existing thumbnail code, per-rendition failures are logged at
 * WARN with the media file id -- they are not silently swallowed -- but a
 * rendition failure never fails the surrounding upload; each width/format is
 * attempted independently so a single bad case can't block its siblings.
 */
public final class RenditionSupport {

    private static final Log log = LogFactory.getFactory().getInstance(RenditionSupport.class);

    /** The responsive width ladder, ascending. Widths >= the original image's width are skipped. */
    public static final List<Integer> LADDER_WIDTHS = List.of(480, 960, 1600, 2400);

    private static final Set<Integer> LADDER_WIDTHS_SET =
            new LinkedHashSet<>(LADDER_WIDTHS);

    /** JPEG encode quality used for both the JPEG ladder rendition and the cwebp source it feeds. */
    private static final float JPEG_QUALITY = 0.85f;

    /** cwebp "-q" quality (0-100). */
    private static final int WEBP_QUALITY = 82;

    /**
     * Matches on-disk rendition sibling files: a 36-character media file id
     * (hex digits and dashes, as produced by {@link org.apache.roller.util.UUIDGenerator})
     * followed by an underscore, the admin thumbnail suffix or a ladder
     * width, and an optional {@code .webp} extension. Deliberately precise
     * -- both {@code originalPath} values and legitimate user filenames may
     * contain underscores, and must NOT be mistaken for renditions.
     */
    private static final Pattern RENDITION_FILENAME = Pattern.compile(
            "^[a-f0-9-]{36}_(sm|480|960|1600|2400)(\\.webp)?$");

    private RenditionSupport() {
    }

    /** Returns true if {@code fileName} is a generated rendition sibling that should be excluded from upload quota. */
    public static boolean isRenditionFileName(String fileName) {
        return fileName != null && RENDITION_FILENAME.matcher(fileName).matches();
    }

    /** Returns the ladder widths in ascending order. */
    public static Set<Integer> ladderWidths() {
        return LADDER_WIDTHS_SET;
    }

    /**
     * Returns every rendition filename this media file COULD have on disk
     * (ladder width x with/without .webp). Used by delete sites: callers
     * attempt to delete each one and silently ignore the ones that were
     * never generated (narrower than the original, or cwebp unavailable at
     * generation time).
     */
    public static List<String> renditionFileIds(String mediaFileId) {
        List<String> ids = new ArrayList<>(LADDER_WIDTHS.size() * 2);
        for (int width : LADDER_WIDTHS) {
            ids.add(mediaFileId + "_" + width);
            ids.add(mediaFileId + "_" + width + ".webp");
        }
        return ids;
    }

    /**
     * Maps a MediaFile content type to the ImageIO/cwebp-source format name
     * that preserves its format family (jpeg to jpeg, png to png). Returns
     * null for formats the ladder does not cover (e.g. gif, bmp, webp
     * originals) -- callers should skip ladder generation entirely.
     */
    static String formatFor(String contentType) {
        if (contentType == null) {
            return null;
        }
        String type = contentType.toLowerCase();
        if (type.equals("image/jpeg") || type.equals("image/jpg")) {
            return "jpg";
        }
        if (type.equals("image/png")) {
            return "png";
        }
        return null;
    }

    /**
     * Generates the width ladder for {@code mediaFile} from the already
     * decoded {@code original} image, storing each rendition as a
     * sibling-suffix file via {@code cmgr}. Never throws: every failure
     * (unsupported format, resize error, disk error, cwebp failure) is
     * logged at WARN with the media file id and the affected step is
     * skipped so the upload itself is never at risk.
     */
    public static void generate(FileContentManager cmgr, MediaFile mediaFile, BufferedImage original) {
        String formatName = formatFor(mediaFile.getContentType());
        if (formatName == null) {
            // Format family not covered by the ladder (gif/bmp/webp/...); nothing to do.
            return;
        }

        Weblog weblog = mediaFile.getWeblog();
        String id = mediaFile.getId();
        int originalWidth = original.getWidth();

        for (int width : LADDER_WIDTHS) {
            if (width >= originalWidth) {
                // Never upscale; the ladder only holds renditions narrower than the source.
                continue;
            }

            BufferedImage resized;
            try {
                resized = Thumbnails.of(original)
                        .width(width)
                        .outputQuality(JPEG_QUALITY)
                        .asBufferedImage();

                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                ImageIO.write(resized, formatName, baos);
                cmgr.saveFileContent(weblog, id + "_" + width, new ByteArrayInputStream(baos.toByteArray()));
            } catch (Exception e) {
                log.warn("Failed to generate " + width + "w rendition for media file " + id, e);
                continue;
            }

            if (CwebpEncoder.isAvailable()) {
                generateWebp(cmgr, weblog, id, width, resized, formatName);
            }
        }
    }

    private static void generateWebp(FileContentManager cmgr, Weblog weblog, String id,
            int width, BufferedImage resized, String sourceFormat) {
        File tempInput = null;
        File tempOutput = null;
        try {
            tempInput = File.createTempFile("roller-rendition-", "." + sourceFormat);
            tempOutput = File.createTempFile("roller-rendition-", ".webp");
            ImageIO.write(resized, sourceFormat, tempInput);

            if (CwebpEncoder.encode(tempInput, tempOutput, WEBP_QUALITY)) {
                try (InputStream is = Files.newInputStream(tempOutput.toPath())) {
                    cmgr.saveFileContent(weblog, id + "_" + width + ".webp", is);
                }
            } else {
                log.warn("cwebp declined to encode the " + width + "w webp rendition for media file " + id);
            }
        } catch (Exception e) {
            log.warn("Failed to generate " + width + "w webp rendition for media file " + id, e);
        } finally {
            deleteQuietly(tempInput);
            deleteQuietly(tempOutput);
        }
    }

    private static void deleteQuietly(File file) {
        if (file != null && file.exists() && !file.delete()) {
            log.debug("Could not delete temp rendition file " + file);
        }
    }
}
