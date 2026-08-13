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

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import org.apache.roller.weblogger.WebloggerException;
import org.apache.roller.weblogger.pojos.User;
import org.apache.roller.weblogger.pojos.WeblogPermission;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ui.Model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link MembersController}, which changes who can do what on a blog.
 *
 * <p>Two invariants protect a weblog from being orphaned or hijacked through
 * this form: a blog must always keep at least one administrator, and an
 * administrator may not demote themselves. Both are enforced here and nowhere
 * else, and both fail open if the guard is skipped — the form would happily
 * revoke the last admin, leaving a blog nobody can administer. The tests
 * therefore assert that no grant or revoke reaches the UserManager when a
 * guard trips, not merely that an error message appeared.
 *
 * <p>{@code grant()} is the other half of the screen: it hands an existing
 * account access to the weblog immediately, with no invitation or acceptance
 * step. It can only ever add an action, so it never has to consult the
 * last-admin guard above — there is nothing here for it to be consistent
 * with.
 */
class MembersControllerTest extends EditorControllerTestSupport {

    private MembersController controller;
    private Model model;
    private List<WeblogPermission> storedPermissions;

    @BeforeEach
    void setUp() throws Exception {
        controller = prepare(new MembersController());
        model = newModel();
        storedPermissions = new ArrayList<>();
        when(weblogger.getUserManager().getWeblogPermissions(weblog))
                .thenReturn(storedPermissions);
        // WeblogPermission stores only a handle and a username and re-resolves
        // both through the managers, so those lookups have to work for the
        // controller to see anything at all.
        when(weblogger.getWeblogManager().getWeblogByHandle(WEBLOG_HANDLE, null)).thenReturn(weblog);
        registerUser(user);
    }

    @Test
    void theListPageShowsEveryMember() throws Exception {
        givenMember(user, WeblogPermission.ADMIN);
        givenMember(otherUser("bob"), WeblogPermission.POST);

        String view = controller.execute(request, model);

        assertEquals(".Members", view);
        assertEquals(2, ((List<?>) model.getAttribute("weblogPermissions")).size());
    }

    @Test
    void theListPageSurvivesAPermissionLookupFailure() throws Exception {
        when(weblogger.getUserManager().getWeblogPermissions(weblog))
                .thenThrow(new WebloggerException("database down"));

        String view = controller.execute(request, model);

        assertEquals(".Members", view);
        assertTrue(((List<?>) model.getAttribute("weblogPermissions")).isEmpty(),
                "A failed lookup must yield an empty list, not a null the JSP would choke on");
    }

    @Test
    void changingAMembersPermissionRevokesTheOldOneAndGrantsTheNew() throws Exception {
        givenMember(user, WeblogPermission.ADMIN);
        User bob = otherUser("bob");
        givenMember(bob, WeblogPermission.EDIT_DRAFT);

        submit(user, WeblogPermission.ADMIN);
        submit(bob, WeblogPermission.POST);

        controller.save(request, model);

        // The old permission must go first; granting on top of an existing row
        // would leave the member holding both action sets.
        org.mockito.InOrder inOrder = org.mockito.Mockito.inOrder(weblogger.getUserManager());
        inOrder.verify(weblogger.getUserManager())
                .revokeWeblogPermission(weblog, bob, WeblogPermission.ALL_ACTIONS);
        inOrder.verify(weblogger.getUserManager())
                .grantWeblogPermission(eq(weblog), eq(bob), eq(List.of(WeblogPermission.POST)));
        assertTrue(messages(model).contains("memberPermissions.membersChanged"),
                "The admin must be told the change took effect: " + messages(model));
    }

    @Test
    void selectingMinusOneRemovesTheMemberWithoutGrantingAnythingBack() throws Exception {
        givenMember(user, WeblogPermission.ADMIN);
        User bob = otherUser("bob");
        givenMember(bob, WeblogPermission.POST);

        submit(user, WeblogPermission.ADMIN);
        submit(bob, "-1");

        controller.save(request, model);

        verify(weblogger.getUserManager())
                .revokeWeblogPermission(weblog, bob, WeblogPermission.ALL_ACTIONS);
        verify(weblogger.getUserManager(), never())
                .grantWeblogPermission(any(), any(), any());
        assertTrue(messages(model).contains("memberPermissions.membersRemoved"),
                "Removal must be confirmed: " + messages(model));
    }

