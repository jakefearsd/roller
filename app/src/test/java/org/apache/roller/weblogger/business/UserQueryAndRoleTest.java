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
package org.apache.roller.weblogger.business;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.List;
import java.util.Map;

import org.apache.roller.weblogger.TestUtils;
import org.apache.roller.weblogger.pojos.User;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * User lookup, listing and roles.
 *
 * <p>The listing side feeds the admin user table and the search endpoint behind
 * the invite picker, and the two of them are how an administrator finds an
 * account to act on. The enabled filter is the part worth pinning: it is
 * tri-state everywhere it appears, and collapsing "not specified" into "only
 * enabled" would silently hide every disabled account from the one screen that
 * exists to re-enable them.
 *
 * <p>Roles are the coarse authority Spring Security reads at sign-in --
 * {@code admin} and {@code editor} -- so granting and revoking them has to
 * round-trip exactly.
 */
class UserQueryAndRoleTest {

    private User alice;
    private User bob;
    private User disabled;

    /**
     * {@code TestUtils.setupUser} prefixes what it is given, so the stored
     * names are junit_uq*, not uq*. Every lookup below goes through the created
     * objects rather than a literal for that reason -- and the letter indexes
     * key on the prefix's initial, not the caller's.
     */
    private String aliceName;
    private String bobName;
    private String disabledName;

    @BeforeEach
    void setUp() throws Exception {
        TestUtils.setupWeblogger();

        alice = TestUtils.setupUser("uqAlice");
        bob = TestUtils.setupUser("uqBob");
        disabled = TestUtils.setupUser("uqCharlie");
        aliceName = alice.getUserName();
        bobName = bob.getUserName();
        disabledName = disabled.getUserName();
        TestUtils.endSession(true);

        User managed = users().getUserByUserName(disabled.getUserName());
        managed.setEnabled(Boolean.FALSE);
        users().saveUser(managed);
        WebloggerFactory.getWeblogger().flush();
        TestUtils.endSession(true);

        // Normalise the role state rather than assume it. addUser grants admin
        // to whichever user it creates while the table is empty
        // (users.firstUserAdmin), so whether these fixtures start out as
        // administrators depends on what else in the suite has just torn its
        // own users down -- which made the "role not held" assertions below
        // pass alone and fail in a full run.
        for (String name : List.of(aliceName, bobName)) {
            User account = users().getUserByUserName(name);
            if (users().hasRole("admin", account)) {
                users().revokeRole("admin", account);
            }
        }
        WebloggerFactory.getWeblogger().flush();
        TestUtils.endSession(true);
    }

    @AfterEach
    void tearDown() throws Exception {
        TestUtils.teardownUser(alice.getUserName());
        TestUtils.teardownUser(bob.getUserName());
        TestUtils.teardownUser(disabled.getUserName());
        TestUtils.endSession(true);
    }

    // --------------------------------------------------------------- lookup

    /**
     * By default the by-name lookup returns only enabled accounts -- it is what
     * sign-in uses, and a disabled account must not authenticate.
     */
    @Test
    void lookupByUserNameFindsAnEnabledAccount() throws Exception {
        assertNotNull(users().getUserByUserName(aliceName));
    }

    @Test
    void lookupByUserNameHidesADisabledAccountByDefault() throws Exception {
        assertNull(users().getUserByUserName(disabledName),
                "the default lookup is the one sign-in uses; a disabled account "
                        + "must not be found by it");
    }

    /**
     * Administration needs the other two states: the disabled account
     * explicitly, and "either" for a screen that lists both.
     */
    @Test
    void lookupByUserNameIsTriStateOnEnabled() throws Exception {
        assertNotNull(users().getUserByUserName(disabledName, Boolean.FALSE),
                "asking for a disabled account must find it");
        assertNotNull(users().getUserByUserName(disabledName, null),
                "asking for either must find it too");
        assertNull(users().getUserByUserName(disabledName, Boolean.TRUE),
                "and asking for enabled must not");
    }

    @Test
    void lookupOfAnAccountThatDoesNotExistReturnsNothing() throws Exception {
        assertNull(users().getUserByUserName("no-such-account"));
        assertNull(users().getUser("no-such-id"));
    }

    /**
     * The forgot-password identifier lookup. {@code TestUtils.setupUser}
     * gives every fixture the same email address, so this gives alice a
     * distinct one first -- otherwise the query could not tell her apart
     * from bob or the disabled account.
     */
    @Test
    void lookupByEmailFindsAnEnabledAccount() throws Exception {
        String email = aliceName + "@example.invalid";
        User managed = users().getUserByUserName(aliceName);
        managed.setEmailAddress(email);
        users().saveUser(managed);
        WebloggerFactory.getWeblogger().flush();
        TestUtils.endSession(true);

        User found = users().getUserByEmail(email);

        assertNotNull(found);
        assertEquals(aliceName, found.getUserName());
    }

