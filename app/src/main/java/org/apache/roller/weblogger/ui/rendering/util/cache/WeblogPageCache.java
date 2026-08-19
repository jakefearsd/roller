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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.apache.roller.weblogger.config.WebloggerConfig;
import org.apache.roller.weblogger.ui.rendering.util.WeblogPageRequest;
import org.apache.roller.weblogger.util.cache.Cache;
import org.apache.roller.weblogger.util.cache.CacheManager;
import org.apache.roller.weblogger.util.cache.LazyExpiringCacheEntry;


/**
 * Cache for weblog page content.
 */
public final class WeblogPageCache {
    
    private static final Logger log = LoggerFactory.getLogger(WeblogPageCache.class);
    
    // a unique identifier for this cache, this is used as the prefix for
    // roller config properties that apply to this cache
    public static final String CACHE_ID = "cache.weblogpage";
    
    // keep cached content
    private boolean cacheEnabled = true;
    private Cache contentCache = null;
    
    // reference to our singleton instance
    private static final WeblogPageCache singletonInstance = new WeblogPageCache();
    
    
    private WeblogPageCache() {
        this(WebloggerConfig.getBooleanProperty(CACHE_ID+".enabled"));
    }


    /**
     * Private -- construction with an explicit flag is for tests only, via
     * {@link #newForTest(boolean)}. The singleton itself reads the flag once,
     * when the class is loaded, and running with the page cache disabled is a
     * supported (and, in development, the usual) configuration.
     */
    private WeblogPageCache(boolean cacheEnabled) {

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
        
        log.info("{}", cacheProps);
        
        if (cacheEnabled) {
            contentCache = CacheManager.constructCache(null, cacheProps);
        } else {
            log.warn("Caching has been DISABLED");
        }
    }
    
    
    public static WeblogPageCache getInstance() {
        return singletonInstance;
    }


    /**
     * Package private for unit tests: builds an instance with caching forced
     * on or off, bypassing the singleton and the config-file read its
     * constructor otherwise does.
     */
    static WeblogPageCache newForTest(boolean cacheEnabled) {
        return new WeblogPageCache(cacheEnabled);
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

        LazyExpiringCacheEntry lazyEntry = (LazyExpiringCacheEntry) this.contentCache.get(key);
        if(lazyEntry != null) {
            entry = lazyEntry.getValue(lastModified);

            if(entry != null) {
                log.debug("HIT {}", key);
            } else {
                log.debug("HIT-EXPIRED {}", key);
            }

        } else {
            log.debug("MISS {}", key);
        }

        return entry;
    }


    public void put(String key, Object value) {

        if (!cacheEnabled) {
            return;
        }

        contentCache.put(key, new LazyExpiringCacheEntry(value));
        log.debug("PUT {}", key);
    }


    public void remove(String key) {

        if (!cacheEnabled) {
            return;
        }

        contentCache.remove(key);
        log.debug("REMOVE {}", key);
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
     * Generate a cache key from a parsed weblog page request.
     * This generates a key of the form ...
     *
     * <handle>[/entry/<anchor>][/<language>][/page=<num>][/user=<user>]
     *   or
     * <handle>[/pageslug/<slug>][/page/<weblogPage>][/date/<date>][/cat/<category>][/tags/<tags>][/<language>][/page=<num>][/user=<user>][/qp=<params>]
     *
     *
     * examples ...
     *
     * cache.weblogpage:foo/en/page=0
     * cache.weblogpage:foo/entry/entry_anchor/en
     * cache.weblogpage:foo/pageslug/about/en/page=0
     * cache.weblogpage:foo/date/20051110/en/page=0
     * cache.weblogpage:foo/cat/MyCategory/en/page=0/user=myname
     *
     * Every segment built from request data is labelled and escaped: a category
     * called "en" must not produce the key of the English homepage.
     */
    // CPD-OFF -- The three render caches are deliberately NOT collapsed into a
    // shared base. Their expiry contracts genuinely differ: WeblogPageCache has
    // no CacheHandler and is expired only lazily against weblog.lastModified,
    // while its siblings are invalidated through CacheManager. Unifying them
    // would be a behavioural change wearing cleanup's clothes. See CLAUDE.md,
    // Templates.
    public String generateKey(WeblogPageRequest pageRequest) {

        StringBuilder key = new StringBuilder(128);

        key.append(CACHE_ID).append(':');
        key.append(pageRequest.getWeblogHandle());

        if(pageRequest.getWeblogAnchor() != null) {
            // may contain spaces or other bad chars
            key.append("/entry/").append(CacheKeys.encode(pageRequest.getWeblogAnchor()));
        } else {

            if(pageRequest.getPageSlug() != null) {
                // the raw path segment of a bare-slug page (/<handle>/about),
                // read here rather than the WeblogPage it resolves to, so
                // that generating a key never queries the database. Without
                // this segment every bare-slug page on a weblog -- and the
                // weblog's homepage -- shared one cache key, so whichever
                // page rendered first was served for all of them.
                key.append("/pageslug/").append(CacheKeys.encode(pageRequest.getPageSlug()));
            }

            if(pageRequest.getWeblogPageName() != null) {
                // comes straight off the url path, so it may contain slashes
                key.append("/page/").append(CacheKeys.encode(pageRequest.getWeblogPageName()));
            }

            if(pageRequest.getWeblogDate() != null) {
                key.append("/date/").append(pageRequest.getWeblogDate());
            }

            if(pageRequest.getWeblogCategoryName() != null) {
                // may contain spaces or other bad chars
                key.append("/cat/").append(CacheKeys.encode(pageRequest.getWeblogCategoryName()));
            }

            if("tags".equals(pageRequest.getContext())
                    && pageRequest.getTags() != null && !pageRequest.getTags().isEmpty()) {
                key.append("/tags/").append(CacheKeys.tags(pageRequest.getTags()));
            }
        }

        if(pageRequest.getLocale() != null) {
            key.append('/').append(pageRequest.getLocale());
        }

        // add page number when applicable
        if(pageRequest.getWeblogAnchor() == null) {
            key.append("/page=").append(pageRequest.getPageNum());
        }

        // add login state
        if(pageRequest.getAuthenticUser() != null) {
            key.append("/user=").append(pageRequest.getAuthenticUser());
        }

        // we allow for arbitrary query params for custom pages
        if(pageRequest.getWeblogPageName() != null && !pageRequest.getCustomParams().isEmpty()) {
            key.append("/qp=").append(CacheKeys.params(pageRequest.getCustomParams()));
        }

        return key.toString();
    }
    // CPD-ON
}
