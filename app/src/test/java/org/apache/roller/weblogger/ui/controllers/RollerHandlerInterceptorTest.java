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
package org.apache.roller.weblogger.ui.controllers;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link RollerHandlerInterceptor}.
 *
 * <p>Only tests that do not require the Guice-wired Weblogger business layer
 * are included here. The interceptor's security and user-resolution logic
 * depends heavily on {@code WebloggerFactory} which is initialized at
 * application startup and is impractical to mock in a lightweight unit test.
 */
class RollerHandlerInterceptorTest {

    private RollerHandlerInterceptor interceptor;
    private HttpServletRequest request;
    private HttpServletResponse response;

    @BeforeEach
    void setUp() {
        interceptor = new RollerHandlerInterceptor();
        request = mock(HttpServletRequest.class);
        response = mock(HttpServletResponse.class);
    }

    @Test
    void nonHandlerMethodPassesThrough() throws Exception {
        // A non-HandlerMethod handler (e.g., a static resource handler)
        // should always pass through without any security checks.
        Object plainHandler = new Object();
        boolean result = interceptor.preHandle(request, response, plainHandler);
        assertTrue(result, "Non-HandlerMethod handlers should pass through");
    }
}
