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

package org.apache.roller.weblogger.pojos;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.io.Serializable;
import java.util.*;

import org.apache.commons.lang3.builder.EqualsBuilder;
import org.apache.commons.lang3.builder.HashCodeBuilder;
import org.apache.roller.weblogger.WebloggerException;
import org.apache.roller.weblogger.business.plugins.entry.WeblogEntryPlugin;
import org.apache.roller.weblogger.business.WebloggerFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.apache.roller.weblogger.business.plugins.PluginManager;
import org.apache.roller.weblogger.business.Weblogger;
import org.apache.roller.weblogger.business.themes.ThemeManager;
import org.apache.roller.weblogger.business.WeblogEntryManager;
import org.apache.roller.weblogger.pojos.WeblogEntry.PubStatus;
import org.apache.roller.util.UUIDGenerator;
import org.apache.roller.weblogger.business.UserManager;
import org.apache.roller.weblogger.util.I18nUtils;
import org.apache.roller.weblogger.util.Utilities;


/**
 * Website has many-to-many association with users. Website has one-to-many and
 * one-direction associations with weblog entries, weblog categories, folders and
 * other objects. Use UserManager to create, fetch, update and retrieve websites.
 *
 * @author David M Johnson
 */
// PMD.GuardLogStatement: every violation in this class is a parameterized
// SLF4J {} call whose data argument is a cheap accessor (a getter,
// getClass(), or similar single-field read), not the expensive
// computation this rule exists to catch. Guarding it with isXEnabled()
// would be pure ceremony -- SLF4J already defers message formatting.
// See CLAUDE.md's Static analysis section.
@SuppressWarnings("PMD.GuardLogStatement")
public class Weblog implements Serializable {
    
    public static final long serialVersionUID = 206437645033737127L;
    
    private static final Logger log = LoggerFactory.getLogger(Weblog.class);

    private static final int MAX_ENTRIES = 100;
    
    // Simple properties
    private String  id               = UUIDGenerator.generateUUID();
    private String  handle           = null;
    private String  name             = null;
    private String  tagline          = null;
    private String  emailAddress     = null;
    private String  editorTheme      = null;
    private String  locale           = null;
    private String  timeZone         = null;
    private Boolean visible          = Boolean.TRUE;
    private Boolean active           = Boolean.TRUE;
    private Date    dateCreated      = new Date();
    private int     entryDisplayCount = 15;
    private Date    lastModified     = new Date();
    private String  iconPath         = null;
    private String  about            = null;
    private String  creator          = null;
    private String  analyticsSiteId  = null;
    private String  analyticsShareUrl = null;
    private String  newsletterListUuid = null;
    private String  customDomain     = null;

    private transient Map<String, WeblogEntryPlugin> initializedPlugins = null;

    private transient List<WeblogCategory> weblogCategories = new ArrayList<>();

    private transient List<MediaFileDirectory> mediaFileDirectories = new ArrayList<>();

    public Weblog() {
    }
    
    public Weblog(
            String handle,
            String creator,
            String name,
            String desc,
            String email,
            String editorTheme,
            String locale,
            String timeZone) {
        
        this.handle = handle;
        this.creator = creator;
        this.name = name;
        this.tagline = desc;
        this.emailAddress = email;
        this.editorTheme = editorTheme;
        this.locale = locale;
        this.timeZone = timeZone;
    }
    
    //------------------------------------------------------- Good citizenship

    @Override
    public String toString() {
        return  "{" + getId() + ", " + getHandle()
        + ", " + getName() + ", " + getEmailAddress()
        + ", " + getLocale() + ", " + getTimeZone() + "}";
    }

    @Override
    public boolean equals(Object other) {
        if (other == this) {
            return true;
        }
        if (!(other instanceof Weblog)) {
            return false;
        }
        Weblog o = (Weblog)other;
        return new EqualsBuilder()
            .append(getHandle(), o.getHandle()) 
            .isEquals();
    }
    
    @Override
    public int hashCode() { 
        return new HashCodeBuilder()
            .append(getHandle())
            .toHashCode();
    } 
    
