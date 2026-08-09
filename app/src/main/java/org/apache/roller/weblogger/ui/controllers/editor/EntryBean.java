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

package org.apache.roller.weblogger.ui.controllers.editor;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.commons.text.StringEscapeUtils;
import org.apache.roller.weblogger.WebloggerException;
import org.apache.roller.weblogger.business.WeblogEntryManager;
import org.apache.roller.weblogger.business.WebloggerFactory;
import org.apache.roller.weblogger.pojos.CommentSearchCriteria;
import org.apache.roller.weblogger.pojos.JsonLdType;
import org.apache.roller.weblogger.pojos.WeblogCategory;
import org.apache.roller.weblogger.pojos.WeblogEntry;
import org.apache.roller.weblogger.pojos.WeblogEntry.PubStatus;
import org.apache.roller.weblogger.util.Utilities;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.TimeZone;


/**
 * Bean for managing entry data.
 */
public class EntryBean {
    
    private static final Log log = LogFactory.getLog(EntryBean.class);
    
    private String id = null;
    private String title = null;
    private String locale = null;
    private String categoryId = null;
    private String tagsString = null;
    private String summary = null;
    private String text = null;
    private String status = null;

    private String pubTimeLocal = null;
    private boolean allowComments = false;
    private Integer commentDays = 0;
    private boolean pinnedToMain = false;
    private String searchDescription = null;

    private int commentCount = 0;

    // Wave 1 media & SEO foundation -- no editor UI yet, plumbing only
    private String featuredImageId = null;
    private String metaTitle = null;
    private String ogImageId = null;
    private String canonicalUrl = null;
    private boolean noindex = false;

    // Wave 3 travel structured data, edited in the SEO card.
    // jsonLdType holds a JsonLdType enum name, like status holds a PubStatus
    // name; the timestamps are carried as-is and the form binds them through
    // the eventStartLocal/eventEndLocal datetime-local accessors below.
    private String jsonLdType = null;
    private Double geoLatitude = null;
    private Double geoLongitude = null;
    private Timestamp eventStart = null;
    private Timestamp eventEnd = null;
    private String eventLocation = null;
    
    
    public String getId() {
        return this.id;
    }
    
    public void setId( String id ) {
        this.id = id;
    }
    
    public String getTitle() {
        return this.title;
    }
    
    public void setTitle( String title ) {
        this.title = title;
    }
    
    public String getSummary() {
        return this.summary;
    }
    
    public void setSummary( String summary ) {
        this.summary = summary;
    }
    
    public String getText() {
        return this.text;
    }
    
    public void setText( String text ) {
        this.text = text;
    }
    
    public String getStatus() {
        return this.status;
    }
    
    public void setStatus( String status ) {
        this.status = status;
    }
    
    public String getLocale() {
        return this.locale;
    }
    
    public void setLocale( String locale ) {
        this.locale = locale;
    }
    
    public String getTagsAsString() {
        return this.tagsString;
    }
    
    public void setTagsAsString( String tagsAsString ) {
        this.tagsString = tagsAsString;
    }
    
    public String getCategoryId() {
        return categoryId;
    }
    
    public void setCategoryId(String categoryId) {
        this.categoryId = categoryId;
    }
    

    public String getPubTimeLocal() {
        return pubTimeLocal;
    }

    public void setPubTimeLocal(String pubTimeLocal) {
        this.pubTimeLocal = pubTimeLocal;
    }

    public boolean getAllowComments() {
        return this.allowComments;
    }
    
    public void setAllowComments( boolean allowComments ) {
        this.allowComments = allowComments;
    }
    
    public Integer getCommentDays() {
        return this.commentDays;
    }
    
    public void setCommentDays(Integer commentDays) {
        this.commentDays = commentDays;
        if (commentDays == -1) {
            allowComments = false;
        }
    }
    
    public int getCommentCount() {
        return commentCount;
    }

    public void setCommentCount(int commentCount) {
        this.commentCount = commentCount;
    }

