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

package org.apache.roller.weblogger.ui.rendering;

import java.io.IOException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.apache.roller.weblogger.config.WebloggerConfig;
import org.apache.roller.weblogger.business.VirtualHostRegistry;
import org.apache.roller.weblogger.business.WebloggerFactory;
import org.apache.roller.weblogger.pojos.Weblog;


/**
 * Handles rendering requests for Roller pages/feeds by routing to the appropriate Servlet.
 *
 * This request mapper is used to map all weblog specific urls of the form
 * /<weblog handle>/* to the appropriate servlet for handling the actual
 * request.
 *
 * TODO: we should try and make this class easier to extend and build upon
 */
// PMD.GuardLogStatement: every violation in this class is a parameterized
// SLF4J {} call whose data argument is a cheap accessor (a getter,
// getClass(), or similar single-field read), not the expensive
// computation this rule exists to catch. Guarding it with isXEnabled()
// would be pure ceremony -- SLF4J already defers message formatting.
// See CLAUDE.md's Static analysis section.
@SuppressWarnings("PMD.GuardLogStatement")
public class WeblogRequestMapper implements RequestMapper {
    
    private static final Logger log = LoggerFactory.getLogger(WeblogRequestMapper.class);
    
    private static final String PAGE_SERVLET = "/roller-ui/rendering/page";
    private static final String FEED_SERVLET = "/roller-ui/rendering/feed";
    private static final String RESOURCE_SERVLET = "/roller-ui/rendering/resources";
    private static final String MEDIA_SERVLET = "/roller-ui/rendering/media-resources";
    private static final String SEARCH_SERVLET = "/roller-ui/rendering/search";


    // url patterns that are not allowed to be considered weblog handles
    Set<String> restricted = null;

    // Reserved on a custom domain. See appProtectedUrls' comment in
    // roller.properties for why this is a strict subset of `restricted`.
    Set<String> appRestricted = null;