    /**
     * Get the Theme object in use by this weblog, or null if no theme selected.
     */
    public WeblogTheme getTheme() {
        try {
            // let the ThemeManager handle it
            ThemeManager themeMgr = WebloggerFactory.getWeblogger().getThemeManager();
            return themeMgr.getTheme(this);
        } catch (WebloggerException ex) {
            log.error("Error getting theme for weblog - {}", getHandle(), ex);
        }
        
        // TODO: maybe we should return a default theme in this case?
        return null;
    }

    /**
     * Id of the Website.
     */
    public String getId() {
        return this.id;
    }
    
    public void setId(String id) {
        this.id = id;
    }
    
    /**
     * Short URL safe string that uniquely identifies the website.
     */
    public String getHandle() {
        return this.handle;
    }
    
    public void setHandle(String handle) {
        this.handle = handle;
    }
    
    /**
     * Name of the Website.
     */
    public String getName() {
        return this.name;
    }
    
    public void setName(String name) {
        this.name = Utilities.removeHTML(name);
    }
    
    /**
     * Description
     *
     */
    public String getTagline() {
        return this.tagline;
    }
    
    public void setTagline(String tagline) {
        this.tagline = Utilities.removeHTML(tagline);
    }
    
    /**
     * Original creator of website.
     */
    public User getCreator() {
        try {
            return WebloggerFactory.getWeblogger().getUserManager().getUserByUserName(creator);
        } catch (Exception e) {
            log.error("ERROR fetching user object for username: {}", creator, e);
        }
        return null;
    }
    
    /**
     * Username of original creator of website.
     */
    public String getCreatorUserName() {
        return creator;
    }
    
    public void setCreatorUserName(String creatorUserName) {
        creator = creatorUserName;
    }

    public String getEmailAddress() {
        return this.emailAddress;
    }
    
    public void setEmailAddress(String emailAddress) {
        this.emailAddress = emailAddress;
    }
    
    /**
     * EditorTheme of the Website.
     */
    public String getEditorTheme() {
        return this.editorTheme;
    }
    
    public void setEditorTheme(String editorTheme) {
        this.editorTheme = editorTheme;
    }
    
    /**
     * Locale of the Website.
     */
    public String getLocale() {
        return this.locale;
    }
    
    public void setLocale(String locale) {
        this.locale = locale;
    }
    
    /**
     * Timezone of the Website.
     */
    public String getTimeZone() {
        return this.timeZone;
    }
    
    public void setTimeZone(String timeZone) {
        this.timeZone = timeZone;
    }
    
    public Date getDateCreated() {
        if (dateCreated == null) {
            return null;
        } else {
            return (Date)dateCreated.clone();
        }
    }

    public void setDateCreated(final Date date) {
        if (date != null) {
            dateCreated = (Date)date.clone();
        } else {
            dateCreated = null;
        }
    }

    /**
     * Set bean properties based on other bean.
     */
    public void setData(Weblog other) {
        
        this.setId(other.getId());
        this.setName(other.getName());
        this.setHandle(other.getHandle());
        this.setTagline(other.getTagline());
        this.setCreatorUserName(other.getCreatorUserName());
        this.setEmailAddress(other.getEmailAddress());
        this.setEditorTheme(other.getEditorTheme());
        this.setLocale(other.getLocale());
        this.setTimeZone(other.getTimeZone());
        this.setVisible(other.getVisible());
        this.setDateCreated(other.getDateCreated());
        this.setEntryDisplayCount(other.getEntryDisplayCount());
        this.setActive(other.getActive());
        this.setLastModified(other.getLastModified());
        this.setWeblogCategories(other.getWeblogCategories());
    }
    
    
    /**
     * Parse locale value and instantiate a Locale object,
     * otherwise return default Locale.
     *
     * @return Locale
     */
    public Locale getLocaleInstance() {
        return I18nUtils.toLocale(getLocale());
    }
    
    
    /**
     * Return TimeZone instance for value of timeZone,
     * otherwise return system default instance.
     * @return TimeZone
     */
    public TimeZone getTimeZoneInstance() {
        if (getTimeZone() == null) {
            this.setTimeZone( TimeZone.getDefault().getID() );
        }
        return TimeZone.getTimeZone(getTimeZone());
    }
    