    @Test
    void lookupByEmailHidesADisabledAccount() throws Exception {
        String email = disabledName + "@example.invalid";
        User managed = users().getUserByUserName(disabledName, Boolean.FALSE);
        managed.setEmailAddress(email);
        users().saveUser(managed);
        WebloggerFactory.getWeblogger().flush();
        TestUtils.endSession(true);

        assertNull(users().getUserByEmail(email),
                "the forgot-password lookup must not find a disabled account, "
                        + "the same as the by-username lookup it backstops");
    }

    @Test
    void lookupByEmailOfAnUnknownAddressReturnsNothing() throws Exception {
        assertNull(users().getUserByEmail("nobody@example.invalid"));
    }

    /**
     * {@code emailaddress} carries no unique constraint at the database level
     * (see V002 baseline schema), so two enabled accounts really can share an
     * address. The lookup must not throw {@code NonUniqueResultException} --
     * it caps to one row, ordered deterministically by username.
     */
    @Test
    void lookupByEmailToleratesTwoAccountsSharingAnAddress() throws Exception {
        String sharedEmail = "shared@example.invalid";
        User managedAlice = users().getUserByUserName(aliceName);
        managedAlice.setEmailAddress(sharedEmail);
        users().saveUser(managedAlice);
        User managedBob = users().getUserByUserName(bobName);
        managedBob.setEmailAddress(sharedEmail);
        users().saveUser(managedBob);
        WebloggerFactory.getWeblogger().flush();
        TestUtils.endSession(true);

        User found = users().getUserByEmail(sharedEmail);

        assertNotNull(found, "a duplicate address must not make the lookup throw or find nothing");
        assertEquals(aliceName, found.getUserName(),
                "the deterministic order must return the same one every time, "
                        + "not whichever the database happens to return first");
    }

    // -------------------------------------------------------------- listing

    @Test
    void listingIsTriStateOnEnabled() throws Exception {
        List<String> enabled = namesOf(users().getUsers(Boolean.TRUE, null, null, 0, -1));
        assertTrue(enabled.contains(aliceName), "got: " + enabled);
        assertFalse(enabled.contains(disabledName), "got: " + enabled);

        List<String> onlyDisabled = namesOf(users().getUsers(Boolean.FALSE, null, null, 0, -1));
        assertTrue(onlyDisabled.contains(disabledName), "got: " + onlyDisabled);
        assertFalse(onlyDisabled.contains(aliceName), "got: " + onlyDisabled);

        List<String> either = namesOf(users().getUsers(null, null, null, 0, -1));
        assertTrue(either.containsAll(List.of(aliceName, disabledName)),
                "the admin listing shows both, or a disabled account can never be "
                        + "re-enabled: " + either);
    }

    @Test
    void listingCanBeBoundedByDate() throws Exception {
        Date future = Date.from(Instant.now().plus(1, ChronoUnit.DAYS));
        Date past = Date.from(Instant.now().minus(1, ChronoUnit.DAYS));

        List<String> withinWindow = namesOf(users().getUsers(null, past, future, 0, -1));
        assertTrue(withinWindow.contains(aliceName), "got: " + withinWindow);

        List<String> beforeEveryone = namesOf(
                users().getUsers(null, null, Date.from(Instant.now().minus(365, ChronoUnit.DAYS)),
                        0, -1));
        assertFalse(beforeEveryone.contains(aliceName),
                "an end date before the account existed must exclude it: " + beforeEveryone);
    }

    @Test
    void listingIsPageable() throws Exception {
        List<User> firstPage = users().getUsers(null, null, null, 0, 1);
        assertEquals(1, firstPage.size(), "maxResults bounds the page");

        List<User> secondPage = users().getUsers(null, null, null, 1, 1);
        assertEquals(1, secondPage.size());
        assertFalse(namesOf(firstPage).equals(namesOf(secondPage)),
                "an offset must move the window, not repeat it");
    }

    // ------------------------------------------------------ prefix searching

    @Test
    void searchingByPrefixMatchesUserNames() throws Exception {
        List<String> found = namesOf(users().getUsersStartingWith(aliceName, null, 0, 10));

        assertTrue(found.contains(aliceName), "got: " + found);
        assertFalse(found.contains(bobName), "got: " + found);
    }

