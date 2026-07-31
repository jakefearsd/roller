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
package org.apache.roller.weblogger.ui.controllers.editor;

import java.util.Date;
import java.util.List;

import org.apache.roller.weblogger.WebloggerException;
import org.apache.roller.weblogger.pojos.User;
import org.apache.roller.weblogger.pojos.WeblogPermission;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ui.Model;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link MembersInviteController}.
 *
 * <p>Inviting somebody hands them standing access to a blog, so the checks here
 * are authorization checks: group blogging must be switched on site-wide, the
 * invitee must exist, and they must not already hold a permission (accepted or
 * pending) that the invite would silently overwrite. Each of those failing open
 * grants access that the site owner did not intend.
 */
class MembersInviteControllerTest extends EditorControllerTestSupport {

    private static final String MEMBERS_REDIRECT =
            "redirect:/roller-ui/authoring/members.rol?weblog=" + WEBLOG_HANDLE;

    private MembersInviteController controller;
    private Model model;
    private RedirectAttributes redirectAttributes;
    private User invitee;
    private String originalGroupBlogging;

    @BeforeEach
    void setUp() throws Exception {
        controller = prepare(new MembersInviteController());
        model = newModel();
        redirectAttributes = newRedirectAttributes();

        originalGroupBlogging = overrideConfigProperty("groupblogging.enabled", "true");

        invitee = new User();
        invitee.setId("user-bob");
        invitee.setUserName("bob");
        invitee.setEmailAddress("bob@example.com");
        invitee.setDateCreated(new Date());
        invitee.setEnabled(Boolean.TRUE);
        when(weblogger.getUserManager().getUserByUserName("bob")).thenReturn(invitee);
    }

    @AfterEach
    void restoreGroupBlogging() {
        // Process-global config: leaving it flipped would change behaviour for
        // every test that runs after this class.
        overrideConfigProperty("groupblogging.enabled", originalGroupBlogging);
    }

    private void enableGroupBlogging(boolean enabled) {
        overrideConfigProperty("groupblogging.enabled", Boolean.toString(enabled));
    }

    @Test
    void theInviteFormOpensWhenGroupBloggingIsEnabled() {
        assertEquals(".MembersInvite", controller.execute(request, model, redirectAttributes));
    }

    @Test
    void theInviteFormIsClosedWhenGroupBloggingIsDisabledSiteWide() {
        // The whole feature is off, so even reaching the form must be refused --
        // otherwise the POST handler is the only thing standing in the way.
        enableGroupBlogging(false);

        assertEquals(MEMBERS_REDIRECT, controller.execute(request, model, redirectAttributes));
        assertTrue(flashErrors(redirectAttributes).contains("inviteMember.disabled"),
                "Expected the disabled notice, got: " + flashErrors(redirectAttributes));
    }

    @Test
    void invitingIsRefusedOutrightWhenGroupBloggingIsDisabled() throws Exception {
        enableGroupBlogging(false);

        String view = controller.save(request, model, "bob", WeblogPermission.POST, redirectAttributes);

        assertEquals(MEMBERS_REDIRECT, view);
        assertTrue(flashErrors(redirectAttributes).contains("inviteMember.disabled"));
        verify(weblogger.getUserManager(), never())
                .grantWeblogPermissionPending(any(), any(), any());
    }

    @Test
    void invitingAnExistingUserGrantsThemAPendingPermission() throws Exception {
        String view = controller.save(request, model, "bob", WeblogPermission.POST, redirectAttributes);

        assertEquals(MEMBERS_REDIRECT, view);
        verify(weblogger.getUserManager()).grantWeblogPermissionPending(
                eq(weblog), eq(invitee), eq(List.of(WeblogPermission.POST)));
        assertTrue(flashMessages(redirectAttributes).contains("inviteMember.userInvited"),
                "The invite must be confirmed: " + flashMessages(redirectAttributes));
    }

    @Test
    void theInviteIsPendingRatherThanImmediatelyEffective() throws Exception {
        // grantWeblogPermissionPending, not grantWeblogPermission: the invitee
        // must accept before they can touch the blog.
        controller.save(request, model, "bob", WeblogPermission.ADMIN, redirectAttributes);

        verify(weblogger.getUserManager(), never()).grantWeblogPermission(any(), any(), any());
        verify(weblogger.getUserManager()).grantWeblogPermissionPending(any(), any(), any());
    }

