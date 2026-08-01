/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  The ASF licenses this file to You
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
package org.apache.roller.weblogger.ui.controllers.core;

import java.io.StringReader;
import java.lang.reflect.Field;

import javax.xml.parsers.DocumentBuilderFactory;

import org.apache.roller.weblogger.TestUtils;
import org.apache.roller.weblogger.business.WebloggerFactory;
import org.apache.roller.weblogger.config.WebloggerRuntimeConfig;
import org.apache.roller.weblogger.pojos.MediaFile;
import org.apache.roller.weblogger.pojos.User;
import org.apache.roller.weblogger.pojos.Weblog;
import org.apache.roller.weblogger.pojos.WeblogEntry;
import org.apache.roller.weblogger.pojos.WeblogEntry.PubStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.w3c.dom.Document;
import org.xml.sax.InputSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link SeoController}, run against the real business tier the way
 * the rendering-servlet tests are: real weblogs and entries in PostgreSQL, the
 * real URL strategy, and the controller invoked directly.
 *
 * <p>What matters here: the index lists a visible weblog and skips a hidden
 * one, the per-weblog sitemap contains only published non-noindex entries, the
 * documents actually parse as namespace-aware XML, image entries carry the
 * featured image's media-resource URL, and robots.txt advertises the index.
 */
class SeoControllerTest {

    private static final String ABSOLUTE_CONTEXT = "http://localhost:8080/roller";

    private SeoController controller;
    private User user;
    private Weblog weblog;

    @BeforeEach
    void setUp() throws Exception {
        TestUtils.setupWeblogger();
        // What InitFilter would have computed from the first request.
        WebloggerRuntimeConfig.setAbsoluteContextURL(ABSOLUTE_CONTEXT);
        WebloggerRuntimeConfig.setRelativeContextURL("/roller");

        user = TestUtils.setupUser("seoControllerUser");
        weblog = TestUtils.setupWeblog("seocontrollerblog", user);
        TestUtils.endSession(true);

        controller = new SeoController();
        inject(controller, "weblogger", WebloggerFactory.getWeblogger());
    }

    @AfterEach
    void tearDown() throws Exception {
        TestUtils.teardownWeblog(weblog.getId());
        TestUtils.teardownUser(user.getUserName());
        TestUtils.endSession(true);
    }

    // --- robots.txt ---

    @Test
    void robotsAllowsEverythingAndAdvertisesTheSitemapIndex() {
        ResponseEntity<String> response = controller.robots();

        assertEquals(200, response.getStatusCode().value());
        String body = response.getBody();
        assertNotNull(body);
        assertTrue(body.contains("User-agent: *"),
                "robots.txt must address all crawlers:\n" + body);
        assertTrue(body.contains("Disallow:\n"),
                "robots.txt must allow everything (empty Disallow):\n" + body);
        assertTrue(body.contains("Sitemap: " + ABSOLUTE_CONTEXT + "/sitemap.xml"),
                "robots.txt must point at the absolute sitemap index URL:\n" + body);
    }

    // --- /sitemap.xml (index) ---

    @Test
    void sitemapIndexListsTheVisibleWeblogAndParsesAsXml() throws Exception {
        ResponseEntity<String> response = controller.sitemapIndex();

        assertEquals(200, response.getStatusCode().value());
        String body = response.getBody();
        assertNotNull(body);
        Document doc = parse(body);
        assertEquals("sitemapindex", doc.getDocumentElement().getLocalName());
        assertEquals("http://www.sitemaps.org/schemas/sitemap/0.9",
                doc.getDocumentElement().getNamespaceURI());
        assertTrue(body.contains(
                "<loc>" + ABSOLUTE_CONTEXT + "/sitemap-" + weblog.getHandle() + ".xml</loc>"),
                "The index must list the per-weblog sitemap URL:\n" + body);
    }

    @Test
    void sitemapIndexExcludesHiddenWeblogs() throws Exception {
        Weblog hidden = TestUtils.setupWeblog("seohiddenblog", user);
        try {
            hidden = TestUtils.getManagedWebsite(hidden);
            hidden.setVisible(Boolean.FALSE);
            WebloggerFactory.getWeblogger().getWeblogManager().saveWeblog(hidden);
            TestUtils.endSession(true);

            String body = controller.sitemapIndex().getBody();
            assertNotNull(body);
            assertFalse(body.contains("/sitemap-" + hidden.getHandle() + ".xml"),
                    "A weblog with visible=false must not appear in the index:\n" + body);
        } finally {
            TestUtils.teardownWeblog(hidden.getId());
            TestUtils.endSession(true);
        }
    }

    // --- /sitemap-<handle>.xml (per-weblog) ---