    public boolean getPinnedToMain() {
        return this.pinnedToMain;
    }

    public void setPinnedToMain( boolean pinnedToMain ) {
        this.pinnedToMain = pinnedToMain;
    }

    public String getSearchDescription() {
        return searchDescription;
    }
    
    public void setSearchDescription(String searchDescription) {
        this.searchDescription = searchDescription;
    }

    public String getFeaturedImageId() {
        return featuredImageId;
    }

    public void setFeaturedImageId(String featuredImageId) {
        this.featuredImageId = featuredImageId;
    }

    public String getMetaTitle() {
        return metaTitle;
    }

    public void setMetaTitle(String metaTitle) {
        this.metaTitle = metaTitle;
    }

    public String getOgImageId() {
        return ogImageId;
    }

    public void setOgImageId(String ogImageId) {
        this.ogImageId = ogImageId;
    }

    public String getCanonicalUrl() {
        return canonicalUrl;
    }

    public void setCanonicalUrl(String canonicalUrl) {
        this.canonicalUrl = canonicalUrl;
    }

    public boolean getNoindex() {
        return noindex;
    }

    public void setNoindex(boolean noindex) {
        this.noindex = noindex;
    }

    public String getJsonLdType() {
        return jsonLdType;
    }

    public void setJsonLdType(String jsonLdType) {
        this.jsonLdType = jsonLdType;
    }

    public Double getGeoLatitude() {
        return geoLatitude;
    }

    public void setGeoLatitude(Double geoLatitude) {
        this.geoLatitude = geoLatitude;
    }

    public Double getGeoLongitude() {
        return geoLongitude;
    }

    public void setGeoLongitude(Double geoLongitude) {
        this.geoLongitude = geoLongitude;
    }

    public Timestamp getEventStart() {
        return eventStart;
    }

    public void setEventStart(Timestamp eventStart) {
        this.eventStart = eventStart;
    }

    public Timestamp getEventEnd() {
        return eventEnd;
    }

    public void setEventEnd(Timestamp eventEnd) {
        this.eventEnd = eventEnd;
    }

    public String getEventLocation() {
        return eventLocation;
    }

    public void setEventLocation(String eventLocation) {
        this.eventLocation = eventLocation;
    }

    /**
     * {@link #getEventStart()} as an {@code <input type="datetime-local">}
     * value, or "" when unset. This -- not the Timestamp property -- is what
     * the SEO card binds, because the two formats do not meet: a
     * datetime-local field submits {@code 2026-08-02T19:30} while Spring's
     * default String-to-Timestamp conversion goes through
     * {@code Timestamp.valueOf}, which demands {@code 2026-08-02 19:30:00} and
     * throws on the browser's format.
     */
    public String getEventStartLocal() {
        return formatLocalInput(eventStart);
    }

    public void setEventStartLocal(String value) {
        this.eventStart = parseLocalInput(value);
    }

    /** {@link #getEventEndLocal} counterpart of {@link #getEventStartLocal}. */
    public String getEventEndLocal() {
        return formatLocalInput(eventEnd);
    }

    public void setEventEndLocal(String value) {
        this.eventEnd = parseLocalInput(value);
    }

    /**
     * The datetime-local wire format, to the minute. Written out rather than
     * reusing {@code ISO_LOCAL_DATE_TIME}, which always formats seconds and
     * would hand the browser a value it re-submits differently; ROOT so a
     * non-Gregorian default locale cannot shift the year.
     */
    private static final DateTimeFormatter DATETIME_LOCAL =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm", Locale.ROOT);

    /**
     * A Timestamp as {@code yyyy-MM-ddTHH:mm}, the shape a datetime-local
     * input round-trips; "" for null so the field renders empty rather than
     * with the word "null".
     */
    private static String formatLocalInput(Timestamp value) {
        if (value == null) {
            return "";
        }
        return value.toLocalDateTime().format(DATETIME_LOCAL);
    }

