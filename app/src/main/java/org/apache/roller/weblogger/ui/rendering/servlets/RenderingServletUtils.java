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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.apache.roller.weblogger.WebloggerException;
import org.apache.roller.weblogger.config.WebloggerConfig;
import org.apache.roller.weblogger.ui.rendering.Renderer;
import org.apache.roller.weblogger.ui.rendering.model.ModelLoader;
import org.apache.roller.weblogger.util.cache.CachedContent;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Map;

final class RenderingServletUtils {

    private static final Logger log = LoggerFactory.getLogger(RenderingServletUtils.class);

    private RenderingServletUtils() {}

    /**
     * Sends a 404 Not Found error, resetting the response first if not yet committed.
     */
    static void sendNotFound(HttpServletResponse response) throws IOException {
        if (!response.isCommitted()) {
            response.reset();
        }
        response.sendError(HttpServletResponse.SC_NOT_FOUND);
    }

    /**
     * Fills the rendering model, plus the site-wide models when this request is
     * for the front-page weblog.
     *
     * <p>All four rendering servlets loaded their own model list and then
     * conditionally loaded the site-wide one, in four identical copies that
     * differed only in which config key named the first list.
     *
     * <p>This deliberately does not handle failure: every caller already sits
     * inside a try that turns a WebloggerException into a reset-and-500, and
     * catching here as well would mean either duplicating that or handing back
     * a status flag the caller has to re-test -- which costs a branch in each
     * servlet to save one, and buys nothing.
     *
     * @param modelsProperty config key naming this servlet's model list
     * @param siteWide       whether to also load the site-wide models
     */
    static void loadModels(String modelsProperty, Map<String, Object> model,
                           Map<String, Object> initData, boolean siteWide)
            throws WebloggerException {

        ModelLoader.loadModels(WebloggerConfig.getProperty(modelsProperty),
                model, initData, true);

        if (siteWide) {
            ModelLoader.loadModels(WebloggerConfig.getProperty("rendering.siteModels"),
                    model, initData, true);
        }
    }

    /**
     * Renders content into a CachedContent buffer. Returns the buffer on success,
     * or null if rendering failed (in which case a 404 has already been sent).
     */
    static CachedContent render(Renderer renderer, Map<String, Object> model,
                                int bufferSize, String logContext,
                                HttpServletResponse response) throws IOException {
        return render(renderer, model, bufferSize, null, logContext, response);
    }

    /**
     * Renders content into a CachedContent buffer with a specific content type.
     * Returns the buffer on success, or null if rendering failed (in which case
     * a 404 has already been sent).
     */
    static CachedContent render(Renderer renderer, Map<String, Object> model,
                                int bufferSize, String contentType, String logContext,
                                HttpServletResponse response) throws IOException {
        CachedContent output = new CachedContent(bufferSize, contentType);
        try {
            log.debug("Doing rendering");
            renderer.render(model, output.getCachedWriter());
            output.flush();
            output.close();
            return output;
        } catch (Exception e) {
            log.error("Error during rendering for {}", logContext, e);
            try {
                output.close();
            } catch (IOException closeFailure) {
                log.debug("Failed to close render buffer after a render error", closeFailure);
            }
            sendNotFound(response);
            return null;
        }
    }
}
