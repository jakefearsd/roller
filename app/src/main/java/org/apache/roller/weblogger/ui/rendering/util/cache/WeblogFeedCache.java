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

package org.apache.roller.weblogger.ui.rendering.util.cache;

import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.roller.weblogger.config.WebloggerConfig;
import org.apache.roller.weblogger.ui.rendering.util.WeblogFeedRequest;
import org.apache.roller.weblogger.util.cache.Cache;
import org.apache.roller.weblogger.util.cache.CacheManager;
import org.apache.roller.weblogger.util.cache.LazyExpiringCacheEntry;


/**
 * Cache for weblog feed content.
 */
public final class WeblogFeedCache {
    
    private static final Log log = LogFactory.getLog(WeblogFeedCache.class);
    
    // a unique identifier for this cache, this is used as the prefix for
    // roller config properties that apply to this cache
    public static final String CACHE_ID = "cache.weblogfeed";
    
    // keep cached content
    private boolean cacheEnabled = true;
    private Cache contentCache = null;
    
    // reference to our singleton instance
    private static final WeblogFeedCache singletonInstance = new WeblogFeedCache();
    
    
    private WeblogFeedCache() {
        this(WebloggerConfig.getBooleanProperty(CACHE_ID+".enabled"));
    }


    /**
     * Package private so that tests can build an instance with caching turned
     * off. The singleton reads that flag once, when the class is loaded, and
     * running with the feed cache disabled is a supported (and, in development,
     * the usual) configuration.
     */
    WeblogFeedCache(boolean cacheEnabled) {

        this.cacheEnabled = cacheEnabled;

        Map<String, String> cacheProps = new HashMap<>();
        cacheProps.put("id", CACHE_ID);
        
        Enumeration<Object> allProps = WebloggerConfig.keys();
        String prop;
        while(allProps.hasMoreElements()) {
            prop = (String) allProps.nextElement();
            
            // we are only interested in props for this cache
            if(prop.startsWith(CACHE_ID+".")) {
                cacheProps.put(prop.substring(CACHE_ID.length()+1), 
                        WebloggerConfig.getProperty(prop));
            }
        }
        
        log.info(cacheProps);
        
        if(cacheEnabled) {
            contentCache = CacheManager.constructCache(null, cacheProps);
        } else {
            log.warn("Caching has been DISABLED");
        }
    }
    
    
    public static WeblogFeedCache getInstance() {
        return singletonInstance;
    }


    // CPD-OFF -- The three render caches are deliberately NOT collapsed into a
    // shared base. Their expiry contracts genuinely differ: WeblogPageCache has
    // no CacheHandler and is expired only lazily against weblog.lastModified,
    // while its siblings are invalidated through CacheManager. Unifying them
    // would be a behavioural change wearing cleanup's clothes. See CLAUDE.md,
    // Templates.
    public Object get(String key, long lastModified) {

        if (!cacheEnabled) {
            return null;
        }

        Object entry = null;

        LazyExpiringCacheEntry lazyEntry =
                (LazyExpiringCacheEntry) this.contentCache.get(key);
        if(lazyEntry != null) {
            entry = lazyEntry.getValue(lastModified);

            if(entry != null) {
                log.debug("HIT "+key);
            } else {
                log.debug("HIT-EXPIRED "+key);
            }

        } else {
            log.debug("MISS "+key);
        }

        return entry;
    }


    public void put(String key, Object value) {

        if (!cacheEnabled) {
            return;
        }

        contentCache.put(key, new LazyExpiringCacheEntry(value));
        log.debug("PUT "+key);
    }


    public void remove(String key) {

        if (!cacheEnabled) {
            return;
        }

        contentCache.remove(key);
        log.debug("REMOVE "+key);
    }


    public void clear() {

        if (!cacheEnabled) {
            return;
        }

        contentCache.clear();
        log.debug("CLEAR");
    }
    // CPD-ON


    /**
     * Generate a cache key from a parsed weblog feed request.
     * This generates a key of the form ...
     *
     * <handle>/<type>/<format>[/search/<term>][/cat/<category>][/tags/<tags>][/language][/excerpts]
     *
     * examples ...
     *
     * cache.weblogfeed:foo/entries/atom/en
     * cache.weblogfeed:foo/entries/atom/cat/MyCategory/en
     * cache.weblogfeed:foo/entries/atom/en/excerpts
     *
     * Every segment built from request data is labelled and escaped: a search
     * for "foo/en" must not produce the key of the English feed for "foo".
     */
    public String generateKey(WeblogFeedRequest feedRequest) {

        StringBuilder key = new StringBuilder(128);

        key.append(CACHE_ID).append(':');
        key.append(feedRequest.getWeblogHandle());

        key.append('/').append(feedRequest.getType());
        key.append('/').append(feedRequest.getFormat());

        if (feedRequest.getTerm() != null) {
            // arbitrary text straight off the query string
            key.append("/search/").append(CacheKeys.encode(feedRequest.getTerm()));
        }

        if(feedRequest.getWeblogCategoryName() != null) {
            key.append("/cat/").append(CacheKeys.encode(feedRequest.getWeblogCategoryName()));
        }

        if(feedRequest.getTags() != null && !feedRequest.getTags().isEmpty()) {
            key.append("/tags/").append(CacheKeys.tags(feedRequest.getTags()));
        }

        if(feedRequest.getLocale() != null) {
            key.append('/').append(feedRequest.getLocale());
        }

        if(feedRequest.isExcerpts()) {
            key.append("/excerpts");
        }

        return key.toString();
    }

}