    /**
     * A datetime-local value as a Timestamp in the server's default zone (the
     * field is a wall-clock instant with no offset, and so is the column).
     * Blank means "cleared". An unparseable value also clears rather than
     * throwing: the input type keeps browsers honest, and a binding exception
     * on a hand-crafted POST would lose the author's whole entry.
     */
    private static Timestamp parseLocalInput(String value) {
        if (StringUtils.isBlank(value)) {
            return null;
        }
        try {
            return Timestamp.valueOf(LocalDateTime.parse(value.trim()));
        } catch (DateTimeParseException e) {
            log.debug("Ignoring unparseable event date '" + value + "'");
            return null;
        }
    }

    /**
     * The final pubtime of the entry: {@link #getPubTimeLocal()} -- what an
     * {@code <input type="datetime-local">} submits -- parsed as wall-clock
     * time in {@code timezone}. Callers pass
     * {@code getActionWeblog(request).getTimeZoneInstance()}, exactly as the
     * old dateString/hours/minutes/seconds combination this replaces did;
     * pubtime has always meant the WEBLOG's timezone, never the server's or
     * the request locale's.
     *
     * <p>A blank {@code pubTimeLocal} means "no time chosen" and returns
     * null, which {@code EntryEditController} reads as "publish now". A
     * non-blank value that will not parse throws {@link
     * DateTimeParseException} rather than being silently discarded the way
     * the old dateString parser did -- a mistyped pubtime must surface as a
     * validation error, not quietly publish "now".
     */
    public Timestamp getPubTime(TimeZone timezone) {
        if (StringUtils.isBlank(pubTimeLocal)) {
            return null;
        }
        LocalDateTime local = LocalDateTime.parse(pubTimeLocal.trim());
        return Timestamp.from(local.atZone(timezone.toZoneId()).toInstant());
    }

    public boolean isDraft() {
        return PubStatus.DRAFT.name().equals(status);
    }
    
    public boolean isPending() {
        return PubStatus.PENDING.name().equals(status);
    }
    
    public boolean isPublished() {
        return PubStatus.PUBLISHED.name().equals(status);
    }
    
    public boolean isScheduled() {
        return PubStatus.SCHEDULED.name().equals(status);
    }
    
