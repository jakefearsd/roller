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

import org.apache.roller.weblogger.WebloggerException;
import org.apache.roller.weblogger.business.PropertiesManager;
import org.apache.roller.weblogger.business.Weblogger;
import org.apache.roller.weblogger.business.WebloggerFactory;
import org.apache.roller.weblogger.pojos.RuntimeConfigProperty;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.HashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ConfigModel}, the {@code $config} object themes read
 * site-wide settings from.
 *
 * <p>Every value here comes out of the runtime properties table, which an
 * administrator edits from the admin UI. A wrong key name is invisible at
 * compile time and renders as empty text, so these tests pin the key each
 * accessor actually reads.
 */
class ConfigModelTest {

    private MockedStatic<WebloggerFactory> factory;
    private PropertiesManager properties;
    private Weblogger weblogger;
    private ConfigModel model;

    @BeforeEach
    void setUp() {
        properties = mock(PropertiesManager.class);
        weblogger = mock(Weblogger.class);
        when(weblogger.getPropertiesManager()).thenReturn(properties);
        factory = mockStatic(WebloggerFactory.class);
        factory.when(WebloggerFactory::getWeblogger).thenReturn(weblogger);
        model = new ConfigModel();
    }

    @AfterEach
    void tearDown() {
        factory.close();
    }

    /** Make the runtime properties table answer with the given value for a key. */
    private void givenProperty(String name, String value) throws WebloggerException {
        when(properties.getProperty(name)).thenReturn(new RuntimeConfigProperty(name, value));
    }

    // ------------------------------------------------------------------ init

    @Test
    void modelIsRegisteredUnderTheNameThemesUse() {
        assertEquals("config", model.getModelName(),
                "Themes reference this model as $config.");
    }

    @Test
    void initAcceptsAnyInitDataBecauseItNeedsNone() {
        // ConfigModel is loaded for feeds, pages and previews alike, none of
        // which pass it anything. It must not start demanding a request.
        assertDoesNotThrow(() -> model.init(new HashMap<>()),
                "init() must tolerate empty init data; every renderer loads this "
                        + "model without passing it anything.");
    }

    // ------------------------------------------------------------- site info

    @Test
    void siteIdentityComesFromTheRuntimePropertiesTable() throws Exception {
        givenProperty("site.name", "Example Blogs");
        givenProperty("site.shortName", "Examples");
        givenProperty("site.description", "blogs about examples");
        givenProperty("site.adminemail", "admin@example.com");

        assertEquals("Example Blogs", model.getSiteName(),
                "$config.siteName reads site.name");
        assertEquals("Examples", model.getSiteShortName(),
                "$config.siteShortName reads site.shortName");
        assertEquals("blogs about examples", model.getSiteDescription(),
                "$config.siteDescription reads site.description");
        assertEquals("admin@example.com", model.getSiteEmail(),
                "$config.siteEmail reads site.adminemail");
    }

    @Test
    void anUnsetPropertyRendersAsNothingRatherThanFailing() {
        // No stubbing: the properties manager returns null for every key, which
        // is what a fresh install looks like before the admin fills these in.
        assertNull(model.getSiteName(),
                "An unset property must come back null so Velocity renders nothing.");
    }

    @Test
    void aPropertyLookupFailureDoesNotBreakThePage() throws Exception {
        when(properties.getProperty("site.name"))
                .thenThrow(new WebloggerException("properties table unavailable"));

        assertNull(model.getSiteName(),
                "A failed property read must degrade to null; the header of every "
                        + "page on the site depends on this call.");
    }

    // ----------------------------------------------------------------- feeds

    @Test
    void feedSizeIsReadAsAnInteger() throws Exception {
        givenProperty("site.newsfeeds.defaultEntries", "25");

        assertEquals(25, model.getFeedSize(),
                "$config.feedSize caps how many entries a feed carries.");
        assertEquals(25, model.getFeedMaxSize(),
                "getFeedMaxSize reads the same property; there is only one entry-count "
                        + "setting in runtimeConfigDefs.xml.");
    }

    @Test
    void anUnreadableFeedSizeFallsBackToMinusOne() {
        assertEquals(-1, model.getFeedSize(),
                "With no value configured the int accessor must return -1 rather "
                        + "than throwing while a feed is being written.");
    }

    @Test
    void aNonNumericFeedSizeDoesNotBreakTheFeed() throws Exception {
        givenProperty("site.newsfeeds.defaultEntries", "twenty");

        assertEquals(-1, model.getFeedSize(),
                "A typo in the admin UI must not abort feed rendering.");
    }

    @Test
    void feedFlagsAreReadAsBooleans() throws Exception {
        givenProperty("site.newsfeeds.history.enabled", "true");
        givenProperty("site.newsfeeds.styledFeeds", "true");

        assertTrue(model.getFeedHistoryEnabled(),
                "$config.feedHistoryEnabled reads site.newsfeeds.history.enabled");
        assertTrue(model.getFeedStyle(),
                "$config.feedStyle reads site.newsfeeds.styledFeeds");
    }

    @Test
    void feedFlagsSetToFalseReadAsFalse() throws Exception {
        // The "true" direction alone would pass even if the accessor ignored the
        // property and always said yes.
        givenProperty("site.newsfeeds.history.enabled", "false");
        givenProperty("site.newsfeeds.styledFeeds", "false");

        assertFalse(model.getFeedHistoryEnabled(),
                "An administrator turning feed history off must actually turn it off.");
        assertFalse(model.getFeedStyle(),
                "An administrator turning styled feeds off must actually turn them off.");
    }

