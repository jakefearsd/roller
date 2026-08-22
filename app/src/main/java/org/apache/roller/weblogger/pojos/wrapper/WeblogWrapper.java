/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  The ASF licenses this file to You
 * under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
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

import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import org.apache.commons.text.StringEscapeUtils;
import org.apache.roller.weblogger.WebloggerException;
import org.apache.roller.weblogger.business.URLStrategy;
import org.apache.roller.weblogger.business.Weblogger;
import org.apache.roller.weblogger.pojos.TagStat;
import org.apache.roller.weblogger.util.HTMLSanitizer;

import java.util.stream.Collectors;
import org.apache.roller.weblogger.pojos.ThemeTemplate.ComponentType;
import org.apache.roller.weblogger.pojos.Weblog;


/**
 * Pojo safety wrapper for Weblog objects.
 */
public final class WeblogWrapper {
    
    // keep a reference to the wrapped pojo
    private final Weblog pojo;
    
    // url strategy to use for any url building
    private final URLStrategy urlStrategy;

    // this is private so that we can force the use of the .wrap(pojo) method
    // the business tier, for the lookups the template API needs
    private final Weblogger weblogger;

    private WeblogWrapper(Weblog toWrap, URLStrategy strat, Weblogger weblogger) {
        this.pojo = toWrap;
        this.urlStrategy = strat;
        this.weblogger = weblogger;
    }
    
    
    /**
     * Wrap the given pojo if it is not null. The wrapper is the template API
     * and does its own lookups: it is handed the (possibly preview-aware)
     * {@link URLStrategy} every URL it emits must come from, and the business
     * tier it resolves related objects through -- never a static locator.
     */
    public static WeblogWrapper wrap(Weblog toWrap, URLStrategy strat, Weblogger weblogger) {
        if (toWrap != null) {
            return new WeblogWrapper(toWrap, strat, weblogger);
        }
        return null;
    }


    public ThemeTemplateWrapper getTemplateByAction(ComponentType action) throws WebloggerException {
        return ThemeTemplateWrapper.wrap(this.pojo.getTheme().getTemplateByAction(action));
    }
    
    
    public ThemeTemplateWrapper getTemplateByName(String name) throws WebloggerException {
        return ThemeTemplateWrapper.wrap(this.pojo.getTheme().getTemplateByName(name));
    }
    
    
    public ThemeTemplateWrapper getTemplateByLink(String link) throws WebloggerException {
        return ThemeTemplateWrapper.wrap(this.pojo.getTheme().getTemplateByLink(link));
    }
    
    
    public List<ThemeTemplateWrapper> getTemplates() throws WebloggerException {
        return this.pojo.getTheme().getTemplates().stream()
                .map(ThemeTemplateWrapper::wrap)
                .collect(Collectors.toList());
    }
    
    
    public String getId() {
        return this.pojo.getId();
    }
    
    
    public String getHandle() {
        return this.pojo.getHandle();
    }
    
    
    public String getName() {
        return StringEscapeUtils.escapeHtml4(this.pojo.getName());
    }
    
    public String getTagline() {
        return HTMLSanitizer.conditionallySanitizeText(this.pojo.getTagline());
    }

    public UserWrapper getCreator() {
        return UserWrapper.wrap(this.pojo.getCreator());
    }

    /**
     * The Umami website UUID the theme macro builds this weblog's tracker
     * tag from, or null if analytics is disabled for this weblog.
     */
    public String getAnalyticsSiteId() {
        return this.pojo.getAnalyticsSiteId();
    }

    /**
     * The operator's saved link to the Umami share dashboard for this
     * weblog's site, or null if none is set.
     */
    public String getAnalyticsShareUrl() {
        return this.pojo.getAnalyticsShareUrl();
    }

    /**
     * The Listmonk list UUID this weblog's subscribe form feeds, or null if
     * the subscribe form is disabled for this weblog.
     */
    public String getNewsletterListUuid() {
        return this.pojo.getNewsletterListUuid();
    }

    public String getEmailAddress() {
        return this.pojo.getEmailAddress();
    }
    
    
    public String getEditorTheme() {
        return this.pojo.getEditorTheme();
    }
    
    
    public String getLocale() {
        return this.pojo.getLocale();
    }
    
    
    public String getTimeZone() {
        return this.pojo.getTimeZone();
    }
    
    
    public Date getDateCreated() {
        return this.pojo.getDateCreated();
    }


    public Locale getLocaleInstance() {
        return this.pojo.getLocaleInstance();
    }


