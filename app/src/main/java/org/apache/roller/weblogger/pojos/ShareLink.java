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

import java.io.Serializable;
import java.sql.Timestamp;
import java.time.Instant;

import org.apache.commons.lang3.builder.HashCodeBuilder;
import org.apache.roller.util.UUIDGenerator;

/**
 * A tokened public link to a single weblog entry or media file directory,
 * optionally protected by a password and an expiry date.
 *
 * <p>The target is referenced by type + id rather than a JPA relationship
 * because entries and directories are deleted independently of their share
 * links; a dangling target degrades to "nothing to share" at render time
 * instead of blocking the delete.
 */
public class ShareLink implements Serializable {

    private static final long serialVersionUID = 1L;

    /** {@link #getTargetType()} value for a link to a weblog entry. */
    public static final String TYPE_ENTRY = "entry";

    /** {@link #getTargetType()} value for a link to a media file directory. */
    public static final String TYPE_DIRECTORY = "directory";

    private String id = UUIDGenerator.generateUUID();
    private Weblog weblog;
    private String targetType;
    private String targetId;
    private String token;
    private String passwordHash;
    /**
     * From {@link Instant#now()} rather than {@code currentTimeMillis()}: the
     * column keeps microseconds (V011) and the queries order by this, so a
     * millisecond tie between two links for one target would leave the
     * database to decide which one an author is shown as theirs.
     */
    private Timestamp created = Timestamp.from(Instant.now());
    private Timestamp expires;

    public ShareLink() {
    }

    /**
     * Database surrogate key.
     */
    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    /**
     * The weblog whose content this link shares.
     */
    public Weblog getWeblog() {
        return weblog;
    }

    public void setWeblog(Weblog weblog) {
        this.weblog = weblog;
    }

    /**
     * What kind of thing is shared: {@link #TYPE_ENTRY} or
     * {@link #TYPE_DIRECTORY}.
     */
    public String getTargetType() {
        return targetType;
    }

    public void setTargetType(String targetType) {
        this.targetType = targetType;
    }

    /**
     * The shared entry's or directory's surrogate key. Deliberately not a
     * foreign key -- see the class comment.
     */
    public String getTargetId() {
        return targetId;
    }

    public void setTargetId(String targetId) {
        this.targetId = targetId;
    }

    /**
     * The URL-safe secret that makes the link unguessable; unique across all
     * share links. Generate with
     * {@link org.apache.roller.weblogger.util.TokenGenerator#newToken()}.
     */
    public String getToken() {
        return token;
    }

    public void setToken(String token) {
        this.token = token;
    }

    /**
     * Password hash produced by the configured
     * {@code DelegatingPasswordEncoder}, or null when the link is not
     * password-protected. Callers hash before storing; this tier never sees
     * the plaintext.
     */
    public String getPasswordHash() {
        return passwordHash;
    }

    public void setPasswordHash(String passwordHash) {
        this.passwordHash = passwordHash;
    }

    /**
     * When the link was created.
     */
    public Timestamp getCreated() {
        return created;
    }

    public void setCreated(Timestamp created) {
        this.created = created;
    }

    /**
     * When the link stops working, or null for a link that never expires.
     */
    public Timestamp getExpires() {
        return expires;
    }

    public void setExpires(Timestamp expires) {
        this.expires = expires;
    }

    // ------------------------------------------------------- Good citizenship

    @Override
    public String toString() {
        return "ShareLink [id=" + getId() + ", targetType=" + getTargetType()
                + ", targetId=" + getTargetId() + "]";
    }

    @Override
    public boolean equals(Object other) {
        if (other == this) {
            return true;
        }
        if (!(other instanceof ShareLink)) {
            return false;
        }
        // our natural key, or business key, is our token
        final ShareLink that = (ShareLink) other;
        return this.getToken() != null && this.getToken().equals(that.getToken());
    }

    @Override
    public int hashCode() {
        return new HashCodeBuilder().append(getToken()).toHashCode();
    }

}
