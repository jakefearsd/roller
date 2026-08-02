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

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;

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
     * Returns true when the ladder covers this content type, i.e. when a
     * {@code ?w=} rendition URL for such a file really serves a resized
     * image. For every other format (gif, bmp, ...) no rendition is ever
     * generated and the media-resource servlet silently falls back to the
     * full-resolution original at the same URL -- so callers that declare
     * the served image's dimensions (e.g. {@code og:image:width/height})
     * must check this before pointing at a rendition.
     */
    public static boolean isLadderEligible(String contentType) {
        return formatFor(contentType) != null;
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
     * Returns {@code image} rotated/flipped into its upright display
     * orientation for the given EXIF Orientation value (1-8, as returned by
     * {@link ExifSupport#readOrientation}).
     *
     * <p>Callers must apply this BEFORE anything derived from the raster --
     * stored width/height, the {@code _sm} thumbnail, the ladder renditions,
     * the blurhash. {@code ImageIO.read()} hands back the raw sensor raster
     * and the encoders here write pixel data with no metadata, so a portrait
     * phone photo (a landscape raster plus Orientation=6) would otherwise
     * produce sideways renditions while the original URL, which keeps its
     * EXIF, still displays upright -- a browser picking between them from a
     * srcset would show the same photo rotated or not depending on which
     * candidate it chose.
     *
     * <p>Orientations 5-8 transpose the image, so the returned image has
     * width and height swapped. Orientation 1 (and any value outside 1-8,
     * i.e. an unreadable tag) returns {@code image} itself: never rotate on
     * a guess. Null in, null out.
     */
    public static BufferedImage applyOrientation(BufferedImage image, int orientation) {
        if (image == null || orientation <= ExifSupport.ORIENTATION_NORMAL || orientation > 8) {
            return image;
        }

        int width = image.getWidth();
        int height = image.getHeight();
        boolean transposed = orientation >= 5;
        int destWidth = transposed ? height : width;
        int destHeight = transposed ? width : height;

        BufferedImage dest = new BufferedImage(destWidth, destHeight,
                image.getColorModel().hasAlpha() ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
        Graphics2D g2 = dest.createGraphics();
        try {
            // Nearest-neighbour: these are exact 90-degree rotations and
            // mirrors, so no resampling should occur at all.
            g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
            g2.drawImage(image, orientationTransform(orientation, width, height), null);
        } finally {
            g2.dispose();
        }
        return dest;
    }

    /**
     * The affine transform that maps the stored raster into display space for
     * each EXIF Orientation value: 2/4 mirror, 3 rotates 180, 5/7 transpose
     * and transverse, 6/8 rotate 90 degrees clockwise and counter-clockwise.
     */
    private static AffineTransform orientationTransform(int orientation, int width, int height) {
        AffineTransform t = new AffineTransform();
        switch (orientation) {
            case 2: // mirror horizontal
                t.scale(-1.0, 1.0);
                t.translate(-width, 0);
                break;
            case 3: // rotate 180
                t.translate(width, height);
                t.rotate(Math.PI);
                break;
            case 4: // mirror vertical
                t.scale(1.0, -1.0);
                t.translate(0, -height);
                break;
            case 5: // transpose (mirror along the main diagonal)
                t.rotate(-Math.PI / 2);
                t.scale(-1.0, 1.0);
                break;
            case 6: // rotate 90 clockwise
                t.translate(height, 0);
                t.rotate(Math.PI / 2);
                break;
            case 7: // transverse (mirror along the anti-diagonal)
                t.scale(-1.0, 1.0);
                t.translate(-height, 0);
                t.translate(0, width);
                t.rotate(3 * Math.PI / 2);
                break;
            case 8: // rotate 90 counter-clockwise
                t.translate(0, width);
                t.rotate(3 * Math.PI / 2);
                break;
            default:
                break;
        }
        return t;
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
                        .asBufferedImage();

                byte[] encoded = encodeToBytes(resized, formatName, JPEG_QUALITY);
                cmgr.saveFileContent(weblog, id + "_" + width, new ByteArrayInputStream(encoded));
            } catch (Exception e) {
                log.warn("Failed to generate " + width + "w rendition for media file " + id, e);
                continue;
            }

            if (CwebpEncoder.isAvailable()) {
                generateWebp(cmgr, weblog, id, width, resized, formatName);
            }
        }
    }

    /**
     * Encodes {@code image} as {@code formatName} bytes, honoring
     * {@code quality} for lossy formats.
     *
     * <p>{@code Thumbnails.outputQuality(...)} has NO effect when the resize
     * result is pulled out via {@code asBufferedImage()}: that call returns
     * raw pixel data without ever invoking Thumbnailator's own encoder, so
     * any {@code outputFormat}/{@code outputQuality} configured on the
     * builder is silently discarded (verified empirically against
     * thumbnailator 0.4.21 -- a JPEG written this way came out at the JDK's
     * default compression regardless of the requested quality). Encoding
     * therefore happens explicitly here, at the one place it actually
     * occurs, via a low-level {@link ImageWriter} with a compression
     * quality param.
     */
    static byte[] encodeToBytes(BufferedImage image, String formatName, float quality) throws IOException {
        if (!"jpg".equals(formatName)) {
            // PNG is lossless -- there is no quality knob to honor.
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ImageIO.write(image, formatName, baos);
            return baos.toByteArray();
        }

        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpg");
        if (!writers.hasNext()) {
            // Every standard JDK ships a JPEG writer; this is defensive only.
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ImageIO.write(image, formatName, baos);
            return baos.toByteArray();
        }

        ImageWriter writer = writers.next();
        try {
            ImageWriteParam param = writer.getDefaultWriteParam();
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(quality);

            BufferedImage rgb = image;
            if (image.getColorModel().hasAlpha()) {
                // JPEG has no alpha channel. formatFor() keeps jpeg/png
                // ladders separate so this path is never actually hit in
                // practice, but stay defensive rather than let the writer
                // throw on an ARGB source.
                rgb = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_RGB);
                Graphics2D g2 = rgb.createGraphics();
                g2.drawImage(image, 0, 0, null);
                g2.dispose();
            }

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            try (ImageOutputStream ios = ImageIO.createImageOutputStream(baos)) {
                writer.setOutput(ios);
                writer.write(null, new IIOImage(rgb, null, null), param);
            }
            return baos.toByteArray();
        } finally {
            writer.dispose();
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
