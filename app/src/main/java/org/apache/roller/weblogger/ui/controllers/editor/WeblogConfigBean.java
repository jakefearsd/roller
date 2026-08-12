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
import org.apache.commons.text.StringEscapeUtils;
import org.apache.roller.weblogger.pojos.Weblog;


/**
 * Bean used to manage data submitted to WeblogConfig action.
 */
public class WeblogConfigBean {
    
    private String handle = null;
    private String name = null;
    private String tagline = null;
    private String emailAddress = null;
    private String locale = null;
    private String timeZone = null;
    private int entryDisplayCount = 15;
    private boolean active = false;
    private String icon = null;
    private String about = null;

    private String analyticsCode = null;
    private String analyticsSiteId = null;
    private String analyticsShareUrl = null;
    private String newsletterListUuid = null;

    private String bloggerCategoryId = null;


    public String getHandle() {
        return this.handle;
    }
    
    public void setHandle( String handle ) {
        this.handle = handle;
    }
    
    public String getName() {
        return this.name;
    }
    
    public void setName( String name ) {
        this.name = name;
    }
    
    public String getTagline() {
        return this.tagline;
    }
    
    public void setTagline( String tagline ) {
        this.tagline = tagline;
    }
    
    public String getEmailAddress() {
        return this.emailAddress;
    }
    
    public void setEmailAddress( String emailAddress ) {
        this.emailAddress = emailAddress;
    }
    
    public String getLocale() {
        return this.locale;
    }
    
    public void setLocale( String locale ) {
        this.locale = locale;
    }
    
    public String getTimeZone() {
        return this.timeZone;
    }
    
    public void setTimeZone( String timeZone ) {
        this.timeZone = timeZone;
    }
    
    public int getEntryDisplayCount() {
        return this.entryDisplayCount;
    }
    
    public void setEntryDisplayCount( int entryDisplayCount ) {
        this.entryDisplayCount = entryDisplayCount;
    }
    
    public String getIcon() {
        return icon;
    }

    public void setIcon(String icon) {
        this.icon = icon;
    }

    public String getAbout() {
        return about;
    }

    public void setAbout(String about) {
        this.about = about;
    }
    
    public String getBloggerCategoryId() {
        return bloggerCategoryId;
    }
    
    public void setBloggerCategoryId(String bloggerCategoryId) {
        this.bloggerCategoryId = bloggerCategoryId;
    }

    public boolean getActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }

    public String getAnalyticsCode() {
        return analyticsCode;
    }

    public void setAnalyticsCode(String analyticsCode) {
        this.analyticsCode = analyticsCode;
    }

    public String getAnalyticsSiteId() {
        return analyticsSiteId;
    }

    public void setAnalyticsSiteId(String analyticsSiteId) {
        this.analyticsSiteId = analyticsSiteId;
    }

    public String getAnalyticsShareUrl() {
        return analyticsShareUrl;
    }

    public void setAnalyticsShareUrl(String analyticsShareUrl) {
        this.analyticsShareUrl = analyticsShareUrl;
    }

    public String getNewsletterListUuid() {
        return newsletterListUuid;
    }

    public void setNewsletterListUuid(String newsletterListUuid) {
        this.newsletterListUuid = newsletterListUuid;
    }

    public void copyFrom(Weblog dataHolder) {
        
        this.handle = dataHolder.getHandle();
        this.name = dataHolder.getName();
        this.tagline = dataHolder.getTagline();
        this.emailAddress = dataHolder.getEmailAddress();
        this.locale = dataHolder.getLocale();
        this.timeZone = dataHolder.getTimeZone();
        this.entryDisplayCount = dataHolder.getEntryDisplayCount();
        setActive(dataHolder.getActive());
        this.analyticsCode = dataHolder.getAnalyticsCode();
        this.analyticsSiteId = dataHolder.getAnalyticsSiteId();
        this.analyticsShareUrl = dataHolder.getAnalyticsShareUrl();
        this.newsletterListUuid = dataHolder.getNewsletterListUuid();
        setIcon(dataHolder.getIconPath());
        setAbout(dataHolder.getAbout());
        if (dataHolder.getBloggerCategory() != null) {
            bloggerCategoryId = dataHolder.getBloggerCategory().getId();
        }
    }
    
    
    public void copyTo(Weblog dataHolder) {
        dataHolder.setName(this.name);
        dataHolder.setTagline(this.tagline);
        dataHolder.setEmailAddress(this.emailAddress);
        dataHolder.setLocale(this.locale);
        dataHolder.setTimeZone(this.timeZone);
        dataHolder.setEntryDisplayCount(this.entryDisplayCount);
        dataHolder.setActive(this.getActive());
        dataHolder.setIconPath(getIcon());
        dataHolder.setAbout(getAbout());
        dataHolder.setAnalyticsCode(this.analyticsCode);
        dataHolder.setAnalyticsSiteId(StringUtils.trimToNull(this.analyticsSiteId));
        dataHolder.setAnalyticsShareUrl(StringUtils.trimToNull(this.analyticsShareUrl));
        dataHolder.setNewsletterListUuid(StringUtils.trimToNull(this.newsletterListUuid));
    }
    


}
