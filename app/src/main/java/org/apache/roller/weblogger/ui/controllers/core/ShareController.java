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

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.roller.util.RollerConstants;
import org.apache.roller.weblogger.WebloggerException;
import org.apache.roller.weblogger.business.RenditionSupport;
import org.apache.roller.weblogger.business.shortcodes.GalleryMarkup;
import org.apache.roller.weblogger.config.WebloggerRuntimeConfig;
import org.apache.roller.weblogger.pojos.FileContent;
import org.apache.roller.weblogger.pojos.MediaFile;
import org.apache.roller.weblogger.pojos.MediaFileDirectory;
import org.apache.roller.weblogger.pojos.ShareLink;
import org.apache.roller.weblogger.pojos.WeblogEntry;
import org.apache.roller.weblogger.pojos.wrapper.MediaFileWrapper;
import org.apache.roller.weblogger.ui.controllers.BaseController;
import org.apache.roller.weblogger.config.WebloggerConfig;
import org.apache.roller.weblogger.ui.core.RollerContext;
import org.apache.roller.weblogger.util.GenericThrottle;
import org.apache.roller.weblogger.util.HTMLSanitizer;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.ModelAndView;

/**
 * Serves password-protectable share links at {@code /share/<token>}: a
 * tokened public page for one weblog entry or one (typically private) media
 * file directory, plus the token-scoped media route that keeps a private
 * directory's bytes behind the same gate as its page.
 *
 * <p><b>Routing.</b> The dispatcher reaches this controller through the
 * {@code /share/*} prefix mapping added in
 * {@code ServletRegistrationConfig#SHARE_URL_PATTERNS}. Because that is a
 * servlet <em>prefix</em> mapping, Spring strips {@code /share} from the
 * lookup path, so every handler mapping here is written relative to it
 * ({@code /{token}}, not {@code /share/{token}}). The token pattern
 * {@code [A-Za-z0-9_-]+} (the {@code TokenGenerator} alphabet, dot-free) also
 * guarantees these handlers can never match a {@code *.rol} or {@code *.xml}
 * lookup path, which always contains a dot.
 *
 * <p><b>The gate.</b> A tokened URL is public by construction
 * ({@code SecurityConfig}'s {@code anyRequest().permitAll()}), so the gate
 * lives here: unknown or expired tokens 404; a password-protected link shows
 * a password form (a JSP carrying {@code <sec:csrfInput/>}) until the
 * visitor's session has unlocked that specific token. Unlock state is a
 * session attribute -- a {@code Set} of unlocked tokens -- never a cookie,
 * and never part of any cache key, because...
 *
 * <p><b>...share pages bypass the render caches entirely.</b>
 * {@code WeblogPageCache} keys carry nothing about unlock state, so rendering
 * a gated page through the normal weblog pipeline would hand the unlocked
 * HTML to the next anonymous visitor. This controller renders its own
 * response per request, touches no cache, and marks every response
 * {@code Cache-Control: private, no-store}.
 *
 * <p><b>Media scoping.</b> A directory share serves its files only through
 * {@code /share/<token>/media/<fileId>} (with the usual {@code ?w=} rendition
 * and {@code ?t=true} thumbnail variants), and only for files of exactly that
 * directory; the base media path 404s anything in a private directory
 * ({@code MediaResourceServlet}). An entry share carries no media route of
 * its own -- an entry's images live in public directories and render through
 * their normal permalinks; a {@code [gallery]} of a <em>private</em>
 * directory inside a shared entry stays refused by the shortcode's own gate.
 */
@Controller
public class ShareController extends BaseController {

    private static final Log log = LogFactory.getLog(ShareController.class);

    /** Session attribute holding the {@code Set<String>} of unlocked share tokens. */
    static final String UNLOCKED_TOKENS_ATTRIBUTE = "shareLinkUnlockedTokens";

    /** The password form view (WEB-INF/jsps/core/SharePassword.jsp). */
    static final String PASSWORD_FORM_VIEW = "core/SharePassword";

    /** The password form's input name. */
    static final String PASSWORD_PARAMETER = "sharePassword";

    /**
     * Throttle for wrong password attempts, keyed by client address.
     *
     * <p>A share link is a public URL whose only protection is its password,
     * so without this the POST below is a guessing oracle that anyone holding
     * the link can drive as fast as the network allows. Built lazily on first
     * use rather than in a constructor, because {@code WebloggerConfig} is not
     * necessarily loaded when Spring instantiates controllers.
     *
     * <p>Only WRONG passwords are counted. A visitor who knows the password
     * can reload a gallery as often as they like, and a shared browser or a
     * NAT gateway cannot lock out a legitimate reader by being busy.
     */
    /** Not in the servlet API's constant list, which stops at 505. */
    private static final int TOO_MANY_REQUESTS = 429;

