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

import java.util.List;

import org.apache.roller.weblogger.WebloggerException;
import org.apache.roller.weblogger.business.MockWeblogger;
import org.apache.roller.weblogger.pojos.GlobalPermission;
import org.apache.roller.weblogger.pojos.User;
import org.apache.roller.weblogger.pojos.Weblog;
import org.apache.roller.weblogger.pojos.WeblogPermission;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ui.ExtendedModelMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link MainMenuController}, the page a user lands on after signing
 * in, and where weblog invitations are accepted or declined.
 */
class MainMenuControllerTest {

    private MockWeblogger weblogger;
    private MainMenuController controller;
    private ExtendedModelMap model;

    @BeforeEach
    void setUp() {
        weblogger = MockWeblogger.install();
        controller = ControllerTestFixture.withMessages(new MainMenuController());
        model = new ExtendedModelMap();
    }

    @AfterEach
    void tearDown() {
        MockWeblogger.uninstall();
    }

    @Test
    void theMenuListsTheBlogsYouHaveAndTheInvitationsYouHaveNotAnsweredYet() throws Exception {
        User user = user("jake");
        List<WeblogPermission> existing = List.of(new WeblogPermission());
        List<WeblogPermission> pending = List.of(new WeblogPermission());
        when(weblogger.userManager().getWeblogPermissions(user)).thenReturn(existing);
        when(weblogger.userManager().getPendingWeblogPermissions(user)).thenReturn(pending);
        when(weblogger.userManager().checkPermission(any(GlobalPermission.class), eq(user))).thenReturn(true);

        String view = controller.execute(ControllerTestFixture.requestFor(user), model);

        assertEquals(".MainMenu", view);
        assertSame(existing, model.getAttribute("existingPermissions"));
        assertSame(pending, model.getAttribute("pendingPermissions"));
        assertEquals(true, model.getAttribute("userIsAdmin"),
                "the admin section of the menu is shown from this flag");
        assertEquals("yourWebsites.title", model.getAttribute("pageTitle"));
        assertSame(user, model.getAttribute("authenticatedUser"));
    }

    @Test
    void anOrdinaryUserIsNotOfferedTheAdminSection() throws Exception {
        User user = user("jake");
        when(weblogger.userManager().checkPermission(any(GlobalPermission.class), eq(user))).thenReturn(false);

        controller.execute(ControllerTestFixture.requestFor(user), model);

        assertEquals(false, model.getAttribute("userIsAdmin"));
    }

    @Test
    void aFailedPermissionLookupShowsAnEmptyMenuRatherThanAnError() throws Exception {
        // The menu is the only page a signed-in user always has; it must render.
        User user = user("jake");
        when(weblogger.userManager().getWeblogPermissions(user)).thenThrow(new WebloggerException("database down"));
        when(weblogger.userManager().getPendingWeblogPermissions(user))
                .thenThrow(new WebloggerException("database down"));
        when(weblogger.userManager().checkPermission(any(GlobalPermission.class), eq(user)))
                .thenThrow(new WebloggerException("database down"));

        String view = controller.execute(ControllerTestFixture.requestFor(user), model);

        assertEquals(".MainMenu", view);
        assertTrue(((List<?>) model.getAttribute("existingPermissions")).isEmpty());
        assertTrue(((List<?>) model.getAttribute("pendingPermissions")).isEmpty());
        assertEquals(false, model.getAttribute("userIsAdmin"));
    }

    @Test
    void acceptingAnInvitationConfirmsThePermissionAndReturnsToTheMenu() throws Exception {
        User user = user("jake");
        Weblog weblog = weblog("travelguide");
        when(weblogger.weblogManager().getWeblog("w1")).thenReturn(weblog);

        List<WeblogPermission> nowIncluding = List.of(new WeblogPermission());
        when(weblogger.userManager().getWeblogPermissions(user)).thenReturn(nowIncluding);

        String view = controller.accept(ControllerTestFixture.requestFor(user), model, "w1");

        assertEquals(".MainMenu", view);
        verify(weblogger.userManager()).confirmWeblogPermission(weblog, user);
        verify(weblogger.weblogger()).flush();
        assertEquals(List.of(), ControllerTestFixture.errors(model));
        assertEquals("yourWebsites.title", model.getAttribute("pageTitle"));
        // The menu is redrawn afterwards, so the blog just accepted appears.
        assertSame(nowIncluding, model.getAttribute("existingPermissions"));
    }

