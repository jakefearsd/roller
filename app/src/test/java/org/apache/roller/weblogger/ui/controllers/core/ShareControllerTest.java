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

import java.lang.reflect.Field;
import java.sql.Timestamp;

import org.apache.roller.weblogger.TestUtils;
import org.apache.roller.weblogger.business.ShareLinkManager;
import org.apache.roller.weblogger.business.WebloggerFactory;
import org.apache.roller.weblogger.config.WebloggerRuntimeConfig;
import org.apache.roller.weblogger.pojos.MediaFile;
import org.apache.roller.weblogger.pojos.ShareLink;
import org.apache.roller.weblogger.pojos.User;
import org.apache.roller.weblogger.pojos.Weblog;
import org.apache.roller.weblogger.pojos.WeblogEntry;
import org.apache.roller.weblogger.ui.core.RollerContext;
import org.apache.roller.weblogger.util.TokenGenerator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.web.servlet.ModelAndView;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ShareController} against the real business tier: real weblogs,
 * entries, media files and share links in PostgreSQL, the controller invoked
 * directly the way {@code SeoControllerTest} does it.
 *
 * <p>What matters here: the gate (unknown/expired 404, password form while
 * locked, per-token unlock), the render (entry content with shortcode
 * expansion, directory gallery with share-scoped media URLs), the
 * token-scoped media route's authorization matrix, and the cache-bypass
 * property -- a change to the entry shows on the very next share render,
 * because share pages never touch {@code WeblogPageCache}.
 */
class ShareControllerTest {

    private static final String ABSOLUTE_CONTEXT = "http://localhost:8080/roller";

    private ShareController controller;
    private User user;
    private Weblog weblog;

    @BeforeEach
    void setUp() throws Exception {
        TestUtils.setupWeblogger();
        WebloggerRuntimeConfig.setAbsoluteContextURL(ABSOLUTE_CONTEXT);
        WebloggerRuntimeConfig.setRelativeContextURL("/roller");
        if (RollerContext.getPasswordEncoder() == null) {
            // published by SecurityConfig at context refresh in production;
            // built directly here because no Spring context runs in this test
            RollerContext.setPasswordEncoder(RollerContext.createPasswordEncoder());
        }

        user = TestUtils.setupUser("shareControllerUser");
        weblog = TestUtils.setupWeblog("sharecontrollerblog", user);
        TestUtils.endSession(true);

        controller = new ShareController();
        inject(controller, "weblogger", WebloggerFactory.getWeblogger());
    }

    @AfterEach
    void tearDown() throws Exception {
        // share links are removed by the weblog cascade
        TestUtils.teardownWeblog(weblog.getId());
        TestUtils.teardownUser(user.getUserName());
        TestUtils.endSession(true);
    }

    // ------------------------------------------------------------- the gate

    @Test
    void anUnknownTokenIs404() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        ModelAndView modelAndView = controller.sharePage(
                TokenGenerator.newToken(), request(), response);

