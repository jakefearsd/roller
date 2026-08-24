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

import java.sql.Timestamp;
import java.util.List;

import jakarta.persistence.Query;
import jakarta.persistence.TypedQuery;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.apache.roller.weblogger.WebloggerException;
import org.apache.roller.weblogger.business.WeblogPageManager;
import org.apache.roller.weblogger.business.Weblogger;
import org.apache.roller.weblogger.pojos.ReservedSlugs;
import org.apache.roller.weblogger.pojos.Weblog;
import org.apache.roller.weblogger.pojos.WeblogPage;

/**
 * JPA implementation of {@link WeblogPageManager}.
 */
public class JPAWeblogPageManagerImpl implements WeblogPageManager {

    private static final Logger log = LoggerFactory.getLogger(JPAWeblogPageManagerImpl.class);

    private final Weblogger roller;
    private final JPAPersistenceStrategy strategy;

    protected JPAWeblogPageManagerImpl(Weblogger roller, JPAPersistenceStrategy strategy) {
        log.debug("Instantiating JPA Weblog Page Manager");
        this.roller = roller;
        this.strategy = strategy;
    }

    @Override
    public void savePage(WeblogPage page) throws WebloggerException {
        String slug = page.getSlug() == null ? null : page.getSlug().trim();
        if (slug == null || slug.isBlank()) {
            throw new WebloggerException("page slug is required");
        }
        if (slug.indexOf('/') >= 0) {
            throw new WebloggerException("page slug may not contain '/': " + slug);
        }
        if (ReservedSlugs.isReserved(slug)) {
            throw new WebloggerException("page slug is reserved: " + slug);
        }
        page.setSlug(slug);

        Timestamp now = new Timestamp(System.currentTimeMillis());
        if (page.getCreated() == null) {
            page.setCreated(now);
        }
        page.setUpdated(now);

        strategy.store(page);

        // Automatic slug history: a rename mints the redirect that keeps the
        // old URL alive. loadedSlug is the JPA post-load snapshot (null on a
        // brand-new page), so this fires exactly when a persisted slug
        // changed. Deliberately NOT best-effort -- same transaction as the
        // save, because a rename whose history row failed to write is a
        // rename that silently killed a URL, which is the exact failure the
        // redirect feature exists to prevent.
        String loadedSlug = page.getLoadedSlug();
        if (loadedSlug != null && !loadedSlug.equals(slug)) {
            roller.getWeblogRedirectManager().recordRename(
                    page.getWeblog(), "/" + loadedSlug, "/" + slug);
        }

        // update weblog last modified date.  date updated by saveWeblog().
        // WeblogPageCache has no CacheHandler -- a rendered page expires
        // lazily by comparing itself against weblog.lastModified (see
        // saveTemplate/saveComment for the same pattern) -- so without this
        // a published page never reaches a reader holding a cached copy.
        roller.getWeblogManager().saveWeblog(page.getWeblog());
    }

    @Override
    public void removePage(WeblogPage page) throws WebloggerException {
        this.strategy.remove(page);

        // update weblog last modified date.  date updated by saveWeblog()
        roller.getWeblogManager().saveWeblog(page.getWeblog());
    }

    @Override
    public WeblogPage getPage(String id) throws WebloggerException {
        return (WeblogPage) strategy.load(WeblogPage.class, id);
    }

    @Override
    public WeblogPage getPageBySlug(Weblog weblog, String slug) throws WebloggerException {
        TypedQuery<WeblogPage> query = strategy.getNamedQuery(
                "WeblogPage.getByWeblogAndSlug", WeblogPage.class);
        query.setParameter(1, weblog);
        query.setParameter(2, slug);
        List<WeblogPage> results = query.getResultList();
        return results.isEmpty() ? null : results.get(0);
    }

    @Override
    public List<WeblogPage> getPages(Weblog weblog) throws WebloggerException {
        TypedQuery<WeblogPage> query = strategy.getNamedQuery(
                "WeblogPage.getByWeblog", WeblogPage.class);
        query.setParameter(1, weblog);
        return query.getResultList();
    }

    @Override
    public List<WeblogPage> getPublishedPages(Weblog weblog) throws WebloggerException {
        TypedQuery<WeblogPage> query = strategy.getNamedQuery(
                "WeblogPage.getByWeblogAndStatus", WeblogPage.class);
        query.setParameter(1, weblog);
        query.setParameter(2, WeblogPage.PubStatus.PUBLISHED);
        return query.getResultList();
    }

    @Override
    public void removePages(Weblog weblog) throws WebloggerException {
        Query removePages = strategy.getNamedUpdate("WeblogPage.removeByWeblog");
        removePages.setParameter(1, weblog);
        removePages.executeUpdate();
    }
}