    @Test
    void anAdminCannotDemoteThemselves() throws Exception {
        // Self-demotion is how an admin accidentally locks themselves out.
        givenMember(user, WeblogPermission.ADMIN);
        User bob = otherUser("bob");
        givenMember(bob, WeblogPermission.ADMIN);

        submit(user, WeblogPermission.POST);
        submit(bob, WeblogPermission.ADMIN);

        controller.save(request, model);

        assertTrue(errors(model).contains("memberPermissions.noSelfModifications"),
                "Expected the self-modification guard to fire: " + errors(model));
        verify(weblogger.getUserManager(), never()).revokeWeblogPermission(any(), any(), any());
        verify(weblogger.getUserManager(), never()).grantWeblogPermission(any(), any(), any());
    }

    @Test
    void aSelfModificationAttemptBlocksEveryOtherChangeInTheSameSubmission() throws Exception {
        // The guard sets a single error flag for the whole form, so a rejected
        // self-demotion must not let an unrelated change through alongside it.
        givenMember(user, WeblogPermission.ADMIN);
        User bob = otherUser("bob");
        givenMember(bob, WeblogPermission.ADMIN);
        User carol = otherUser("carol");
        givenMember(carol, WeblogPermission.EDIT_DRAFT);

        submit(user, WeblogPermission.EDIT_DRAFT);
        submit(bob, WeblogPermission.ADMIN);
        submit(carol, WeblogPermission.POST);

        controller.save(request, model);

        verify(weblogger.getUserManager(), never()).grantWeblogPermission(any(), any(), any());
        verify(weblogger.getUserManager(), never()).revokeWeblogPermission(any(), any(), any());
    }

    @Test
    void theLastAdministratorCannotBeRemoved() throws Exception {
        // Leaves a weblog that nobody can administer.
        User bob = otherUser("bob");
        givenMember(bob, WeblogPermission.ADMIN);

        submit(bob, "-1");

        controller.save(request, model);

        assertTrue(errors(model).contains("memberPermissions.oneAdminRequired"),
                "Expected the last-admin guard to fire: " + errors(model));
        verify(weblogger.getUserManager(), never()).revokeWeblogPermission(any(), any(), any());
    }

    @Test
    void demotingTheOnlyAdministratorIsAlsoRefused() throws Exception {
        User bob = otherUser("bob");
        givenMember(bob, WeblogPermission.ADMIN);

        submit(bob, WeblogPermission.POST);

        controller.save(request, model);

        assertTrue(errors(model).contains("memberPermissions.oneAdminRequired"),
                "Demotion is as dangerous as removal for the last admin: " + errors(model));
        verify(weblogger.getUserManager(), never()).grantWeblogPermission(any(), any(), any());
    }

    @Test
    void aMemberWhosePermissionIsUnchangedIsLeftAlone() throws Exception {
        // Revoking and re-granting an identical permission would churn rows and
        // inflate the "changed" count the admin is shown.
        givenMember(user, WeblogPermission.ADMIN);
        User bob = otherUser("bob");
        givenMember(bob, WeblogPermission.POST);

        submit(user, WeblogPermission.ADMIN);
        submit(bob, WeblogPermission.POST);

        controller.save(request, model);

        verify(weblogger.getUserManager(), never()).revokeWeblogPermission(any(), any(), any());
        verify(weblogger.getUserManager(), never()).grantWeblogPermission(any(), any(), any());
        assertTrue(messages(model).isEmpty(),
                "Nothing changed, so nothing should be reported: " + messages(model));
    }

    @Test
    void aMemberWithNoFieldInTheSubmissionIsUntouched() throws Exception {
        // The form only posts rows the browser rendered; an absent row means
        // "no opinion", not "remove".
        givenMember(user, WeblogPermission.ADMIN);
        User bob = otherUser("bob");
        givenMember(bob, WeblogPermission.POST);

        submit(user, WeblogPermission.ADMIN);
        // bob deliberately not submitted

        controller.save(request, model);

        verify(weblogger.getUserManager(), never())
                .revokeWeblogPermission(eq(weblog), eq(bob), any());
    }

