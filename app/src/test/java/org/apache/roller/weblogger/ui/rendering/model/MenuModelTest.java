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

package org.apache.roller.weblogger.ui.rendering.model;

import org.apache.roller.weblogger.WebloggerException;
import org.apache.roller.weblogger.business.UserManager;
import org.apache.roller.weblogger.business.Weblogger;
import org.apache.roller.weblogger.business.WebloggerFactory;
import org.apache.roller.weblogger.pojos.User;
import org.apache.roller.weblogger.pojos.Weblog;
import org.apache.roller.weblogger.ui.rendering.util.WeblogFeedRequest;
import org.apache.roller.weblogger.ui.rendering.util.WeblogPageRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link MenuModel}, the {@code $menuModel} object that renders
 * the editor and admin navigation bars into a live blog page.
 *
 * <p>This model is an authorisation boundary: it decides whether a visitor sees
 * links into the editing UI. The tests below concentrate on it failing closed —
 * an anonymous visitor, or one whose permission check errors, must get nothing.
 */
class MenuModelTest {

    private MockedStatic<WebloggerFactory> factory;
    private UserManager userManager;
    private Weblog weblog;

    @BeforeEach
    void setUp() {
        userManager = mock(UserManager.class);
        Weblogger weblogger = mock(Weblogger.class);
        when(weblogger.getUserManager()).thenReturn(userManager);
        factory = mockStatic(WebloggerFactory.class);
        factory.when(WebloggerFactory::getWeblogger).thenReturn(weblogger);

        weblog = new Weblog("testblog", "testuser", "Test Blog", "a test blog",
                "blog@example.com", "journal", "en_US", "UTC");
    }

    @AfterEach
    void tearDown() {
        factory.close();
    }

    private WeblogPageRequest pageRequest() {
        WeblogPageRequest request = new WeblogPageRequest();
        request.setWeblog(weblog);
        request.setWeblogHandle(weblog.getHandle());
        return request;
    }

    private MenuModel modelFor(WeblogPageRequest request) throws WebloggerException {
        Map<String, Object> initData = new HashMap<>();
        initData.put("parsedRequest", request);
        MenuModel model = new MenuModel();
        model.init(initData);
        return model;
    }

    @Test
    void modelIsRegisteredUnderTheNameThemesUse() {
        assertEquals("menuModel", new MenuModel().getModelName(),
                "Themes reference this model as $menuModel.");
    }

    @Test
    void initWithoutAParsedRequestFails() {
        MenuModel model = new MenuModel();
        WebloggerException thrown = assertThrows(WebloggerException.class,
                () -> model.init(new HashMap<>()),
                "init() must reject init data with no request.");
        assertTrue(thrown.getMessage().contains("weblogRequest"),
                "The failure should name what was missing; was: " + thrown.getMessage());
    }

    @Test
    void initRejectsRequestsThatAreNotPageRequests() {
        WeblogFeedRequest feedRequest = new WeblogFeedRequest();
        feedRequest.setWeblog(weblog);
        Map<String, Object> initData = new HashMap<>();
        initData.put("parsedRequest", feedRequest);

        MenuModel model = new MenuModel();
        WebloggerException thrown = assertThrows(WebloggerException.class,
                () -> model.init(initData),
                "MenuModel must refuse a request it cannot serve.");
        assertTrue(thrown.getMessage().contains("WeblogPageRequest"),
                "The failure should say which request type is required; was: "
                        + thrown.getMessage());
    }

    @Test
    void anonymousVisitorsGetNoMenus() throws Exception {
        MenuModel model = modelFor(pageRequest());

        assertNull(model.getAuthorMenu(),
                "A visitor who is not logged in must not be offered the editor menu.");
        assertNull(model.getAdminMenu(),
                "A visitor who is not logged in must not be offered the admin menu.");
    }

    @Test
    void aLoggedInVisitorWithoutAdminRightsGetsNoAdminMenu() throws Exception {
        User user = new User();
        user.setUserName("bob");
        WeblogPageRequest request = pageRequest();
        request.setAuthenticUser("bob");
        request.setUser(user);
        when(userManager.checkPermission(any(), any())).thenReturn(false);

        assertNull(modelFor(request).getAdminMenu(),
                "Being logged in is not enough; the admin menu needs the global "
                        + "admin permission.");
    }

    @Test
    void aFailedPermissionCheckHidesTheAdminMenu() throws Exception {
        // Fail closed: if we cannot confirm the permission we must not show the
        // menu, and the page must still render.
        User user = new User();
        user.setUserName("bob");
        WeblogPageRequest request = pageRequest();
        request.setAuthenticUser("bob");
        request.setUser(user);
        when(userManager.checkPermission(any(), any()))
                .thenThrow(new WebloggerException("database is down"));

        assertNull(modelFor(request).getAdminMenu(),
                "A permission check that blows up must hide the menu, not show it.");
    }

    @Test
    void aLoggedInAdministratorGetsTheAdminMenu() throws Exception {
        User user = new User();
        user.setUserName("root");
        WeblogPageRequest request = pageRequest();
        request.setAuthenticUser("root");
        request.setUser(user);
        when(userManager.checkPermission(any(), any())).thenReturn(true);

        assertNotNull(modelFor(request).getAdminMenu(),
                "A logged-in global administrator must be offered the admin menu.");
    }

    @Test
    void aLoggedInVisitorGetsTheEditorMenu() throws Exception {
        User user = new User();
        user.setUserName("bob");
        WeblogPageRequest request = pageRequest();
        request.setAuthenticUser("bob");
        request.setUser(user);

        assertNotNull(modelFor(request).getAuthorMenu(),
                "A logged-in author must be offered the editor menu; this is the only "
                        + "route back into the admin UI from a blog page.");
    }
}
