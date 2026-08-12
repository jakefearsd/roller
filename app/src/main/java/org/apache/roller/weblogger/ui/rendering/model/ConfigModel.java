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

package org.apache.roller.weblogger.ui.rendering.model;

import java.util.Map;
import org.apache.roller.weblogger.WebloggerException;
import org.apache.roller.weblogger.business.WebloggerFactory;
import org.apache.roller.weblogger.config.WebloggerConfig;
import org.apache.roller.weblogger.config.WebloggerRuntimeConfig;

/**
 * Model which provides access to application config data like site
 * config properties.
 */
public class ConfigModel implements Model {
    
    /** Template context name to be used for model */
    @Override
    public String getModelName() {
        return "config";
    }
    
    
    /** Init model */
    @Override
    public void init(Map<String, Object> map) throws WebloggerException {
        // no-op
    }
    
    
    public String getSiteName() {
        return getProperty("site.name");
    }
    
    public String getSiteShortName() {
        return getProperty("site.shortName");
    }
    
    public String getSiteDescription() {
        return getProperty("site.description");
    }
    
    public String getSiteEmail() {
        return getProperty("site.adminemail");
    }
    
    public boolean getFeedHistoryEnabled() {
        return getBooleanProperty("site.newsfeeds.history.enabled");
    }
    
    public int getFeedSize() {
        return getIntProperty("site.newsfeeds.defaultEntries");
    }
    
    public int getFeedMaxSize() {
        return getIntProperty("site.newsfeeds.defaultEntries");
    }
    
    public boolean getFeedStyle() {
        return getBooleanProperty("site.newsfeeds.styledFeeds");
    }
    
    /** @deprecated The comment subsystem was removed. Always returns false,
     *  the same way {@link #getCommentAutoFormat} and
     *  {@link #getTrackbacksEnabled} already did for their own removed
     *  features -- kept so a custom theme still referencing
     *  {@code $config.commentHtmlAllowed} degrades quietly instead of
     *  breaking. */
    public boolean getCommentHtmlAllowed() {
        return false;
    }

    /** @deprecated This prop was removed in 4.0. Always returns false. */
    public boolean getCommentAutoFormat() {
        return false;
    }

    /** @deprecated The comment subsystem was removed; there is no comment
     *  HTML left to escape, so this stays permanently true. */
    public boolean getCommentEscapeHtml() {
        return !getCommentHtmlAllowed();
    }

    /** @deprecated The comment subsystem was removed. Always returns false. */
    public boolean getCommentEmailNotify() {
        return false;
    }
    
    /** @deprecated Trackbacks have been removed. Always returns false. */
    public boolean getTrackbacksEnabled() {
        return false;
    }
    
    
    /** Get Roller version string */
    public String getRollerVersion() {
        return WebloggerFactory.getWeblogger().getVersion();
    }
    
    
    /** Get timestamp of Roller build */
    public String getRollerBuildTimestamp() {
        return WebloggerFactory.getWeblogger().getBuildTime();
    }
    
    
    /** Get username who created Roller build */
    public String getRollerBuildUser() {
        return WebloggerFactory.getWeblogger().getBuildUser();
    }

    public String getDefaultAnalyticsTrackingCode() {
        return getProperty("analytics.default.tracking.code");
    }

    public boolean getAnalyticsOverrideAllowed() {
        return getBooleanProperty("analytics.code.override.allowed");
    }

    /**
     * The raster tile template {@code #showMapAssets} hands to Leaflet for
     * the {@code [map]} shortcode, e.g.
     * {@code https://tile.openstreetmap.org/{z}/{x}/{y}.png}.
     *
     * <p>Unlike every other accessor here this reads the <em>static</em>
     * config (roller.properties / the deployer's override file), not the
     * runtime properties table: a runtime property would need a
     * {@code configForm.*} message key that appears only in
     * runtimeConfigDefs.xml, which MessageKeyTest's orphan ratchet counts as
     * unused. Deployers who want their own tile server set
     * {@code travel.map.tileUrl} in their config file.
     */
    public String getMapTileUrl() {
        return WebloggerConfig.getProperty("travel.map.tileUrl");
    }

    /**
     * Where the reverse proxy mounts the Umami tracker on the blog's own
     * origin, e.g. {@code /analytics}. {@code #showAnalyticsTrackingCode}
     * builds the {@code <script src>} and {@code data-host-url} from this.
     *
     * <p>Same static-config precedent as {@link #getMapTileUrl}: this
     * describes the reverse proxy in front of the JVM, which does not change
     * while the JVM runs, so it is read from roller.properties /
     * roller-custom.properties rather than the hot runtime properties table.
     */
    public String getAnalyticsBasePath() {
        return WebloggerConfig.getProperty("analytics.umami.basePath");
    }

    /**
     * The tracker script's filename under {@link #getAnalyticsBasePath}, e.g.
     * {@code script.js}. Overridable for installations that renamed the
     * tracker (Umami's {@code UMAMI_SCRIPT_NAME}) to dodge content blockers.
     * Startup-scoped for the same reason as {@link #getAnalyticsBasePath}.
     */
    public String getAnalyticsScriptName() {
        return WebloggerConfig.getProperty("analytics.umami.scriptName");
    }

    private String getProperty(String name) {
        return WebloggerRuntimeConfig.getProperty(name);
    }
    
    
    private int getIntProperty(String name) {
        return WebloggerRuntimeConfig.getIntProperty(name);
    }
    
    
    private boolean getBooleanProperty(String name) {
        return WebloggerRuntimeConfig.getBooleanProperty(name);
    }
    
}

