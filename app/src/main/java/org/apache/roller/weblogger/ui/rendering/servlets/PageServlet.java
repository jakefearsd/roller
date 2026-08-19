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

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.apache.roller.util.RollerConstants;
import org.apache.roller.weblogger.WebloggerException;
import org.apache.roller.weblogger.business.WeblogEntryManager;
import org.apache.roller.weblogger.business.WebloggerFactory;
import org.apache.roller.weblogger.business.themes.ThemeManager;
import org.apache.roller.weblogger.config.WebloggerConfig;
import org.apache.roller.weblogger.config.WebloggerRuntimeConfig;
import org.apache.roller.weblogger.pojos.StaticThemeTemplate;
import org.apache.roller.weblogger.pojos.TemplateRendition.TemplateLanguage;
import org.apache.roller.weblogger.pojos.ThemeTemplate;
import org.apache.roller.weblogger.pojos.ThemeTemplate.ComponentType;
import org.apache.roller.weblogger.pojos.Weblog;
import org.apache.roller.weblogger.pojos.WeblogEntry;
import org.apache.roller.weblogger.pojos.WeblogTheme;
import org.apache.roller.weblogger.ui.core.RollerContext;
import org.apache.roller.weblogger.ui.rendering.Renderer;
import org.apache.roller.weblogger.ui.rendering.RendererManager;
import org.apache.roller.weblogger.ui.rendering.model.ModelLoader;
import org.apache.roller.weblogger.ui.rendering.util.ModDateHeaderUtil;
import org.apache.roller.weblogger.ui.rendering.util.WeblogPageRequest;
import org.apache.roller.weblogger.ui.rendering.util.cache.SiteWideCache;
import org.apache.roller.weblogger.ui.rendering.util.cache.WeblogPageCache;
import org.apache.roller.weblogger.util.I18nMessages;
import org.apache.roller.weblogger.util.cache.CachedContent;

import jakarta.servlet.ServletConfig;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.jsp.JspFactory;
import jakarta.servlet.jsp.PageContext;
import java.io.IOException;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

/**
 * Provides access to weblog pages.
 */
public class PageServlet extends HttpServlet {

    private static final long serialVersionUID = 1L;

    private static final Logger log = LoggerFactory.getLogger(PageServlet.class);
    // for caching
    private boolean excludeOwnerPages = false;
    private transient WeblogPageCache weblogPageCache = null;
    private transient SiteWideCache siteWideCache = null;

    // Development theme reloading
    Boolean themeReload = false;

    /**
     * Init method for this servlet
     */
    @Override
    public void init(ServletConfig servletConfig) throws ServletException {

        super.init(servletConfig);

        log.info("Initializing PageServlet");

        this.excludeOwnerPages = WebloggerConfig
                .getBooleanProperty("cache.excludeOwnerEditPages");

        // get a reference to the weblog page cache
        this.weblogPageCache = WeblogPageCache.getInstance();

        // get a reference to the site wide cache
        this.siteWideCache = SiteWideCache.getInstance();

        // Development theme reloading
        themeReload = WebloggerConfig.getBooleanProperty("themes.reload.mode");
    }

