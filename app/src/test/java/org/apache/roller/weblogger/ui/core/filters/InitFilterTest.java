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

package org.apache.roller.weblogger.ui.core.filters;

import jakarta.servlet.http.HttpServlet;

import org.apache.roller.weblogger.config.WebloggerRuntimeConfig;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.filter.ForwardedHeaderFilter;

import static org.junit.jupiter.api.Assertions.assertEquals;


/**
 * @author Kohei Nozaki
 */
public class InitFilterTest  {

    private static final String SERVER_NAME = "roller.example.com";

    @Test
    public void testGetAbsoluteUrlOnRootWithHttp() throws Exception {
        boolean secure = false;
        String contextPath = "";
        String requestURI = "/";
        String requestURL = "http://roller.example.com/";

        String absoluteUrl = InitFilter.getAbsoluteUrl(secure, SERVER_NAME, contextPath, requestURI, requestURL);
        assertEquals("http://roller.example.com", absoluteUrl);
    }

    @Test
    public void testGetAbsoluteUrlOnRootWithHttps() throws Exception {
        boolean secure = true;
        String contextPath = "";
        String requestURI = "/";
        String requestURL = "https://roller.example.com/";

        String absoluteUrl = InitFilter.getAbsoluteUrl(secure, SERVER_NAME, contextPath, requestURI, requestURL);
        assertEquals("https://roller.example.com", absoluteUrl);
    }

    @Test
    public void testGetAbsoluteUrlAgainstPermalinkWithHttps() throws Exception {
        boolean secure = true;
        String contextPath = "/roller";
        String requestURI = "/roller/handle/entry/title";
        String requestURL = "https://roller.example.com/roller/handle/entry/title";

        String absoluteUrl = InitFilter.getAbsoluteUrl(secure, SERVER_NAME, contextPath, requestURI, requestURL);
        assertEquals("https://roller.example.com/roller", absoluteUrl);
    }

    @Test
    public void testGetAbsoluteUrlPreservesAnExplicitHttpsPort() throws Exception {
        boolean secure = true;
        String contextPath = "/roller";
        String requestURI = "/roller/";
        String requestURL = "https://roller.example.com:8443/roller/";

        String absoluteUrl = InitFilter.getAbsoluteUrl(secure, SERVER_NAME, contextPath, requestURI, requestURL);
        assertEquals("https://roller.example.com:8443/roller", absoluteUrl);
    }

    @Test
    public void testASecureRequestWhoseUrlStillSaysHttpIsUpgraded() throws Exception {
        // a connector that sets the secure flag without rewriting the scheme
        boolean secure = true;
        String contextPath = "/roller";
        String requestURI = "/roller/";
        String requestURL = "http://roller.example.com/roller/";

        String absoluteUrl = InitFilter.getAbsoluteUrl(secure, SERVER_NAME, contextPath, requestURI, requestURL);
        assertEquals("https://roller.example.com/roller", absoluteUrl);
    }

    @Test
    public void testGetAbsoluteUrlAgainstTop() throws Exception {
        boolean secure = false;
        String contextPath = "/roller";
        String requestURI = "/roller/";
        String requestURL = "http://roller.example.com/roller/";

        String absoluteUrl = InitFilter.getAbsoluteUrl(secure, SERVER_NAME, contextPath, requestURI, requestURL);
        assertEquals("http://roller.example.com/roller", absoluteUrl);
    }

    @Test
    public void testGetAbsoluteUrlAgainstPermalink() throws Exception {
        boolean secure = false;
        String contextPath = "/roller";
        String requestURI = "/roller/handle/entry/title";
        String requestURL = "http://roller.example.com/roller/handle/entry/title";

        String absoluteUrl = InitFilter.getAbsoluteUrl(secure, SERVER_NAME, contextPath, requestURI, requestURL);
        assertEquals("http://roller.example.com/roller", absoluteUrl);
    }

    @Test
    public void testRemoveTrailingSlash() throws Exception {
        assertEquals("http://www.example.com", InitFilter.removeTrailingSlash("http://www.example.com/"));
        assertEquals("http://www.example.com", InitFilter.removeTrailingSlash("http://www.example.com"));
    }

    // ------------------------------------------------- forwarded-proto chain
    //
    // Production terminates TLS at Caddy and proxies to the app over plain
    // HTTP, signalling the original scheme in X-Forwarded-Proto.
    // server.forward-headers-strategy=framework (application.properties)
    // registers Spring's ForwardedHeaderFilter at highest precedence for
    // every URL pattern, so by the time InitFilter captures the absolute
    // context URL the request already reports https. These tests run the
    // exact same two-filter chain against a mock request.

    @Test
    public void forwardedProtoHttpsYieldsAnHttpsAbsoluteContextUrl() throws Exception {
        String priorAbsolute = WebloggerRuntimeConfig.getAbsoluteContextURL();
        String priorRelative = WebloggerRuntimeConfig.getRelativeContextURL();
        try {
            MockHttpServletRequest request = proxiedRequest();
            request.addHeader("X-Forwarded-Proto", "https");

            new ForwardedHeaderFilter().doFilter(request, new MockHttpServletResponse(),
                    new MockFilterChain(new HttpServlet() { }, new InitFilter()));

            assertEquals("https://photos.example.com/roller",
                    WebloggerRuntimeConfig.getAbsoluteContextURL(),
                    "X-Forwarded-Proto: https must produce an https absolute context URL");
            assertEquals("/roller", WebloggerRuntimeConfig.getRelativeContextURL());
        } finally {
            WebloggerRuntimeConfig.setAbsoluteContextURL(priorAbsolute);
            WebloggerRuntimeConfig.setRelativeContextURL(priorRelative);
        }
    }

    @Test
    public void plainHttpWithoutTheHeaderStaysHttp() throws Exception {
        String priorAbsolute = WebloggerRuntimeConfig.getAbsoluteContextURL();
        String priorRelative = WebloggerRuntimeConfig.getRelativeContextURL();
        try {
            new ForwardedHeaderFilter().doFilter(proxiedRequest(), new MockHttpServletResponse(),
                    new MockFilterChain(new HttpServlet() { }, new InitFilter()));

            assertEquals("http://photos.example.com/roller",
                    WebloggerRuntimeConfig.getAbsoluteContextURL(),
                    "plain-http dev behavior must be unchanged when no proxy header is present");
        } finally {
            WebloggerRuntimeConfig.setAbsoluteContextURL(priorAbsolute);
            WebloggerRuntimeConfig.setRelativeContextURL(priorRelative);
        }
    }

    /** A plain-http request as the app sees it behind the reverse proxy. */
    private static MockHttpServletRequest proxiedRequest() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/roller/");
        request.setServerName("photos.example.com");
        request.setServerPort(80);
        request.setContextPath("/roller");
        return request;
    }

}