    @Test
    void decliningAnInvitationRejectsThePermissionAndSaysWhichBlog() throws Exception {
        User user = user("jake");
        Weblog weblog = weblog("travelguide");
        when(weblogger.weblogManager().getWeblog("w1")).thenReturn(weblog);

        List<WeblogPermission> stillPending = List.of(new WeblogPermission());
        when(weblogger.userManager().getPendingWeblogPermissions(user)).thenReturn(stillPending);

        String view = controller.decline(ControllerTestFixture.requestFor(user), model, "w1");

        assertEquals(".MainMenu", view);
        verify(weblogger.userManager()).declineWeblogPermission(weblog, user);
        verify(weblogger.weblogger()).flush();
        assertEquals(List.of("yourWebsites.declined[travelguide]"), ControllerTestFixture.messages(model));
        assertEquals("yourWebsites.title", model.getAttribute("pageTitle"));
        assertSame(stillPending, model.getAttribute("pendingPermissions"),
                "the menu is redrawn from fresh permissions afterwards");
    }

    @Test
    void aFailedDeclineIsReportedInsteadOfLookingLikeItWorked() throws Exception {
        User user = user("jake");
        Weblog weblog = weblog("travelguide");
        when(weblogger.weblogManager().getWeblog("w1")).thenReturn(weblog);
        org.mockito.Mockito.doThrow(new WebloggerException("database down"))
                .when(weblogger.userManager()).declineWeblogPermission(weblog, user);

        String view = controller.decline(ControllerTestFixture.requestFor(user), model, "w1");

        assertEquals(".MainMenu", view);
        assertEquals(List.of("yourWebsites.permNotFound"), ControllerTestFixture.errors(model));
        assertEquals(List.of(), ControllerTestFixture.messages(model),
                "a decline that failed must not report the invitation as declined");
    }

    @Test
    void acceptingAnInvitationThatNoLongerExistsIsReportedNotCrashed() throws Exception {
        // Invitations get revoked, and the link stays in the user's inbox. That
        // used to hand a null weblog to the permission code and 500 the page.
        User user = user("jake");
        when(weblogger.weblogManager().getWeblog("gone")).thenReturn(null);

        String view = controller.accept(ControllerTestFixture.requestFor(user), model, "gone");

        assertEquals(".MainMenu", view);
        assertEquals(List.of("yourWebsites.permNotFound"), ControllerTestFixture.errors(model));
        verify(weblogger.userManager(), never()).confirmWeblogPermission(any(), any());
    }

    @Test
    void decliningAnInvitationThatNoLongerExistsIsReportedNotCrashed() throws Exception {
        User user = user("jake");
        when(weblogger.weblogManager().getWeblog("gone")).thenReturn(null);

        String view = controller.decline(ControllerTestFixture.requestFor(user), model, "gone");

        assertEquals(".MainMenu", view);
        assertEquals(List.of("yourWebsites.permNotFound"), ControllerTestFixture.errors(model));
        verify(weblogger.userManager(), never()).declineWeblogPermission(any(), any());
    }

    @Test
    void anInvitationLinkWithNoIdAtAllIsReportedNotCrashed() throws Exception {
        User user = user("jake");

        String view = controller.accept(ControllerTestFixture.requestFor(user), model, null);

        assertEquals(".MainMenu", view);
        assertEquals(List.of("yourWebsites.permNotFound"), ControllerTestFixture.errors(model));
    }

    @Test
    void aFailedAcceptIsReportedInsteadOfLookingLikeItWorked() throws Exception {
        User user = user("jake");
        Weblog weblog = weblog("travelguide");
        when(weblogger.weblogManager().getWeblog("w1")).thenReturn(weblog);
        org.mockito.Mockito.doThrow(new WebloggerException("database down"))
                .when(weblogger.userManager()).confirmWeblogPermission(weblog, user);

        String view = controller.accept(ControllerTestFixture.requestFor(user), model, "w1");

        assertEquals(".MainMenu", view);
        assertEquals(List.of("yourWebsites.permNotFound"), ControllerTestFixture.errors(model));
    }

    @Test
    void theMenuNeedsNoWeblogOfItsOwn() {
        // It is the page you reach before choosing one.
        assertFalse(controller.isWeblogRequired());
        assertEquals("yourWebsites.title", controller.getPageTitle());
    }

    private static User user(String userName) {
        User user = new User();
        user.setUserName(userName);
        user.setEnabled(Boolean.TRUE);
        return user;
    }

    private static Weblog weblog(String handle) {
        Weblog weblog = new Weblog();
        weblog.setHandle(handle);
        return weblog;
    }
}
