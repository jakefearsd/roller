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
package org.apache.roller.weblogger.business.shortcodes;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.roller.weblogger.business.WebloggerFactory;
import org.apache.roller.weblogger.pojos.MediaFile;
import org.apache.roller.weblogger.pojos.MediaFileDirectory;
import org.apache.roller.weblogger.pojos.Weblog;
import org.apache.roller.weblogger.pojos.WeblogEntry;
import org.apache.roller.weblogger.pojos.wrapper.MediaFileWrapper;
import org.apache.roller.weblogger.util.HTMLSanitizer;

/**
 * The built-in {@code [gallery dir="Name" row="260" max="0"]} shortcode:
 * renders every image in the entry's weblog's named media directory as a
 * justified grid ({@code <div class="jgrid">} of {@code <figure>}s packed by
 * the flex-grow CSS in the {@code #showGalleryGridStyles} theme macro).
 *
 * <p>Each figure carries the image's aspect ratio as a CSS custom property
 * ({@code style="--ar:1.3405;"}) -- verified to survive the
 * {@link HTMLSanitizer} style re-parse by
 * {@code HTMLSanitizerTest.GalleryGridMarkup} -- and wraps the {@code <img>}
 * in an anchor to the full-size original for the lightbox (Wave 2 T4):
 * {@code data-pswp-width}/{@code data-pswp-height} full-size dimensions,
 * {@code data-caption}, {@code data-exif-*} overlay payload, and
 * {@code data-blurhash}, all double-quoted and null-skipped so the sanitizer
 * keeps them and the lightbox never sees empty values.
 *
 * <p>Ordering is the curated block first -- {@code sortOrder} ascending with
 * ties broken by name -- then the never-ordered files ({@code sortOrder}
 * null) by name, matching the contract documented on
 * {@link MediaFile#getSortOrder()}.
 *
 * <p>Returns null -- the expander's "leave the shortcode text exactly as
 * written" failure signal -- when the {@code dir} attribute is missing, the
 * directory does not exist, it is {@linkplain MediaFileDirectory#isPrivate()
 * private} (share-page rendering is a separate, gated path), or it contains
 * no image files.
 */
public class GalleryShortcode implements ShortcodeHandler {

    private static final Log log = LogFactory.getLog(GalleryShortcode.class);

    /** Desktop target row height (px) when no {@code row} attribute is given; mirrored in the grid CSS. */
    static final int DEFAULT_ROW_HEIGHT = 260;

    /** Mobile row height (px) from the grid CSS's 640px breakpoint, used only for the sizes hint. */
    private static final int MOBILE_ROW_HEIGHT = 160;

    @Override
    public String getName() {
        return "gallery";
    }

    @Override
    public String render(Map<String, String> attributes, String body, WeblogEntry entry) {
        String directoryName = attributes.get("dir");
        if (StringUtils.isBlank(directoryName)) {
            log.debug("[gallery] shortcode without a dir attribute; leaving it as written");
            return null;
        }
        Weblog weblog = entry == null ? null : entry.getWebsite();
        if (weblog == null) {
            return null;
        }

        List<MediaFileWrapper> images;
        try {
            MediaFileDirectory directory = WebloggerFactory.getWeblogger()
                    .getMediaFileManager().getMediaFileDirectoryByName(weblog, directoryName);
            if (directory == null) {
                log.debug("[gallery] shortcode dir \"" + directoryName
                        + "\" does not exist in weblog " + weblog.getHandle());
                return null;
            }
            if (directory.isPrivate()) {
                // A private directory is only reachable through its share
                // link (Wave 2 T5); the normal render path must not leak it.
                log.debug("[gallery] shortcode dir \"" + directoryName
                        + "\" is private; not rendering it inline");
                return null;
            }
            images = directory.getMediaFiles().stream()
                    .filter(MediaFile::isImageFile)
                    .sorted(GALLERY_ORDER)
                    .map(mf -> MediaFileWrapper.wrap(mf,
                            WebloggerFactory.getWeblogger().getUrlStrategy()))
                    .toList();
        } catch (Exception e) {
            log.warn("[gallery] shortcode could not resolve directory \""
                    + directoryName + "\"", e);
            return null;
        }

        int max = parsePositiveInt(attributes.get("max"), 0);
        if (max > 0 && images.size() > max) {
            images = images.subList(0, max);
        }
        if (images.isEmpty()) {
            log.debug("[gallery] shortcode dir \"" + directoryName
                    + "\" holds no image files; leaving it as written");
            return null;
        }

        int rowHeight = parsePositiveInt(attributes.get("row"), 0);
        StringBuilder html = new StringBuilder(512 * images.size());
        html.append("<div class=\"jgrid\"");
        if (rowHeight > 0 && rowHeight != DEFAULT_ROW_HEIGHT) {
            html.append(" style=\"--row-h:").append(rowHeight).append("px;\"");
        }
        html.append(">\n");
        for (MediaFileWrapper image : images) {
            appendFigure(html, image, rowHeight > 0 ? rowHeight : DEFAULT_ROW_HEIGHT);
        }
        html.append("</div>");
        return html.toString();
    }

