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
package org.apache.roller.weblogger.business;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ListmonkClientTest {

    private HttpServer server;
    private final AtomicReference<String> lastBody = new AtomicReference<>();
    private volatile int respondWith = 200;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/public/subscription", exchange -> {
            lastBody.set(new String(exchange.getRequestBody().readAllBytes(),
                    StandardCharsets.UTF_8));
            byte[] out = "{}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(respondWith, out.length);
            try (OutputStream os = exchange.getResponseBody()) { os.write(out); }
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private ListmonkClient client() {
        return new ListmonkClient("http://127.0.0.1:" + server.getAddress().getPort(),
                HttpClient.newHttpClient());
    }

    @Test
    void subscribePostsTheListmonkPublicShapeAndReturnsTheStatus() throws Exception {
        int status = client().subscribe("reader@example.com",
                "2f0f1b0c-1111-2222-3333-444455556666");

        assertEquals(200, status);
        assertTrue(lastBody.get().contains("\"email\":\"reader@example.com\""), lastBody.get());
        assertTrue(lastBody.get().contains("\"list_uuids\":[\"2f0f1b0c-1111-2222-3333-444455556666\"]"),
                lastBody.get());
    }

    @Test
    void aConflictPassesThroughAsItsOwnStatus() throws Exception {
        respondWith = 409;
        assertEquals(409, client().subscribe("reader@example.com",
                "2f0f1b0c-1111-2222-3333-444455556666"));
    }

    @Test
    void anUnconfiguredClientReportsItself() {
        assertTrue(new ListmonkClient("", HttpClient.newHttpClient()).isUnconfigured());
        assertTrue(new ListmonkClient(null, HttpClient.newHttpClient()).isUnconfigured());
    }

    /**
     * A quote or backslash in the email must not break the hand-built JSON
     * body -- {@code jsonString} is the only thing standing between
     * {@code subscribe}'s two interpolated values and a malformed request.
     * Round-tripping the captured body through a real JSON parser is a
     * stronger check than matching an escaped literal: it fails on ANY
     * escaping bug, not just the one this hand-picked value happens to hit.
     */
    @Test
    void aQuoteAndBackslashInTheEmailAreEscapedSoTheBodyStaysValidJson() throws Exception {
        String tricky = "weird\"name\\test@example.com";

        client().subscribe(tricky, "2f0f1b0c-1111-2222-3333-444455556666");

        JsonNode body = new ObjectMapper().readTree(lastBody.get());
        assertEquals(tricky, body.get("email").asText(), lastBody.get());
    }
}
