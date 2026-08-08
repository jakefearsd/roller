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
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import org.apache.commons.lang3.StringUtils;
import org.apache.roller.weblogger.config.WebloggerConfig;

/**
 * The only outbound HTTP in the audience wave, and it points at deployer
 * configuration, never at author or reader input: the Listmonk base URL
 * comes from roller.properties, and the request body carries an email plus
 * a list uuid that has already been matched against a weblog's configured
 * list. There is nothing here a reader can aim at an internal address.
 */
public class ListmonkClient {

    private final String baseUrl;
    private final HttpClient http;

    public ListmonkClient(String baseUrl, HttpClient http) {
        this.baseUrl = StringUtils.stripEnd(StringUtils.trimToNull(baseUrl), "/");
        this.http = http;
    }

    public static ListmonkClient fromConfig() {
        return new ListmonkClient(
                WebloggerConfig.getProperty("newsletter.listmonk.baseurl"),
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build());
    }

    public boolean isUnconfigured() {
        return baseUrl == null;
    }

    /** Forwards a subscription; returns Listmonk's status code (200, 409, ...). */
    public int subscribe(String email, String listUuid) throws IOException {
        String body = "{\"email\":" + jsonString(email)
                + ",\"list_uuids\":[" + jsonString(listUuid) + "]}";
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/public/subscription"))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        try {
            return http.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted forwarding subscription", ex);
        }
    }

    private static String jsonString(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
