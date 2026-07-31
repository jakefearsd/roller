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

package org.apache.roller.weblogger.util;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests linkback (trackback) extraction against a local HTTP server.
 *
 * <p>The extractor fetches a page that claims to link here and pulls out the
 * title and the surrounding sentence, which are then stored and displayed as a
 * comment. The original test in this class pointed at live sites on the public
 * internet and was never run (it had no {@code @Test} annotations); serving
 * the pages from loopback makes the same behaviour testable and deterministic.
 */
public class LinkbackExtractorTest {

    private HttpServer server;
    private String baseUrl;

    /** The URL on "our" weblog that the remote page links to. */
    private String entryUrl;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        entryUrl = baseUrl + "/roller/entry/my_post";

        serve("/plain.html", "text/html", """
                <html><head><title>A Referring Weblog</title></head><body>
                <p>An unrelated opening paragraph.</p>
                <p>Roller has a <a href="%s">good post</a> about linkbacks today.</p>
                <p>A later paragraph nobody asked about.</p>
                </body></html>
                """.formatted(entryUrl));

        serve("/withfeed.html", "text/html", """
                <html><head><title>A Referring Weblog</title>
                <link rel="alternate" type="application/rss+xml" title="RSS" href="/feed.xml">
                </head><body>
                <p>Roller has a <a href="%s">good post</a> about linkbacks today.</p>
                </body></html>
                """.formatted(entryUrl));

        serve("/feed.xml", "application/rss+xml", """
                <?xml version="1.0" encoding="UTF-8"?>
                <rss version="2.0"><channel>
                <title>A Referring Weblog</title>
                <link>%s/</link>
                <description>Feed of the referring weblog</description>
                <item>
                  <title>Unrelated older post</title>
                  <link>%s/older</link>
                  <description>Nothing to do with anything.</description>
                </item>
                <item>
                  <title>On linkbacks</title>
                  <link>%s/on-linkbacks</link>
                  <description>Roller has a &lt;a href="%s"&gt;good post&lt;/a&gt; about linkbacks today.</description>
                </item>
                </channel></rss>
                """.formatted(baseUrl, baseUrl, baseUrl, entryUrl));

        serve("/nolink.html", "text/html", """
                <html><head><title>Somebody Else Entirely</title></head><body>
                <p>This page never mentions the entry.</p>
                </body></html>
                """);

        // Links to the bare-host form of a weblog that is normally reached
        // through www, and vice versa.
        serve("/nonwww.html", "text/html", """
                <html><head><title>A Referring Weblog</title></head><body>
                <p>See <a href="http://example.com/entry/my_post">this</a> for details.</p>
                </body></html>
                """);
        serve("/www.html", "text/html", """
                <html><head><title>A Referring Weblog</title></head><body>
                <p>See <a href="http://www.example.com/entry/my_post">this</a> for details.</p>
                </body></html>
                """);

        serve("/long.html", "text/html", """
                <html><head><title>A Referring Weblog</title></head><body>
                <p>Roller has a <a href="%s">good post</a>. %s</p>
                <p>The end.</p>
                </body></html>
                """.formatted(entryUrl, "padding ".repeat(120)));
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    public void readsTheTitleOfTheReferringPage() throws IOException {
        LinkbackExtractor extractor = new LinkbackExtractor(baseUrl + "/plain.html", entryUrl);

        assertEquals("A Referring Weblog", extractor.getTitle(),
                "The page title becomes the linkback's display name; an empty one shows up "
                        + "as a blank entry in the comment list.");
    }

    @Test
    public void extractsTheSentenceAroundTheLinkAsTheExcerpt() throws IOException {
        LinkbackExtractor extractor = new LinkbackExtractor(baseUrl + "/plain.html", entryUrl);

        String excerpt = extractor.getExcerpt();
        assertNotNull(excerpt, "No excerpt was extracted, so the linkback would display as "
                + "a bare title with no context.");
        assertTrue(excerpt.contains("about linkbacks today"),
                "The excerpt must be the text around the link, not some other paragraph. Got: " + excerpt);
        assertTrue(excerpt.contains("good post"),
                "The excerpt must include the linking text itself. Got: " + excerpt);
        assertFalse(excerpt.contains("<"),
                "The excerpt is stored and rendered as comment text, so it must have had its "
                        + "markup stripped. Got: " + excerpt);
    }