    private volatile GenericThrottle passwordThrottle;
    private volatile boolean passwordThrottleResolved;


    // ------------------------------------------------------------- security
    //
    // Share links are for anonymous visitors; the interceptor must not send
    // them to the login page. requiredWeblogPermissionActions() is overridden
    // too (belt and braces): today that check is nested inside the
    // isUserRequired() branch, but this controller must stay anonymous even
    // if the interceptor's nesting changes.

    @Override
    public boolean isUserRequired() {
        return false;
    }

    @Override
    public boolean isWeblogRequired() {
        return false;
    }

    @Override
    public List<String> requiredWeblogPermissionActions() {
        return Collections.emptyList();
    }

    @Override
    public List<String> requiredGlobalPermissionActions() {
        return Collections.emptyList();
    }

    // ------------------------------------------------------------ the page

    /**
     * The share page: 404 for unknown/expired tokens, the password form while
     * the session has not unlocked a password-protected link, otherwise the
     * rendered target (entry or directory gallery).
     */
    @GetMapping("/{token:[A-Za-z0-9_-]+}")
    public ModelAndView sharePage(@PathVariable("token") String token,
            HttpServletRequest request, HttpServletResponse response)
            throws WebloggerException, IOException {
        ShareLink link = liveLink(token);
        if (link == null) {
            response.sendError(HttpServletResponse.SC_NOT_FOUND);
            return null;
        }
        if (isLocked(link, request)) {
            return passwordForm(token, false);
        }
        String html = renderTarget(link);
        if (html == null) {
            // dangling target: the entry or directory was deleted after the
            // link was created -- nothing to share any more
            response.sendError(HttpServletResponse.SC_NOT_FOUND);
            return null;
        }
        response.setContentType("text/html;charset=UTF-8");
        response.setHeader("Cache-Control", "private, no-store");
        response.getWriter().write(html);
        return null;
    }

    /**
     * The password form's POST: on the right password the session unlocks
     * this token (and only this token) and redirects to the GET above; on a
     * wrong or missing password the form renders again with an error.
     */
    @PostMapping("/{token:[A-Za-z0-9_-]+}")
    public ModelAndView unlock(@PathVariable("token") String token,
            @RequestParam(value = PASSWORD_PARAMETER, required = false) String password,
            HttpServletRequest request, HttpServletResponse response)
            throws WebloggerException, IOException {
        ShareLink link = liveLink(token);
        if (link == null) {
            response.sendError(HttpServletResponse.SC_NOT_FOUND);
            return null;
        }
        if (link.getPasswordHash() != null) {
            if (isGuessingTooFast(request)) {
                // 429 rather than the form again: an attacker learns nothing
                // from it, and a real visitor who has genuinely mistyped ten
                // times in a minute is told to wait rather than silently
                // having their next correct attempt ignored.
                response.sendError(TOO_MANY_REQUESTS);
                return null;
            }
            if (password == null
                    || !RollerContext.getPasswordEncoder().matches(password, link.getPasswordHash())) {
                countWrongPassword(request);
                return passwordForm(token, true);
            }
            unlock(request.getSession(true), token);
        }
        return new ModelAndView("redirect:/share/" + token);
    }

    /** Whether this client has already used up its wrong-password budget. */
    private boolean isGuessingTooFast(HttpServletRequest request) {
        GenericThrottle throttle = passwordThrottle();
        return throttle != null && throttle.isAbusive(request.getRemoteAddr());
    }

    /** Records one wrong password against this client's budget. */
    private void countWrongPassword(HttpServletRequest request) {
        GenericThrottle throttle = passwordThrottle();
        if (throttle != null) {
            throttle.processHit(request.getRemoteAddr());
        }
    }

    /**
     * The throttle, or null when disabled. Resolved once and cached; the
     * double-checked flag is what keeps a disabled throttle from re-reading
     * configuration on every request.
     */
    private GenericThrottle passwordThrottle() {
        if (!passwordThrottleResolved) {
            synchronized (this) {
                if (!passwordThrottleResolved) {
                    passwordThrottle = buildPasswordThrottle();
                    passwordThrottleResolved = true;
                }
            }
        }
        return passwordThrottle;
    }

