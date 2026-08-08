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
package org.apache.roller.it;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;

import org.apache.roller.it.support.BrowserHealth;
import org.apache.roller.it.support.RollerIT;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static com.codeborne.selenide.Condition.attribute;
import static com.codeborne.selenide.Condition.exist;
import static com.codeborne.selenide.Condition.visible;
import static com.codeborne.selenide.Selenide.$;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The {@code [subscribe]}/{@code #showSubscribeForm} newsletter block: a
 * weblog with a list uuid configured shows the footer block and its
 * client-injected form ({@code #showAudienceAssets}); clearing the uuid
 * removes it; the forwarding endpoint fails closed without Listmonk and 404s
 * for a uuid no weblog owns.
 *
 * <p>Owns its own weblog rather than the seeded {@code it_weblog}, and never
 * switches that seeded weblog's theme -- per the suite-wide rule.
 *
 * <p>The endpoint checks go through raw {@code HttpClient}, not the browser:
 * the forward legitimately fails in this environment (no Listmonk configured,
 * {@code newsletter.listmonk.baseurl} blank -- see {@code roller-it.properties}),
 * and a browser {@code fetch} surfacing that 503 would trip
 * {@code assertNoFailedRequests}/{@code assertNoBrokenResources} for a failure
 * the test asked for on purpose. This is exactly the {@code PageIT} precedent
 * for a deliberate non-2xx check.
 */
class SubscribeFormIT extends RollerIT {

    private static final String SUBSCRIBE_ENDPOINT = "/newsletter/subscribe";

    private HttpClient http;

    @BeforeEach
    void setUp() {
        http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(20))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    @Test
    void theSubscribeBlockAppearsWithAListAndDisappearsWithoutOne() throws Exception {
        String uuid = UUID.randomUUID().toString();

        loginAsAdmin();
        String handle = createWeblog();
        setNewsletterUuid(handle, uuid);
        logout();

        // --- the footer block, as an anonymous reader sees it -----------------
        openPath("/" + handle + "/");
        $(".newsletter-subscribe-block").should(exist);
        $(".subscribe-form-slot").shouldHave(attribute("data-list-uuid", uuid));
        $(".newsletter-subscribe-block form.newsletter-subscribe input[name='email']")
                .should(visible);
        $(".newsletter-subscribe-block form.newsletter-subscribe input[name='website']")
                .should(exist);
        BrowserHealth.current().assertNoBrokenResources();
        BrowserHealth.current().assertNoFailedRequests();

        // --- raw HTTP: fails closed without Listmonk; 404s for an unknown list
        assertEquals(503, postSubscribe(uuid, 5000),
                "an unconfigured Listmonk must fail closed (503), not silently succeed");
        assertEquals(404, postSubscribe(UUID.randomUUID().toString(), 5000),
                "a list uuid matching no weblog must 404");

        // --- clearing the uuid removes the block -------------------------------
        loginAsAdmin();
        setNewsletterUuid(handle, "");
        logout();

        openPath("/" + handle + "/");
        $(".newsletter-subscribe-block").shouldNot(exist);
        BrowserHealth.current().assertNoBrokenResources();
        BrowserHealth.current().assertNoFailedRequests();
    }

    // ---------------------------------------------------------------- helpers

    /** Posts a subscribe request for one list uuid and returns the HTTP status. */
    private int postSubscribe(String uuid, long elapsedMs) throws Exception {
        String json = "{"
                + "\"email\":\"reader@example.invalid\","
                + "\"list_uuids\":[\"" + uuid + "\"],"
                + "\"website\":\"\","
                + "\"elapsedMs\":" + elapsedMs
                + "}";
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl() + SUBSCRIBE_ENDPOINT))
                .timeout(Duration.ofSeconds(20))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();
        HttpResponse<Void> response = http.send(request, HttpResponse.BodyHandlers.discarding());
        return response.statusCode();
    }

    /** Creates a weblog owned by the seeded admin, on the default theme. */
    private String createWeblog() {
        String handle = "subscribeit" + nonce();

        openPath("/roller-ui/createWeblog.rol");
        $("#name").should(visible).setValue("Subscribe " + handle);
        $("#handle").setValue(handle);
        $("#emailAddress").setValue(handle + "@example.invalid");
        $("select[name='theme']").selectOptionByValue("basic");
        $("button[type='submit']").click();

        $("#messages").should(exist);
        return handle;
    }

    /** Sets (or, given a blank uuid, clears) the weblog's newsletter list uuid through Settings. */
    private void setNewsletterUuid(String handle, String uuid) {
        openPath("/roller-ui/authoring/weblogConfig.rol?weblog=" + handle);
        $("input[name='bean.newsletterListUuid']").should(visible).setValue(uuid);
        $("button[type='submit'].btn-success").should(visible).click();
        $("#messages").should(exist);
        BrowserHealth.current().settle();
    }

    private static String nonce() {
        return Long.toString(System.nanoTime(), 36);
    }
}
