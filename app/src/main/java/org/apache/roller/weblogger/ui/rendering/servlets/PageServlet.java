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
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.roller.util.RollerConstants;
import org.apache.roller.weblogger.WebloggerException;
import org.apache.roller.weblogger.business.HitCountQueue;
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
import org.apache.roller.weblogger.ui.rendering.util.WeblogEntryCommentForm;
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

    private static Log log = LogFactory.getLog(PageServlet.class);
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
                && !weblog.getEditorTheme().equals(WeblogTheme.CUSTOM)
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
                log.error("ERROR - reloading theme " + ex);
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
                log.debug("HIT " + cacheKey);

                // allow for hit counting
                if (!isSiteWide
                        && (pageRequest.isWebsitePageHit() || pageRequest
                                .isOtherPageHit())) {
                    this.processHit(weblog);
                }

                response.setContentLength(cachedContent.getContent().length);
                response.setContentType(cachedContent.getContentType());
                response.getOutputStream().write(cachedContent.getContent());
                return;
            } else {
                log.debug("MISS " + cacheKey);
            }
        }

        log.debug("Looking for template to use for rendering");

        // figure out what template to use
        ThemeTemplate page = selectTemplate(request, pageRequest, weblog);
        if (page == null) {
            RenderingServletUtils.sendNotFound(response);
            return;
        }

        log.debug("page found, dealing with it");

        // validation. make sure that request input makes sense.
        String rejection = rejectionReason(pageRequest, weblog, page, isSiteWide);
        if (rejection != null) {
            log.debug("page failed validation, bailing out: " + rejection);
            RenderingServletUtils.sendNotFound(response);
            return;
        }

        // do we need to force a specific locale for the request?
        if (pageRequest.getLocale() == null && !weblog.isShowAllLangs()) {
            pageRequest.setLocale(weblog.getLocale());
        }

        // allow for hit counting
        if (!isSiteWide
                && (pageRequest.isWebsitePageHit() || pageRequest
                        .isOtherPageHit())) {
            this.processHit(weblog);
        }

        // looks like we need to render content
        // set the content deviceType
        String contentType = resolveContentType(page);

        HashMap<String, Object> model = new HashMap<>();
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

            // if this was a comment posting, check for comment form
            WeblogEntryCommentForm commentForm = (WeblogEntryCommentForm) request
                    .getAttribute("commentForm");
            if (commentForm != null) {
                initData.put("commentForm", commentForm);
            }

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
            log.error("Couldn't find renderer for page " + page.getId(), e);
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
            log.debug("PUT " + cacheKey);

            // put it in the right cache
            if (isSiteWide) {
                siteWideCache.put(cacheKey, rendererOutput);
            } else {
                weblogPageCache.put(cacheKey, rendererOutput);
            }
        } else {
            log.debug("SKIPPED " + cacheKey);
        }

        log.debug("Exiting");
    }


    /**
     * The template that renders this request, or {@code null} when nothing
     * does and the answer is a 404.
     *
     * <p>Extracted from {@code doGet}, which reached 380 lines doing eight
     * separate jobs and could only be exercised end to end. The order of the
     * branches below is the behaviour: a popup wins over everything, an
     * explicitly named page never falls through to the default (asking for a
     * page that does not exist is a 404, not the front page), a tags index
     * likewise, and only a permalink is allowed to fall back.
     */
    static ThemeTemplate selectTemplate(HttpServletRequest request,
            WeblogPageRequest pageRequest, Weblog weblog) {

        ThemeTemplate page = null;

        // If this is a popup request, then deal with it specially
        if (request.getParameter("popup") != null) {
            try {
                // Does user have a popupcomments page?
                page = weblog.getTheme().getTemplateByName("_popupcomments");
            } catch (Exception e) {
                // ignored ... considered page not found
            }

            // User doesn't have one so return the default
            if (page == null) {
                page = new StaticThemeTemplate(
                        "templates/weblog/popupcomments.vm", TemplateLanguage.VELOCITY);
            }
            return page;
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
                log.error("Error getting default page for weblog = "
                        + weblog.getHandle(), e);
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

        // locale view allowed only if weblog has enabled it
        if (pageRequest.getLocale() != null && !pageRequest.getWeblog().isEnableMultiLang()) {
            return "locale view requested but the weblog does not enable multiple languages";
        }

        if (pageRequest.getWeblogAnchor() != null) {
            // permalink specified. entry must exist, be published before the
            // current time, and its locale must match.
            WeblogEntry entry = pageRequest.getWeblogEntry();
            if (entry == null) {
                return "no entry with that anchor";
            }
            if (pageRequest.getLocale() != null
                    && !entry.getLocale().startsWith(pageRequest.getLocale())) {
                return "entry is not in the requested locale";
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
     * Handle POST requests.
     * 
     * We have this here because the comment servlet actually forwards some of
     * its requests on to us to render some pages with cusom messaging. We may
     * want to revisit this approach in the future and see if we can do this in
     * a different way, but for now this is the easy way.
     */
    @Override
    public void doPost(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {

        // make sure caching is disabled
        request.setAttribute("skipCache", "true");

        // handle just like a GET request
        this.doGet(request, response);
    }

    /**
     * Notify the hit tracker that it has an incoming page hit.
     */
    private void processHit(Weblog weblog) {

        HitCountQueue counter = HitCountQueue.getInstance();
        counter.processHit(weblog);
    }
}