    public void copyTo(WeblogEntry entry) throws WebloggerException {
        
        entry.setTitle(StringEscapeUtils.escapeHtml4(getTitle()));
        entry.setStatus(PubStatus.valueOf(getStatus()));
        entry.setLocale(getLocale());
        entry.setSummary(getSummary());
        entry.setText(getText());
        entry.setTagsAsString(getTagsAsString() != null
                ? Utilities.replaceNonAlphanumeric(getTagsAsString(), ' ') : "");
        entry.setSearchDescription(getSearchDescription());
        entry.setFeaturedImageId(getFeaturedImageId());
        entry.setMetaTitle(getMetaTitle());
        entry.setOgImageId(getOgImageId());
        entry.setCanonicalUrl(getCanonicalUrl());
        entry.setNoindex(getNoindex());
        // fromString is lenient by design: null/blank (no dropdown yet) and
        // unknown values both normalize to the BLOG_POSTING default.
        entry.setJsonLdType(JsonLdType.fromString(getJsonLdType()));
        entry.setGeoLatitude(getGeoLatitude());
        entry.setGeoLongitude(getGeoLongitude());
        entry.setEventStart(getEventStart());
        entry.setEventEnd(getEventEnd());
        entry.setEventLocation(getEventLocation());

        // figure out the category selected
        if (getCategoryId() != null) {
            WeblogCategory cat = null;
            try {
                WeblogEntryManager wmgr = WebloggerFactory.getWeblogger().getWeblogEntryManager();
                cat = wmgr.getWeblogCategory(getCategoryId());
            } catch (WebloggerException ex) {
                log.error("Error getting category by id", ex);
            }
            
            if(cat == null) {
                throw new WebloggerException("Category could not be found - "+getCategoryId());
            } else if(!entry.getWebsite().equals(cat.getWeblog())) {
                throw new WebloggerException("Illegal category, not owned by action weblog");
            } else {
                entry.setCategory(cat);
            }
        } else {
            throw new WebloggerException("No category specified");
        }

        // comment settings
        entry.setAllowComments(getAllowComments());
        entry.setCommentDays(getCommentDays());

        // NOTE: pubtime and pinned to main attributes are set in action
    }
    
    
    /**
     * Copy values from WeblogEntryData to this Form.
     */
    public void copyFrom(WeblogEntry entry, Locale locale) {
        
        setId(entry.getId());
        setTitle(StringEscapeUtils.unescapeHtml4(entry.getTitle()));
        setLocale(entry.getLocale());
        setStatus(entry.getStatus().name());
        setSummary(entry.getSummary());
        setText(entry.getText());
        setCategoryId(entry.getCategory().getId());
        setTagsAsString(entry.getTagsAsString());
        setSearchDescription(entry.getSearchDescription());
        setFeaturedImageId(entry.getFeaturedImageId());
        setMetaTitle(entry.getMetaTitle());
        setOgImageId(entry.getOgImageId());
        setCanonicalUrl(entry.getCanonicalUrl());
        setNoindex(entry.getNoindex() != null && entry.getNoindex());
        // Null (entries persisted before this field existed) stays null; the
        // editor treats it as the BLOG_POSTING default.
        setJsonLdType(entry.getJsonLdType() != null ? entry.getJsonLdType().name() : null);
        setGeoLatitude(entry.getGeoLatitude());
        setGeoLongitude(entry.getGeoLongitude());
        setEventStart(entry.getEventStart());
        setEventEnd(entry.getEventEnd());
        setEventLocation(entry.getEventLocation());

        // set comment count for all comments (including spam and unapproved)
        try {
            WeblogEntryManager wmgr = WebloggerFactory.getWeblogger().getWeblogEntryManager();
            CommentSearchCriteria csc = new CommentSearchCriteria();
            csc.setWeblog(entry.getWebsite());
            csc.setEntry(entry);
            setCommentCount(wmgr.getComments(csc).size());
        } catch (WebloggerException e) {
            log.error("Error getting comment count", e);
            setCommentCount(0);
        }

        // init pubtime value -- emitted in the WEBLOG's timezone, matching
        // what getPubTime(TimeZone) parses it back with, so opening and
        // saving an entry unchanged does not move its publication time.
        if (entry.getPubTime() != null) {
            log.debug("entry pubtime is " + entry.getPubTime());
            ZonedDateTime zoned = entry.getPubTime().toInstant()
                    .atZone(entry.getWebsite().getTimeZoneInstance().toZoneId());
            setPubTimeLocal(zoned.toLocalDateTime().format(DATETIME_LOCAL));
        }

        setAllowComments(entry.getAllowComments());
        setCommentDays(entry.getCommentDays());
        setPinnedToMain(entry.getPinnedToMain());
    }
    
    
    @Override
    public String toString() {
        StringBuilder buf = new StringBuilder();
        
        //title,locale,catId,tags,text,summary,pubTimeLocal,status,comments
        buf.append("title = ").append(getTitle()).append("\n");
        buf.append("locale = ").append(getLocale()).append("\n");
        buf.append("status = ").append(getStatus()).append("\n");
        buf.append("catId = ").append(getCategoryId()).append("\n");
        buf.append("tags = ").append(getTagsAsString()).append("\n");
        buf.append("pubTimeLocal = ").append(getPubTimeLocal()).append("\n");
        buf.append("text = ").append(getText()).append("\n");
        buf.append("summary = ").append(getSummary()).append("\n");
        buf.append("search description = ").append(getSearchDescription()).append("\n");
        buf.append("comments = ").append(getAllowComments()).append("\n");
        buf.append("commentDays = ").append(getCommentDays()).append("\n");

        return buf.toString();
    }
}