    /**
     * Handle GET requests for weblog pages.
     */
    // Two CachedContent sites here are false positives for CloseResource:
    // the cache-hit object (siteWideCache/weblogPageCache.get(...)) is owned
    // by the render cache and may still be serving other concurrent
    // requests -- this method must not close it. The cache-miss object
    // (RenderingServletUtils.render(...)) is already flushed and closed
    // internally before it is returned; there is nothing left to close here.
    @SuppressWarnings("PMD.CloseResource")
    @Override
    public void doGet(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {

        log.debug("Entering");

        Weblog weblog;
        boolean isSiteWide;

        WeblogPageRequest pageRequest;
        try {
            pageRequest = new WeblogPageRequest(request);

            weblog = pageRequest.getWeblog();
            if (weblog == null) {
                throw new WebloggerException("unable to lookup weblog: "
                        + pageRequest.getWeblogHandle());
            }

            // is this the site-wide weblog?
            isSiteWide = WebloggerRuntimeConfig.isSiteWideWeblog(pageRequest
                    .getWeblogHandle());
        } catch (Exception e) {
            // some kind of error parsing the request or looking up weblog
            log.debug("error creating page request", e);
            RenderingServletUtils.sendNotFound(response);
            return;
        }

        // determine the lastModified date for this content
        long lastModified = System.currentTimeMillis();
        if (isSiteWide) {
            lastModified = siteWideCache.getLastModified().getTime();
        } else if (weblog.getLastModified() != null) {
            lastModified = weblog.getLastModified().getTime();
        }

        // 304 Not Modified handling.
        // We skip this for logged in users to avoid the scenerio where a user
        // views their weblog, logs in, then gets a 304 without the 'edit' links
        if (!pageRequest.isLoggedIn()) {
            if (ModDateHeaderUtil.respondIfNotModified(request, response,
                    lastModified)) {
                return;
            } else {
                // set last-modified date
                ModDateHeaderUtil.setLastModifiedHeader(response, lastModified);
            }
        }

        // generate cache key
        String cacheKey;
        if (isSiteWide) {
            cacheKey = siteWideCache.generateKey(pageRequest);
        } else {
            cacheKey = weblogPageCache.generateKey(pageRequest);
        }

        // Development only. Reload if theme has been modified
        if (themeReload
                && !WeblogTheme.CUSTOM.equals(weblog.getEditorTheme())
                && (pageRequest.getPathInfo() == null || pageRequest
                        .getPathInfo() != null
                        && !pageRequest.getPathInfo().endsWith(".css"))) {
            try {
                ThemeManager manager = WebloggerFactory.getWeblogger()
                        .getThemeManager();
                boolean reloaded = manager.reLoadThemeFromDisk(weblog
                        .getEditorTheme());
                if (reloaded) {
                    if (isSiteWide) {
                        siteWideCache.clear();
                    } else {
                        weblogPageCache.clear();
                    }
                    I18nMessages.reloadBundle(weblog.getLocaleInstance());
                }

            } catch (Exception ex) {
                log.error("ERROR - reloading theme", ex);
            }
        }

        // cached content checking
        if ((!this.excludeOwnerPages || !pageRequest.isLoggedIn())
                && request.getAttribute("skipCache") == null
                && request.getParameter("skipCache") == null) {

            CachedContent cachedContent;
            if (isSiteWide) {
                cachedContent = (CachedContent) siteWideCache.get(cacheKey);
            } else {
                cachedContent = (CachedContent) weblogPageCache.get(cacheKey,
                        lastModified);
            }

            if (cachedContent != null) {
                log.debug("HIT {}", cacheKey);

                // hit counting used to gate on isWebsitePageHit()/isOtherPageHit()
                // here; Umami owns traffic counting now (see WeblogPageRequest).

                response.setContentLength(cachedContent.getContent().length);
                response.setContentType(cachedContent.getContentType());
                response.getOutputStream().write(cachedContent.getContent());
                return;
            } else {
                log.debug("MISS {}", cacheKey);
            }
        }

        log.debug("Looking for template to use for rendering");

        // figure out what template to use
        ThemeTemplate page = selectTemplate(pageRequest, weblog);
        if (page == null) {
            RenderingServletUtils.sendNotFound(response);
            return;
        }

        log.debug("page found, dealing with it");

        // validation. make sure that request input makes sense.
        String rejection = rejectionReason(pageRequest, weblog, page, isSiteWide);
        if (rejection != null) {
            log.debug("page failed validation, bailing out: {}", rejection);
            RenderingServletUtils.sendNotFound(response);
            return;
        }

        // Multi-locale weblogs are gone (see Weblog.isEnableMultiLang's
        // removal above): a weblog's default view always shows every
        // locale now, so forcing pageRequest.getLocale() to the weblog's own
        // locale here -- the old showAllLangs=false behaviour -- no longer
        // has anything to trigger it.

        // hit counting used to gate on isWebsitePageHit()/isOtherPageHit()
        // here; Umami owns traffic counting now (see WeblogPageRequest).

        // looks like we need to render content
        // set the content deviceType
        String contentType = resolveContentType(page);

        Map<String, Object> model = new HashMap<>();
        try {
            PageContext pageContext = JspFactory.getDefaultFactory()
                    .getPageContext(this, request, response, "", false,
                            RollerConstants.EIGHT_KB_IN_BYTES, true);

            // special hack for menu tag
            request.setAttribute("pageRequest", pageRequest);

            // populate the rendering model
            Map<String, Object> initData = new HashMap<>();
            initData.put("requestParameters", request.getParameterMap());
            initData.put("parsedRequest", pageRequest);
            initData.put("pageContext", pageContext);

            // define url strategy
            initData.put("urlStrategy", WebloggerFactory.getWeblogger()
                    .getUrlStrategy());

            // Load models for pages
            String pageModels = WebloggerConfig
                    .getProperty("rendering.pageModels");
            ModelLoader.loadModels(pageModels, model, initData, true);
            // Load special models for site-wide blog
            if (WebloggerRuntimeConfig.isSiteWideWeblog(weblog.getHandle())) {
                String siteModels = WebloggerConfig
                        .getProperty("rendering.siteModels");
                ModelLoader.loadModels(siteModels, model, initData, true);
            }

        } catch (WebloggerException ex) {
            log.error("Error loading model objects for page", ex);

            if (!response.isCommitted()) {
                response.reset();
            }
            response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            return;
        }

        // lookup Renderer we are going to use
        Renderer renderer;
        try {
            log.debug("Looking up renderer");
            renderer = RendererManager.getRenderer(page);
        } catch (Exception e) {
            // nobody wants to render my content :(
            log.error("Couldn't find renderer for page {}", page.getId(), e);
            RenderingServletUtils.sendNotFound(response);
            return;
        }

        // render content
        CachedContent rendererOutput = RenderingServletUtils.render(
                renderer, model, RollerConstants.TWENTYFOUR_KB_IN_BYTES, contentType,
                "page " + page.getId(), response);
        if (rendererOutput == null) {
            return;
        }

        // post rendering process
        // flush rendered content to response
        log.debug("Flushing response output");
        response.setContentType(contentType);
        response.setContentLength(rendererOutput.getContent().length);
        response.getOutputStream().write(rendererOutput.getContent());

        // cache rendered content. only cache if user is not logged in?
        if ((!this.excludeOwnerPages || !pageRequest.isLoggedIn())
                && request.getAttribute("skipCache") == null) {
            log.debug("PUT {}", cacheKey);

            // put it in the right cache
            if (isSiteWide) {
                siteWideCache.put(cacheKey, rendererOutput);
            } else {
                weblogPageCache.put(cacheKey, rendererOutput);
            }
        } else {
            log.debug("SKIPPED {}", cacheKey);
        }

        log.debug("Exiting");
    }


    /**
     * The template that renders this request, or {@code null} when nothing
     * does and the answer is a 404.
     *
     * <p>Extracted from {@code doGet}, which reached 380 lines doing eight
     * separate jobs and could only be exercised end to end. The order of the
     * branches below is the behaviour: an explicitly named page never falls
     * through to the default (asking for a page that does not exist is a
     * 404, not the front page), a tags index likewise, and only a permalink
     * is allowed to fall back.
     */
    static ThemeTemplate selectTemplate(WeblogPageRequest pageRequest, Weblog weblog) {

        ThemeTemplate page = null;

        // A static page: getPageSlug() is the syntactic signal, set during
        // parsing with no database access, that this request's single path
        // segment looked like a page slug. Only inside this branch do we
        // resolve it -- getWeblogPageContent() is the deferred database
        // lookup -- so that a cache hit, or a request that never gets this
        // far, never pays for it. An unresolved slug (unknown, or a draft
        // this reader is not entitled to see) is a 404, not a fall-through
        // to the branches below: a page slug never doubles as a permalink or
        // the weblog's default view.
        if (pageRequest.getPageSlug() != null) {
            if (pageRequest.getWeblogPageContent() == null) {
                return null;
            }

            // The theme may override with a custom template named _page;
            // otherwise the shipped default renders it. Falling back rather
            // than 404ing means a theme does not have to know pages exist.
            ThemeTemplate template = null;
            try {
                template = weblog.getTheme().getTemplateByName("_page");
            } catch (Exception e) {
                // Not simply "no _page override" -- getTemplateByName
                // returns null for that. An exception here means a real
                // lookup failure, so fall back to the default page template
                // (unchanged behavior) but leave a trace for whoever is
                // debugging why a themed page render looks generic.
                log.warn("Error looking up '_page' template for weblog {}", weblog.getHandle(), e);
            }
            return template != null ? template
                    : new StaticThemeTemplate("templates/weblog/page.vm",
                            TemplateLanguage.VELOCITY);
        }

        // If request specified the page, then go with that. No fallback: this
        // one 404s rather than quietly serving the default template.
        if ("page".equals(pageRequest.getContext())) {
            return pageRequest.getWeblogPage();
        }

        // If request specified tags section index, then look for a custom
        // template. Also no fallback.
        if ("tags".equals(pageRequest.getContext()) && pageRequest.getTags() != null) {
            try {
                return weblog.getTheme().getTemplateByAction(ComponentType.TAGSINDEX);
            } catch (Exception e) {
                log.error("Error getting weblog page for action 'tagsIndex'", e);
                return null;
            }
        }

        // If this is a permalink then look for a permalink template
        if (pageRequest.getWeblogAnchor() != null) {
            try {
                page = weblog.getTheme().getTemplateByAction(ComponentType.PERMALINK);
            } catch (Exception e) {
                log.error("Error getting weblog page for action 'permalink'", e);
            }
        }

        // if we haven't found a page yet then try our default page
        if (page == null) {
            try {
                page = weblog.getTheme().getDefaultTemplate();
            } catch (Exception e) {
                log.error("Error getting default page for weblog = {}", weblog.getHandle(), e);
            }
        }

        return page;
    }

    /**
     * Why this request cannot be served, or {@code null} when it can.
     *
     * <p>Returns a reason rather than a boolean deliberately. Every one of
     * these ends in the same bare 404, and when one fires wrongly -- a
     * permalink readers say is missing, a locale view that will not open --
     * the log line used to read only "page failed validation", which is the
     * same sentence for eight different causes.
     */
    static String rejectionReason(WeblogPageRequest pageRequest, Weblog weblog,
            ThemeTemplate page, boolean isSiteWide) {

        if (pageRequest.getWeblogPageName() != null && page.isHidden()) {
            return "template is hidden";
        }

        // multi-locale weblogs are gone: a request naming a locale view is
        // never servable, the same outcome this check already produced for
        // every real weblog (the flag defaulted false and nothing could turn
        // it on for a bundled theme).
        if (pageRequest.getLocale() != null) {
            return "locale view requested but the weblog does not enable multiple languages";
        }

        if (pageRequest.getWeblogAnchor() != null) {
            // permalink specified. entry must exist and be published before
            // the current time. (A per-entry locale mismatch was checked
            // here too, but pageRequest.getLocale() != null already returned
            // above -- this branch is only ever reached with a null locale.)
            WeblogEntry entry = pageRequest.getWeblogEntry();
            if (entry == null) {
                return "no entry with that anchor";
            }
            if (!entry.isPublished()) {
                return "entry is not published";
            }
            if (new Date().before(entry.getPubTime())) {
                return "entry is scheduled for the future";
            }
            return null;
        }

        if (pageRequest.getWeblogCategoryName() != null) {
            // category specified. category must exist.
            if (pageRequest.getWeblogCategory() == null) {
                return "no category by that name";
            }
            return null;
        }

        if (pageRequest.getTags() != null && !pageRequest.getTags().isEmpty()) {
            try {
                // tags specified. make sure they exist.
                WeblogEntryManager wmgr = WebloggerFactory.getWeblogger()
                        .getWeblogEntryManager();
                if (!wmgr.getTagComboExists(pageRequest.getTags(),
                        isSiteWide ? null : weblog)) {
                    return "no entries carry that combination of tags";
                }
            } catch (WebloggerException ex) {
                return "tag lookup failed: " + ex.getMessage();
            }
        }

        return null;
    }

    /**
     * The content type this template renders as: its own declaration, else the
     * type implied by its link's extension, else HTML.
     *
     * <p>The middle case is what lets a custom template named
     * {@code something.css} be served as a stylesheet rather than as markup a
     * browser refuses to apply.
     */
    static String resolveContentType(ThemeTemplate page) {
        if (StringUtils.isNotEmpty(page.getOutputContentType())) {
            return page.getOutputContentType() + "; charset=utf-8";
        }

        final String defaultContentType = "text/html; charset=utf-8";
        if (page.getLink() == null) {
            return defaultContentType;
        }

        String mimeType = RollerContext.getServletContext().getMimeType(page.getLink());
        return mimeType != null ? mimeType + "; charset=utf-8" : defaultContentType;
    }

    /**
     * Handle POST requests the same as GET, with caching disabled.
     *
     * <p>Nothing in {@code WeblogRequestMapper} forwards a POST here anymore
     * (that was comment-submission forwarding, now removed with the comment
     * subsystem); this stays as a defensive fallback for a direct POST to
     * the servlet path.
     */
    @Override
    public void doPost(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {

        // make sure caching is disabled
        request.setAttribute("skipCache", "true");

        // handle just like a GET request
        this.doGet(request, response);
    }
}
