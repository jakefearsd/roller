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
package org.apache.roller.weblogger.ui.rendering.servlets;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Map;

import javax.imageio.ImageIO;

import org.apache.roller.weblogger.TestUtils;
import org.apache.roller.weblogger.business.MediaFileManager;
import org.apache.roller.weblogger.business.WeblogEntryManager;
import org.apache.roller.weblogger.business.WebloggerFactory;
import org.apache.roller.weblogger.pojos.MediaFile;
import org.apache.roller.weblogger.pojos.MediaFileDirectory;
import org.apache.roller.weblogger.pojos.RuntimeConfigProperty;
import org.apache.roller.weblogger.pojos.User;
import org.apache.roller.weblogger.pojos.Weblog;
import org.apache.roller.weblogger.pojos.WeblogEntry;
import org.apache.roller.weblogger.util.RollerMessages;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Renders real themes through the real PageServlet and asserts on the
 * {@code #showResponsiveImage} macro: the featured image becomes a
 * {@code <picture>} whose srcset climbs the rendition ladder, a hero on the
 * permalink page and a linked thumb on entry-list pages; formats outside the
 * ladder (gif) degrade to a plain honest {@code <img>}; and entries without
 * a featured image render exactly as they always did.
 */
class ResponsiveImageRenderingTest {

    private static final String HANDLE = "responsiverenderblog";
    private static final String BASE = "http://localhost:8080/roller/" + HANDLE;

    private User user;
    private Weblog weblog;

    @BeforeEach
    void setUp() throws Exception {
        RenderingTestSupport.ensureRenderingRuntime();
        RenderingTestSupport.clearRenderCaches();
        user = TestUtils.setupUser("responsiverenderuser");
        weblog = TestUtils.setupWeblog(HANDLE, user);
        TestUtils.endSession(true);
    }

    @AfterEach
    void tearDown() throws Exception {
        TestUtils.teardownWeblog(weblog.getId());
        TestUtils.teardownUser(user.getUserName());
        TestUtils.endSession(true);
    }

    // ---------------------------------------------------------------- helpers

    private String render(String pathInfo) throws Exception {
        MockHttpServletRequest request = RenderingTestSupport
                .anonymousGet("/roller-ui/rendering/page", pathInfo);
        MockHttpServletResponse response = RenderingTestSupport
                .execute(RenderingTestSupport.pageServlet(), request);
        assertEquals(200, response.getStatus(),
                "page must render for " + pathInfo);
        return response.getContentAsString();
    }

    private WeblogEntry entryWithFeaturedImage(String anchor, String imageId)
            throws Exception {
        WeblogEntry entry = TestUtils.setupWeblogEntry(anchor, weblog, user);
        WeblogEntryManager mgr = WebloggerFactory.getWeblogger().getWeblogEntryManager();
        WeblogEntry managed = mgr.getWeblogEntry(entry.getId());
        managed.setFeaturedImageId(imageId);
        mgr.saveWeblogEntry(managed);
        TestUtils.endSession(true);
        return entry;
    }

    /**
     * Persists a content-backed media file from a generated blank image --
     * the bundled hawk.jpg is always jpeg, which can never exercise the
     * outside-the-ladder degradation path.
     */
    private MediaFile setupGeneratedImage(String name, String contentType,
            String format, int width, int height) throws Exception {
        Map<String, RuntimeConfigProperty> config = WebloggerFactory
                .getWeblogger().getPropertiesManager().getProperties();
        config.get("uploads.enabled").setValue("true");

        MediaFileManager mgr = WebloggerFactory.getWeblogger().getMediaFileManager();
        Weblog managed = TestUtils.getManagedWebsite(weblog);
        MediaFileDirectory root = mgr.getDefaultMediaFileDirectory(managed);

        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        assertTrue(ImageIO.write(image, format, bytes),
                "no ImageIO writer for format " + format);

        MediaFile mediaFile = new MediaFile();
        mediaFile.setName(name);
        mediaFile.setDirectory(root);
        mediaFile.setWeblog(managed);
        mediaFile.setContentType(contentType);
        mediaFile.setInputStream(new ByteArrayInputStream(bytes.toByteArray()));
        mgr.createMediaFile(managed, mediaFile, new RollerMessages());
        WebloggerFactory.getWeblogger().flush();
        TestUtils.endSession(true);
        return mediaFile;
    }

    // ------------------------------------------------------------------ hero

    @Test
    void thePermalinkShowsTheFeaturedImageAsAResponsiveHero() throws Exception {
        MediaFile image = TestUtils.setupImageMediaFile(weblog, "hero-image");
        String imageId = image.getId();
        TestUtils.endSession(true);
        entryWithFeaturedImage("hero-entry", imageId);

        String body = render("/" + HANDLE + "/entry/hero-entry");

        String imageUrl = BASE + "/mediaresource/" + imageId;
        // hawk.jpg is 500x373: the 480 rung exists, the original tops the set.
        String srcset = imageUrl + "?w=480 480w, " + imageUrl + " 500w";
        assertTrue(body.contains("<picture>"), body);
        assertTrue(body.contains(
                "<source type=\"image/webp\" srcset=\"" + srcset + "\" sizes=\"100vw\">"),
                "webp-capable browsers get an early pick of the same URLs:\n" + body);
        assertTrue(body.contains("<img src=\"" + imageUrl + "\" srcset=\"" + srcset
                        + "\" sizes=\"100vw\""),
                "the img itself must carry the ladder srcset:\n" + body);
        assertTrue(body.contains(" width=\"500\" height=\"373\""),
                "intrinsic dimensions must be declared to prevent layout shift:\n" + body);
        assertTrue(body.contains(" loading=\"lazy\" decoding=\"async\""), body);
        assertTrue(body.contains(" class=\"entry-hero\""),
                "the permalink slot is the full-width hero:\n" + body);
        assertTrue(body.contains(" data-blurhash=\""),
                "fresh uploads carry a blurhash for client-side blur-up:\n" + body);
        assertTrue(body.contains(" style=\"background-color:#"),
                "the blurhash average color is the JS-free placeholder:\n" + body);
    }

    @Test
    void entryListPagesShowALinkedFeaturedThumb() throws Exception {
        MediaFile image = TestUtils.setupImageMediaFile(weblog, "thumb-image");
        String imageId = image.getId();
        TestUtils.endSession(true);
        entryWithFeaturedImage("thumb-entry", imageId);

        String body = render("/" + HANDLE);

        assertTrue(body.contains(" class=\"entry-thumb\""),
                "list pages show the card thumb, not the hero:\n" + body);
        assertFalse(body.contains("entry-hero"), body);
        assertTrue(body.contains(" sizes=\"240px\""),
                "the thumb declares its rendered width so the browser picks a small rung:\n"
                        + body);
        assertTrue(body.contains("<a href=\"" + BASE + "/entry/thumb-entry\"><picture>"),
                "the thumb must link to the entry:\n" + body);
    }

    // ------------------------------------------------- honest degradation

    @Test
    void aGifFeaturedImageDegradesToAPlainImg() throws Exception {
        // The ladder only covers jpeg/png; for a gif every ?w= URL silently
        // serves the full-size original, so no srcset and no webp <source>
        // may be offered.
        MediaFile image = setupGeneratedImage("plain.gif", "image/gif", "gif", 600, 400);
        entryWithFeaturedImage("gif-entry", image.getId());

        String body = render("/" + HANDLE + "/entry/gif-entry");

        String imageUrl = BASE + "/mediaresource/" + image.getId();
        assertTrue(body.contains("<img src=\"" + imageUrl + "\""), body);
        assertFalse(body.contains("srcset"), body);
        assertFalse(body.contains("image/webp"), body);
        assertTrue(body.contains(" width=\"600\" height=\"400\""),
                "known dimensions still prevent layout shift:\n" + body);
    }

    @Test
    void anEntryWithoutAFeaturedImageRendersExactlyAsBefore() throws Exception {
        TestUtils.setupWeblogEntry("bare-entry", weblog, user);
        TestUtils.endSession(true);

        String body = render("/" + HANDLE + "/entry/bare-entry");

        assertFalse(body.contains("<picture>"), body);
        assertFalse(body.contains("entry-hero"), body);
        assertFalse(body.contains("entry-thumb"), body);
    }

    // ------------------------------------------------------ other themes

    @Test
    void theFrontpageThemeStillRendersWithTheNewCardMarkup() throws Exception {
        Weblog managed = TestUtils.getManagedWebsite(weblog);
        managed.setEditorTheme("frontpage");
        WebloggerFactory.getWeblogger().getWeblogManager().saveWeblog(managed);
        TestUtils.endSession(true);

        String body = render("/" + HANDLE);

        // The macro call in _entry.vm must at least parse: a Velocity error
        // would leak the raw directive or reference text into the page.
        assertFalse(body.contains("#showResponsiveImage"), body);
        assertFalse(body.contains("$entry.featuredImage"), body);
    }
}