    @Test
    void theSavedPageIsRepopulatedFromTheDatabaseRatherThanTheSubmission() throws Exception {
        // The list has to be re-read after the writes, or the page would redraw
        // from the pre-change snapshot and appear not to have saved.
        givenMember(user, WeblogPermission.ADMIN);
        User bob = otherUser("bob");
        givenMember(bob, WeblogPermission.POST);
        submit(user, WeblogPermission.ADMIN);
        submit(bob, "-1");

        controller.save(request, model);

        verify(weblogger.getUserManager(), org.mockito.Mockito.times(2))
                .getWeblogPermissions(weblog);
        assertEquals(storedPermissions, model.getAttribute("weblogPermissions"));
    }

    @Test
    void countsReportedToTheAdminMatchTheNumberOfMembersAffected() throws Exception {
        registerMessage("memberPermissions.membersRemoved", "removed {0}");
        registerMessage("memberPermissions.membersChanged", "changed {0}");

        givenMember(user, WeblogPermission.ADMIN);
        User bob = otherUser("bob");
        User carol = otherUser("carol");
        User dave = otherUser("dave");
        givenMember(bob, WeblogPermission.POST);
        givenMember(carol, WeblogPermission.POST);
        givenMember(dave, WeblogPermission.POST);

        submit(user, WeblogPermission.ADMIN);
        submit(bob, "-1");
        submit(carol, "-1");
        submit(dave, WeblogPermission.EDIT_DRAFT);

        controller.save(request, model);

        assertTrue(messages(model).contains("removed 2"),
                "Expected a count of 2 removals, got: " + messages(model));
        assertTrue(messages(model).contains("changed 1"),
                "Expected a count of 1 change, got: " + messages(model));
    }

    @Test
    void aFailureWhileSavingIsReportedRatherThanSwallowed() throws Exception {
        givenMember(user, WeblogPermission.ADMIN);
        User bob = otherUser("bob");
        givenMember(bob, WeblogPermission.POST);
        submit(user, WeblogPermission.ADMIN);
        submit(bob, "-1");
        org.mockito.Mockito.doThrow(new WebloggerException("database down"))
                .when(weblogger.getUserManager()).revokeWeblogPermission(any(), any(), any());

        String view = controller.save(request, model);

        assertEquals(".Members", view);
        assertTrue(errors(model).contains("memberPermissions.saveError"),
                "A failed permission change must be reported: " + errors(model));
    }

    // --- grant() : adding a brand-new member by username, no ceremony ---

    @Test
    void grantingAnExistingUsernameAddsThemImmediately() throws Exception {
        User bob = otherUser("bob");

        String view = controller.grant(request, model, "bob", WeblogPermission.POST);

        assertEquals(".Members", view);
        verify(weblogger.getUserManager())
                .grantWeblogPermission(weblog, bob, List.of(WeblogPermission.POST));
        assertTrue(messages(model).contains("memberPermissions.membersChanged"),
                "Granting access must be confirmed: " + messages(model));
    }

    @Test
    void grantingAnUnknownUsernameIsAFieldErrorNotAnException() throws Exception {
        // getUserByUserName legitimately returns null for both an unknown and a
        // disabled username -- MembersController never has to tell them apart.
        when(weblogger.getUserManager().getUserByUserName("ghost")).thenReturn(null);

        String view = controller.grant(request, model, "ghost", WeblogPermission.POST);

        assertEquals(".Members", view);
        assertTrue(errors(model).contains("inviteMember.error.userNotFound"),
                "Expected the not-found error: " + errors(model));
        verify(weblogger.getUserManager(), never()).grantWeblogPermission(any(), any(), any());
    }

    @Test
    void grantingWithABlankUsernameIsAFieldErrorAndNeverLooksUpTheEmptyString() throws Exception {
        String view = controller.grant(request, model, "  ", WeblogPermission.POST);

        assertEquals(".Members", view);
        assertTrue(errors(model).contains("inviteMember.error.userNotFound"));
        verify(weblogger.getUserManager(), never()).getUserByUserName(any());
        verify(weblogger.getUserManager(), never()).grantWeblogPermission(any(), any(), any());
    }

