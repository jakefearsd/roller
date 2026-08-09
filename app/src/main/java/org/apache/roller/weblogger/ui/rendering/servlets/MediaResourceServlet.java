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

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import jakarta.servlet.ServletConfig;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.roller.util.RollerConstants;
import org.apache.roller.weblogger.WebloggerException;
import org.apache.roller.weblogger.business.FileContentManager;
import org.apache.roller.weblogger.business.MediaFileManager;
import org.apache.roller.weblogger.business.RenditionSupport;
import org.apache.roller.weblogger.business.WebloggerFactory;
import org.apache.roller.weblogger.pojos.FileContent;
import org.apache.roller.weblogger.pojos.MediaFile;
import org.apache.roller.weblogger.pojos.Weblog;
import org.apache.roller.weblogger.ui.rendering.util.ModDateHeaderUtil;
import org.apache.roller.weblogger.ui.rendering.util.WeblogMediaResourceRequest;

/**
 * Serves media files uploaded by users.
 * 
 * Since we keep resources in a location outside of the webapp context we need a
 * way to serve them up. This servlet assumes that resources are stored on a
 * filesystem in the "uploads.dir" directory.
 */
public class MediaResourceServlet extends HttpServlet {

    private static final long serialVersionUID = 1L;

    private static Log log = LogFactory.getLog(MediaResourceServlet.class);

    @Override
    public void init(ServletConfig config) throws ServletException {

        super.init(config);
        log.info("Initializing ResourceServlet");

    }