        assertNull(modelAndView, "a 404 must not fall through to a view");
        assertEquals(404, response.getStatus());
    }

    @Test
    void anExpiredLinkIs404() throws Exception {
        WeblogEntry entry = TestUtils.setupWeblogEntry("shareExpiredEntry", weblog, user);
        ShareLink link = createLink(ShareLink.TYPE_ENTRY, entry.getId(), null,
                new Timestamp(System.currentTimeMillis() - 60_000));

        MockHttpServletResponse response = new MockHttpServletResponse();
        assertNull(controller.sharePage(link.getToken(), request(), response));
        assertEquals(404, response.getStatus(),
                "an expired share link must be indistinguishable from an unknown one");
    }

    @Test
    void aDanglingTargetIs404() throws Exception {
        ShareLink link = createLink(ShareLink.TYPE_ENTRY, "no-such-entry-id", null);

        MockHttpServletResponse response = new MockHttpServletResponse();
        assertNull(controller.sharePage(link.getToken(), request(), response));
        assertEquals(404, response.getStatus(),
                "a link whose entry was deleted must degrade to 404, not error");
    }

    @Test
    void shareEndpointsAreServableToAnonymousVisitors() {
        assertFalse(controller.isUserRequired(),
                "share links exist for visitors without accounts");
        assertFalse(controller.isWeblogRequired(),
                "the routes carry no weblog request parameter");
        assertTrue(controller.requiredGlobalPermissionActions().isEmpty());
        assertTrue(controller.requiredWeblogPermissionActions().isEmpty(),
                "belt-and-braces: no weblog permission even if the "
                        + "interceptor's check nesting ever changes");
    }

    // -------------------------------------------------------- password flow

    @Test
    void aPasswordProtectedLinkShowsTheFormWhileLocked() throws Exception {
        ShareLink link = passwordProtectedEntryLink("shareLockedEntry", "secret-pw");

        MockHttpServletResponse response = new MockHttpServletResponse();
        ModelAndView modelAndView = controller.sharePage(link.getToken(), request(), response);

        assertNotNull(modelAndView);
        assertEquals(ShareController.PASSWORD_FORM_VIEW, modelAndView.getViewName());
        assertEquals(link.getToken(), modelAndView.getModel().get("shareToken"));
        assertEquals(Boolean.FALSE, modelAndView.getModel().get("shareWrongPassword"));
        assertEquals(200, response.getStatus(),
                "the form renders normally; only unknown tokens 404");
    }

    @Test
    void theWrongPasswordStaysLocked() throws Exception {
        ShareLink link = passwordProtectedEntryLink("shareWrongPwEntry", "right-pw");
        MockHttpSession session = new MockHttpSession();

        ModelAndView post = controller.unlock(link.getToken(), "wrong-pw",
                request(session), new MockHttpServletResponse());
        assertEquals(ShareController.PASSWORD_FORM_VIEW, post.getViewName());
        assertEquals(Boolean.TRUE, post.getModel().get("shareWrongPassword"));

        // the session must still be locked afterwards
        ModelAndView get = controller.sharePage(link.getToken(),
                request(session), new MockHttpServletResponse());
        assertNotNull(get);
        assertEquals(ShareController.PASSWORD_FORM_VIEW, get.getViewName(),
                "a failed attempt must not unlock anything");
    }

    @Test
    void theRightPasswordUnlocksThisSessionAndRedirects() throws Exception {
        ShareLink link = passwordProtectedEntryLink("shareUnlockEntry", "right-pw");
        MockHttpSession session = new MockHttpSession();

        ModelAndView post = controller.unlock(link.getToken(), "right-pw",
                request(session), new MockHttpServletResponse());
        assertEquals("redirect:/share/" + link.getToken(), post.getViewName());

        MockHttpServletResponse response = new MockHttpServletResponse();
        assertNull(controller.sharePage(link.getToken(), request(session), response),
                "an unlocked session gets the content, not a view");
        assertEquals(200, response.getStatus());
        assertTrue(response.getContentAsString().contains("shareUnlockEntry"),
                "the unlocked page must render the entry");
        assertEquals("private, no-store", response.getHeader("Cache-Control"),
                "an unlocked page must never be cached for another visitor");
    }

    @Test
    void unlockingOneTokenDoesNotUnlockAnother() throws Exception {
        ShareLink first = passwordProtectedEntryLink("shareIsolationOne", "pw-one");
        ShareLink second = passwordProtectedEntryLink("shareIsolationTwo", "pw-two");
        MockHttpSession session = new MockHttpSession();

        controller.unlock(first.getToken(), "pw-one",
                request(session), new MockHttpServletResponse());

        ModelAndView other = controller.sharePage(second.getToken(),
                request(session), new MockHttpServletResponse());
        assertNotNull(other);
        assertEquals(ShareController.PASSWORD_FORM_VIEW, other.getViewName(),
                "unlock state is per token, not per session-wide flag");
    }

    // ------------------------------------------------------- entry rendering

    @Test
    void anEntryShareRendersTitleAndTransformedContent() throws Exception {
        WeblogEntry entry = TestUtils.setupWeblogEntry("shareEntryRender", weblog, user);
        entry = TestUtils.getManagedWeblogEntry(entry);
        entry.setText("<p>the share body text</p>");
        WebloggerFactory.getWeblogger().getWeblogEntryManager().saveWeblogEntry(entry);
        ShareLink link = createLink(ShareLink.TYPE_ENTRY, entry.getId(), null);

        String html = renderedPage(link.getToken());

        assertTrue(html.contains("shareEntryRender"), "the title must render:\n" + html);
        assertTrue(html.contains("the share body text"), "the body must render:\n" + html);
        assertTrue(html.contains("noindex"),
                "share pages must not invite crawlers:\n" + html);
    }

    @Test
    void aPublicDirectoryGalleryInsideASharedEntryExpands() throws Exception {
        TestUtils.setupImageMediaFile(weblog, "share-inline.jpg", "sharepublicdir");
        WeblogEntry entry = TestUtils.setupWeblogEntry("shareInlineGallery", weblog, user);
        entry = TestUtils.getManagedWeblogEntry(entry);
        entry.setText("before [gallery dir=\"sharepublicdir\"] after");
        WebloggerFactory.getWeblogger().getWeblogEntryManager().saveWeblogEntry(entry);
        ShareLink link = createLink(ShareLink.TYPE_ENTRY, entry.getId(), null);

        String html = renderedPage(link.getToken());

        assertTrue(html.contains("class=\"jgrid\""),
                "a public-directory [gallery] must expand inside a shared entry:\n" + html);
        assertFalse(html.contains("[gallery"),
                "the shortcode text must not survive expansion:\n" + html);
    }

    @Test
    void aPrivateDirectoryGalleryInsideASharedEntryStaysRefused() throws Exception {
        // The shortcode's own gate, not this controller, refuses private
        // directories on the inline path -- even inside a share page. A
        // private directory is shared as a directory-target link instead.
        MediaFile image = TestUtils.setupImageMediaFile(weblog, "share-priv-inline.jpg",
                "shareprivinline");
        markDirectoryPrivate(image);
        WeblogEntry entry = TestUtils.setupWeblogEntry("sharePrivInlineGallery", weblog, user);
        entry = TestUtils.getManagedWeblogEntry(entry);
        entry.setText("before [gallery dir=\"shareprivinline\"] after");
        WebloggerFactory.getWeblogger().getWeblogEntryManager().saveWeblogEntry(entry);
        ShareLink link = createLink(ShareLink.TYPE_ENTRY, entry.getId(), null);

        String html = renderedPage(link.getToken());

        assertTrue(html.contains("[gallery"),
                "a private-directory [gallery] must stay as written even in a "
                        + "shared entry:\n" + html);
        // note: the page wrapper always ships the .jgrid CSS rules; leaked
        // *markup* is what must not appear
        assertFalse(html.contains("class=\"jgrid\""),
                "no grid markup may leak from a private directory on the inline path:\n" + html);
    }

    @Test
    void shareRenderingBypassesThePageCache() throws Exception {
        WeblogEntry entry = TestUtils.setupWeblogEntry("shareCacheBypass", weblog, user);
        entry = TestUtils.getManagedWeblogEntry(entry);
        entry.setText("<p>first version</p>");
        WebloggerFactory.getWeblogger().getWeblogEntryManager().saveWeblogEntry(entry);
        ShareLink link = createLink(ShareLink.TYPE_ENTRY, entry.getId(), null);

        assertTrue(renderedPage(link.getToken()).contains("first version"));

        entry = TestUtils.getManagedWeblogEntry(entry);
        entry.setText("<p>second version</p>");
        WebloggerFactory.getWeblogger().getWeblogEntryManager().saveWeblogEntry(entry);
        TestUtils.endSession(true);

        String rerendered = renderedPage(link.getToken());
        assertTrue(rerendered.contains("second version"),
                "the very next render must show the change -- share pages must "
                        + "never be served from a whole-page cache:\n" + rerendered);
        assertFalse(rerendered.contains("first version"));
    }

    // --------------------------------------------------- directory rendering

    @Test
    void aDirectoryShareRendersTheGridWithShareScopedMediaUrls() throws Exception {
        MediaFile image = TestUtils.setupImageMediaFile(weblog, "share-dir-a.jpg", "sharedirgrid");
        TestUtils.setupImageMediaFile(weblog, "share-dir-b.jpg", "sharedirgrid");
        markDirectoryPrivate(image);
        ShareLink link = createLink(ShareLink.TYPE_DIRECTORY, directoryId(image), null);

        String html = renderedPage(link.getToken());

        assertTrue(html.contains("class=\"jgrid\""), "the grid must render:\n" + html);
        assertTrue(html.contains(ABSOLUTE_CONTEXT + "/share/" + link.getToken()
                        + "/media/" + image.getId()),
                "every media URL must be scoped to the share token:\n" + html);
        assertTrue(html.contains("?w=480"),
                "the srcset must offer the share-scoped rendition rungs:\n" + html);
        assertFalse(html.contains("/mediaresource/"),
                "no base media-path URL may appear -- those 404 for private "
                        + "directories:\n" + html);
    }

    // ------------------------------------------------------------ share media

    @Test
    void shareMediaServesTheBytesWithPrivateCacheControl() throws Exception {
        MediaFile image = TestUtils.setupImageMediaFile(weblog, "share-media.jpg", "sharemediadir");
        markDirectoryPrivate(image);
        ShareLink link = createLink(ShareLink.TYPE_DIRECTORY, directoryId(image), null);

        MockHttpServletResponse response = new MockHttpServletResponse();
        controller.shareMedia(link.getToken(), image.getId(), null, null, request(), response);

        assertEquals(200, response.getStatus());
        assertEquals("image/jpeg", response.getContentType());
        assertEquals("private, no-store", response.getHeader("Cache-Control"),
                "gated bytes must never land in a shared cache");
        assertTrue(response.getContentAsByteArray().length > 1000,
                "the real image bytes must be streamed");
    }

    @Test
    void shareMediaServesThumbnailAndRenditionVariants() throws Exception {
        MediaFile image = TestUtils.setupImageMediaFile(weblog, "share-variants.jpg",
                "sharevariantdir");
        markDirectoryPrivate(image);
        ShareLink link = createLink(ShareLink.TYPE_DIRECTORY, directoryId(image), null);

        MockHttpServletResponse original = new MockHttpServletResponse();
        controller.shareMedia(link.getToken(), image.getId(), null, null, request(), original);

        MockHttpServletResponse thumbnail = new MockHttpServletResponse();
        controller.shareMedia(link.getToken(), image.getId(), null, "true", request(), thumbnail);
        assertEquals(200, thumbnail.getStatus());
        assertEquals("image/png", thumbnail.getContentType());
        assertTrue(thumbnail.getContentAsByteArray().length
                        < original.getContentAsByteArray().length,
                "the thumbnail must be smaller than the original");

        MockHttpServletResponse rendition = new MockHttpServletResponse();
        controller.shareMedia(link.getToken(), image.getId(), 480, null, request(), rendition);
        assertEquals(200, rendition.getStatus());
        assertEquals("image/jpeg", rendition.getContentType());
        assertTrue(rendition.getContentAsByteArray().length
                        < original.getContentAsByteArray().length,
                "the 480w rendition must be smaller than the 500w original");
    }

    @Test
    void shareMediaIsLockedUntilThePasswordSessionUnlocks() throws Exception {
        MediaFile image = TestUtils.setupImageMediaFile(weblog, "share-locked-media.jpg",
                "sharelockedmediadir");
        markDirectoryPrivate(image);
        ShareLink link = createLink(ShareLink.TYPE_DIRECTORY, directoryId(image),
                RollerContext.getPasswordEncoder().encode("media-pw"));
        MockHttpSession session = new MockHttpSession();

        MockHttpServletResponse locked = new MockHttpServletResponse();
        controller.shareMedia(link.getToken(), image.getId(), null, null,
                request(session), locked);
        assertEquals(404, locked.getStatus(),
                "the media route sits behind exactly the page's gate");
        assertEquals(0, locked.getContentAsByteArray().length);

        controller.unlock(link.getToken(), "media-pw",
                request(session), new MockHttpServletResponse());

        MockHttpServletResponse unlocked = new MockHttpServletResponse();
        controller.shareMedia(link.getToken(), image.getId(), null, null,
                request(session), unlocked);
        assertEquals(200, unlocked.getStatus());
        assertTrue(unlocked.getContentAsByteArray().length > 1000);
    }

    @Test
    void shareMediaRefusesFilesOutsideTheSharedDirectory() throws Exception {
        MediaFile shared = TestUtils.setupImageMediaFile(weblog, "share-inside.jpg",
                "sharematrixdir");
        MediaFile outside = TestUtils.setupImageMediaFile(weblog, "share-outside.jpg",
                "sharematrixother");
        markDirectoryPrivate(shared);
        ShareLink link = createLink(ShareLink.TYPE_DIRECTORY, directoryId(shared), null);

        MockHttpServletResponse crossDirectory = new MockHttpServletResponse();
        controller.shareMedia(link.getToken(), outside.getId(), null, null,
                request(), crossDirectory);
        assertEquals(404, crossDirectory.getStatus(),
                "a token covers exactly its own directory's files");

        MockHttpServletResponse unknownToken = new MockHttpServletResponse();
        controller.shareMedia(TokenGenerator.newToken(), shared.getId(), null, null,
                request(), unknownToken);
        assertEquals(404, unknownToken.getStatus());

        MockHttpServletResponse unknownFile = new MockHttpServletResponse();
        controller.shareMedia(link.getToken(), "no-such-file-id", null, null,
                request(), unknownFile);
        assertEquals(404, unknownFile.getStatus());
    }

    @Test
    void anEntryShareServesNoMediaAtAll() throws Exception {
        MediaFile image = TestUtils.setupImageMediaFile(weblog, "share-entry-media.jpg");
        WeblogEntry entry = TestUtils.setupWeblogEntry("shareNoMediaEntry", weblog, user);
        ShareLink link = createLink(ShareLink.TYPE_ENTRY, entry.getId(), null);

        MockHttpServletResponse response = new MockHttpServletResponse();
        controller.shareMedia(link.getToken(), image.getId(), null, null,
                request(), response);
        assertEquals(404, response.getStatus(),
                "entry-target shares render public media as normal; their token "
                        + "must not open the media route");
    }

    // --------------------------------------------------------------- support

    private MockHttpServletRequest request() {
        return request(new MockHttpSession());
    }

    private MockHttpServletRequest request(MockHttpSession session) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setSession(session);
        return request;
    }

    private String renderedPage(String token) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        ModelAndView modelAndView = controller.sharePage(token, request(), response);
        assertNull(modelAndView, "expected a direct render, not a view");
        assertEquals(200, response.getStatus());
        return response.getContentAsString();
    }

    private ShareLink createLink(String targetType, String targetId, String passwordHash)
            throws Exception {
        return createLink(targetType, targetId, passwordHash, null);
    }

    private ShareLink createLink(String targetType, String targetId, String passwordHash,
            Timestamp expires) throws Exception {
        ShareLinkManager manager = WebloggerFactory.getWeblogger().getShareLinkManager();
        ShareLink link = new ShareLink();
        link.setWeblog(TestUtils.getManagedWebsite(weblog));
        link.setTargetType(targetType);
        link.setTargetId(targetId);
        link.setToken(TokenGenerator.newToken());
        link.setPasswordHash(passwordHash);
        link.setExpires(expires);
        manager.createShareLink(link);
        TestUtils.endSession(true);
        return link;
    }

    private ShareLink passwordProtectedEntryLink(String anchor, String password)
            throws Exception {
        WeblogEntry entry = TestUtils.setupWeblogEntry(anchor, weblog, user);
        return createLink(ShareLink.TYPE_ENTRY, entry.getId(),
                RollerContext.getPasswordEncoder().encode(password));
    }

    private static void markDirectoryPrivate(MediaFile image) throws Exception {
        MediaFile managed = WebloggerFactory.getWeblogger().getMediaFileManager()
                .getMediaFile(image.getId());
        managed.getDirectory().setPrivate(true);
        TestUtils.endSession(true);
    }

    private static String directoryId(MediaFile image) throws Exception {
        return WebloggerFactory.getWeblogger().getMediaFileManager()
                .getMediaFile(image.getId()).getDirectory().getId();
    }

    /** Sets {@code BaseController}'s protected {@code weblogger} field, as autowiring would. */
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

    /**
     * A share link is a public URL whose only protection is its password, so
     * the POST that checks it is a guessing oracle unless something stops it.
     * The threshold ships at 10 wrong attempts per minute.
     */
    @Test
    void repeatedWrongPasswordsAreRefused() throws Exception {
        ShareLink link = passwordProtectedEntryLink("shareThrottleEntry", "right-pw");
        MockHttpSession session = new MockHttpSession();

        // Well past the configured threshold, all from one address.
        int refusals = 0;
        for (int attempt = 0; attempt < 30; attempt++) {
            MockHttpServletResponse response = new MockHttpServletResponse();
            controller.unlock(link.getToken(), "wrong-" + attempt,
                    guessingRequest(session), response);
            if (response.getStatus() == 429) {
                refusals++;
            }
        }

        assertTrue(refusals > 0,
                "an unthrottled password form lets anyone with the link guess at "
                        + "network speed; none of 30 wrong attempts was refused");
    }

    /**
     * The throttle counts wrong passwords only. Someone who knows the password
     * must not be locked out of their own gallery by reloading it, and a NAT
     * gateway must not let one clumsy visitor lock out everyone behind it.
     */
    @Test
    void correctPasswordsAreNeverThrottled() throws Exception {
        ShareLink link = passwordProtectedEntryLink("shareThrottleOkEntry", "right-pw");

        for (int attempt = 0; attempt < 30; attempt++) {
            MockHttpServletResponse response = new MockHttpServletResponse();
            ModelAndView post = controller.unlock(link.getToken(), "right-pw",
                    guessingRequest(new MockHttpSession()), response);
            assertEquals("redirect:/share/" + link.getToken(), post.getViewName(),
                    "a correct password was refused on attempt " + attempt);
        }
    }

    /** A request from a fixed address, so the throttle sees one client. */
    private MockHttpServletRequest guessingRequest(MockHttpSession session) {
        MockHttpServletRequest request = request(session);
        request.setRemoteAddr("198.51.100.7");
        return request;
    }

    /**
     * The throttle is switchable, and a controller built with it off must let
     * every attempt through -- otherwise an operator who turns it off in a
     * trusted network still gets 429s and no way to see why.
     */
    @Test
    void throttlingCanBeTurnedOff() throws Exception {
        String previous = ControllerTestFixture.setConfigProperty(
                "share.password.throttle.enabled", "false");
        try {
            // A fresh controller: the throttle is resolved once, on first use.
            ShareController unthrottled = new ShareController();
            inject(unthrottled, "weblogger", WebloggerFactory.getWeblogger());
            ShareLink link = passwordProtectedEntryLink("shareThrottleOffEntry", "right-pw");

            for (int attempt = 0; attempt < 30; attempt++) {
                MockHttpServletResponse response = new MockHttpServletResponse();
                unthrottled.unlock(link.getToken(), "wrong-" + attempt,
                        guessingRequest(new MockHttpSession()), response);
                assertEquals(200, response.getStatus(),
                        "no attempt should be refused when throttling is off");
            }
        } finally {
            ControllerTestFixture.restoreConfigProperty(
                    "share.password.throttle.enabled", previous);
        }
    }

    /**
     * A mistyped number in the properties file must not take the share pages
     * down. The throttle falls back to its default rather than letting a
     * NumberFormatException escape on the first password attempt anyone makes.
     */
    @Test
    void aMalformedThrottleSettingFallsBackInsteadOfFailing() throws Exception {
        String previous = ControllerTestFixture.setConfigProperty(
                "share.password.throttle.threshold", "ten");
        try {
            ShareController misconfigured = new ShareController();
            inject(misconfigured, "weblogger", WebloggerFactory.getWeblogger());
            ShareLink link = passwordProtectedEntryLink("shareThrottleBadEntry", "right-pw");

            MockHttpServletResponse response = new MockHttpServletResponse();
            ModelAndView view = misconfigured.unlock(link.getToken(), "wrong-pw",
                    guessingRequest(new MockHttpSession()), response);

            assertEquals(ShareController.PASSWORD_FORM_VIEW, view.getViewName(),
                    "a bad threshold must degrade to the default, not throw");
        } finally {
            ControllerTestFixture.restoreConfigProperty(
                    "share.password.throttle.threshold", previous);
        }
    }
}