    @Test
    void grantingWithABlankPermissionStringIsAFieldErrorNotAnException() throws Exception {
        // A hand-crafted POST omitting permissionString used to NPE inside
        // Utilities.stringToStringList(null, ","); that was caught further
        // down and surfaced as a field error, but logged a full stack trace
        // at ERROR for what is really just malformed input. This pins the
        // clean-validation replacement: a field error, no lookup, no grant,
        // and (implicitly) no exception thrown out of the controller.
        User bob = otherUser("bob");

        String view = controller.grant(request, model, "bob", null);

        assertEquals(".Members", view);
        assertTrue(errors(model).contains("memberPermissions.saveError"),
                "Expected a field error for the missing permission: " + errors(model));
        verify(weblogger.getUserManager(), never()).grantWeblogPermission(any(), any(), any());
    }

    @Test
    void grantingToAnAlreadyPresentMemberUpdatesRatherThanDuplicates() throws Exception {
        // grantWeblogPermission itself merges into an existing row when one is
        // found (JPAUserManagerImpl) -- this pins that MembersController relies
        // on that behaviour rather than working around it.
        User bob = otherUser("bob");
        givenMember(bob, WeblogPermission.POST);

        String view = controller.grant(request, model, "bob", WeblogPermission.ADMIN);

        assertEquals(".Members", view);
        verify(weblogger.getUserManager())
                .grantWeblogPermission(weblog, bob, List.of(WeblogPermission.ADMIN));
    }

    @Test
    void aFailureWhileGrantingIsReportedRatherThanSwallowed() throws Exception {
        User bob = otherUser("bob");
        org.mockito.Mockito.doThrow(new WebloggerException("database down"))
                .when(weblogger.getUserManager()).grantWeblogPermission(any(), any(), any());

        String view = controller.grant(request, model, "bob", WeblogPermission.POST);

        assertEquals(".Members", view);
        assertTrue(errors(model).contains("memberPermissions.saveError"),
                "A failed grant must be reported: " + errors(model));
    }

    // --- grant() : groupblogging.enabled ---

    @Test
    void grantingIsRefusedWhenGroupBloggingIsOff() throws Exception {
        // The menu gate in editor-menu.xml hides the Members tab, but the page
        // is still reachable by URL and a hand-crafted POST reaches grant()
        // regardless -- the same gap themes.customtheme.allowed had before
        // ThemeEditController enforced it server-side.
        givenRuntimeProperty("groupblogging.enabled", "false");
        User bob = otherUser("bob");

        String view = controller.grant(request, model, "bob", WeblogPermission.POST);

        assertEquals(".Members", view);
        assertTrue(errors(model).contains("memberPermissions.error.groupBloggingDisabled"),
                "Expected the group-blogging refusal: " + errors(model));
        verify(weblogger.getUserManager(), never()).grantWeblogPermission(any(), any(), any());
        verify(weblogger.getUserManager(), never()).getUserByUserName(any());
    }

    @Test
    void turningGroupBloggingOffStillLetsExistingMembersBeRemoved() throws Exception {
        // Only granting is gated. An operator who turns the setting off must
        // still be able to undo the members it left behind, otherwise the
        // switch strands exactly the state it is meant to discourage.
        givenRuntimeProperty("groupblogging.enabled", "false");
        User bob = otherUser("bob");
        givenMember(bob, WeblogPermission.POST);
        givenMember(user, WeblogPermission.ADMIN);
        when(request.getParameter("perm-" + bob.getId())).thenReturn("-1");
        when(request.getParameter("perm-" + user.getId())).thenReturn(WeblogPermission.ADMIN);

        controller.save(request, model);

        verify(weblogger.getUserManager())
                .revokeWeblogPermission(weblog, bob, WeblogPermission.ALL_ACTIONS);
    }

    // --- helpers ---

    private User otherUser(String userName) {
        User other = new User();
        other.setId("user-" + userName);
        other.setUserName(userName);
        other.setEmailAddress(userName + "@example.com");
        other.setDateCreated(new Date());
        other.setEnabled(Boolean.TRUE);
        registerUser(other);
        return other;
    }

    private void registerUser(User member) {
        try {
            when(weblogger.getUserManager().getUserByUserName(member.getUserName()))
                    .thenReturn(member);
        } catch (WebloggerException e) {
            throw new IllegalStateException(e);
        }
    }

    private void givenMember(User member, String action) {
        WeblogPermission permission = new WeblogPermission(weblog, member, List.of(action));
        storedPermissions.add(permission);
    }

    /** Simulate the form posting {@code perm-<userId>=<value>}. */
    private void submit(User member, String value) {
        when(request.getParameter("perm-" + member.getId())).thenReturn(value);
    }
}