    private GenericThrottle buildPasswordThrottle() {
        if (!WebloggerConfig.getBooleanProperty("share.password.throttle.enabled")) {
            log.info("Share-link password throttling DISABLED");
            return null;
        }
        int threshold = intProperty("share.password.throttle.threshold", 10);
        int interval = intProperty("share.password.throttle.interval", 60);
        int maxEntries = intProperty("share.password.throttle.maxentries", 250);
        log.info("Share-link password throttling ENABLED: " + threshold
                + " wrong attempts per " + interval + "s");
        return new GenericThrottle(threshold, interval * RollerConstants.SEC_IN_MS, maxEntries);
    }

    private static int intProperty(String name, int fallback) {
        try {
            return Integer.parseInt(WebloggerConfig.getProperty(name));
        } catch (NumberFormatException e) {
            log.warn("bad input for config property " + name + "; using " + fallback, e);
            return fallback;
        }
    }

    // ----------------------------------------------------- token-scoped media

    /**
     * Serves a directory share's bytes, behind exactly the gate the page is
     * behind: valid unexpired token, unlocked session when password-set, and
     * the file must belong to the shared directory itself. Everything else --
     * unknown token, entry-target links, cross-directory ids -- is a 404
     * indistinguishable from an unknown file.
     *
     * <p>{@code ?w=} serves the ladder rendition (raster only -- no webp
     * negotiation here, so no {@code Vary} needed) and {@code ?t=true} the
     * thumbnail, mirroring {@code MediaResourceServlet}'s variants so
     * {@code GalleryMarkup}'s srcset URLs work unchanged in this URL space.
     */
    @GetMapping("/{token:[A-Za-z0-9_-]+}/media/{fileId}")
    public void shareMedia(@PathVariable("token") String token,
            @PathVariable("fileId") String fileId,
            @RequestParam(value = "w", required = false) Integer width,
            @RequestParam(value = "t", required = false) String thumbnail,
            HttpServletRequest request, HttpServletResponse response)
            throws WebloggerException, IOException {
        ShareLink link = liveLink(token);
        if (link == null || isLocked(link, request)
                || !ShareLink.TYPE_DIRECTORY.equals(link.getTargetType())) {
            response.sendError(HttpServletResponse.SC_NOT_FOUND);
            return;
        }

        MediaFile mediaFile;
        try {
            mediaFile = weblogger.getMediaFileManager().getMediaFile(fileId, true);
        } catch (Exception e) {
            log.debug("share media lookup failed for id " + fileId, e);
            response.sendError(HttpServletResponse.SC_NOT_FOUND);
            return;
        }
        if (mediaFile == null || mediaFile.getDirectory() == null
                || !link.getTargetId().equals(mediaFile.getDirectory().getId())) {
            response.sendError(HttpServletResponse.SC_NOT_FOUND);
            return;
        }

        // The gate decided per-visitor; neither browsers-shared caches nor
        // proxies may replay this response to anyone else.
        response.setHeader("Cache-Control", "private, no-store");

        String contentType = mediaFile.getContentType();
        InputStream content = null;
        if ("true".equals(thumbnail)) {
            try {
                content = mediaFile.getThumbnailInputStream();
                contentType = "image/png";
            } catch (Exception e) {
                log.debug("no thumbnail for shared media file " + fileId, e);
            }
        } else if (width != null && RenditionSupport.ladderWidths().contains(width)) {
            try {
                FileContent rendition = weblogger.getFileContentManager().getFileContent(
                        mediaFile.getDirectory().getWeblog(), fileId + "_" + width);
                content = rendition.getInputStream();
            } catch (Exception e) {
                // rendition never generated -- fall through to the original
                log.debug("no " + width + "w rendition for shared media file " + fileId);
            }
        }
        if (content == null) {
            contentType = mediaFile.getContentType();
            content = mediaFile.getInputStream();
        }
        response.setContentType(contentType);
        try (InputStream in = content) {
            OutputStream out = response.getOutputStream();
            byte[] buffer = new byte[RollerConstants.EIGHT_KB_IN_BYTES];
            int length;
            while ((length = in.read(buffer)) > 0) {
                out.write(buffer, 0, length);
            }
        }
    }

    // ------------------------------------------------------------ rendering

