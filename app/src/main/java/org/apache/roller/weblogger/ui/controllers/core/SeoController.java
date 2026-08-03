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
package org.apache.roller.weblogger.ui.controllers.core;

import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.Date;
import java.util.List;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.roller.weblogger.WebloggerException;
import org.apache.roller.weblogger.business.URLStrategy;
import org.apache.roller.weblogger.config.WebloggerRuntimeConfig;
import org.apache.roller.weblogger.pojos.MediaFile;
import org.apache.roller.weblogger.pojos.Weblog;
import org.apache.roller.weblogger.pojos.WeblogEntry;
import org.apache.roller.weblogger.pojos.WeblogEntry.PubStatus;
import org.apache.roller.weblogger.pojos.WeblogEntrySearchCriteria;
import org.apache.roller.weblogger.ui.controllers.BaseController;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

/**
 * Serves the crawler-facing SEO surface: {@code /robots.txt}, the sitemap
 * index at {@code /sitemap.xml}, and one sitemap per weblog at
 * {@code /sitemap-<handle>.xml}.
 *
 * <p>Everything is generated per request, uncached: at this installation's
 * scale (10-50 weblogs, a few hundred entries each) regenerating the XML is
 * cheaper than wiring these documents into the render-cache invalidation
 * machinery.
 *
 * <p>The per-weblog sitemap lists the weblog home page plus every PUBLISHED
 * entry that is not flagged {@code noindex} ({@code null} on pre-existing rows
 * means indexable). Entries with a featured image get a Google image-sitemap
 * {@code image:image} element whose {@code loc} is the media-resource URL of
 * the largest rendition ({@code ?w=1600}); before renditions exist for a file,
 * MediaResourceServlet serves the original for that URL, so the link is valid
 * either way.
 *
 * <p>Routing: the DispatcherServlet is mapped {@code *.rol} plus the explicit
 * SEO patterns added in {@code ServletRegistrationConfig} -- see
 * {@code ServletRegistrationConfig#SEO_URL_PATTERNS} for why the per-weblog
 * sitemaps ride on a {@code *.xml} extension mapping.
 */
@Controller
public class SeoController extends BaseController {

    private static final Log log = LogFactory.getLog(SeoController.class);

    private static final String SITEMAP_NS = "http://www.sitemaps.org/schemas/sitemap/0.9";
    private static final String IMAGE_NS = "http://www.google.com/schemas/sitemap-image/1.1";
    private static final MediaType APPLICATION_XML_UTF8 =
            new MediaType("application", "xml", java.nio.charset.StandardCharsets.UTF_8);

    @Override
    public boolean isUserRequired() {
        return false;
    }

    @Override
    public boolean isWeblogRequired() {
        return false;
    }

    @Override
    public List<String> requiredGlobalPermissionActions() {
        return Collections.emptyList();
    }

    /** Allow-all robots policy that advertises the sitemap index. */
    @GetMapping("/robots.txt")
    public ResponseEntity<String> robots() {
        String body = "User-agent: *\n"
                + "Disallow:\n"
                + "\n"
                + "Sitemap: " + absoluteContextURL() + "/sitemap.xml\n";
        return ResponseEntity.ok()
                .contentType(new MediaType(MediaType.TEXT_PLAIN,
                        java.nio.charset.StandardCharsets.UTF_8))
                .body(body);
    }

    /** Sitemap index listing one sitemap per visible, active weblog. */
    @GetMapping("/sitemap.xml")
    public ResponseEntity<String> sitemapIndex() {
        List<Weblog> weblogs;
        try {
            weblogs = weblogger.getWeblogManager()
                    .getWeblogs(Boolean.TRUE, Boolean.TRUE, null, null, 0, -1);
        } catch (WebloggerException e) {
            log.error("Error listing weblogs for the sitemap index", e);
            return ResponseEntity.internalServerError().build();
        }

        StringBuilder xml = new StringBuilder();
        xml.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        xml.append("<sitemapindex xmlns=\"").append(SITEMAP_NS).append("\">\n");
        for (Weblog weblog : weblogs) {
            xml.append("  <sitemap>\n");
            xml.append("    <loc>").append(escapeXml(absoluteContextURL()
                    + "/sitemap-" + weblog.getHandle() + ".xml")).append("</loc>\n");
            appendLastmod(xml, "    ", weblog.getLastModified());
            xml.append("  </sitemap>\n");
        }
        xml.append("</sitemapindex>\n");
        return xmlResponse(xml);
    }

    /** Sitemap for one weblog: home page plus published, indexable entries. */
    /**
     * The sitemap protocol's own limit: a sitemap may list at most 50,000
     * URLs. A weblog with more needs a sitemap index of its own, which is a
     * feature to add when some weblog here is within sight of it.
     */
    static final int MAX_SITEMAP_URLS = 50_000;