    @Test
    void invitingAnUnknownUserIsRefusedAndRedisplaysTheForm() throws Exception {
        when(weblogger.getUserManager().getUserByUserName("nobody")).thenReturn(null);

        String view = controller.save(request, model, "nobody", WeblogPermission.POST, redirectAttributes);

        assertEquals(".MembersInvite", view);
        assertTrue(errors(model).contains("inviteMember.error.userNotFound"),
                "Expected a user-not-found error, got: " + errors(model));
        verify(weblogger.getUserManager(), never())
                .grantWeblogPermissionPending(any(), any(), any());
    }

    @Test
    void theRedisplayedFormKeepsWhatTheAdminTyped() throws Exception {
        when(weblogger.getUserManager().getUserByUserName("nobody")).thenReturn(null);

        controller.save(request, model, "nobody", WeblogPermission.ADMIN, redirectAttributes);

        assertEquals("nobody", model.getAttribute("userName"),
                "Re-showing the form empty would make the admin retype everything");
        assertEquals(WeblogPermission.ADMIN, model.getAttribute("permissionString"));
    }

    @Test
    void invitingSomebodyWhoAlreadyHasAPendingInviteIsRefused() throws Exception {
        WeblogPermission pending = new WeblogPermission(weblog, invitee, List.of(WeblogPermission.POST));
        pending.setPending(true);
        when(weblogger.getUserManager().getWeblogPermissionIncludingPending(weblog, invitee))
                .thenReturn(pending);

        String view = controller.save(request, model, "bob", WeblogPermission.ADMIN, redirectAttributes);

        assertEquals(".MembersInvite", view);
        assertTrue(errors(model).contains("inviteMember.error.userAlreadyInvited"),
                "Expected an already-invited error, got: " + errors(model));
        verify(weblogger.getUserManager(), never())
                .grantWeblogPermissionPending(any(), any(), any());
    }

    @Test
    void invitingAnExistingMemberIsRefused() throws Exception {
        // Re-inviting would otherwise be a way to quietly change an existing
        // member's permission level without going through the members page.
        WeblogPermission accepted = new WeblogPermission(weblog, invitee, List.of(WeblogPermission.EDIT_DRAFT));
        accepted.setPending(false);
        when(weblogger.getUserManager().getWeblogPermissionIncludingPending(weblog, invitee))
                .thenReturn(accepted);

        String view = controller.save(request, model, "bob", WeblogPermission.ADMIN, redirectAttributes);

        assertEquals(".MembersInvite", view);
        assertTrue(errors(model).contains("inviteMember.error.userAlreadyMember"),
                "Expected an already-member error, got: " + errors(model));
        verify(weblogger.getUserManager(), never())
                .grantWeblogPermissionPending(any(), any(), any());
    }

    @Test
    void aFailedUserLookupStopsTheInviteRatherThanInvitingNobody() throws Exception {
        when(weblogger.getUserManager().getUserByUserName("bob"))
                .thenThrow(new WebloggerException("database down"));

        String view = controller.save(request, model, "bob", WeblogPermission.POST, redirectAttributes);

        assertEquals(".MembersInvite", view);
        verify(weblogger.getUserManager(), never())
                .grantWeblogPermissionPending(any(), any(), any());
    }

    @Test
    void aFailedPermissionCheckStopsTheInvite() throws Exception {
        // If we cannot tell whether the user is already a member, granting
        // anyway risks overwriting an existing permission.
        when(weblogger.getUserManager().getWeblogPermissionIncludingPending(weblog, invitee))
                .thenThrow(new WebloggerException("database down"));

        String view = controller.save(request, model, "bob", WeblogPermission.POST, redirectAttributes);

        assertEquals(".MembersInvite", view);
        verify(weblogger.getUserManager(), never())
                .grantWeblogPermissionPending(any(), any(), any());
    }

    @Test
    void aFailedGrantIsReportedAndKeepsTheAdminOnTheForm() throws Exception {
        org.mockito.Mockito.doThrow(new WebloggerException("constraint violation"))
                .when(weblogger.getUserManager())
                .grantWeblogPermissionPending(any(), any(), any());

        String view = controller.save(request, model, "bob", WeblogPermission.POST, redirectAttributes);

        assertEquals(".MembersInvite", view);
        assertTrue(flashMessages(redirectAttributes).isEmpty(),
                "A failed grant must not report the invite as sent");
        assertEquals(1, errors(model).size(),
                "Expected the failure to be surfaced, got: " + errors(model));
    }

    @Test
    void cancellingReturnsToTheMembersListWithoutInviting() throws Exception {
        assertEquals(MEMBERS_REDIRECT, controller.cancel(request));
        verify(weblogger.getUserManager(), never())
                .grantWeblogPermissionPending(any(), any(), any());
    }
}