    /** The rendered share page for the link's target, or null for a dangling target. */
    private String renderTarget(ShareLink link) throws WebloggerException {
        if (ShareLink.TYPE_ENTRY.equals(link.getTargetType())) {
            WeblogEntry entry = weblogger.getWeblogEntryManager()
                    .getWeblogEntry(link.getTargetId());
            if (entry == null) {
                return null;
            }
            // displayContent runs the full transform pipeline: entry plugins,
            // unconditional shortcode expansion (a [gallery] of a public
            // directory renders; a private directory's stays as written by
            // the shortcode's own gate), then the sanitizer.
            return page(entry.getTitle(),
                    "<article class=\"share-entry\">\n"
                            + StringUtils.defaultString(entry.displayContent(null))
                            + "\n</article>");
        }
        if (ShareLink.TYPE_DIRECTORY.equals(link.getTargetType())) {
            MediaFileDirectory directory = weblogger.getMediaFileManager()
                    .getMediaFileDirectory(link.getTargetId());
            if (directory == null) {
                return null;
            }
            List<MediaFileWrapper> images = directory.getMediaFiles().stream()
                    .filter(MediaFile::isImageFile)
                    .sorted(GalleryMarkup.GALLERY_ORDER)
                    .map(mf -> MediaFileWrapper.wrap(mf, weblogger.getUrlStrategy()))
                    .toList();
            // The same grid markup the inline shortcode emits, but with every
            // media URL scoped to this share's token: the bytes stay behind
            // the gate that protects the page.
            String grid = GalleryMarkup.grid(images, 0, shareImageUrls(link.getToken()));
            return page(directory.getName(), grid);
        }
        log.warn("share link " + link.getId() + " has unknown target type "
                + link.getTargetType());
        return null;
    }

    /** Share-scoped media URLs: {@code <context>/share/<token>/media/<id>[?w=]}. */
    private static GalleryMarkup.ImageUrls shareImageUrls(String token) {
        String base = WebloggerRuntimeConfig.getAbsoluteContextURL()
                + "/share/" + token + "/media/";
        return new GalleryMarkup.ImageUrls() {
            @Override
            public String original(MediaFileWrapper image) {
                return base + image.getId();
            }

            @Override
            public String rendition(MediaFileWrapper image, int width) {
                return base + image.getId() + "?w=" + width;
            }
        };
    }

    /**
     * A minimal, theme-independent page around the shared content. Share
     * pages are not part of the weblog's public surface: no theme chrome, and
     * {@code noindex,nofollow} so a leaked link is not compounded by a
     * crawler.
     */
    private static String page(String title, String body) {
        String safeTitle = HTMLSanitizer.htmlEncodeApexesAndTags(
                StringUtils.defaultString(title));
        return "<!DOCTYPE html>\n<html>\n<head>\n"
                + "<meta charset=\"utf-8\">\n"
                + "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n"
                + "<meta name=\"robots\" content=\"noindex, nofollow\">\n"
                + "<title>" + safeTitle + "</title>\n"
                + "<style>\n"
                + "body { margin: 0 auto; max-width: 72rem; padding: 1rem; "
                + "font-family: system-ui, sans-serif; }\n"
                + "img { max-width: 100%; }\n"
                + GalleryMarkup.gridStyles()
                + "</style>\n"
                + galleryAssets()
                + "</head>\n<body>\n"
                + "<h1>" + safeTitle + "</h1>\n"
                + body
                + "\n</body>\n</html>\n";
    }

    /** Classpath location the PhotoSwipe webjar serves its assets from. */
    private static final String PHOTOSWIPE_DIST =
            "/META-INF/resources/webjars/photoswipe/5.4.3/dist/";

