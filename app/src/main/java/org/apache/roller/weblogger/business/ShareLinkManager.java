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

import java.util.List;

import org.apache.roller.weblogger.WebloggerException;
import org.apache.roller.weblogger.pojos.ShareLink;
import org.apache.roller.weblogger.pojos.Weblog;

/**
 * Interface to share link management.
 *
 * <p>A {@link ShareLink} is a tokened public URL for one weblog entry or one
 * media file directory. This tier stores whatever password hash it is handed
 * (or null) -- hashing the plaintext is the caller's job.
 */
public interface ShareLinkManager {

    /**
     * Store a new share link. The link must name a weblog, a target of a
     * known type ({@link ShareLink#TYPE_ENTRY} or
     * {@link ShareLink#TYPE_DIRECTORY}), and a token.
     */
    void createShareLink(ShareLink shareLink) throws WebloggerException;

    /**
     * Look a share link up by its URL token. Returns null when no link
     * carries the token.
     */
    ShareLink getShareLinkByToken(String token) throws WebloggerException;

    /**
     * All share links belonging to a weblog, newest first.
     */
    List<ShareLink> getShareLinksForWeblog(Weblog weblog) throws WebloggerException;

    /**
     * The newest share link for a specific target, or null when the target
     * has never been shared.
     *
     * @param targetType {@link ShareLink#TYPE_ENTRY} or {@link ShareLink#TYPE_DIRECTORY}
     * @param targetId   the entry's or directory's id
     */
    ShareLink getShareLinkForTarget(String targetType, String targetId)
            throws WebloggerException;

    /**
     * Remove a share link; the shared content itself is untouched.
     */
    void removeShareLink(ShareLink shareLink) throws WebloggerException;

    /**
     * Release all resources associated with Roller session.
     */
    void release();
}