    @Test
    public void prefersTheFeedWhenThePageAdvertisesOneWithAutodiscovery() throws IOException {
        // With an RSS link the extractor re-reads the entry from the feed,
        // which is the only way it can learn the referring post's permalink
        // (an HTML page has no marker for "this is the permalink of the
        // paragraph you are reading").
        LinkbackExtractor extractor = new LinkbackExtractor(baseUrl + "/withfeed.html", entryUrl);

        assertEquals(baseUrl + "/on-linkbacks", extractor.getPermalink(),
                "The permalink must come from the matching feed entry, not from the first "
                        + "item or from the feed itself.");
        assertEquals("A Referring Weblog: On linkbacks", extractor.getTitle(),
                "A feed-sourced linkback is titled 'weblog: entry' so the comment list shows "
                        + "who linked as well as what.");
        assertTrue(extractor.getExcerpt().contains("about linkbacks today"),
                "Excerpt should come from the matching item's description. Got: " + extractor.getExcerpt());
    }

    @Test
    public void aLinkToTheBareHostCountsWhenTheWeblogIsReachedThroughWww() throws IOException {
        // Readers link to whichever form of the address they happen to have.
        // The extractor normalises the two so a linkback is not lost just
        // because someone dropped the "www.".
        LinkbackExtractor extractor =
                new LinkbackExtractor(baseUrl + "/nonwww.html", "http://www.example.com/entry/my_post");

        assertNotNull(extractor.getExcerpt(),
                "A link to http://example.com/... must be recognised when the entry URL is "
                        + "the http://www.example.com/... form of the same address.");
        assertTrue(extractor.getExcerpt().contains("for details"), extractor.getExcerpt());
    }

    @Test
    public void aLinkToTheWwwHostCountsWhenTheWeblogIsReachedWithoutIt() throws IOException {
        LinkbackExtractor extractor =
                new LinkbackExtractor(baseUrl + "/www.html", "http://example.com/entry/my_post");

        assertNotNull(extractor.getExcerpt(),
                "A link to http://www.example.com/... must be recognised when the entry URL "
                        + "is the bare-host form of the same address.");
    }

    @Test
    public void aVeryLongExcerptIsTruncated() throws IOException {
        // Without the cap, whatever the remote page happens to contain is
        // stored verbatim as a comment -- which is exactly what a spammer
        // wants.
        LinkbackExtractor extractor = new LinkbackExtractor(baseUrl + "/long.html", entryUrl);

        String excerpt = extractor.getExcerpt();
        assertEquals(503, excerpt.length(),
                "Excerpts are capped at 500 characters plus an ellipsis; got " + excerpt.length());
        assertTrue(excerpt.endsWith("..."), excerpt);
    }

    @Test
    public void reportsNothingWhenThePageDoesNotLinkHere() throws IOException {
        // A referer that does not actually link back is the common case for
        // spam; there must be no excerpt to display.
        LinkbackExtractor extractor = new LinkbackExtractor(baseUrl + "/nolink.html", entryUrl);

        assertNull(extractor.getExcerpt());
        assertNull(extractor.getPermalink());
    }

    @Test
    public void anUnreachableRefererIsSwallowedRatherThanFailingTheRequest() throws IOException {
        // This runs while serving a page request; a dead referer must not turn
        // into a 500 for the reader.
        LinkbackExtractor extractor = new LinkbackExtractor("http://127.0.0.1:1/gone.html", entryUrl);

        assertEquals("", extractor.getTitle());
        assertNull(extractor.getExcerpt());
    }

    @Test
    public void aMalformedRefererUrlIsSwallowedToo() throws IOException {
        LinkbackExtractor extractor = new LinkbackExtractor("not even a url", entryUrl);

        assertEquals("", extractor.getTitle());
        assertNull(extractor.getExcerpt());
    }

    @Test
    public void thePermalinkCanBeOverriddenByTheCaller() {
        // The comment plumbing sets this when it already knows the permalink.
        LinkbackExtractor extractor = assertExtracted(baseUrl + "/nolink.html", entryUrl);
        extractor.setPermalink("http://example.com/elsewhere");
        assertEquals("http://example.com/elsewhere", extractor.getPermalink());
    }

    private LinkbackExtractor assertExtracted(String referer, String request) {
        try {
            return new LinkbackExtractor(referer, request);
        } catch (IOException e) {
            throw new AssertionError("Extraction threw for " + referer, e);
        }
    }

    private void serve(String path, String contentType, String body) {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        server.createContext(path, new HttpHandler() {
            @Override
            public void handle(HttpExchange exchange) throws IOException {
                exchange.getResponseHeaders().set("Content-Type", contentType);
                exchange.sendResponseHeaders(200, bytes.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(bytes);
                }
            }
        });
    }
}