    /**
     * The self-hosted lightbox for share-page galleries: the same
     * link+module-script block the {@code #showGalleryAssets} macro in
     * {@code WEB-INF/velocity/weblog.vm} puts into every theme head (keep the
     * two in sync). Share pages render outside the theme pipeline, so the
     * block is emitted here; the script binds to {@code .jgrid a}, so the
     * shared grid markup lights up identically. Emitted only when the
     * PhotoSwipe webjar is actually on the classpath -- without it the grid
     * still renders, the anchors just navigate to the full-size image.
     */
    private static String galleryAssets() {
        if (ShareController.class.getResource(PHOTOSWIPE_DIST + "photoswipe.css") == null) {
            return "";
        }
        String dist = WebloggerRuntimeConfig.getRelativeContextURL()
                + "/webjars/photoswipe/5.4.3/dist/";
        return "<link rel=\"stylesheet\" href=\"" + dist + "photoswipe.css\">\n"
                + "<style>\n"
                + ".pswp__custom-caption {\n"
                + "  position: absolute; left: 50%; bottom: 18px; transform: translateX(-50%);\n"
                + "  max-width: calc(100% - 32px); padding: 3px 10px; border-radius: 4px;\n"
                + "  background: rgba(0, 0, 0, 0.55); color: white;\n"
                + "  font-size: 0.9em; text-align: center;\n"
                + "}\n"
                + ".pswp__exif {\n"
                + "  position: absolute; right: 12px; top: 56px; max-width: 60%;\n"
                + "  padding: 3px 10px; border-radius: 4px;\n"
                + "  background: rgba(0, 0, 0, 0.55); color: white;\n"
                + "  font-size: 0.8em; text-align: right;\n"
                + "}\n"
                + "</style>\n"
                + "<script type=\"module\">\n"
                + "if (document.querySelector('.jgrid')) {\n"
                + "  const { default: PhotoSwipeLightbox } =\n"
                + "      await import('" + dist + "photoswipe-lightbox.esm.min.js');\n"
                + "  const lightbox = new PhotoSwipeLightbox({\n"
                + "    gallery: '.jgrid',\n"
                + "    children: 'a',\n"
                + "    pswpModule: () => import('" + dist + "photoswipe.esm.min.js')\n"
                + "  });\n"
                + "  lightbox.addFilter('itemData', (itemData) => {\n"
                + "    const anchor = itemData.element;\n"
                + "    if (anchor && !itemData.msrc) {\n"
                + "      const img = anchor.querySelector('img');\n"
                + "      itemData.msrc = (img && (img.currentSrc || img.src)) || (itemData.src + '?t=true');\n"
                + "    }\n"
                + "    return itemData;\n"
                + "  });\n"
                + "  lightbox.on('uiRegister', () => {\n"
                + "    const pswp = lightbox.pswp;\n"
                + "    pswp.ui.registerElement({\n"
                + "      name: 'custom-caption', order: 9, isButton: false, appendTo: 'root',\n"
                + "      onInit: (el) => {\n"
                + "        pswp.on('change', () => {\n"
                + "          const anchor = pswp.currSlide.data.element;\n"
                + "          const figure = anchor ? anchor.closest('figure') : null;\n"
                + "          const caption = figure ? figure.querySelector('figcaption') : null;\n"
                + "          el.textContent = caption ? caption.textContent : '';\n"
                + "          el.hidden = !caption;\n"
                + "        });\n"
                + "      }\n"
                + "    });\n"
                + "    pswp.ui.registerElement({\n"
                + "      name: 'exif', order: 10, isButton: false, appendTo: 'root',\n"
                + "      onInit: (el) => {\n"
                + "        pswp.on('change', () => {\n"
                + "          const anchor = pswp.currSlide.data.element;\n"
                + "          const data = anchor ? anchor.dataset : {};\n"
                + "          const gear = [data.exifCamera, data.exifLens]\n"
                + "              .filter(Boolean).join(', ');\n"
                + "          const settings = [\n"
                + "            data.exifFocal, data.exifAperture, data.exifExposure,\n"
                + "            data.exifIso ? 'ISO ' + data.exifIso : ''\n"
                + "          ].filter(Boolean).join(' · ');\n"
                + "          const text = [gear, settings].filter(Boolean).join(' — ');\n"
                + "          el.textContent = text;\n"
                + "          el.hidden = !text;\n"
                + "        });\n"
                + "      }\n"
                + "    });\n"
                + "  });\n"
                + "  lightbox.init();\n"
                + "}\n"
                + "</script>\n";
    }

    // ------------------------------------------------------------- the gate

    /** The link for the token, or null when unknown or expired. */
    private ShareLink liveLink(String token) throws WebloggerException {
        ShareLink link = weblogger.getShareLinkManager().getShareLinkByToken(token);
        if (link == null) {
            return null;
        }
        if (link.getExpires() != null && link.getExpires().before(new Date())) {
            log.debug("share link " + link.getId() + " has expired");
            return null;
        }
        return link;
    }

    /** True when the link needs a password this session has not supplied yet. */
    private static boolean isLocked(ShareLink link, HttpServletRequest request) {
        if (link.getPasswordHash() == null) {
            return false;
        }
        HttpSession session = request.getSession(false);
        return session == null || !unlockedTokens(session).contains(link.getToken());
    }

    private static void unlock(HttpSession session, String token) {
        Set<String> unlocked = new HashSet<>(unlockedTokens(session));
        unlocked.add(token);
        session.setAttribute(UNLOCKED_TOKENS_ATTRIBUTE, unlocked);
    }

    @SuppressWarnings("unchecked")
    private static Set<String> unlockedTokens(HttpSession session) {
        Object attribute = session.getAttribute(UNLOCKED_TOKENS_ATTRIBUTE);
        return attribute instanceof Set ? (Set<String>) attribute : Set.of();
    }

    private static ModelAndView passwordForm(String token, boolean wrongPassword) {
        ModelAndView modelAndView = new ModelAndView(PASSWORD_FORM_VIEW);
        modelAndView.addObject("shareToken", token);
        modelAndView.addObject("shareWrongPassword", wrongPassword);
        return modelAndView;
    }
}
