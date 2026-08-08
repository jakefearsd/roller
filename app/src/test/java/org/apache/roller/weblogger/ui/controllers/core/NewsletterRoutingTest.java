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

import org.apache.roller.weblogger.business.MockWeblogger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.method.HandlerMethod;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Pins the dispatch-precedence claim in {@link NewsletterController}'s class
 * javadoc with a real Spring MVC {@code RequestMappingHandlerMapping},
 * rather than trusting the javadoc's prose.
 *
 * <p>In production, {@code ServletRegistrationConfig.NEWSLETTER_URL_PATTERNS}
 * and {@code SHARE_URL_PATTERNS} each strip their own servlet prefix
 * ({@code /newsletter}, {@code /share}) before Spring resolves a handler, so
 * both controllers are matched against the SAME lookup-path space --
 * {@code NewsletterController}'s exact-literal {@code /subscribe} can
 * collide with {@code ShareController}'s {@code /{token:[A-Za-z0-9_-]+}}
 * template (token = "subscribe"). Standalone {@code MockMvc} over both
 * controllers reproduces exactly that shared space directly, with no
 * servlet-container prefix stripping needed to get there: registering both
 * controllers' {@code @RequestMapping} methods on one handler mapping is the
 * same pattern-matching decision Spring would make in production once the
 * prefixes are stripped.
 *
 * <p>Confirmed to fail (wrong handler / no handler) if
 * {@code NewsletterController.subscribe}'s {@code @PostMapping} were removed
 * or renamed -- checked by hand, not committed.
 */
class NewsletterRoutingTest {

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        MockWeblogger.install();
        NewsletterController newsletterController =
                ControllerTestFixture.withMessages(new NewsletterController());
        ShareController shareController =
                ControllerTestFixture.withMessages(new ShareController());
        // Registration order deliberately puts ShareController's template
        // mapping first: if precedence were decided by registration order
        // rather than specificity, this ordering would be the one that
        // exposes it.
        mockMvc = MockMvcBuilders.standaloneSetup(shareController, newsletterController).build();
    }

    @AfterEach
    void tearDown() {
        MockWeblogger.uninstall();
    }

    @Test
    void aPostToSubscribeResolvesToNewsletterControllerNotTheShareTokenTemplate() throws Exception {
        MvcResult result = mockMvc.perform(post("/subscribe")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"reader@example.com\",\"list_uuids\":[\"no-such-uuid\"]}"))
                .andReturn();

        HandlerMethod handler = assertInstanceOf(HandlerMethod.class, result.getHandler(),
                "expected a @Controller method to have handled the request");
        assertEquals(NewsletterController.class, handler.getBeanType());
        assertEquals("subscribe", handler.getMethod().getName());
    }
}
