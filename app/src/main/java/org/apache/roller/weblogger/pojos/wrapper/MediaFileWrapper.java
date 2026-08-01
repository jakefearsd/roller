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

package org.apache.roller.weblogger.pojos.wrapper;

import java.sql.Timestamp;
import java.util.List;
import java.util.stream.Collectors;

import org.apache.roller.weblogger.business.RenditionSupport;
import org.apache.roller.weblogger.business.URLStrategy;
import org.apache.roller.weblogger.pojos.MediaFile;
import org.apache.roller.weblogger.pojos.MediaFileTag;
import org.apache.roller.weblogger.util.HTMLSanitizer;

/**
 * Pojo safety wrapper for MediaFile objects, adding srcset-ready accessors
 * for the responsive-image rendition ladder (see {@link RenditionSupport})
 * on top of the usual sanitize-on-the-way-out behavior of the other
 * wrappers.
 */
public final class MediaFileWrapper {

    // keep a reference to the wrapped pojo
    private final MediaFile pojo;

    // url strategy to use for any url building
    private final URLStrategy urlStrategy;

    // this is private so that we can force the use of the .wrap(pojo) method
    private MediaFileWrapper(MediaFile toWrap, URLStrategy strat) {
        this.pojo = toWrap;
        this.urlStrategy = strat;
    }

    // wrap the given pojo if it is not null
    public static MediaFileWrapper wrap(MediaFile toWrap, URLStrategy strat) {
        if (toWrap != null) {
            return new MediaFileWrapper(toWrap, strat);
        }
        return null;
    }

    public String getId() {
        return this.pojo.getId();
    }

    public String getName() {
        return HTMLSanitizer.conditionallySanitize(this.pojo.getName());
    }

    public String getDescription() {
        return HTMLSanitizer.conditionallySanitize(this.pojo.getDescription());
    }

    public String getContentType() {
        return this.pojo.getContentType();
    }

    public int getWidth() {
        return this.pojo.getWidth();
    }

    public int getHeight() {
        return this.pojo.getHeight();
    }

    public Timestamp getDateUploaded() {
        return this.pojo.getDateUploaded();
    }

    public Timestamp getLastUpdated() {
        return this.pojo.getLastUpdated();
    }

    public UserWrapper getCreator() {
        return UserWrapper.wrap(this.pojo.getCreator());
    }

    public List<MediaFileTag> getTags() {
        return this.pojo.getTags().stream()
                .sorted((a, b) -> a.getName().compareTo(b.getName()))
                .collect(Collectors.toList());
    }

    /** Permalink to the original, full-size file. */
    public String getPermalink() {
        return this.pojo.getPermalink();
    }

    /** Permalink to the admin/feed thumbnail (the {@code _sm} rendition). */
    public String getThumbnailURL() {
        return this.pojo.getThumbnailURL();
    }

    /**
     * BlurHash placeholder string for a CSS/JS blur-up effect while the real
     * image loads. Null if it was never encoded (upload predates the
     * rendition pipeline, or encoding failed).
     */
    public String getBlurhash() {
        return this.pojo.getBlurhash();
    }

    /**
     * URL for the {@code width}-wide rendition of this image, for use in a
     * {@code srcset}. Falls back to the original's permalink when
     * {@code width} is not one of {@link RenditionSupport#ladderWidths()} --
     * the servlet does the same fallback when the rendition itself is
     * missing (narrower than the original, or generation failed), so this
     * URL is always safe to emit even if that particular rung doesn't
     * actually exist on disk.
     */
    public String url(int width) {
        if (!RenditionSupport.ladderWidths().contains(width)) {
            return getPermalink();
        }
        return getPermalink() + (getPermalink().contains("?") ? "&" : "?") + "w=" + width;
    }

    /**
     * Same URL as {@link #url(int)}: WebP is served for this same URL via
     * {@code Accept}-header content negotiation (see
     * {@code MediaResourceServlet}) rather than a distinct path, so a
     * {@code <source type="image/webp">} element can point straight at it.
     * Kept as a separate accessor because a future serving change (e.g. an
     * explicit {@code ?fmt=webp}) should only require updating this method,
     * not every template that calls it.
     */
    public String webpUrl(int width) {
        return url(width);
    }

    // ---------------------------------------------------------- EXIF display

    public String getExifCamera() {
        return HTMLSanitizer.conditionallySanitize(this.pojo.getExifCamera());
    }

    public String getExifLens() {
        return HTMLSanitizer.conditionallySanitize(this.pojo.getExifLens());
    }

    public String getExifExposure() {
        return HTMLSanitizer.conditionallySanitize(this.pojo.getExifExposure());
    }

    public String getExifAperture() {
        return HTMLSanitizer.conditionallySanitize(this.pojo.getExifAperture());
    }

    public Integer getExifIso() {
        return this.pojo.getExifIso();
    }

    public String getExifFocalLength() {
        return HTMLSanitizer.conditionallySanitize(this.pojo.getExifFocalLength());
    }

    public Timestamp getExifTaken() {
        return this.pojo.getExifTaken();
    }

    /** Null whenever GPS was absent from the source image or stripped by {@code uploads.exif.stripGps}. */
    public Double getGpsLatitude() {
        return this.pojo.getGpsLatitude();
    }

    public Double getGpsLongitude() {
        return this.pojo.getGpsLongitude();
    }

    /**
     * This is a special method to access the original pojo. We don't really
     * want to do this, but it's necessary because some parts of the
     * rendering process still need the original pojo object.
     */
    public MediaFile getPojo() {
        return this.pojo;
    }
}