    public WeblogRequestMapper() {

        this.restricted = new HashSet<>();

        // build roller restricted list
        String restrictList =
                WebloggerConfig.getProperty("rendering.weblogMapper.rollerProtectedUrls");
        if(restrictList != null && !restrictList.isBlank()) {
            String[] restrict = restrictList.split(",");
            this.restricted.addAll(Arrays.asList(restrict));
        }

        // add user restricted list
        restrictList =
                WebloggerConfig.getProperty("rendering.weblogMapper.userProtectedUrls");
        if(restrictList != null && !restrictList.isBlank()) {
            String[] restrict = restrictList.split(",");
            this.restricted.addAll(Arrays.asList(restrict));
        }

        this.appRestricted = new HashSet<>();

        // build app-level restricted list -- the subset reserved on a
        // custom domain (see appProtectedUrls' comment in roller.properties)
        String appRestrictList =
                WebloggerConfig.getProperty("rendering.weblogMapper.appProtectedUrls");
        if(appRestrictList != null && !appRestrictList.isBlank()) {
            String[] restrict = appRestrictList.split(",");
            this.appRestricted.addAll(Arrays.asList(restrict));
        }

        // add user restricted list -- app-level by definition, so it applies
        // on a custom domain too
        restrictList =
                WebloggerConfig.getProperty("rendering.weblogMapper.userProtectedUrls");
        if(restrictList != null && !restrictList.isBlank()) {
            String[] restrict = restrictList.split(",");
            this.appRestricted.addAll(Arrays.asList(restrict));
        }
    }
    
    
    @Override
    public boolean handleRequest(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {
        
        // kinda silly, but we need to keep track of whether or not the url had
        // a trailing slash so that we can act accordingly
        boolean trailingSlash = false;
        
        String weblogHandle = null;
        String weblogLocale = null;
        String weblogRequestContext = null;
        String weblogRequestData = null;
        
        log.debug("evaluating [{}]", request.getRequestURI());

        // Host-first resolution. A weblog that owns a hostname supplies its own
        // handle, so the ENTIRE path is weblog-relative and the first segment is
        // content (a page slug, a context) rather than a handle. Everything
        // after this block -- locale detection, context/data splitting, the
        // trailing-slash rules, the forward url -- is identical either way,
        // which is the whole reason resolution lives here rather than in a
        // second mapper that would have to reimplement it.
        String vhostHandle = VirtualHostRegistry.handleFor(request.getHeader("Host"));

        String servlet = request.getRequestURI();
        String pathInfo = null;

        if (servlet == null) {
            return false;
        }
        if (request.getContextPath() != null) {
            servlet = servlet.substring(request.getContextPath().length());
        }
        if (servlet.startsWith("/")) {
            servlet = servlet.substring(1);
        }
        if (servlet.endsWith("/")) {
            servlet = servlet.substring(0, servlet.length() - 1);
            trailingSlash = true;
        }

        // The first path segment, whatever it turns out to mean. On a custom
        // domain it is content; otherwise it is the candidate weblog handle.
        int firstSlash = servlet.indexOf('/');
        String firstSegment = firstSlash < 0 ? servlet : servlet.substring(0, firstSlash);

        if (vhostHandle != null) {
            weblogHandle = vhostHandle;
            pathInfo = servlet.isEmpty() ? null : servlet;
            if (servlet.isEmpty()) {
                // The custom domain's root IS the weblog home and is already the
                // canonical url. There is no shorter form to redirect to, so
                // suppress the trailing-slash redirect for this case ONLY --
                // suppressing it for every vhost request would make the 404
                // branch below swallow every permalink.
                trailingSlash = true;
            }
        } else if (!servlet.isEmpty()) {
            weblogHandle = firstSegment;
            if (firstSlash != -1) {
                pathInfo = servlet.substring(firstSlash + 1);
            }
        }

        log.debug("potential weblog handle = {}", weblogHandle);

        // The protected-url list applies in BOTH modes, but which list differs.
        // On the site host a context is always the SECOND segment
        // (/<handle>/page/x), so the full `restricted` set (application paths
        // PLUS weblog request contexts) is tested against the first segment,
        // which is always the candidate handle. On a custom domain the whole
        // path is weblog-relative and the first segment is content -- a
        // context there is the FIRST segment (/page/x) -- so only
        // APPLICATION paths are reserved: /themes/**, /webjars/**,
        // /roller-ui/rendering/**, /newsletter/**, /robots.txt and
        // /sitemap.xml still must not be swallowed, but reserving the weblog
        // request contexts here would kill /page/<theme>.css (the theme
        // stylesheet on every single page), /search and /resource/<file> on
        // every vhost page. Only the isWeblog() half is skippable: a
        // host-resolved handle is a weblog by construction.
        Set<String> reserved = (vhostHandle != null) ? appRestricted : restricted;
        if (reserved.contains(firstSegment)) {
            log.debug("SKIPPED {}", firstSegment);
            return false;
        }
        if (vhostHandle == null && !this.isWeblog(weblogHandle)) {
            log.debug("SKIPPED {}", weblogHandle);
            return false;
        }

        // The custom domain is canonical: a weblog that has one is reachable at
        // exactly one address per page, and any other host permanently
        // redirects there. Absolute by necessity -- this crosses hosts -- and
        // it STILL needs the context path: this is one Tomcat context reached
        // under many hostnames, so a deployment under a prefix (e.g. /roller)
        // has that prefix on the custom domain too. pathInfo was stripped of
        // it above (line ~139), so it has to be added back here rather than
        // assumed away the way the container-internal FORWARD below can.
        // Redirect precisely when the weblog has a hostname and THIS request
        // did not arrive on it. vhostHandle != null means the host already
        // resolved the weblog, i.e. we are on the canonical domain already.
        String canonicalHost = VirtualHostRegistry.hostFor(weblogHandle);
        if (vhostHandle == null && canonicalHost != null) {
            StringBuilder target = new StringBuilder("https://").append(canonicalHost);
            target.append(request.getContextPath());
            if (pathInfo != null) {
                target.append('/').append(pathInfo);
            }
            if (trailingSlash && (pathInfo == null || !pathInfo.endsWith("/"))) {
                target.append('/');
            }
            if (request.getQueryString() != null) {
                target.append('?').append(request.getQueryString());
            }
            response.setStatus(HttpServletResponse.SC_MOVED_PERMANENTLY);
            response.setHeader("Location", target.toString());
            return true;
        }

        log.debug("WEBLOG_URL {}", request.getServletPath());
        
        // parse the rest of the url and build forward url
        if(pathInfo != null) {
            
            // parse the next portion of the url
            // we expect [locale/]<context>/<extra>/<info>
            String[] urlPath = pathInfo.split("/", 3);
            
            // if we have a locale, deal with it
            if(this.isLocale(urlPath[0])) {
                weblogLocale = urlPath[0];
                
                // no extra path info specified
                if(urlPath.length == 2) {
                    weblogRequestContext = urlPath[1];
                    weblogRequestData = null;
                    
                // request contains extra path info
                } else if(urlPath.length == 3) {
                    weblogRequestContext = urlPath[1];
                    weblogRequestData = urlPath[2];
                }
            
            // otherwise locale is empty
            } else {
                weblogLocale = null;
                weblogRequestContext = urlPath[0];
                
                // last part of request is extra path info
                if(urlPath.length == 2) {
                    weblogRequestData = urlPath[1];
                    
                // if we didn't have a locale then we have split too much
                // so we reassemble the last 2 path elements together
                } else if(urlPath.length == 3) {
                    weblogRequestData = urlPath[1] + "/" + urlPath[2];
                }
            }
            
        }
        
        // special handling for trailing slash issue
        // we need this because by http standards the urls /foo and /foo/ are
        // supposed to be considered different, so we must enforce that
        if(weblogRequestContext == null && !trailingSlash) {
            // this means someone referred to a weblog index page with the
            // shortest form of url /<weblog> or /<weblog>/<locale> and we need
            // to do a redirect to /<weblog>/ or /<weblog>/<locale>/
            //
            // Two things here are easy to get wrong, and both were:
            //
            // 1. The context path has to be on the front. Unlike the FORWARD
            //    below -- which is dispatched inside the container and is
            //    therefore context-relative -- this location goes back to the
            //    browser, and a leading-slash location is resolved against the
            //    SERVER root, not the application root (Servlet 6.1,
            //    sendRedirect). Deployed under /roller, omitting it sent every
            //    reader of /roller/<weblog> to /<weblog>, which 404s. The bug
            //    is invisible at the root context, which is now the default --
            //    hence the test at both contexts.
            // 2. The locale has to survive. The comment above always claimed
            //    /<weblog>/<locale>/ was a redirect target, but the url was
            //    built from the handle alone, so /<weblog>/de quietly landed on
            //    the weblog's default-locale home instead of the German one.
            // 3. In vhost mode there is no handle segment at all -- the host
            //    already supplies it (see the "Host-first resolution" comment
            //    above) -- so appending weblogHandle here would leak the
            //    handle back into a user-visible url on exactly the host this
            //    feature exists to remove it from, and the resulting
            //    /<contextPath>/<handle>/<locale>/ url is not one this mapper
            //    (or WeblogRequestMapper in vhost mode) can ever resolve: it
            //    404s.
            StringBuilder redirectUrl = new StringBuilder(request.getContextPath()).append('/');
            if (vhostHandle == null) {
                redirectUrl.append(weblogHandle).append('/');
            }
            if(weblogLocale != null) {
                redirectUrl.append(weblogLocale).append('/');
            }
            if(request.getQueryString() != null) {
                redirectUrl.append('?').append(request.getQueryString());
            }

            response.sendRedirect(redirectUrl.toString());
            return true;
            
        } else if(weblogRequestContext != null &&
                "tags".equals(weblogRequestContext)) {
            // tags section can have an index page at /<weblog>/tags/ and
            // a tags query at /<weblog>/tags/tag1+tag2, buth that's it
            if((weblogRequestData == null && !trailingSlash) ||
                    (weblogRequestData != null && trailingSlash)) {
                response.sendError(HttpServletResponse.SC_NOT_FOUND);
                return true;
            }
        } else if(weblogRequestContext != null && trailingSlash) {
            // this means that someone has accessed a weblog url and included
            // a trailing slash, like /<weblog>/entry/<anchor>/ which is not
            // supported, so we need to offer up a 404 Not Found
            response.sendError(HttpServletResponse.SC_NOT_FOUND);
            return true;
        }
        
        // calculate forward url
        String forwardUrl = calculateForwardUrl(request, weblogHandle, weblogLocale,
                weblogRequestContext, weblogRequestData);
        
        // if we don't have a forward url then the request was invalid somehow
        if(forwardUrl == null) {
            return false;
        }
        
        // dispatch to forward url
        log.debug("forwarding to {}", forwardUrl);
        RequestDispatcher dispatch = request.getRequestDispatcher(forwardUrl);
        dispatch.forward(request, response);
        
        // we dealt with this request ourselves, so return "true"
        return true;
    }

    
    /**
     * Convenience method for caculating the servlet forward url given a set
     * of information to make the decision with.
     *
     * handle is always assumed valid, all other params may be null.
     */
    private String calculateForwardUrl(HttpServletRequest request,
                                       String handle, String locale,
                                       String context, String data) {
        
        log.debug("{},{},{},{}", handle, locale, context, data);
        
        StringBuilder forwardUrl = new StringBuilder(64);

        // POST used to be routed here only for comment submission -- the
        // permalink, carrying a "content" param, forwarded to the comment
        // servlet. That servlet is gone with the comment subsystem, and
        // nothing else in the public url space accepts a POST, so every POST
        // is declined and falls through to the next filter/servlet.
        if("POST".equals(request.getMethod())) {
            return null;

        } else {
            // no context means weblog homepage
            if(context == null) {
                
                forwardUrl.append(PAGE_SERVLET);
                forwardUrl.append('/');
                forwardUrl.append(handle);
                if(locale != null) {
                    forwardUrl.append('/');
                    forwardUrl.append(locale);
                }
                
            } else {
                
                switch (context) {
                    // requests handled by PageServlet
                    case "page":
                    case "entry":
                    case "date":
                    case "category":
                    case "tags":
                        forwardUrl.append(PAGE_SERVLET);
                        forwardUrl.append('/');
                        forwardUrl.append(handle);
                        if(locale != null) {
                            forwardUrl.append('/');
                            forwardUrl.append(locale);
                        }
                        forwardUrl.append('/');
                        forwardUrl.append(context);
                        if(data != null) {
                            forwardUrl.append('/');
                            forwardUrl.append(data);
                        }
                        break;
                        
                    // requests handled by FeedServlet
                    case "feed":
                        forwardUrl.append(FEED_SERVLET);
                        forwardUrl.append('/');
                        forwardUrl.append(handle);
                        if(locale != null) {
                            forwardUrl.append('/');
                            forwardUrl.append(locale);
                        }
                        if(data != null) {
                            forwardUrl.append('/');
                            forwardUrl.append(data);
                        }
                        break;
                        
                    // requests handled by ResourceServlet
                    case "resource":
                        forwardUrl.append(RESOURCE_SERVLET);
                        forwardUrl.append('/');
                        forwardUrl.append(handle);
                        if(data != null) {
                            forwardUrl.append('/');
                            forwardUrl.append(data);
                        }
                        break;
                        
                    // requests handled by MediaResourceServlet
                    case "mediaresource":
                        forwardUrl.append(MEDIA_SERVLET);
                        forwardUrl.append('/');
                        forwardUrl.append(handle);
                        if(data != null) {
                            forwardUrl.append('/');
                            forwardUrl.append(data);
                        }
                        break;
                        
                    // requests handled by SearchServlet
                    case "search":
                        forwardUrl.append(SEARCH_SERVLET);
                        forwardUrl.append('/');
                        forwardUrl.append(handle);
                        if(locale != null) {
                            forwardUrl.append('/');
                            forwardUrl.append(locale);
                        }
                        break;

                    // Every reserved first-segment word (page/entry/date/
                    // category/tags/feed/resource/mediaresource/search) is
                    // one of the cases above, so anything that reaches here
                    // is either a static-page slug (/<handle>/<slug>, no
                    // further path data) or truly unsupported
                    // (/<handle>/<foo>/<bar>). Forward the first kind to
                    // PageServlet -- WeblogPageRequest resolves the slug
                    // (WeblogPageManager) and 404s itself, drafts included,
                    // exactly like an unknown "page"/"entry" name already
                    // does above. The second kind stays unsupported: a
                    // second path segment is never a page slug.
                    default:
                        if (data == null) {
                            forwardUrl.append(PAGE_SERVLET);
                            forwardUrl.append('/');
                            forwardUrl.append(handle);
                            if(locale != null) {
                                forwardUrl.append('/');
                                forwardUrl.append(locale);
                            }
                            forwardUrl.append('/');
                            forwardUrl.append(context);
                            break;
                        }
                        return null;
                }
            }
        }
        
        log.debug("FORWARD_URL {}", forwardUrl.toString());
        
        return forwardUrl.toString();
    }
    
    
    /**
     * convenience method which determines if the given string is a valid
     * weblog handle.
     */
    private boolean isWeblog(String potentialHandle) {
        
        log.debug("checking weblog handle {}", potentialHandle);
        
        boolean isWeblog = false;
        
        try {
            Weblog weblog = WebloggerFactory.getWeblogger().getWeblogManager()
                    .getWeblogByHandle(potentialHandle);
            
            if(weblog != null) {
                isWeblog = true;
            }
        } catch(Exception ignored) {
            // Any exception here (bad handle format, a manager not yet
            // ready) means this is not a valid weblog handle -- isWeblog
            // stays false either way, which is all this caller checks.
        }
        
        return isWeblog;
    }
    
    
    /**
     * Convenience method which determines if the given string is a valid
     * locale string.
     */
    private boolean isLocale(String potentialLocale) {
        
        boolean isLocale = false;
        
        // we only support 2 or 5 character locale strings, so check that first
        if(potentialLocale != null && 
                (potentialLocale.length() == 2 || potentialLocale.length() == 5)) {
            
            // now make sure that the format is proper ... e.g. "en_US"
            // we are not going to be picky about capitalization
            String[] langCountry = potentialLocale.split("_");
            if(langCountry.length == 1 && 
                    langCountry[0] != null && langCountry[0].length() == 2) {
                isLocale = true;
                
            } else if(langCountry.length == 2 && 
                    langCountry[0] != null && langCountry[0].length() == 2 && 
                    langCountry[1] != null && langCountry[1].length() == 2) {
                
                isLocale = true;
            }
        }
        
        return isLocale;
    }
    
}
