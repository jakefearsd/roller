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

import org.apache.commons.lang3.StringUtils;
import org.apache.roller.weblogger.business.RenditionSupport;
import org.apache.roller.weblogger.pojos.MediaFile;
import org.apache.roller.weblogger.pojos.wrapper.MediaFileWrapper;
import org.apache.roller.weblogger.util.HTMLSanitizer;

/**
 * The single emitter of justified-grid gallery markup: a {@code <div
 * class="jgrid">} of {@code <figure>}s carrying the aspect-ratio custom
 * property, the lightbox anchor with its {@code data-*} payload, and the
 * responsive {@code <img>}. Two callers share it so their output cannot
 * drift: the inline {@link GalleryShortcode} (public directories, media URLs
 * from {@link MediaFileWrapper#getPermalink()}) and the share-page renderer
 * ({@code ShareController}, private directories, media URLs scoped to the
 * share token so the bytes stay behind the share gate).
 *
 * <p>The markup contract -- what each attribute is for, and why every value
 * is double-quoted and null-skipped -- is documented on
 * {@link GalleryShortcode}; the grid CSS lives in the
 * {@code #showGalleryGridStyles} macro in
 * {@code WEB-INF/velocity/weblog.vm}.
 */
public final class GalleryMarkup {

    /** Desktop target row height (px) when none is given; mirrored in the grid CSS. */
    public static final int DEFAULT_ROW_HEIGHT = 260;

    /** Mobile row height (px) from the grid CSS's 640px breakpoint, used only for the sizes hint. */
    private static final int MOBILE_ROW_HEIGHT = 160;

    /**
     * Where a gallery image's URLs come from. The grid needs two: the
     * full-size original (anchor href and {@code <img src>}) and the
     * {@code ?w=} rendition rungs for the {@code srcset}.
     */
    public interface ImageUrls {

        /** Absolute URL of the full-size original. */
        String original(MediaFileWrapper image);

        /** Absolute URL of the {@code width}-wide rendition. */
        String rendition(MediaFileWrapper image, int width);
    }

    /**
     * The normal public URL space: the media-resource permalink and its
     * {@code ?w=} variants, exactly what {@link MediaFileWrapper#getSrcset()}
     * emits.
     */
    public static final ImageUrls PERMALINKS = new ImageUrls() {
        @Override
        public String original(MediaFileWrapper image) {
            return image.getPermalink();
        }

        @Override
        public String rendition(MediaFileWrapper image, int width) {
            return image.url(width);
        }
    };

    /**
     * Gallery display order: the curated block first ({@code sortOrder}
     * ascending, name breaking ties), then the never-ordered rest by name --
     * see {@link MediaFile#getSortOrder()}.
     */
    public static final Comparator<MediaFile> GALLERY_ORDER = Comparator
            .comparing(MediaFile::getSortOrder, Comparator.nullsLast(Comparator.naturalOrder()))
            .thenComparing(MediaFile::getName, Comparator.nullsLast(Comparator.naturalOrder()));

    private GalleryMarkup() {
        // static use only
    }

    /**
     * The full grid for the given (already filtered and ordered) images.
     *
     * @param rowHeight explicit target row height in px, or {@code 0} for
     *                  {@link #DEFAULT_ROW_HEIGHT}
     */
    public static String grid(List<MediaFileWrapper> images, int rowHeight, ImageUrls urls) {
        StringBuilder html = new StringBuilder(512 * images.size());
        html.append("<div class=\"jgrid\"");
        if (rowHeight > 0 && rowHeight != DEFAULT_ROW_HEIGHT) {
            html.append(" style=\"--row-h:").append(rowHeight).append("px;\"");
        }
        html.append(">\n");
        for (MediaFileWrapper image : images) {
            appendFigure(html, image, rowHeight > 0 ? rowHeight : DEFAULT_ROW_HEIGHT, urls);
        }
        html.append("</div>");
        return html.toString();
    }

    private static void appendFigure(StringBuilder html, MediaFileWrapper image,
            int rowHeight, ImageUrls urls) {
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
        html.append("<a href=\"").append(urls.original(image)).append('"');
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
        html.append("<img src=\"").append(urls.original(image)).append('"');
        String srcset = srcset(image, urls);
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

    /**
     * The {@code srcset} value for the image through the given URL space, or
     * null when the image is not rendition-eligible or has no stored width --
     * the same gating (and, for {@link #PERMALINKS}, the same bytes) as
     * {@link MediaFileWrapper#getSrcset()}.
     */
    private static String srcset(MediaFileWrapper image, ImageUrls urls) {
        int originalWidth = image.getWidth();
        if (originalWidth <= 0 || !image.isRenditionEligible()) {
            return null;
        }
        StringBuilder srcset = new StringBuilder();
        for (int width : RenditionSupport.LADDER_WIDTHS) {
            if (width >= originalWidth) {
                break;
            }
            srcset.append(urls.rendition(image, width)).append(' ').append(width).append("w, ");
        }
        srcset.append(urls.original(image)).append(' ').append(originalWidth).append('w');
        return srcset.toString();
    }

    private static void appendDataAttribute(StringBuilder html, String name, String value) {
        if (StringUtils.isNotBlank(value)) {
            html.append(' ').append(name).append("=\"").append(value).append('"');
        }
    }

    private static String escape(String value) {
        return value == null ? null : HTMLSanitizer.htmlEncodeApexesAndTags(value);
    }
}
