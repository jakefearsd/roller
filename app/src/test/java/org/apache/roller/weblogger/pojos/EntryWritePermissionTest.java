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
package org.apache.roller.weblogger.pojos;

import org.apache.roller.weblogger.WebloggerException;
import org.apache.roller.weblogger.business.UserManager;
import org.apache.roller.weblogger.business.Weblogger;
import org.apache.roller.weblogger.business.WebloggerFactory;
import org.apache.roller.weblogger.pojos.WeblogEntry.PubStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Covers {@link WeblogEntry#hasWritePermissions} and the permission helpers on
 * {@link Weblog} and {@link User}.
 *
 * <p>These three methods are the ones the editor UI actually calls to decide
 * whether to show someone an edit form. The interesting rule is the "limited"
 * one: a member with only {@code edit_draft} may edit a post while it is a
 * draft or pending moderation, but must lose that ability the moment the post
 * is published -- otherwise a contributor could rewrite live content. That rule
 * exists nowhere else, so it is asserted for every publication status.
 */
class EntryWritePermissionTest {

    private Weblog weblog;
    private WeblogEntry entry;
    private User user;
    private UserManager userManager;
    private Weblogger weblogger;

    @BeforeEach
    void setUp() {
        weblog = new Weblog();
        weblog.setHandle("testblog");

        entry = new WeblogEntry();
        entry.setWebsite(weblog);
        entry.setAnchor("hello-world");

        user = new User();
        user.setUserName("alice");

        userManager = mock(UserManager.class);
        weblogger = mock(Weblogger.class);
        when(weblogger.getUserManager()).thenReturn(userManager);
    }

    /** Runs the given check with {@link WebloggerFactory} pointed at the mocks. */
    private <T> T withWeblogger(ThrowingSupplier<T> body) {
        try (MockedStatic<WebloggerFactory> factory = mockStatic(WebloggerFactory.class)) {
            factory.when(WebloggerFactory::getWeblogger).thenReturn(weblogger);
            return body.get();
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private interface ThrowingSupplier<T> {
        T get() throws Exception;
    }

    private void grantOnWeblog(String... actions) throws Exception {
        WeblogPermission perm = new WeblogPermission();
        perm.setActionsAsList(List.of(actions));
        perm.setObjectId(weblog.getHandle());
        perm.setUserName(user.getUserName());
        when(userManager.getWeblogPermission(weblog, user)).thenReturn(perm);
    }

    @Test
    void aSiteAdminMayEditAnyEntryInAnyState() throws Exception {
        when(userManager.checkPermission(any(GlobalPermission.class), org.mockito.ArgumentMatchers.eq(user)))
                .thenReturn(true);
        grantOnWeblog();

        for (PubStatus status : PubStatus.values()) {
            entry.setStatus(status);
            assertTrue(withWeblogger(() -> entry.hasWritePermissions(user)),
                    "A site admin must be able to edit a " + status + " entry even with no "
                            + "permission on the weblog itself -- this is the escape hatch "
                            + "the admin UI depends on");
        }
    }

    @Test
    void theGlobalCheckAsksForAdminSpecifically() throws Exception {
        grantOnWeblog(WeblogPermission.EDIT_DRAFT);
        entry.setStatus(PubStatus.PUBLISHED);

        ArgumentCaptor<GlobalPermission> captor = ArgumentCaptor.forClass(GlobalPermission.class);
        withWeblogger(() -> entry.hasWritePermissions(user));

        verify(userManager).checkPermission(captor.capture(), org.mockito.ArgumentMatchers.eq(user));
        assertEquals(List.of(GlobalPermission.ADMIN), captor.getValue().getActionsAsList(),
                "The site-wide escape hatch must demand admin. Asking for a weaker action "
                        + "would let every logged-in user edit every blog's posts.");
    }

    @Test
    void anAuthorMayEditTheirWeblogsEntriesInAnyState() throws Exception {
        grantOnWeblog(WeblogPermission.POST);

        for (PubStatus status : PubStatus.values()) {
            entry.setStatus(status);
            assertTrue(withWeblogger(() -> entry.hasWritePermissions(user)),
                    "Someone who may publish may also edit a " + status + " entry");
        }
    }

    @Test
    void aWeblogAdminMayEditItsEntriesInAnyState() throws Exception {
        grantOnWeblog(WeblogPermission.ADMIN);

        for (PubStatus status : PubStatus.values()) {
            entry.setStatus(status);
            assertTrue(withWeblogger(() -> entry.hasWritePermissions(user)),
                    "A weblog admin may edit a " + status + " entry");
        }
    }

    @Test
    void aLimitedMemberMayOnlyEditEntriesThatAreNotYetLive() throws Exception {
        grantOnWeblog(WeblogPermission.EDIT_DRAFT);

        entry.setStatus(PubStatus.DRAFT);
        assertTrue(withWeblogger(() -> entry.hasWritePermissions(user)),
                "A limited member may work on their own draft");

        entry.setStatus(PubStatus.PENDING);
        assertTrue(withWeblogger(() -> entry.hasWritePermissions(user)),
                "and may still revise it while it waits for moderation");

        entry.setStatus(PubStatus.PUBLISHED);
        assertFalse(withWeblogger(() -> entry.hasWritePermissions(user)),
                "but must NOT be able to edit a published entry -- that is the whole "
                        + "point of the limited role");

        entry.setStatus(PubStatus.SCHEDULED);
        assertFalse(withWeblogger(() -> entry.hasWritePermissions(user)),
                "and must not be able to edit one already queued to go live");
    }

    @Test
    void someoneWithNoPermissionOnTheWeblogMayEditNothing() throws Exception {
        grantOnWeblog();

        for (PubStatus status : PubStatus.values()) {
            entry.setStatus(status);
            assertFalse(withWeblogger(() -> entry.hasWritePermissions(user)),
                    "A user with no grant on the weblog must not be able to edit a "
                            + status + " entry");
        }
    }

    @Test
    void aFailureToLoadThePermissionDeniesAccess() throws Exception {
        when(userManager.getWeblogPermission(weblog, user))
                .thenThrow(new WebloggerException("database down"));
        entry.setStatus(PubStatus.DRAFT);

        assertFalse(withWeblogger(() -> entry.hasWritePermissions(user)),
                "If the permission cannot be read the answer must be no. Failing open "
                        + "here would hand the editor to anyone during a database outage.");
    }

    // -------------------------------------------------- weblog-level helpers

    @Test
    void weblogPermissionCheckAsksForTheActionsRequested() throws Exception {
        ArgumentCaptor<WeblogPermission> captor = ArgumentCaptor.forClass(WeblogPermission.class);
        when(userManager.checkPermission(any(), org.mockito.ArgumentMatchers.eq(user)))
                .thenReturn(true);

        assertTrue(withWeblogger(() -> weblog.hasUserPermission(user, WeblogPermission.POST)));

        verify(userManager).checkPermission(captor.capture(), org.mockito.ArgumentMatchers.eq(user));
        WeblogPermission demanded = captor.getValue();
        assertEquals(List.of(WeblogPermission.POST), demanded.getActionsAsList(),
                "The check must demand exactly the action the caller named");
        assertEquals("testblog", demanded.getObjectId(),
                "and must be scoped to this weblog, not to weblogs in general");
        assertEquals("alice", demanded.getUserName());
    }

    @Test
    void weblogPermissionCheckReportsADenialAsADenial() throws Exception {
        when(userManager.checkPermission(any(), org.mockito.ArgumentMatchers.eq(user)))
                .thenReturn(false);

        assertFalse(withWeblogger(() -> weblog.hasUserPermission(user, WeblogPermission.ADMIN)));
        assertFalse(withWeblogger(() -> weblog.hasUserPermissions(user,
                List.of(WeblogPermission.POST, WeblogPermission.ADMIN))));
    }

    @Test
    void weblogPermissionCheckDeniesWhenItCannotBeAnswered() throws Exception {
        when(userManager.checkPermission(any(), org.mockito.ArgumentMatchers.eq(user)))
                .thenThrow(new WebloggerException("database down"));

        assertFalse(withWeblogger(() -> weblog.hasUserPermission(user, WeblogPermission.POST)),
                "An unanswerable permission check must deny rather than allow");
    }

    // ---------------------------------------------------- user-level helpers

    @Test
    void globalPermissionCheckAsksForTheActionsRequested() throws Exception {
        ArgumentCaptor<GlobalPermission> captor = ArgumentCaptor.forClass(GlobalPermission.class);
        when(userManager.checkPermission(any(), org.mockito.ArgumentMatchers.eq(user)))
                .thenReturn(true);

        assertTrue(withWeblogger(() -> user.hasGlobalPermission(GlobalPermission.ADMIN)));

        verify(userManager).checkPermission(captor.capture(), org.mockito.ArgumentMatchers.eq(user));
        assertEquals(List.of(GlobalPermission.ADMIN), captor.getValue().getActionsAsList());
    }

    @Test
    void globalPermissionCheckDeniesOnDenialAndOnFailure() throws Exception {
        when(userManager.checkPermission(any(), org.mockito.ArgumentMatchers.eq(user)))
                .thenReturn(false);
        assertFalse(withWeblogger(() -> user.hasGlobalPermission(GlobalPermission.ADMIN)));

        when(userManager.checkPermission(any(), org.mockito.ArgumentMatchers.eq(user)))
                .thenThrow(new WebloggerException("database down"));
        assertFalse(withWeblogger(() -> user.hasGlobalPermissions(List.of(GlobalPermission.WEBLOG))),
                "An unanswerable check must deny");
    }

    // ------------------------------------------- permission -> entity lookups

    @Test
    void aWeblogPermissionCanResolveTheWeblogAndUserItNames() throws Exception {
        org.apache.roller.weblogger.business.WeblogManager weblogManager =
                mock(org.apache.roller.weblogger.business.WeblogManager.class);
        when(weblogger.getWeblogManager()).thenReturn(weblogManager);
        when(weblogManager.getWeblogByHandle("testblog", null)).thenReturn(weblog);
        when(userManager.getUserByUserName("alice")).thenReturn(user);

        WeblogPermission perm = new WeblogPermission(weblog, user, WeblogPermission.POST);

        assertSame(weblog, withWeblogger(perm::getWeblog),
                "The permission stores a handle, and must resolve it back to the weblog "
                        + "the admin UI is about to display");
        assertSame(user, withWeblogger(perm::getUser));
    }

    @Test
    void anUnassignedWeblogPermissionResolvesToNothing() {
        // The weblog-only and default constructors leave these unset; the
        // lookups must not go to the database with a null key.
        WeblogPermission perm = new WeblogPermission();

        assertEquals(null, withWeblogger(perm::getWeblog),
                "A permission with no weblog must resolve to null rather than querying "
                        + "for a weblog whose handle is null");
        assertEquals(null, withWeblogger(perm::getUser));
    }
}
