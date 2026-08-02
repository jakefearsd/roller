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
package org.apache.roller.weblogger.business.jpa;

import java.util.List;

import jakarta.persistence.TypedQuery;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.roller.weblogger.WebloggerException;
import org.apache.roller.weblogger.business.ShareLinkManager;
import org.apache.roller.weblogger.pojos.ShareLink;
import org.apache.roller.weblogger.pojos.Weblog;

/**
 * JPA implementation of {@link ShareLinkManager}.
 */
public class JPAShareLinkManagerImpl implements ShareLinkManager {

    private static final Log log = LogFactory.getLog(JPAShareLinkManagerImpl.class);

    private final JPAPersistenceStrategy strategy;

    protected JPAShareLinkManagerImpl(JPAPersistenceStrategy strategy) {
        log.debug("Instantiating JPA Share Link Manager");
        this.strategy = strategy;
    }

    @Override
    public void createShareLink(ShareLink shareLink) throws WebloggerException {
        if (shareLink.getWeblog() == null) {
            throw new WebloggerException("Share link must belong to a weblog");
        }
        if (!ShareLink.TYPE_ENTRY.equals(shareLink.getTargetType())
                && !ShareLink.TYPE_DIRECTORY.equals(shareLink.getTargetType())) {
            throw new WebloggerException(
                    "Unknown share link target type: " + shareLink.getTargetType());
        }
        if (shareLink.getTargetId() == null) {
            throw new WebloggerException("Share link must name a target id");
        }
        if (shareLink.getToken() == null) {
            throw new WebloggerException("Share link must carry a token");
        }
        this.strategy.store(shareLink);
    }

    @Override
    public ShareLink getShareLinkByToken(String token) throws WebloggerException {
        TypedQuery<ShareLink> query = strategy.getNamedQuery(
                "ShareLink.getByToken", ShareLink.class);
        query.setParameter(1, token);
        List<ShareLink> results = query.getResultList();
        return results.isEmpty() ? null : results.get(0);
    }

    @Override
    public List<ShareLink> getShareLinksForWeblog(Weblog weblog) throws WebloggerException {
        TypedQuery<ShareLink> query = strategy.getNamedQuery(
                "ShareLink.getByWeblog", ShareLink.class);
        query.setParameter(1, weblog);
        return query.getResultList();
    }

    @Override
    public ShareLink getShareLinkForTarget(String targetType, String targetId)
            throws WebloggerException {
        TypedQuery<ShareLink> query = strategy.getNamedQuery(
                "ShareLink.getByTarget", ShareLink.class);
        query.setParameter(1, targetType);
        query.setParameter(2, targetId);
        List<ShareLink> results = query.getResultList();
        return results.isEmpty() ? null : results.get(0);
    }

    @Override
    public void removeShareLink(ShareLink shareLink) throws WebloggerException {
        this.strategy.remove(shareLink);
    }

    @Override
    public void release() {
        // no resources to release
    }
}