    @GetMapping("/sitemap-{handle}.xml")
    public ResponseEntity<String> weblogSitemap(@PathVariable("handle") String handle) {
        Weblog weblog;
        try {
            weblog = weblogger.getWeblogManager().getWeblogByHandle(handle, Boolean.TRUE);
        } catch (WebloggerException e) {
            // getWeblogByHandle throws for syntactically invalid handles
            // (crawlers probe all sorts of /sitemap-*.xml URLs) -- that is a
            // 404, not a server error.
            log.debug("Rejected sitemap request for handle " + handle, e);
            return ResponseEntity.notFound().build();
        }
        if (weblog == null || !Boolean.TRUE.equals(weblog.getActive())) {
            return ResponseEntity.notFound().build();
        }

        List<WeblogEntry> entries;
        try {
            WeblogEntrySearchCriteria criteria = new WeblogEntrySearchCriteria();
            criteria.setWeblog(weblog);
            criteria.setStatus(PubStatus.PUBLISHED);
            // Bounded, because this is an anonymous endpoint that loads every
            // matching entry into memory and renders it. Unbounded it is a
            // free amplification lever for anyone who can spell the URL, and
            // the protocol caps a sitemap at 50,000 URLs anyway -- past that
            // the file is invalid, so fetching more would be work done to
            // produce something no crawler will accept.
            criteria.setMaxResults(MAX_SITEMAP_URLS);
            entries = weblogger.getWeblogEntryManager().getWeblogEntries(criteria);
        } catch (WebloggerException e) {
            log.error("Error building the sitemap for weblog " + handle, e);
            return ResponseEntity.internalServerError().build();
        }

        URLStrategy urlStrategy = weblogger.getUrlStrategy();

        StringBuilder xml = new StringBuilder();
        xml.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        xml.append("<urlset xmlns=\"").append(SITEMAP_NS)
                .append("\" xmlns:image=\"").append(IMAGE_NS).append("\">\n");

        // the weblog home page
        xml.append("  <url>\n");
        xml.append("    <loc>").append(escapeXml(
                urlStrategy.getWeblogURL(weblog, null, true))).append("</loc>\n");
        appendLastmod(xml, "    ", weblog.getLastModified());
        xml.append("  </url>\n");

        for (WeblogEntry entry : entries) {
            if (Boolean.TRUE.equals(entry.getNoindex())) {
                continue;
            }
            xml.append("  <url>\n");
            xml.append("    <loc>").append(escapeXml(urlStrategy.getWeblogEntryURL(
                    weblog, null, entry.getAnchor(), true))).append("</loc>\n");
            appendLastmod(xml, "    ",
                    entry.getUpdateTime() != null ? entry.getUpdateTime() : entry.getPubTime());
            appendFeaturedImage(xml, urlStrategy, weblog, entry);
            xml.append("  </url>\n");
        }
        xml.append("</urlset>\n");
        return xmlResponse(xml);
    }

    /**
     * Emits an {@code image:image} element for the entry's featured image, if
     * it has one that still resolves to a media file. A dangling id is skipped
     * silently: the sitemap must stay valid even when a referenced upload was
     * deleted after the entry was saved.
     */
    private void appendFeaturedImage(StringBuilder xml, URLStrategy urlStrategy,
            Weblog weblog, WeblogEntry entry) {
        String imageId = entry.getFeaturedImageId();
        if (imageId == null || imageId.isBlank()) {
            return;
        }
        MediaFile image = null;
        try {
            image = weblogger.getMediaFileManager().getMediaFile(imageId);
        } catch (WebloggerException e) {
            log.debug("Featured image " + imageId + " of entry " + entry.getAnchor()
                    + " could not be loaded; omitting it from the sitemap", e);
        }
        if (image == null) {
            return;
        }
        // A private directory's files 404 on the public media path (they are
        // served only through their share link), so advertising them to
        // crawlers would leak the URL and then dead-link it.
        if (image.getDirectory() != null && image.getDirectory().isPrivate()) {
            return;
        }
        // Largest rendition of the pipeline's ladder; MediaResourceServlet
        // serves the original when no such rendition exists for the file.
        String imageURL = urlStrategy.getMediaFileURL(weblog, image.getId(), true) + "?w=1600";
        xml.append("    <image:image>\n");
        xml.append("      <image:loc>").append(escapeXml(imageURL)).append("</image:loc>\n");
        xml.append("    </image:image>\n");
    }

    private static ResponseEntity<String> xmlResponse(StringBuilder xml) {
        return ResponseEntity.ok()
                .contentType(APPLICATION_XML_UTF8)
                .body(xml.toString());
    }

    /** Appends a {@code lastmod} element in W3C datetime (UTC) if a date is known. */
    private static void appendLastmod(StringBuilder xml, String indent, Date date) {
        if (date == null) {
            return;
        }
        xml.append(indent).append("<lastmod>")
                .append(DateTimeFormatter.ISO_INSTANT.format(
                        java.time.Instant.ofEpochSecond(date.getTime() / 1000)))
                .append("</lastmod>\n");
    }

    private static String absoluteContextURL() {
        return WebloggerRuntimeConfig.getAbsoluteContextURL();
    }

    private static String escapeXml(String text) {
        return text.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;");
    }
}
