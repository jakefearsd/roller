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

import java.util.Map;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.roller.weblogger.business.RenditionSupport;
import org.apache.roller.weblogger.business.WebloggerFactory;
import org.apache.roller.weblogger.pojos.MediaFile;
import org.apache.roller.weblogger.pojos.WeblogEntry;
import org.apache.roller.weblogger.pojos.wrapper.MediaFileWrapper;
import org.apache.roller.weblogger.util.HTMLSanitizer;

/**
 * The built-in {@code [image id=<mediaFileId> caption="..." alt="..."]}
 * shortcode: renders an uploaded image as a responsive
 * {@code <figure><picture>} block whose {@code srcset} climbs the
 * {@link RenditionSupport} width ladder ({@code ?w=} URLs, WebP negotiated
 * by the media servlet via the Accept header on those same URLs).
 *
 * <p>A body ({@code [image id=x]caption markup[/image]}) is used as the
 * caption verbatim -- it is entry HTML like any other and flows through the
 * downstream sanitizer -- while a {@code caption} attribute is escaped.
 * When the media file cannot be resolved the handler returns null so the
 * expander leaves the shortcode text visible to the author.
 */
public class ImageShortcode implements ShortcodeHandler {

    private static final Log log = LogFactory.getLog(ImageShortcode.class);

    /** The browser hint for how wide the image renders; themes show entry images full-column. */
    private static final String SIZES = "100vw";

    @Override
    public String getName() {
        return "image";
    }

    @Override
    public String render(Map<String, String> attributes, String body, WeblogEntry entry) {
        String id = attributes.get("id");
        if (StringUtils.isBlank(id)) {
            log.debug("[image] shortcode without an id attribute; leaving it as written");
            return null;
        }

        MediaFileWrapper media;
        try {
            MediaFile mediaFile = WebloggerFactory.getWeblogger()
                    .getMediaFileManager().getMediaFile(id);
            if (mediaFile == null || !mediaFile.isImageFile()) {
                log.debug("[image] shortcode id " + id + " is not an image media file");
                return null;
            }
            media = MediaFileWrapper.wrap(mediaFile,
                    WebloggerFactory.getWeblogger().getUrlStrategy());
        } catch (Exception e) {
            log.warn("[image] shortcode could not resolve media file " + id, e);
            return null;
        }

        // srcset: every ladder rung narrower than the original, then the
        // original itself as the largest candidate. Files that predate the
        // width metadata (width <= 0) get a plain <img>: we cannot know which
        // rungs exist, and an honest src always renders.
        String srcset = buildSrcset(media);

        StringBuilder html = new StringBuilder(256);
        html.append("<figure class=\"shortcode-image\">\n<picture>\n");
        if (srcset != null) {
            // WebP is negotiated on the same URLs via the Accept header, so
            // this source just gives webp-capable browsers an early pick.
            html.append("<source type=\"image/webp\" srcset=\"").append(srcset)
                    .append("\" sizes=\"").append(SIZES).append("\">\n");
        }

        html.append("<img src=\"").append(media.getPermalink()).append('"');
        if (srcset != null) {
            html.append(" srcset=\"").append(srcset)
                    .append("\" sizes=\"").append(SIZES).append('"');
        }
        if (media.getWidth() > 0 && media.getHeight() > 0) {
            html.append(" width=\"").append(media.getWidth())
                    .append("\" height=\"").append(media.getHeight()).append('"');
        }
        String alt = attributes.containsKey("alt") ? attributes.get("alt") : media.getName();
        html.append(" alt=\"").append(escape(StringUtils.defaultString(alt))).append('"');
        html.append(" loading=\"lazy\" decoding=\"async\"");
        if (StringUtils.isNotBlank(media.getBlurhash())) {
            html.append(" data-blurhash=\"").append(escape(media.getBlurhash())).append('"');
        }
        html.append(">\n</picture>\n");

        String caption = StringUtils.isNotBlank(body)
                ? body.trim()
                : escape(StringUtils.trimToNull(attributes.get("caption")));
        if (caption != null) {
            html.append("<figcaption>").append(caption).append("</figcaption>\n");
        }

        html.append("</figure>");
        return html.toString();
    }

    private static String buildSrcset(MediaFileWrapper media) {
        int originalWidth = media.getWidth();
        if (originalWidth <= 0) {
            return null;
        }
        StringBuilder srcset = new StringBuilder();
        for (int width : RenditionSupport.LADDER_WIDTHS) {
            if (width >= originalWidth) {
                break;
            }
            srcset.append(media.url(width)).append(' ').append(width).append("w, ");
        }
        srcset.append(media.getPermalink()).append(' ').append(originalWidth).append('w');
        return srcset.toString();
    }

    private static String escape(String value) {
        return value == null ? null : HTMLSanitizer.htmlEncodeApexesAndTags(value);
    }
}
