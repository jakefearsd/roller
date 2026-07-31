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

import org.apache.roller.weblogger.ui.core.RollerContext;
import org.apache.roller.weblogger.util.HTMLSanitizer;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * Covers the account state on {@link User} that is not a plain field:
 * the enabled flag that gates login, the sanitising setters, the defensive
 * copies around the creation date, and password reset.
 */
class UserAccountTest {

    @Test
    void theEnabledFlagAnswersForItselfInBothStates() {
        // Spring Security reads this to decide whether an account may
        // authenticate. An accessor stuck at one answer either locks everyone
        // out or lets disabled accounts back in.
        User user = new User();
        assertEquals(Boolean.TRUE, user.getEnabled(),
                "A newly constructed user is enabled; a disabled default would make "
                        + "every account created through the admin UI unusable");

        user.setEnabled(Boolean.FALSE);
        assertEquals(Boolean.FALSE, user.getEnabled(),
                "A disabled account must report as disabled -- this is the only thing "
                        + "stopping a suspended user from logging back in");

        user.setEnabled(Boolean.TRUE);
        assertEquals(Boolean.TRUE, user.getEnabled());
    }

    @Test
    void markupIsStrippedFromTheNamesTheBlogPublishes() {
        Boolean previous = HTMLSanitizer.xssEnabled;
        try {
            HTMLSanitizer.xssEnabled = Boolean.TRUE;
            User user = new User();
            user.setUserName("<script>alert(1)</script>alice");
            user.setScreenName("<script>alert(1)</script>Alice");
            user.setFullName("<script>alert(1)</script>Alice Anderson");
            user.setEmailAddress("<script>alert(1)</script>alice@example.com");

            assertFalse(user.getUserName().contains("<script>"),
                    "These four values are rendered next to comments and in bylines, so "
                            + "the setters clean them on the way in rather than relying on "
                            + "every call site to escape");
            assertFalse(user.getScreenName().contains("<script>"));
            assertFalse(user.getFullName().contains("<script>"));
            assertFalse(user.getEmailAddress().contains("<script>"));
        } finally {
            HTMLSanitizer.xssEnabled = previous;
        }
    }

    @Test
    void theCreationDateCannotBeMutatedThroughItsAccessor() {
        User user = new User();
        Date original = new Date(1_000_000L);
        user.setDateCreated(original);

        original.setTime(2_000_000L);
        assertEquals(1_000_000L, user.getDateCreated().getTime(),
                "java.util.Date is mutable; keeping the caller's instance would let them "
                        + "change a persisted field behind the entity's back");

        Date handedOut = user.getDateCreated();
        handedOut.setTime(3_000_000L);
        assertEquals(1_000_000L, user.getDateCreated().getTime());
        assertNotSame(handedOut, user.getDateCreated());

        user.setDateCreated(null);
        assertNull(user.getDateCreated(),
                "and a null date must stay null rather than becoming the epoch");
    }

    @Test
    void theFullConstructorPopulatesTheAccountAndCopiesItsDate() {
        Date created = new Date(1_700_000_000_000L);

        User user = new User("ignored-id", "alice", "secret", "Alice Anderson",
                "alice@example.com", "en_US", "America/New_York", created, Boolean.FALSE);

        assertEquals("alice", user.getUserName());
        assertEquals("secret", user.getPassword(),
                "The constructor stores the password it was handed; callers are "
                        + "responsible for encoding it first");
        assertEquals("Alice Anderson", user.getFullName());
        assertEquals("alice@example.com", user.getEmailAddress());
        assertEquals("en_US", user.getLocale());
        assertEquals("America/New_York", user.getTimeZone());
        assertEquals(Boolean.FALSE, user.getEnabled(),
                "An account created disabled must stay disabled");
        assertNotEquals("ignored-id", user.getId(),
                "The id argument is deliberately ignored -- the entity keeps the UUID it "
                        + "generated for itself, so callers cannot collide two users");

        created.setTime(0L);
        assertEquals(1_700_000_000_000L, user.getDateCreated().getTime(),
                "The creation date must be copied, not aliased to the caller's Date");
    }

    @Test
    void resettingThePasswordStoresTheEncodedForm() {
        // The plain text must never reach the database, so resetPassword has to
        // put the encoder's output on the entity, not its own input.
        PasswordEncoder encoder = mock(PasswordEncoder.class);
        when(encoder.encode("hunter2")).thenReturn("{bcrypt}encoded-form");

        User user = new User();
        try (MockedStatic<RollerContext> context = mockStatic(RollerContext.class)) {
            context.when(RollerContext::getPasswordEncoder).thenReturn(encoder);
            user.resetPassword("hunter2");
        }

        assertEquals("{bcrypt}encoded-form", user.getPassword(),
                "The stored password must be the encoded form");
        assertFalse("hunter2".equals(user.getPassword()),
                "Storing the plain text would put every user's password in the database "
                        + "in the clear");
    }

    @Test
    void aFreshObjectPermissionIsNotPending() {
        // Pending is how an unaccepted invitation is marked. Defaulting to
        // pending would make every directly granted permission look unaccepted;
        // there is no "accepted" flag to compensate.
        WeblogPermission granted = new WeblogPermission();

        assertFalse(granted.isPending(),
                "A permission created directly is in force, not awaiting acceptance");

        granted.setPending(true);
        assertTrue(granted.isPending(),
                "and an invitation must be markable as pending");
    }
}