    /**
     * Returns true if user has all permission action specified.
     */
    public boolean hasUserPermission(User user, String action) {
        return hasUserPermissions(user, Collections.singletonList(action));
    }
    
    
    /**
     * Returns true if user has all permissions actions specified in the weblog.
     */
    public boolean hasUserPermissions(User user, List<String> actions) {
        try {
            // look for user in website's permissions
            UserManager umgr = WebloggerFactory.getWeblogger().getUserManager();
            WeblogPermission userPerms = new WeblogPermission(this, user, actions);
            return umgr.checkPermission(userPerms, user);
            
        } catch (WebloggerException ex) {
            // something is going seriously wrong, not much we can do here
            log.error("ERROR checking user permssion", ex);
        }
        return false;
    }
    
    public int getEntryDisplayCount() {
        return entryDisplayCount;
    }
    
    public void setEntryDisplayCount(int entryDisplayCount) {
        this.entryDisplayCount = entryDisplayCount;
    }
    
    /**
     * Set to FALSE to disable and hide this weblog from public view.
     */
    public Boolean getVisible() {
        return this.visible;
    }
    
    public void setVisible(Boolean visible) {
        this.visible = visible;
    }
    
    /**
     * Set to FALSE to exclude this weblog from community areas such as the
     * front page and the planet page.
     */
    public Boolean getActive() {
        return active;
    }
    
    public void setActive(Boolean active) {
        this.active = active;
    }
    
    /**
     * The last time any visible part of this weblog was modified.
     * This includes a change to weblog settings, entries, themes, templates,
     * categories, etc.
     */
    public Date getLastModified() {
        return lastModified;
    }

    public void setLastModified(Date lastModified) {
        this.lastModified = lastModified;
    }

    // NM_CONFUSING pairs this with AbstractPager.getUrl() (inherited into
    // FeedModel$FeedEntriesPager, which is what the finding actually names)
    // purely on case -- the two classes share no relationship. getURL() is
    // this pojo's canonical spelling, delegated to by WeblogWrapper.getURL()
    // (the legacy plugin-facing wrapper API, see the Plugin System section
    // of CLAUDE.md), and getUrl() on AbstractPager is inherited by every
    // pager in the rendering model (grepped:
    // ui/rendering/pagers/AbstractPager.java:106), used across the whole
    // paginated-template family. Renaming either would break its own
    // unrelated, unrenameable call chain to fix a purely cosmetic collision
    // between the two.
    @SuppressFBWarnings(
            value = "NM_CONFUSING",
            justification = "getURL() is delegated to by WeblogWrapper.getURL() (the legacy "
                    + "plugin-facing wrapper API); the method it collides with on case alone, "
                    + "AbstractPager.getUrl(), is inherited by every pager in the rendering model. "
                    + "Neither side can be renamed without breaking its own, entirely unrelated "
                    + "call chain.")
    public String getURL() {
        return WebloggerFactory.getWeblogger().getUrlStrategy().getWeblogURL(this, null, false);
    }

    public String getAbsoluteURL() {
        return WebloggerFactory.getWeblogger().getUrlStrategy().getWeblogURL(this, null, true);
    }

    /**
     * The path under the weblog's resources to an icon image.
     */
    public String getIconPath() {
        return iconPath;
    }

    public void setIconPath(String iconPath) {
        this.iconPath = iconPath;
    }

    /**
     * The Umami website UUID the theme macro builds this weblog's tracker
     * tag from. Null or blank disables analytics for this weblog.
     */
    public String getAnalyticsSiteId() {
        return analyticsSiteId;
    }

    public void setAnalyticsSiteId(String analyticsSiteId) {
        this.analyticsSiteId = analyticsSiteId;
    }

    /**
     * The operator's saved link to the Umami share dashboard for this
     * weblog's site. Display-only convenience; never used to build the
     * tracker tag.
     */
    public String getAnalyticsShareUrl() {
        return analyticsShareUrl;
    }

    public void setAnalyticsShareUrl(String analyticsShareUrl) {
        this.analyticsShareUrl = analyticsShareUrl;
    }

    /**
     * The Listmonk list UUID this weblog's subscribe form feeds. Null or
     * blank disables the subscribe form for this weblog; Roller stores no
     * subscriber data itself.
     */
    public String getNewsletterListUuid() {
        return newsletterListUuid;
    }