    /**
     * Curated block first ({@code sortOrder} ascending, name breaking ties),
     * then the never-ordered rest by name -- see {@link MediaFile#getSortOrder()}.
     */
    private static final Comparator<MediaFile> GALLERY_ORDER = Comparator
            .comparing(MediaFile::getSortOrder, Comparator.nullsLast(Comparator.naturalOrder()))
            .thenComparing(MediaFile::getName, Comparator.nullsLast(Comparator.naturalOrder()));

    private static void appendFigure(StringBuilder html, MediaFileWrapper image,
            int rowHeight) {
        boolean knownDimensions = image.getWidth() > 0 && image.getHeight() > 0;
        double aspectRatio = knownDimensions
                ? (double) image.getWidth() / image.getHeight() : 0;

        html.append("<figure");
        if (knownDimensions) {
            // The grid packs rows with flex-grow: var(--ar); the trailing
            // semicolon keeps the value byte-identical through the
            // sanitizer's style re-parse (which appends one anyway).
            html.append(" style=\"--ar:")
                    .append(String.format(Locale.ROOT, "%.4f", aspectRatio))
                    .append(";\"");
        }
        html.append(">\n");

        // ---- lightbox anchor: absolute original + data payload (T4 reads these)
        html.append("<a href=\"").append(image.getPermalink()).append('"');
        if (knownDimensions) {
            html.append(" data-pswp-width=\"").append(image.getWidth())
                    .append("\" data-pswp-height=\"").append(image.getHeight()).append('"');
        }
        String caption = StringUtils.trimToNull(image.getDescription());
        appendDataAttribute(html, "data-caption", escape(caption));
        appendDataAttribute(html, "data-exif-camera", escape(image.getExifCamera()));
        appendDataAttribute(html, "data-exif-lens", escape(image.getExifLens()));
        appendDataAttribute(html, "data-exif-exposure", escape(image.getExifExposure()));
        appendDataAttribute(html, "data-exif-aperture", escape(image.getExifAperture()));
        appendDataAttribute(html, "data-exif-iso",
                image.getExifIso() == null ? null : image.getExifIso().toString());
        appendDataAttribute(html, "data-exif-focal", escape(image.getExifFocalLength()));
        appendDataAttribute(html, "data-blurhash", escape(image.getBlurhash()));
        html.append(">\n");

        // ---- the grid image itself
        html.append("<img src=\"").append(image.getPermalink()).append('"');
        String srcset = image.getSrcset();
        if (srcset != null) {
            // sizes = the figure's flex-basis (--ar * row height): what the
            // grid asks the row to give this image before stretch.
            html.append(" srcset=\"").append(srcset)
                    .append("\" sizes=\"(max-width: 640px) ")
                    .append(Math.round(aspectRatio * MOBILE_ROW_HEIGHT))
                    .append("px, ").append(Math.round(aspectRatio * rowHeight))
                    .append("px\"");
        }
        if (knownDimensions) {
            html.append(" width=\"").append(image.getWidth())
                    .append("\" height=\"").append(image.getHeight()).append('"');
        }
        html.append(" alt=\"").append(escape(StringUtils.defaultString(image.getName())))
                .append('"');
        html.append(" loading=\"lazy\" decoding=\"async\"");
        if (StringUtils.isNotBlank(image.getBlurhash())) {
            html.append(" data-blurhash=\"").append(escape(image.getBlurhash())).append('"');
            String averageColor = image.getAverageColor();
            if (averageColor != null) {
                // JS-free placeholder while the pixels arrive, exactly as the
                // [image] shortcode does it.
                html.append(" style=\"background-color:").append(averageColor).append('"');
            }
        }
        html.append(">\n</a>\n");

        if (caption != null) {
            html.append("<figcaption>").append(escape(caption)).append("</figcaption>\n");
        }
        html.append("</figure>\n");
    }

    private static void appendDataAttribute(StringBuilder html, String name, String value) {
        if (StringUtils.isNotBlank(value)) {
            html.append(' ').append(name).append("=\"").append(value).append('"');
        }
    }

    private static int parsePositiveInt(String value, int fallback) {
        if (StringUtils.isBlank(value)) {
            return fallback;
        }
        try {
            int parsed = Integer.parseInt(value.trim());
            return parsed > 0 ? parsed : fallback;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static String escape(String value) {
        return value == null ? null : HTMLSanitizer.htmlEncodeApexesAndTags(value);
    }
}
