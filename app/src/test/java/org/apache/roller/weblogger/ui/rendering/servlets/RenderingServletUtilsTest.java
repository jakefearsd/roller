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

import java.util.Map;

import org.apache.roller.weblogger.ui.rendering.RenderingException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Tests for {@link RenderingServletUtils}.
 */
class RenderingServletUtilsTest {

    @Test
    void aSuccessfulRenderReturnsTheBufferAndSendsNoErrorResponse() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        var output = RenderingServletUtils.render(
                (model, writer) -> {
                    try {
                        writer.write("hi");
                    } catch (java.io.IOException e) {
                        throw new java.io.UncheckedIOException(e);
                    }
                },
                Map.of(), 1024, "test", response);

        assertEquals("hi", output.getContentAsString());
        assertEquals(200, response.getStatus());
    }

    @Test
    void aRenderingFailureSends404AndReturnsNull() throws Exception {
        // The buffer's own best-effort close() cannot be made to throw from a
        // real CachedContent (ByteArrayOutputStream/PrintWriter never do), so
        // this covers the render-failure path up to and including the 404 --
        // not the doubly-nested close-failure log line, which is genuinely
        // unreachable without a production seam built solely for the test.
        MockHttpServletResponse response = new MockHttpServletResponse();

        var output = RenderingServletUtils.render(
                (model, writer) -> {
                    throw new RenderingException("boom");
                },
                Map.of(), 1024, "test", response);

        assertNull(output);
        assertEquals(404, response.getStatus());
    }
}
