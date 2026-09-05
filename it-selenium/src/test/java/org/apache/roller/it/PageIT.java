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

import org.apache.roller.it.support.BrowserHealth;
import org.apache.roller.it.support.RollerIT;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceAccessMode;
import org.junit.jupiter.api.parallel.ResourceLock;

import static com.codeborne.selenide.Condition.checked;
import static com.codeborne.selenide.Condition.exist;
import static com.codeborne.selenide.Condition.text;
import static com.codeborne.selenide.Condition.visible;
import static com.codeborne.selenide.Selenide.$;
import static com.codeborne.selenide.Selenide.$$;
import static com.codeborne.selenide.Selenide.executeJavaScript;
import static com.codeborne.selenide.Selenide.webdriver;
import static com.codeborne.selenide.WebDriverConditions.urlContaining;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Static pages: draft-to-published visibility, Markdown and shortcode
 * rendering, and the "show in navigation" toggle.
 *
 * <p>This owns its own weblog on the {@code portfolio} shared theme rather
 * than using the seeded {@code it_weblog}: nav-link coverage needs a theme
 * that renders {@code #showPageLinks}, and owning the weblog keeps this
 * suite honest about the rule that the seeded weblog's theme is never
 * switched -- sidestepped here by not needing to.
 *
 * <p>The draft-is-invisible assertion is a plain anonymous HTTP status check
 * rather than a browser navigation, the same choice {@code AuthoringJourneyIT}
 * makes for a draft entry: navigating a real browser straight at a 404 would
 * record the main document itself as a broken resource and fail the
 * suite's automatic {@code BrowserHealth} check at teardown for a 404 the
 * test asked for on purpose.
 */
@ResourceLock(value = RollerIT.GLOBAL_CONFIG, mode = ResourceAccessMode.READ)
class PageIT extends RollerIT {

    private static final String CREATE_WEBLOG = "/roller-ui/createWeblog.rol";

    private HttpClient http;

    @BeforeEach
    void logIn() {
        loginAsAdmin();
        http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(20))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    @Test
    void aPageGoesFromDraftToPublishedAndCanBeHiddenFromNav() {
        String handle = createWeblog();
        String pageUrl = baseUrl() + "/" + handle + "/about";

        // --- create as a draft, with Markdown and a [cta] in the body ------
        openPath("/roller-ui/authoring/pageEdit.rol?weblog=" + handle);
        $("#pageEditForm").should(exist);
        $("#page_bean_slug").setValue("about");
        $("#page_bean_title").setValue("About Us");
        $(".CodeMirror").should(visible);
        executeJavaScript(
                "rollerSetEntryText(arguments[0]);",
                "This page has **bold** text.\n\n"
                        + "[cta href=\"https://example.com/book\" label=\"Book now\" "
                        + "note=\"Free cancellation\"]");
        saveOpenPage();

        assertEquals(404, statusOf(pageUrl),
                "a draft page must not resolve for an anonymous reader, got a page at " + pageUrl);

        // --- publish ----------------------------------------------------------
        openPageByTitle(handle, "About Us");
        $("#page_bean_status").selectOptionByValue("PUBLISHED");
        saveOpenPage();

        String publicBody = getAnonymously(pageUrl);
        assertEquals(200, statusOf(pageUrl), "a published page must resolve for an anonymous reader");
        assertTrue(publicBody.contains("About Us"),
                "the published page must carry its title, got: " + truncate(publicBody));
        assertTrue(publicBody.contains("<strong>bold</strong>"),
                "Markdown in the page body must be rendered, got: " + truncate(publicBody));
        assertTrue(publicBody.contains("class=\"cta-card\"") && publicBody.contains("Book now"),
                "the [cta] shortcode in the page body must render as a CTA card, got: "
                        + truncate(publicBody));

        // --- the page appears in the theme's nav, and clicking it arrives ----
        logout();
        openPath("/" + handle + "/");
        $$("li.page-nav-item a").findBy(text("About Us")).should(exist).click();
        webdriver().shouldHave(urlContaining("/" + handle + "/about"));
        $("h2.pf-entry-title").shouldHave(text("About Us"));
        BrowserHealth.current().assertNoBrokenResources();
        BrowserHealth.current().assertNoFailedRequests();

        // --- unticking "show in navigation" hides the link, not the page -----
        loginAsAdmin();
        openPageByTitle(handle, "About Us");
        $("input[name='bean.showInNav']").shouldBe(checked).click();
        saveOpenPage();

        logout();

        openPath("/" + handle + "/");
        $$("li.page-nav-item a").findBy(text("About Us")).shouldNot(exist);
        BrowserHealth.current().assertNoBrokenResources();
        BrowserHealth.current().assertNoFailedRequests();

        assertEquals(200, statusOf(pageUrl),
                "the page URL must keep resolving for a reader even once it is hidden from navigation");
    }

    // ---------------------------------------------------------------- helpers

    /**
     * Creates a weblog owned by the seeded admin on the {@code portfolio}
     * theme -- see the class comment for why it owns a weblog at all.
     */
    private String createWeblog() {
        String handle = "pageit" + nonce();

        openPath(CREATE_WEBLOG);
        $("#name").should(visible).setValue("Pages " + handle);
        $("#handle").setValue(handle);
        $("#emailAddress").setValue(handle + "@example.invalid");
        $("select[name='theme']").selectOptionByValue("portfolio");
        $("button[type='submit']").click();

        $("#messages").should(exist);
        return handle;
    }

    /** Opens a page for editing from the list, found by its title link text. */
    private void openPageByTitle(String handle, String title) {
        openPath("/roller-ui/authoring/pages.rol?weblog=" + handle);
        $("#pageRemoveForm").should(exist);
        $$("a").findBy(text(title)).should(exist).click();
        $("#pageEditForm").should(exist);
    }

    /** Submits the page edit form and waits for the save to land. */
    private void saveOpenPage() {
        $("#pageEditForm button[type='submit']").click();
        $("#messages").should(exist);
        BrowserHealth.current().settle();
    }

    /** Status of an anonymous GET -- the public visibility of a URL, not the author's view. */
    private int statusOf(String url) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(20))
                    .GET()
                    .build();
            HttpResponse<Void> response = http.send(request, HttpResponse.BodyHandlers.discarding());
            return response.statusCode();
        } catch (Exception e) {
            throw new IllegalStateException("Could not GET " + url, e);
        }
    }

    private static String truncate(String text) {
        String flat = text.replaceAll("\\s+", " ").trim();
        return flat.length() <= 600 ? flat : flat.substring(0, 600) + "...";
    }

    private static String nonce() {
        return Long.toString(System.nanoTime(), 36);
    }
}
