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
import java.util.List;
import org.apache.roller.weblogger.WebloggerException;
import org.apache.roller.weblogger.pojos.ApiToken;
import org.apache.roller.weblogger.pojos.User;

/**
 * Long-lived credentials for the automation API.
 *
 * <p>Distinct from {@link UserTokenManager}, whose tokens are single-use and
 * expire in an hour. These are multi-use, optionally perpetual, and revocable.
 */
public interface ApiTokenManager {

    /** Prefix on every raw token, so one is recognisable in a log or a paste. */
    String TOKEN_PREFIX = "rlr_";

    /**
     * Mints a token and returns the raw secret. This is the only time the
     * secret exists outside the caller's hands -- only its digest is stored.
     */
    String issueToken(User user, String label, String scopeWeblog,
                      ApiToken.Role role, Timestamp expiresAt) throws WebloggerException;

    /** The token behind this secret, or null if unknown, expired or revoked. */
    ApiToken authenticate(String rawToken) throws WebloggerException;

    List<ApiToken> getTokens(User user) throws WebloggerException;

    /** Revokes {@code tokenId} if {@code user} owns it. False otherwise. */
    boolean revoke(User user, String tokenId) throws WebloggerException;
}
