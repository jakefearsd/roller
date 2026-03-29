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

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.roller.weblogger.ui.rendering.Renderer;
import org.apache.roller.weblogger.util.cache.CachedContent;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Map;

final class RenderingServletUtils {

    private static final Log log = LogFactory.getLog(RenderingServletUtils.class);

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
            log.error("Error during rendering for " + logContext, e);
            try { output.close(); } catch (IOException ignored) {}
            sendNotFound(response);
            return null;
        }
    }
}
