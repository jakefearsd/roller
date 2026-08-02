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

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ShareLink value semantics: id assigned at construction, sensible defaults,
 * and token as the business key.
 */
class ShareLinkPojoTest {

    @Test
    void freshLinkHasIdCreatedAndNoSecrets() {
        ShareLink link = new ShareLink();

        assertNotNull(link.getId(), "House style: UUID assigned at construction");
        assertNotNull(link.getCreated(), "Creation time defaults to now");
        assertNull(link.getPasswordHash(), "Links are unprotected until a hash is stored");
        assertNull(link.getExpires(), "Links do not expire unless asked to");
    }

    @Test
    void twoFreshLinksGetDistinctIds() {
        assertNotEquals(new ShareLink().getId(), new ShareLink().getId());
    }

    @Test
    void equalityFollowsTheToken() {
        ShareLink a = new ShareLink();
        a.setToken("token-a");
        ShareLink sameToken = new ShareLink();
        sameToken.setToken("token-a");
        ShareLink other = new ShareLink();
        other.setToken("token-b");

        assertEquals(a, a);
        assertEquals(a, sameToken);
        assertEquals(a.hashCode(), sameToken.hashCode());
        assertNotEquals(a, other);
        assertFalse(a.equals("token-a"), "Different type is never equal");

        ShareLink tokenless = new ShareLink();
        assertNotEquals(tokenless, a, "A link without a token equals nothing");
    }

    @Test
    void toStringNamesTheTarget() {
        ShareLink link = new ShareLink();
        link.setTargetType(ShareLink.TYPE_DIRECTORY);
        link.setTargetId("dir-1");

        String s = link.toString();
        assertTrue(s.contains(ShareLink.TYPE_DIRECTORY));
        assertTrue(s.contains("dir-1"));
        assertFalse(s.contains("token"), "Secrets stay out of logs");
    }
}
