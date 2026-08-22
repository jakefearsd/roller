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
 * Covers the permission helpers on {@link Weblog} and {@link User}, which still
 * reach the user manager through the static locator until plan Task 16 moves
 * them to their callers. ({@code WeblogEntry.hasWritePermissions} used to be
 * covered here too; it had no production caller and was deleted in Task 14 --
 * the edit-draft rule it implemented lived only in that dead method.)
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
