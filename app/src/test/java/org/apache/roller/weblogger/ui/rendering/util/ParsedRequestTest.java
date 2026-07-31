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

package org.apache.roller.weblogger.ui.rendering.util;

import java.security.Principal;

import jakarta.servlet.http.HttpServletRequest;

import org.apache.roller.weblogger.WebloggerException;
import org.apache.roller.weblogger.business.UserManager;
import org.apache.roller.weblogger.business.Weblogger;
import org.apache.roller.weblogger.business.WebloggerFactory;
import org.apache.roller.weblogger.pojos.User;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ParsedRequest}, the root of the request-parsing
 * hierarchy. It carries the login identity and resolves it to a
 * {@link User} lazily.
 *
 * <p>The laziness is the point: the class documents itself as lightweight and
 * is constructed on every request, so the database round trip must happen only
 * when something actually asks for the user.
 */
class ParsedRequestTest {

    /** ParsedRequest is abstract; the concrete behaviour under test lives entirely in it. */
    private static final class TestRequest extends ParsedRequest {
        TestRequest(HttpServletRequest request) throws InvalidRequestException {
            super(request);
        }
    }

    private static HttpServletRequest requestFor(String username) {
        HttpServletRequest request = mock(HttpServletRequest.class);
        if (username != null) {
            Principal principal = mock(Principal.class);
            when(principal.getName()).thenReturn(username);
            when(request.getUserPrincipal()).thenReturn(principal);
        }
        return request;
    }

    @Test
    void anonymousRequestHasNoAuthenticUserAndIsNotLoggedIn() throws Exception {
        TestRequest request = new TestRequest(requestFor(null));

        assertNull(request.getAuthenticUser());
        assertFalse(request.isLoggedIn(),
                "An anonymous reader must not be reported as logged in, or the "
                        + "templates emit edit links to the public");
    }

    @Test
    void authenticatedRequestTakesItsUsernameFromThePrincipal() throws Exception {
        TestRequest request = new TestRequest(requestFor("ada"));

        assertEquals("ada", request.getAuthenticUser());
        assertTrue(request.isLoggedIn());
    }

    @Test
    void anonymousRequestNeverReachesTheUserManager() throws Exception {
        // Constructing a parsed request happens on every single page view,
        // including every crawler hit. Looking a user up for an anonymous
        // request would be one wasted query per hit.
        TestRequest request = new TestRequest(requestFor(null));

        try (MockedStatic<WebloggerFactory> factory = mockStatic(WebloggerFactory.class)) {
            Weblogger weblogger = mock(Weblogger.class);
            factory.when(WebloggerFactory::getWeblogger).thenReturn(weblogger);

            assertNull(request.getUser(), "There is no user to resolve");
            verify(weblogger, never()).getUserManager();
        }
    }

    @Test
    void authenticatedRequestResolvesTheUserOnceAndCachesIt() throws Exception {
        TestRequest request = new TestRequest(requestFor("ada"));

        try (MockedStatic<WebloggerFactory> factory = mockStatic(WebloggerFactory.class)) {
            Weblogger weblogger = mock(Weblogger.class);
            UserManager userManager = mock(UserManager.class);
            User user = new User();
            when(weblogger.getUserManager()).thenReturn(userManager);
            when(userManager.getUserByUserName("ada")).thenReturn(user);
            factory.when(WebloggerFactory::getWeblogger).thenReturn(weblogger);

            assertEquals(user, request.getUser());
            assertEquals(user, request.getUser());

            verify(userManager).getUserByUserName("ada");
        }
    }

    @Test
    void aFailedLookupIsSwallowedSoTheRequestCanStillRender() throws Exception {
        // A user row that cannot be read is not a reason to fail the page --
        // the reader simply gets the anonymous view.
        TestRequest request = new TestRequest(requestFor("ada"));

        try (MockedStatic<WebloggerFactory> factory = mockStatic(WebloggerFactory.class)) {
            Weblogger weblogger = mock(Weblogger.class);
            UserManager userManager = mock(UserManager.class);
            when(weblogger.getUserManager()).thenReturn(userManager);
            when(userManager.getUserByUserName("ada"))
                    .thenThrow(new WebloggerException("database is down"));
            factory.when(WebloggerFactory::getWeblogger).thenReturn(weblogger);

            assertNull(request.getUser(),
                    "A lookup failure must degrade to no user, not propagate out of "
                            + "the getter and abort the render");
        }
    }

    @Test
    void injectedUserIsUsedWithoutAnyLookup() throws Exception {
        // The servlets pre-populate this from the session to avoid a query.
        TestRequest request = new TestRequest(requestFor("ada"));
        User user = new User();
        request.setUser(user);

        try (MockedStatic<WebloggerFactory> factory = mockStatic(WebloggerFactory.class)) {
            Weblogger weblogger = mock(Weblogger.class);
            factory.when(WebloggerFactory::getWeblogger).thenReturn(weblogger);

            assertEquals(user, request.getUser());
            verify(weblogger, never()).getUserManager();
        }
    }

    @Test
    void authenticUserCanBeOverriddenAfterParsing() throws Exception {
        TestRequest request = new TestRequest(requestFor(null));
        request.setAuthenticUser("ada");

        assertTrue(request.isLoggedIn(),
                "isLoggedIn() must follow the current authentic user, not the "
                        + "principal that was present at construction time");
    }
}