    public void setNewsletterListUuid(String newsletterListUuid) {
        this.newsletterListUuid = newsletterListUuid;
    }

    /**
     * The hostname this weblog is served at, or null to be served under
     * /<handle>/ on the site host. Stored lowercased; see
     * WeblogConfigController for the validation, and VirtualHostRegistry for
     * how it is resolved on a request.
     */
    public String getCustomDomain() {
        return customDomain;
    }

    public void setCustomDomain(String customDomain) {
        this.customDomain = customDomain;
    }

    /**
     * A description for the weblog (its purpose, authors, etc.)
     *
     * This field is meant to hold a paragraph describing the weblog, in contrast
     * to the short sentence or two 'description' attribute meant for blog taglines
     * and HTML header META description tags.
     *
     */
    public String getAbout() {
        return about;
    }

    public void setAbout(String about) {
        this.about = Utilities.removeHTML(about);
    }
    
    
    /**
     * Get initialized plugins for use during rendering process.
     */
    public Map<String, WeblogEntryPlugin> getInitializedPlugins() {
        if (initializedPlugins == null) {
            try {
                Weblogger roller = WebloggerFactory.getWeblogger();
                PluginManager ppmgr = roller.getPluginManager();
                initializedPlugins = ppmgr.getWeblogEntryPlugins(this);
            } catch (Exception e) {
                log.error("ERROR: initializing plugins");
            }
        }
        return initializedPlugins;
    }
    
    /** 
     * Get weblog entry specified by anchor or null if no such entry exists.
     * @param anchor Weblog entry anchor
     * @return Weblog entry specified by anchor
     */
    public WeblogEntry getWeblogEntry(String anchor) {
        WeblogEntry entry = null;
        try {
            Weblogger roller = WebloggerFactory.getWeblogger();
            WeblogEntryManager wmgr = roller.getWeblogEntryManager();
            entry = wmgr.getWeblogEntryByAnchor(this, anchor);
        } catch (WebloggerException e) {
            log.error("ERROR: getting entry by anchor");
        }
        return entry;
    }

    public WeblogCategory getWeblogCategory(String categoryName) {
        WeblogCategory category = null;
        try {
            Weblogger roller = WebloggerFactory.getWeblogger();
            WeblogEntryManager wmgr = roller.getWeblogEntryManager();
            if (categoryName != null && !"nil".equals(categoryName)) {
                category = wmgr.getWeblogCategoryByName(this, categoryName);
            } else if (!getWeblogCategories().isEmpty()) {
                // Same "first category found" fallback saveWeblogEntry uses, and
                // the same reason for the guard: this is reachable from a
                // template, where an unchecked NoSuchElementException escapes the
                // catch below and takes the whole render with it. Returning null
                // is what every other failure here already does.
                category = getWeblogCategories().getFirst();
            }
        } catch (WebloggerException e) {
            log.error("ERROR: fetching category: {}", categoryName, e);
        }
        return category;
    }

    
    /**
     * Get up to 100 most recent published entries in weblog.
     * @param cat Category name or null for no category restriction
     * @param length Max entries to return (1-100)
     * @return List of weblog entry objects.
     */
    public List<WeblogEntry> getRecentWeblogEntries(String cat, int length) {
        if (cat != null && "nil".equals(cat)) {
            cat = null;
        }
        if (length > MAX_ENTRIES) {
            length = MAX_ENTRIES;
        }
        if (length < 1) {
            return Collections.emptyList();
        }
        try {
            WeblogEntryManager wmgr = WebloggerFactory.getWeblogger().getWeblogEntryManager();
            WeblogEntrySearchCriteria wesc = new WeblogEntrySearchCriteria();
            wesc.setWeblog(this);
            wesc.setCatName(cat);
            wesc.setStatus(PubStatus.PUBLISHED);
            wesc.setMaxResults(length);
            return wmgr.getWeblogEntries(wesc);
        } catch (WebloggerException e) {
            log.error("ERROR: getting recent entries", e);
        }
        return Collections.emptyList();
    }
    