    @Test
    void weblogSitemapContainsHomeAndPublishedPermalinkOnly() throws Exception {
        WeblogEntry published = TestUtils.setupWeblogEntry("seoPublishedEntry", weblog, user);
        WeblogEntry draft = TestUtils.setupWeblogEntry("seoDraftEntry",
                TestUtils.getManagedWebsite(weblog).getWeblogCategories().iterator().next(),
                PubStatus.DRAFT, weblog, user);
        WeblogEntry noindexed = TestUtils.setupWeblogEntry("seoNoindexEntry", weblog, user);
        noindexed = TestUtils.getManagedWeblogEntry(noindexed);
        noindexed.setNoindex(Boolean.TRUE);
        WebloggerFactory.getWeblogger().getWeblogEntryManager().saveWeblogEntry(noindexed);
        TestUtils.endSession(true);

        ResponseEntity<String> response = controller.weblogSitemap(weblog.getHandle());

        assertEquals(200, response.getStatusCode().value());
        String body = response.getBody();
        assertNotNull(body);
        Document doc = parse(body);
        assertEquals("urlset", doc.getDocumentElement().getLocalName());
        assertTrue(body.contains(
                "<loc>" + ABSOLUTE_CONTEXT + "/" + weblog.getHandle() + "/</loc>"),
                "The weblog home page must be listed:\n" + body);
        assertTrue(body.contains("/entry/" + published.getAnchor()),
                "The published entry's permalink must be listed:\n" + body);
        assertFalse(body.contains(draft.getAnchor()),
                "Draft entries must not leak into the sitemap:\n" + body);
        assertFalse(body.contains(noindexed.getAnchor()),
                "noindex entries must be excluded from the sitemap:\n" + body);
    }

    @Test
    void weblogSitemapEmitsAnImageEntryForTheFeaturedImage() throws Exception {
        MediaFile image = TestUtils.setupImageMediaFile(weblog, "seo-featured.jpg");
        WeblogEntry entry = TestUtils.setupWeblogEntry("seoFeaturedEntry", weblog, user);
        entry = TestUtils.getManagedWeblogEntry(entry);
        entry.setFeaturedImageId(image.getId());
        WebloggerFactory.getWeblogger().getWeblogEntryManager().saveWeblogEntry(entry);
        TestUtils.endSession(true);

        String body = controller.weblogSitemap(weblog.getHandle()).getBody();

        assertNotNull(body);
        Document doc = parse(body);
        assertEquals(1, doc.getElementsByTagNameNS(
                "http://www.google.com/schemas/sitemap-image/1.1", "image").getLength(),
                "Exactly one image:image element must be emitted:\n" + body);
        assertTrue(body.contains("<image:loc>" + ABSOLUTE_CONTEXT + "/" + weblog.getHandle()
                + "/mediaresource/" + image.getId() + "?w=1600</image:loc>"),
                "The image loc must be the media-resource URL of the largest rendition:\n"
                        + body);
    }

    @Test
    void weblogSitemapSkipsAFeaturedImageThatNoLongerExists() throws Exception {
        WeblogEntry entry = TestUtils.setupWeblogEntry("seoGoneImageEntry", weblog, user);
        entry = TestUtils.getManagedWeblogEntry(entry);
        entry.setFeaturedImageId("no-such-media-file-id");
        WebloggerFactory.getWeblogger().getWeblogEntryManager().saveWeblogEntry(entry);
        TestUtils.endSession(true);

        String body = controller.weblogSitemap(weblog.getHandle()).getBody();

        assertNotNull(body);
        parse(body);
        assertTrue(body.contains("/entry/" + entry.getAnchor()),
                "The entry itself must still be listed:\n" + body);
        assertFalse(body.contains("image:loc"),
                "A dangling featuredImageId must not produce an image entry:\n" + body);
    }

    @Test
    void weblogSitemapReturns404ForAnUnknownHandle() {
        ResponseEntity<String> response = controller.weblogSitemap("no-such-weblog-handle");
        assertEquals(404, response.getStatusCode().value());
    }

    // --- support ---

    private static Document parse(String xml) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        return factory.newDocumentBuilder().parse(new InputSource(new StringReader(xml)));
    }

    /**
     * Sets {@code BaseController}'s protected {@code weblogger} field the way
     * Spring's autowiring would; this test package cannot see the field
     * directly and spring-test is not on the classpath.
     */
    private static void inject(Object target, String name, Object value) throws Exception {
        Class<?> type = target.getClass();
        while (type != null) {
            try {
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                field.set(target, value);
                return;
            } catch (NoSuchFieldException e) {
                type = type.getSuperclass();
            }
        }
        throw new IllegalStateException("No field '" + name + "' on " + target.getClass());
    }
}
