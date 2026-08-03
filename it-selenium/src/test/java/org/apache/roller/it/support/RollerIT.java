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

package org.apache.roller.it.support;

import com.codeborne.selenide.Configuration;
import com.codeborne.selenide.Selenide;
import org.junit.jupiter.api.extension.ExtendWith;
import org.openqa.selenium.chrome.ChromeOptions;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import static com.codeborne.selenide.Condition.disappear;
import static com.codeborne.selenide.Condition.exist;
import static com.codeborne.selenide.Selenide.$;
import static com.codeborne.selenide.Selenide.webdriver;
import static com.codeborne.selenide.WebDriverConditions.urlContaining;

/**
 * Base class for Roller integration tests: drives a headless Chrome against a running
 * Roller instance and, by way of {@link BrowserHealthExtension}, fails any test whose
 * pages loaded broken resources or produced JavaScript errors.
 *
 * <p>The instance under test is named by the {@value #BASE_URL_PROPERTY} system property.
 * Tests assume the seed fixture named by the constants below already exists; they never
 * create it.
 */
@ExtendWith(BrowserHealthExtension.class)
public abstract class RollerIT {

    /** Root URL of the Roller under test, context path included, e.g. {@code http://localhost:8080/roller}. */
    public static final String BASE_URL_PROPERTY = "it.base.url";

    /** Seed administrator, created by the integration-test fixture. */
    protected static final String ADMIN_USERNAME = "it_admin";
    protected static final String ADMIN_PASSWORD = "it-admin-password";

    /** Seed weblog owned by {@link #ADMIN_USERNAME}. */
    protected static final String WEBLOG_HANDLE = "it_weblog";

    private static final String LOGIN_PATH = "/roller-ui/login.rol";
    private static final String MENU_PATH = "/roller-ui/menu.rol";

    /** Present on every page of an authenticated session and nowhere else - see bannerStatus.jsp. */
    private static final String LOGOUT_LINK = "a[href$='/roller-ui/logout.rol']";

    private static boolean selenideConfigured;

    /**
     * Root URL of the Roller under test, without a trailing slash.
     *
     * @throws IllegalStateException if the property is unset, rather than letting the suite
     *                               run on and fail later with a confusing connection error
     */
    protected static String baseUrl() {
        String configured = System.getProperty(BASE_URL_PROPERTY);
        if (configured == null || configured.isBlank()) {
            throw new IllegalStateException("System property '" + BASE_URL_PROPERTY + "' is not set. "
                    + "Point it at the Roller instance under test, "
                    + "e.g. -D" + BASE_URL_PROPERTY + "=http://localhost:8080/roller");
        }
        return configured.endsWith("/") ? configured.substring(0, configured.length() - 1) : configured;
    }

    /**
     * Applies the browser settings shared by the whole suite. Called by
     * {@link BrowserHealthExtension} before it starts a browser; idempotent, because
     * Selenide's configuration is global and only read at browser startup.
     */
    static synchronized void configureSelenide() {
        if (selenideConfigured) {
            return;
        }
        Configuration.baseUrl = baseUrl();
        Configuration.browser = "chrome";
        Configuration.headless = true;
        // Roller's admin UI is Bootstrap: below the responsive breakpoint the navigation bar
        // collapses behind a toggle and tests would be clicking links that are not on screen.
        Configuration.browserSize = "1366x768";
        // --no-sandbox and --disable-dev-shm-usage are what make Chrome usable inside the
        // containers this suite runs in on CI.
        Configuration.browserCapabilities = new ChromeOptions()
                .addArguments("--no-sandbox", "--disable-gpu", "--disable-dev-shm-usage");
        selenideConfigured = true;
    }

    /**
     * Opens a path on the Roller under test and waits for the browser to finish, so the
     * health recorder has seen every request the page made before the test asserts anything.
     *
     * @param path server-relative path below the Roller context, e.g. {@code /roller-ui/menu.rol}
     */
    protected void openPath(String path) {
        Selenide.open(baseUrl() + (path.startsWith("/") ? path : "/" + path));
        BrowserHealth.current().settle();
    }

    /**
     * Signs in as the seed administrator and leaves the browser on the main menu.
     *
     * <p>Goes to the login form directly and navigates to the menu afterwards, rather
     * than requesting the menu first and letting Spring Security replay the saved
     * request. That replay appends its {@code continue} marker
     * ({@code menu.rol?continue}, the default since Spring Security 6.1) and Roller
     * answers that URL with a 404 -- a real defect that affects anyone deep-linking
     * while logged out, tracked separately. Depending on it here would make every
     * test in the suite fail for a reason that has nothing to do with the route
     * under test.
     */
    protected void loginAsAdmin() {
        loginAs(ADMIN_USERNAME, ADMIN_PASSWORD);
    }

    /**
     * Signs in as any user. Journeys that create users need this; everything
     * else wants {@link #loginAsAdmin()}.
     */
    protected void loginAs(String username, String password) {
        openPath(LOGIN_PATH);

        $("#j_username").setValue(username);
        $("#j_password").setValue(password);
        $("#login").click();

        // Wait for the login form to go away before navigating again.
        //
        // Settling on network silence is not enough on its own: the POST and its
        // redirect can still be in flight when the quiet period expires, and the
        // next open() then arrives unauthenticated and gets bounced to the front
        // page. That showed up as one route in twenty-odd failing for a reason
        // that had nothing to do with the route under test. Waiting on a DOM
        // condition ties us to the thing we actually care about - that the
        // browser has left the login page.
        $("#j_username").should(disappear);
        BrowserHealth.current().settle();

        openPath(MENU_PATH);

        // Bad credentials bounce back to login.rol?error=true, so reaching the menu
        // with a logout link is what distinguishes success from failure.
        webdriver().shouldHave(urlContaining(MENU_PATH));
        $(LOGOUT_LINK).should(exist);

        BrowserHealth.current().settle();
    }

    /**
     * Signs out, so the next login starts from a clean session.
     *
     * <p>Waits for the login form rather than for the logout link to vanish:
     * logout redirects, and asserting on the page we left is how a browser
     * test ends up racing a navigation.
     */
    protected void logout() {
        openPath("/roller-ui/logout.rol");

        // Then go to the login page ourselves rather than waiting on the
        // redirect chain logout kicks off. The session is already invalidated
        // by the time logout.rol responds, so this is not papering over a
        // race -- it removes one: on a loaded runner the redirect can still be
        // in flight when the assertion below runs, and the failure ("no login
        // form") looks nothing like its cause.
        openPath(LOGIN_PATH);
        $("#j_username").should(exist);
        BrowserHealth.current().settle();
    }

    /** GET with no cookies, i.e. exactly what an anonymous reader sends. */
    protected static String getAnonymously(String url) {
        try {
            HttpClient http = HttpClient.newHttpClient();
            HttpResponse<String> response = http.send(
                    HttpRequest.newBuilder()
                            .uri(URI.create(url))
                            .timeout(Duration.ofSeconds(20))
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            return response.body();
        } catch (Exception e) {
            throw new IllegalStateException("Could not GET " + url + " anonymously", e);
        }
    }
}