    /**
     * Get up to 100 most recent published entries in weblog.
     * @param tag Blog entry tag to query by
     * @param length Max entries to return (1-100)
     * @return List of weblog entry objects.
     */
    public List<WeblogEntry> getRecentWeblogEntriesByTag(String tag, int length) {
        if (tag != null && "nil".equals(tag)) {
            tag = null;
        }
        if (length > MAX_ENTRIES) {
            length = MAX_ENTRIES;
        }
        if (length < 1) {
            return Collections.emptyList();
        }
        List<String> tags = Collections.emptyList();
        if (tag != null) {
            tags = List.of(tag);
        }
        try {
            WeblogEntryManager wmgr = WebloggerFactory.getWeblogger().getWeblogEntryManager();
            WeblogEntrySearchCriteria wesc = new WeblogEntrySearchCriteria();
            wesc.setWeblog(this);
            wesc.setTags(tags);
            wesc.setStatus(PubStatus.PUBLISHED);
            wesc.setMaxResults(length);
            return wmgr.getWeblogEntries(wesc);
        } catch (WebloggerException e) {
            log.error("ERROR: getting recent entries", e);
        }
        return Collections.emptyList();
    }   
    
    /**
     * Get a list of TagStats objects for the most popular tags
     *
     * @param sinceDays Number of days into past (or -1 for all days)
     * @param length    Max number of tags to return.
     * @return          Collection of WeblogEntryTag objects
     */
    public List<TagStat> getPopularTags(int sinceDays, int length) {
        Date startDate = null;
        if(sinceDays > 0) {
            Calendar cal = Calendar.getInstance();
            cal.setTime(new Date());
            cal.add(Calendar.DATE, -1 * sinceDays);        
            startDate = cal.getTime();     
        }        
        try {            
            Weblogger roller = WebloggerFactory.getWeblogger();
            WeblogEntryManager wmgr = roller.getWeblogEntryManager();
            return wmgr.getPopularTags(this, startDate, 0, length);
        } catch (Exception e) {
            log.error("ERROR: fetching popular tags for weblog {}", this.getName(), e);
        }
        return Collections.emptyList();
    }      

    public long getEntryCount() {
        long count = 0;
        try {
            Weblogger roller = WebloggerFactory.getWeblogger();
            WeblogEntryManager mgr = roller.getWeblogEntryManager();
            count = mgr.getEntryCount(this);            
        } catch (WebloggerException e) {
            log.error("Error getting entry count for weblog {}", this.getName(), e);
        }
        return count;
    }


    /**
     * Add a category as a child of this category.
     */
    public void addCategory(WeblogCategory category) {

        // make sure category is not null
        if(category == null || category.getName() == null) {
            throw new IllegalArgumentException("Category cannot be null and must have a valid name");
        }

        // make sure we don't already have a category with that name
        if(hasCategory(category.getName())) {
            throw new IllegalArgumentException("Duplicate category name '"+category.getName()+"'");
        }

        // add it to our list of child categories
        getWeblogCategories().add(category);
    }

    public List<WeblogCategory> getWeblogCategories() {
        return weblogCategories;
    }

    public void setWeblogCategories(List<WeblogCategory> cats) {
        this.weblogCategories = cats;
    }

    public boolean hasCategory(String name) {
        for (WeblogCategory cat : getWeblogCategories()) {
            if(name.equals(cat.getName())) {
                return true;
            }
        }
        return false;
    }

    public List<MediaFileDirectory> getMediaFileDirectories() {
        return mediaFileDirectories;
    }

    public void setMediaFileDirectories(List<MediaFileDirectory> mediaFileDirectories) {
        this.mediaFileDirectories = mediaFileDirectories;
    }

    /**
     * Indicates whether this weblog contains the specified media file directory
     *
     * @param name directory name
     *
     * @return true if directory is present, false otherwise.
     */
    public boolean hasMediaFileDirectory(String name) {
        for (MediaFileDirectory directory : this.getMediaFileDirectories()) {
            if (directory.getName().equals(name)) {
                return true;
            }
        }
        return false;
    }

    public MediaFileDirectory getMediaFileDirectory(String name) {
        for (MediaFileDirectory dir : this.getMediaFileDirectories()) {
            if (name.equals(dir.getName())) {
                return dir;
            }
        }
        return null;
    }

}
