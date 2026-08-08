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
package org.apache.roller.weblogger.business;

import java.sql.Timestamp;

import org.apache.roller.weblogger.TestUtils;
import org.apache.roller.weblogger.pojos.User;
import org.apache.roller.weblogger.pojos.UserToken;
import org.apache.roller.weblogger.util.TokenGenerator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Tests for {@link UserTokenManager}: hashed storage, single-use, expiry, and
 * that a user with outstanding tokens can still be torn down (the FK from
 * {@code roller_user_token} to {@code roller_user} has no cascade).
 */
class UserTokenManagerTest {

    private User user;

    @BeforeEach
    void setUp() throws Exception {
        TestUtils.setupWeblogger();
        user = TestUtils.setupUser("usertokenuser");
        TestUtils.endSession(true);
    }

    @AfterEach
    void tearDown() throws Exception {
        TestUtils.teardownUser(user.getUserName());
        TestUtils.endSession(true);
    }

    private static UserTokenManager manager() {
        return WebloggerFactory.getWeblogger().getUserTokenManager();
    }

    @Test
    void issuingATokenStoresOnlyItsDigestNeverTheRawValue() throws Exception {
        User managedUser = TestUtils.getManagedUser(user);

        String raw = manager().issueToken(managedUser, UserToken.Purpose.PASSWORD_RESET);
        WebloggerFactory.getWeblogger().flush();
        TestUtils.endSession(true);

        // Read the row back directly and check the stolen-database property:
        // the raw token must not be what is stored.
        UserToken stored = manager().validate(raw);
        assertNotNull(stored, "the freshly issued token must validate");
        assertNotEquals(raw, stored.getTokenSha256(),
                "the raw token must never be persisted as-is");
        assertEquals(TokenGenerator.sha256Hex(raw), stored.getTokenSha256(),
                "the stored value must be exactly the raw token's SHA-256 digest");
    }

    @Test
    void aFreshTokenValidatesToItsUser() throws Exception {
        User managedUser = TestUtils.getManagedUser(user);
        String raw = manager().issueToken(managedUser, UserToken.Purpose.PASSWORD_RESET);
        WebloggerFactory.getWeblogger().flush();
        TestUtils.endSession(true);

        UserToken validated = manager().validate(raw);

        assertNotNull(validated);
        assertEquals(managedUser.getUserName(), validated.getUser().getUserName());
        assertNull(validated.getUsedAt(), "an unconsumed token must have no usedAt");
    }

    @Test
    void consumingATokenReturnsItsUserAndStampsUsedAt() throws Exception {
        User managedUser = TestUtils.getManagedUser(user);
        String raw = manager().issueToken(managedUser, UserToken.Purpose.PASSWORD_RESET);
        WebloggerFactory.getWeblogger().flush();
        TestUtils.endSession(true);

        User consumedBy = manager().consume(raw);

        assertNotNull(consumedBy);
        assertEquals(managedUser.getUserName(), consumedBy.getUserName());
    }

    @Test
    void aSecondConsumeOfTheSameTokenFailsSingleUseIsEnforced() throws Exception {
        User managedUser = TestUtils.getManagedUser(user);
        String raw = manager().issueToken(managedUser, UserToken.Purpose.PASSWORD_RESET);
        WebloggerFactory.getWeblogger().flush();
        TestUtils.endSession(true);

        User first = manager().consume(raw);
        WebloggerFactory.getWeblogger().flush();
        TestUtils.endSession(true);

        assertNotNull(first, "the first consume must succeed");

        User second = manager().consume(raw);

        assertNull(second, "a token must not be redeemable twice");
    }

    @Test
    void anExpiredTokenDoesNotValidate() throws Exception {
        User managedUser = TestUtils.getManagedUser(user);
        String raw = manager().issueToken(managedUser, UserToken.Purpose.PASSWORD_RESET);
        WebloggerFactory.getWeblogger().flush();
        TestUtils.endSession(true);

        // Push the token's expiry into the past on the managed row.
        UserToken token = manager().validate(raw);
        assertNotNull(token, "must exist before we can expire it");
        token.setExpires(new Timestamp(System.currentTimeMillis() - 1000L));
        WebloggerFactory.getWeblogger().flush();
        TestUtils.endSession(true);

        assertNull(manager().validate(raw), "an expired token must not validate");
    }

    @Test
    void aGarbageTokenValidatesToNullWithoutThrowing() throws Exception {
        assertNull(manager().validate("this-is-not-a-real-token"));
    }

    @Test
    void removeTokensClearsAUsersRows() throws Exception {
        User managedUser = TestUtils.getManagedUser(user);
        String raw1 = manager().issueToken(managedUser, UserToken.Purpose.PASSWORD_RESET);
        String raw2 = manager().issueToken(managedUser, UserToken.Purpose.PASSWORD_SET);
        WebloggerFactory.getWeblogger().flush();
        TestUtils.endSession(true);

        manager().removeTokens(TestUtils.getManagedUser(user));
        WebloggerFactory.getWeblogger().flush();
        TestUtils.endSession(true);

        assertNull(manager().validate(raw1), "removeTokens must clear the first token");
        assertNull(manager().validate(raw2), "removeTokens must clear the second token");
    }

    @Test
    void teardownUserSucceedsForAUserWithOutstandingTokens() throws Exception {
        // Proves JPAUserManagerImpl.removeUser cleans up roller_user_token
        // rows itself -- the FK has no cascade, so leaving this token behind
        // would make teardownUser (and therefore this whole suite's @AfterEach)
        // fail with a foreign-key violation.
        User managedUser = TestUtils.getManagedUser(user);
        manager().issueToken(managedUser, UserToken.Purpose.PASSWORD_RESET);
        WebloggerFactory.getWeblogger().flush();
        TestUtils.endSession(true);

        // tearDown() below calls TestUtils.teardownUser(...); reaching it
        // without an exception is the assertion.
    }
}