    /**
     * Handles requests for user uploaded media file resources.
     */
    @Override
    public void doGet(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {

        MediaFileManager mfMgr = WebloggerFactory.getWeblogger()
                .getMediaFileManager();

        Weblog weblog;

        WeblogMediaResourceRequest resourceRequest;
        try {
            // parse the incoming request and extract the relevant data
            resourceRequest = new WeblogMediaResourceRequest(request);

            weblog = resourceRequest.getWeblog();
            if (weblog == null) {
                throw new WebloggerException("unable to lookup weblog: "
                        + resourceRequest.getWeblogHandle());
            }

        } catch (Exception e) {
            // invalid resource request or weblog doesn't exist
            log.debug("error creating weblog resource request", e);
            response.sendError(HttpServletResponse.SC_NOT_FOUND);
            return;
        }

        long resourceLastMod;
        InputStream resourceStream = null;
        MediaFile mediaFile;

        try {
            mediaFile = mfMgr.getMediaFile(resourceRequest.getResourceId(),
                    true);
            resourceLastMod = mediaFile.getLastModified();

        } catch (Exception ex) {
            // still not found? then we don't have it, 404.
            log.debug("Unable to get resource", ex);
            response.sendError(HttpServletResponse.SC_NOT_FOUND);
            return;
        }

        // A media file is only addressable through its own weblog's URL
        // space. The id lookup above is global, so without this check any
        // media id would be fetchable through any weblog handle (and the
        // ?t=/?w= variants below with it). Respond exactly as for an
        // unknown id. The directory's weblog is the authority here -- it is
        // also where the manager reads the bytes from.
        Weblog owningWeblog = mediaFile.getDirectory() == null
                ? null : mediaFile.getDirectory().getWeblog();
        if (!weblog.equals(owningWeblog)) {
            log.debug("media file " + resourceRequest.getResourceId()
                    + " does not belong to weblog " + weblog.getHandle());
            response.sendError(HttpServletResponse.SC_NOT_FOUND);
            return;
        }

        // A private directory's files are not served publicly at all. The
        // one exception is the owning weblog's own editors:
        // the media-library screens render their thumbnails through this
        // path, and an author must be able to see the photos they just
        // marked private. Those responses are marked private/no-store so no
        // shared cache can replay an editor's copy to an anonymous reader.
        if (mediaFile.getDirectory().isPrivate()) {
            if (!requesterMayEditWeblog(weblog)) {
                log.debug("media file " + resourceRequest.getResourceId()
                        + " is in a private directory; not serving it on the base path");
                response.sendError(HttpServletResponse.SC_NOT_FOUND);
                return;
            }
            response.setHeader("Cache-Control", "private, no-store");
        }

        // A ?w= URL serves different bytes and a different Content-Type
        // depending on the request's Accept header (webp sibling vs raster
        // rendition), so any shared cache must key on that header. Set before
        // the 304 branch below so revalidation responses carry it too.
        boolean renditionRequest = RenditionSupport.ladderWidths()
                .contains(resourceRequest.getWidth());
        if (renditionRequest) {
            response.setHeader("Vary", "Accept");
        }

        // Respond with 304 Not Modified if it is not modified.
        if (ModDateHeaderUtil.respondIfNotModified(request, response,
                resourceLastMod)) {
            return;
        } else {
            // set last-modified date
            ModDateHeaderUtil.setLastModifiedHeader(response, resourceLastMod);
        }

        // set the content type based on whatever is in our web.xml mime defs
        String servedContentType = null;
        if (resourceRequest.isThumbnail()) {
            servedContentType = "image/png";
            try {
                resourceStream = mediaFile.getThumbnailInputStream();
            } catch (Exception e) {
                if (log.isDebugEnabled()) {
                    log.debug(
                            "ERROR loading thumbnail for " + mediaFile.getId(),
                            e);
                } else {
                    log.warn("ERROR loading thumbnail for " + mediaFile.getId());
                }
            }
        } else if (renditionRequest) {
            // ?w= is validated against the ladder set only; any other value
            // (including a syntactically valid width Roller never generates)
            // falls straight through to the original below.
            int width = resourceRequest.getWidth();
            FileContentManager cmgr = WebloggerFactory.getWeblogger().getFileContentManager();

            if (acceptsWebp(request)) {
                try {
                    FileContent webp = cmgr.getFileContent(weblog, mediaFile.getId() + "_" + width + ".webp");
                    resourceStream = webp.getInputStream();
                    servedContentType = "image/webp";
                } catch (Exception e) {
                    // no webp sibling (cwebp unavailable at generation time, or
                    // this width was never generated) -- fall through
                    log.debug("No webp rendition for " + mediaFile.getId() + " at width " + width);
                }
            }

            if (resourceStream == null) {
                try {
                    FileContent rendition = cmgr.getFileContent(weblog, mediaFile.getId() + "_" + width);
                    resourceStream = rendition.getInputStream();
                    servedContentType = mediaFile.getContentType();
                } catch (Exception e) {
                    // rendition was never generated (narrower than the original,
                    // or generation failed) -- 404-safe fallback to the original
                    log.debug("No " + width + "w rendition for " + mediaFile.getId() + "; serving original");
                }
            }
        }

        if (resourceStream == null) {
            servedContentType = mediaFile.getContentType();
            resourceStream = mediaFile.getInputStream();
        }
        response.setContentType(servedContentType);

        OutputStream out;
        try {
            // ok, lets serve up the file
            byte[] buf = new byte[RollerConstants.EIGHT_KB_IN_BYTES];
            int length;
            out = response.getOutputStream();
            while ((length = resourceStream.read(buf)) > 0) {
                out.write(buf, 0, length);
            }

            // close output stream
            out.close();

        } catch (Exception ex) {
            log.error("ERROR", ex);
            if (!response.isCommitted()) {
                response.reset();
                response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            }
        } finally {
            // make sure stream to resource file is closed
            resourceStream.close();
        }

    }

    /**
     * True if the request's {@code Accept} header indicates the client will
     * take {@code image/webp} (Chrome/Firefox/Edge send this on every image
     * request; browsers that don't understand WebP simply omit it).
     */
    private static boolean acceptsWebp(HttpServletRequest request) {
        String accept = request.getHeader("Accept");
        return accept != null && accept.contains("image/webp");
    }

    /**
     * True when the current request carries an authenticated session whose
     * user holds at least the weakest permission ({@code EDIT_DRAFT}) on the
     * weblog -- i.e. one of the weblog's own editors. Anonymous requests and
     * users of other weblogs are false; any lookup failure fails closed.
     */
    private static boolean requesterMayEditWeblog(Weblog weblog) {
        try {
            org.springframework.security.core.Authentication authentication =
                    org.springframework.security.core.context.SecurityContextHolder
                            .getContext().getAuthentication();
            if (authentication == null || !authentication.isAuthenticated()
                    || authentication instanceof
                            org.springframework.security.authentication.AnonymousAuthenticationToken) {
                return false;
            }
            org.apache.roller.weblogger.pojos.User user = WebloggerFactory.getWeblogger()
                    .getUserManager().getUserByUserName(authentication.getName());
            if (user == null) {
                return false;
            }
            return WebloggerFactory.getWeblogger().getUserManager().checkPermission(
                    new org.apache.roller.weblogger.pojos.WeblogPermission(weblog,
                            java.util.List.of(
                                    org.apache.roller.weblogger.pojos.WeblogPermission.EDIT_DRAFT)),
                    user);
        } catch (Exception e) {
            log.debug("could not resolve an editor session for private media", e);
            return false;
        }
    }

}