    /**
     * A null prefix means "everyone", which is what the picker asks for before
     * anything has been typed.
     */
    @Test
    void searchingWithNoPrefixReturnsEveryone() throws Exception {
        List<String> found = namesOf(users().getUsersStartingWith(null, null, 0, 50));

        assertTrue(found.containsAll(List.of(aliceName, bobName)), "got: " + found);
    }

    @Test
    void searchingByPrefixHonoursTheEnabledFilter() throws Exception {
        List<String> enabledOnly = namesOf(users().getUsersStartingWith(prefix(), Boolean.TRUE, 0, 50));
        assertFalse(enabledOnly.contains(disabledName), "got: " + enabledOnly);

        List<String> either = namesOf(users().getUsersStartingWith(prefix(), null, 0, 50));
        assertTrue(either.contains(disabledName), "got: " + either);
    }

    // -------------------------------------------------------- letter indexes

    @Test
    void theLetterMapCountsUsersUnderTheirInitial() throws Exception {
        Map<String, Long> letters = users().getUserNameLetterMap();

        assertEquals(26, letters.size(), "one entry per letter of the alphabet");
        String initial = aliceName.substring(0, 1).toUpperCase();
        assertTrue(letters.get(initial) >= 3,
                "the three fixture accounts must be counted under " + initial
                        + ": " + letters.get(initial));
    }

    @Test
    void usersCanBeFetchedByInitialLetter() throws Exception {
        List<String> found = namesOf(
                users().getUsersByLetter(Character.toUpperCase(aliceName.charAt(0)), 0, 50));

        assertTrue(found.containsAll(List.of(aliceName, bobName)), "got: " + found);
    }

    /**
     * The count is of <em>enabled</em> accounts -- its named query filters on
     * it -- which is what the admin dashboard means by "users".
     */
    @Test
    void theUserCountIsOfEnabledAccounts() throws Exception {
        long before = users().getUserCount();

        User managed = users().getUserByUserName(disabledName, Boolean.FALSE);
        managed.setEnabled(Boolean.TRUE);
        users().saveUser(managed);
        WebloggerFactory.getWeblogger().flush();
        TestUtils.endSession(true);

        assertEquals(before + 1, users().getUserCount(),
                "re-enabling an account must add it to the count");
    }

    // ----------------------------------------------------------------- roles

    @Test
    void aGrantedRoleIsReadBack() throws Exception {
        User managed = users().getUserByUserName(aliceName);
        users().grantRole("admin", managed);
        WebloggerFactory.getWeblogger().flush();
        TestUtils.endSession(true);

        managed = users().getUserByUserName(aliceName);
        assertTrue(users().hasRole("admin", managed));
        assertTrue(users().getRoles(managed).contains("admin"), "got: " + users().getRoles(managed));
    }

    @Test
    void aRevokedRoleIsGone() throws Exception {
        User managed = users().getUserByUserName(aliceName);
        users().grantRole("editor", managed);
        WebloggerFactory.getWeblogger().flush();
        TestUtils.endSession(true);

        managed = users().getUserByUserName(aliceName);
        users().revokeRole("editor", managed);
        WebloggerFactory.getWeblogger().flush();
        TestUtils.endSession(true);

        managed = users().getUserByUserName(aliceName);
        assertFalse(users().hasRole("editor", managed),
                "a revoked role must not survive, or an account keeps authority "
                        + "an administrator believed they removed");
    }

    /**
     * Roles are per user. Granting one to somebody must not grant it to
     * everybody, which a query missing its username predicate would do.
     */
    @Test
    void aRoleGrantedToOneUserIsNotHeldByAnother() throws Exception {
        User managedAlice = users().getUserByUserName(aliceName);
        users().grantRole("admin", managedAlice);
        WebloggerFactory.getWeblogger().flush();
        TestUtils.endSession(true);

        User managedBob = users().getUserByUserName(bobName);
        assertFalse(users().hasRole("admin", managedBob),
                "bob was granted nothing and must hold nothing");
    }

    @Test
    void aRoleThatWasNeverGrantedIsNotHeld() throws Exception {
        assertFalse(users().hasRole("admin", users().getUserByUserName(bobName)));
    }

    // --------------------------------------------------------------- helpers

    private static UserManager users() {
        return WebloggerFactory.getWeblogger().getUserManager();
    }

    /** The shared prefix of this test's fixture accounts. */
    private String prefix() {
        return aliceName.substring(0, aliceName.length() - "Alice".length());
    }

    private static List<String> namesOf(List<User> users) {
        return users.stream().map(User::getUserName).toList();
    }
}