    /**
     * The weblog's locale as a BCP-47 language tag, for {@code <html lang>}.
     *
     * <p>{@link #getLocale()} returns Java's underscore form as stored
     * ({@code en_US}), which is not a valid {@code lang} attribute value -- a
     * user agent that cannot parse it treats the document as having no
     * declared language, which is what every bundled theme's markup used to
     * do. {@link Locale#toLanguageTag()} is the conversion
     * ({@code en_US -> en-US}); an unset locale falls back to the JVM default
     * through {@code Weblog#getLocaleInstance}, so this never returns null.
     */
    public String getLanguageTag() {
        return this.pojo.getLocaleInstance().toLanguageTag();
    }
    
    
    public TimeZone getTimeZoneInstance() {
        return this.pojo.getTimeZoneInstance();
    }
    
    
    public int getEntryDisplayCount() {
        return this.pojo.getEntryDisplayCount();
    }
    
    
    public Boolean getVisible() {
        return this.pojo.getVisible();
    }

    /* deprecated in Roller 5.1 */
    @Deprecated
    public Boolean getEnabled() {
        return getVisible();
    }

    public Boolean getActive() {
        return this.pojo.getActive();
    }
    
    
    public Date getLastModified() {
        return this.pojo.getLastModified();
    }


    public String getStylesheet() throws WebloggerException {
        // custom stylesheet comes from the weblog theme
        if(this.pojo.getTheme().getStylesheet() != null) {
            return urlStrategy.getWeblogPageURL(this.pojo, null, this.pojo.getTheme().getStylesheet().getLink(), null, null, null, null, 0, false);
        }
        return null;
    }

    
    /**
     * Get path to weblog icon image if defined.
     *
     * This method is somewhat smart in the sense that it will check the entered
     * icon value and if it is a full url then it will be left alone, but if it
     * is a relative path to a file in the weblog's uploads section then it will
     * build the full url to that resource and return it.
     */
    public String getIcon() {
        
        String iconPath = this.pojo.getIconPath();
        if(iconPath == null) {
            return null;
        }
        
        if(iconPath.startsWith("http") || iconPath.startsWith("/")) {
            // if icon path is a relative path then assume it's a weblog resource
            return iconPath;
        } else {
            // otherwise it's just a plain old url
            return urlStrategy.getWeblogResourceURL(this.pojo, iconPath, false);
        }
        
    }
    
    
    public String getAbout() {
        return HTMLSanitizer.conditionallySanitizeText(this.pojo.getAbout());
    }
    
    
    
    /**
     * Relative weblog url, from the strategy this wrapper was given -- a
     * theme preview stays inside the preview, the live site links to itself.
     */
    public String getURL() {
        return urlStrategy.getWeblogURL(this.pojo, null, false);
    }


    public String getAbsoluteURL() {
        return urlStrategy.getWeblogURL(this.pojo, null, true);
    }
    
    
    public WeblogEntryWrapper getWeblogEntry(String anchor) {
        return WeblogEntryWrapper.wrap(this.pojo.getWeblogEntry(anchor), urlStrategy, weblogger);
    }


    public List<WeblogCategoryWrapper> getWeblogCategories() {
        return this.pojo.getWeblogCategories().stream()
                .map(cat -> WeblogCategoryWrapper.wrap(cat, urlStrategy, weblogger))
                .collect(Collectors.toList());
    }

    public WeblogCategoryWrapper getWeblogCategory(String categoryName) {
        return WeblogCategoryWrapper.wrap(this.pojo.getWeblogCategory(categoryName), urlStrategy, weblogger);
    }

    
    public List<WeblogEntryWrapper> getRecentWeblogEntries(String cat, int length) {
        return this.pojo.getRecentWeblogEntries(cat, length).stream()
                .map(entry -> WeblogEntryWrapper.wrap(entry, urlStrategy, weblogger))
                .collect(Collectors.toList());
    }
    
    
    public List<WeblogEntryWrapper> getRecentWeblogEntriesByTag(String tag, int length) {
        return this.pojo.getRecentWeblogEntriesByTag(tag, length).stream()
                .map(entry -> WeblogEntryWrapper.wrap(entry, urlStrategy, weblogger))
                .collect(Collectors.toList());
    }
    
    
    public List<TagStat> getPopularTags(int sinceDays,int length) {
        return this.pojo.getPopularTags(sinceDays,length);
    }


    public long getEntryCount() {
        return this.pojo.getEntryCount();
    }
    
    
    /**
     * this is a special method to access the original pojo
     * we don't really want to do this, but it's necessary
     * because some parts of the rendering process still need the
     * original pojo object
     */
    public Weblog getPojo() {
        return this.pojo;
    }
}
