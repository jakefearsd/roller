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

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Tests podcast/enclosure lookup against a real (loopback) HTTP server.
 *
 * <p>The entry editor calls this while saving a post and shows the message key
 * carried by the exception, so the codes and keys below are what an author
 * actually reads when an enclosure URL does not work. A local
 * {@link HttpServer} keeps the test hermetic: no network, no flakiness, but
 * the real HttpURLConnection code path.
 */
public class MediacastUtilTest {

    private HttpServer server;
    private String baseUrl;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);

        // A well formed enclosure: declares both a type and a length.
        server.createContext("/podcast.mp3", exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "audio/mpeg");
            exchange.getResponseHeaders().set("Content-Length", "123456");
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });

        // 200, but the server tells us nothing about the file.
        server.createContext("/nolength", exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "audio/mpeg");
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });

        server.createContext("/missing", exchange -> {
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
        });

        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    public void readsTheTypeAndLengthOfAValidEnclosure() throws Exception {
        MediacastResource resource = MediacastUtil.lookupResource(baseUrl + "/podcast.mp3");

        assertEquals(baseUrl + "/podcast.mp3", resource.getUrl());
        assertEquals("audio/mpeg", resource.getContentType());
        assertEquals(123456L, resource.getLength(),
                "The declared length is written into the entry as att_mediacast_length and "
                        + "ends up in the feed's <enclosure length=...>, which podcast "
                        + "clients use to show download progress.");
        assertEquals("url = " + baseUrl + "/podcast.mp3\ncontentType = audio/mpeg\nlength = 123456\n",
                resource.toString());
    }

    @Test
    public void aBlankUrlMeansNoEnclosureRatherThanAnError() {
        // Clearing the enclosure field on an existing entry sends "" here.
        assertNull(assertLooksUpQuietly(null));
        assertNull(assertLooksUpQuietly(""));
        assertNull(assertLooksUpQuietly("   "));
    }

    @Test
    public void reportsAMalformedUrlWithItsOwnMessageKey() {
        MediacastException thrown = assertThrows(MediacastException.class,
                () -> MediacastUtil.lookupResource("this is not a url"));

        assertEquals(MediacastUtil.BAD_URL, thrown.getErrorCode());
        assertEquals("weblogEdit.mediaCastUrlMalformed", thrown.getErrorKey());
    }

    @Test
    public void reportsAnHttpErrorAsABadResponseNotAsAGenericFailure() {
        // Regression guard: the specific exception used to be caught by the
        // method's own catch-all and rewritten as CHECK_FAILED, so an author
        // whose URL 404s was told "failed fetching info" and had no idea the
        // file simply was not there.
        MediacastException thrown = assertThrows(MediacastException.class,
                () -> MediacastUtil.lookupResource(baseUrl + "/missing"));

        assertEquals(MediacastUtil.BAD_RESPONSE, thrown.getErrorCode());
        assertEquals("weblogEdit.mediaCastResponseError", thrown.getErrorKey());
    }

    @Test
    public void reportsAResponseWithoutALengthAsIncomplete() {
        MediacastException thrown = assertThrows(MediacastException.class,
                () -> MediacastUtil.lookupResource(baseUrl + "/nolength"));

        assertEquals(MediacastUtil.INCOMPLETE, thrown.getErrorCode());
        assertEquals("weblogEdit.mediaCastLacksContentTypeOrLength", thrown.getErrorKey());
    }

    @Test
    public void reportsAnUnreachableHostAsACheckFailure() {
        // Port 1 on the loopback interface refuses connections immediately, so
        // this exercises the transport-failure branch without a timeout.
        MediacastException thrown = assertThrows(MediacastException.class,
                () -> MediacastUtil.lookupResource("http://127.0.0.1:1/podcast.mp3"));

        assertEquals(MediacastUtil.CHECK_FAILED, thrown.getErrorCode());
        assertEquals("weblogEdit.mediaCastFailedFetchingInfo", thrown.getErrorKey());
    }

    private MediacastResource assertLooksUpQuietly(String url) {
        try {
            return MediacastUtil.lookupResource(url);
        } catch (MediacastException e) {
            throw new AssertionError("An empty enclosure URL must not raise an error; "
                    + "saving an entry with no enclosure would then show a failure message.", e);
        }
    }
}