    @Test
    void anUnsetBooleanFallsBackToTheStaticConfigFile() {
        // site.newsfeeds.history.enabled is shipped as false in roller.properties,
        // so an empty runtime table must still produce a definite answer.
        assertFalse(model.getFeedHistoryEnabled(),
                "With no runtime value the static roller.properties default applies.");
    }

    // -------------------------------------------------------------- comments

    @Test
    void removedFeaturesReportThemselvesAsOff() {
        // The comment subsystem is gone entirely; these accessors are
        // permanently-false compat shims (like getTrackbacksEnabled already
        // was for trackbacks) so a custom theme still referencing them
        // degrades quietly instead of breaking. No runtime property backs
        // any of these any more.
        assertFalse(model.getCommentHtmlAllowed(),
                "The comment subsystem was removed; HTML-in-comments is permanently off.");
        assertTrue(model.getCommentEscapeHtml(),
                "commentEscapeHtml is the inverse of commentHtmlAllowed, so with that "
                        + "permanently false this must be permanently true.");
        assertFalse(model.getCommentEmailNotify(),
                "The comment subsystem was removed; comment email notification is "
                        + "permanently off.");
        assertFalse(model.getCommentAutoFormat(),
                "Comment auto-format was removed in 4.0 and is permanently off.");
        assertFalse(model.getTrackbacksEnabled(),
                "Trackbacks were removed and must stay off.");
    }

    // ------------------------------------------------------------- analytics

    @Test
    void analyticsSettingsAreReadFromTheirOwnKeys() throws Exception {
        givenProperty("analytics.default.tracking.code", "<script>ga()</script>");
        givenProperty("analytics.code.override.allowed", "true");

        assertEquals("<script>ga()</script>", model.getDefaultAnalyticsTrackingCode(),
                "The tracking snippet is stored and returned verbatim — it is markup "
                        + "an administrator pasted in deliberately.");
        assertTrue(model.getAnalyticsOverrideAllowed(),
                "$config.analyticsOverrideAllowed reads analytics.code.override.allowed");
    }

    @Test
    void analyticsOverrideIsOffWhenNotExplicitlyAllowed() throws Exception {
        // This one decides whether a blog owner may inject their own script tag
        // into their pages, so it must not default to permissive.
        givenProperty("analytics.code.override.allowed", "false");
        assertFalse(model.getAnalyticsOverrideAllowed(),
                "Per-blog analytics code must stay disabled unless an administrator "
                        + "explicitly allows it.");
    }

    // ------------------------------------------------------------------ maps

    @Test
    void theMapTileUrlComesFromTheStaticConfigNotTheRuntimeTable() {
        // No stubbing at all: this accessor deliberately bypasses the runtime
        // properties table (a runtime property would need a configForm.* key
        // that only lives in XML, which MessageKeyTest counts as an orphan),
        // so it must still answer with the roller.properties default even
        // when the table knows nothing.
        assertEquals("https://tile.openstreetmap.org/{z}/{x}/{y}.png",
                model.getMapTileUrl(),
                "$config.mapTileUrl reads travel.map.tileUrl out of "
                        + "roller.properties; #showMapAssets hands it straight to "
                        + "L.tileLayer.");
    }

    @Test
    void theMapTileUrlCarriesNoSubdomainPlaceholder() {
        // The a/b/c. tile aliases are deprecated by the current OSM policy and
        // may be withdrawn without notice; HTTP/2 made domain sharding
        // pointless. A default that reintroduced {s} would aim this
        // deployment's traffic at hosts OSM no longer promises to serve.
        assertFalse(model.getMapTileUrl().contains("{s}"),
                "The default tile URL must not use the deprecated {s} subdomain "
                        + "placeholder.");
        assertTrue(model.getMapTileUrl().startsWith("https://"),
                "Tiles must be fetched over https or a secure page blocks them.");
    }

    @Test
    void analyticsBasePathAndScriptNameComeFromTheStaticConfigNotTheRuntimeTable() {
        // No stubbing: like getMapTileUrl, these describe the reverse proxy in
        // front of the JVM and must answer from roller.properties even when
        // the runtime table (an administrator-editable DB row) knows nothing.
        assertEquals("/analytics", model.getAnalyticsBasePath(),
                "$config.analyticsBasePath reads analytics.umami.basePath out of "
                        + "roller.properties; #showAnalyticsTrackingCode builds the "
                        + "script src and data-host-url from it.");
        assertEquals("script.js", model.getAnalyticsScriptName(),
                "$config.analyticsScriptName reads analytics.umami.scriptName out "
                        + "of roller.properties.");
    }

    // --------------------------------------------------------- build details

    @Test
    void buildDetailsComeFromTheRunningWebloggerInstance() {
        when(weblogger.getVersion()).thenReturn("6.2.0");
        when(weblogger.getBuildTime()).thenReturn("2026-07-31T12:00:00Z");
        when(weblogger.getBuildUser()).thenReturn("release-bot");

        assertEquals("6.2.0", model.getRollerVersion(),
                "$config.rollerVersion is rendered in the footer of every bundled theme.");
        assertEquals("2026-07-31T12:00:00Z", model.getRollerBuildTimestamp(),
                "$config.rollerBuildTimestamp reports the build time.");
        assertEquals("release-bot", model.getRollerBuildUser(),
                "$config.rollerBuildUser reports who built the release.");
    }
}
