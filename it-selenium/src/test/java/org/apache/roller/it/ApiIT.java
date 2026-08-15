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
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Base64;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.roller.it.support.RollerIT;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * The automation API against the packaged, running WAR -- no browser.
 *
 * <p>MockMvc never runs a servlet container, so three things in this wave
 * have never been proved for real before this class: that the container
 * really strips the {@code /api} servlet-path prefix so a controller mapped
 * at {@code /v1/...} is reachable at {@code /api/v1/...}; that the real
 * Spring Security filter chain (not filters called directly with mocks, as
 * the unit tests do) answers an unauthenticated call with 401 and mints no
 * session; and, for the two tests added beyond the brief, that springdoc's
 * own auto-registered document mapping survives the same prefix rewrite and
 * that the full Basic-auth-mint / Bearer-auth-use bootstrap actually works
 * end to end against a real server.
 *
 * <p>This is acceptance testing, not TDD: every behaviour here was already
 * driven out by a failing unit test in Tasks 1-17. A failure in this class
 * means the assembled WAR does not behave the way those unit tests said it
 * would -- that is a defect in the packaging to report, never a reason to
 * weaken the assertion.
 */
class ApiIT extends RollerIT {

    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private HttpResponse<String> get(String path, String bearer) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl() + path))
                .timeout(Duration.ofSeconds(20));
        if (bearer != null) {
            builder.header("Authorization", "Bearer " + bearer);
        }
        return client.send(builder.GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String basicAuthHeader() {
        String credentials = ADMIN_USERNAME + ":" + ADMIN_PASSWORD;
        return "Basic " + Base64.getEncoder().encodeToString(credentials.getBytes());
    }

    private HttpResponse<String> getWithBasicAuth(String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl() + path))
                .timeout(Duration.ofSeconds(20))
                .header("Authorization", basicAuthHeader())
                .GET().build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    /**
     * The whole point of the prefix-mapping constraint
     * ({@code ServletRegistrationConfig.API_URL_PATTERNS}, a servlet-spec
     * prefix mapping on {@code /api/*}): the container strips {@code /api}
     * before Spring MVC ever sees the lookup path, so every controller under
     * {@code ui.restapi.v1} is mapped relative to {@code /v1}. A controller
     * written with the full {@code /api/v1/...} path 404s here and nowhere
     * else -- this already caused one Critical in this wave.
     */
    @Test
    void thePrefixMappingResolvesToAController() throws Exception {
        HttpResponse<String> response = get("/api/v1/ping", null);
        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("\"status\""));
    }

    /**
     * MockMvc calls filters directly with mocks, bypassing
     * {@code ExceptionTranslationFilter} and {@code authorizeHttpRequests}
     * entirely. The API chain is configured
     * {@code SessionCreationPolicy.STATELESS}
     * ({@code SecurityConfig.apiSecurityFilterChain}); only a real servlet
     * container can prove that an unauthenticated call is refused by that
     * chain -- not Boot's own error page, not a container-level 401 outside
     * {@code DispatcherServlet} -- and that no session is minted along the way.
     */
    @Test
    void anUnauthenticatedRequestIsRefusedWithoutASessionCookie() throws Exception {
        HttpResponse<String> response = get("/api/v1/me", null);
        assertEquals(401, response.statusCode());
        assertTrue(response.headers().allValues("Set-Cookie").stream()
                        .noneMatch(c -> c.startsWith("JSESSIONID")),
                "the API chain is stateless -- it must not mint a session");
    }

    @Test
    void aBadBearerTokenIsRefused() throws Exception {
        assertEquals(401, get("/api/v1/me", "rlr_definitelynotreal").statusCode());
    }

    @Test
    void anErrorRespondsAsProblemJson() throws Exception {
        HttpResponse<String> response = get("/api/v1/me", null);
        assertTrue(response.headers().firstValue("Content-Type").orElse("")
                        .startsWith("application/problem+json"),
                "every API error carries the problem+json content type");
    }

    /**
     * springdoc (Task 20) publishes the automation API's own machine-readable
     * document at {@code GET /api/v1/openapi.json}, guarded by the same
     * {@code apiSecurityFilterChain} as every other {@code /api/**} route.
     * Nothing before this task has ever fetched it through a real servlet
     * container: only that container proves springdoc's own auto-registered
     * mapping survives the {@code /api} prefix strip the same way the
     * hand-written controllers do.
     */
    @Test
    void theOpenApiDocumentIsServedAndParses() throws Exception {
        HttpResponse<String> response = getWithBasicAuth("/api/v1/openapi.json");
        assertEquals(200, response.statusCode(), response.body());
        assertTrue(response.headers().firstValue("Content-Type").orElse("")
                        .startsWith("application/json"),
                "the OpenAPI document must be served as JSON");
        String body = response.body();
        assertTrue(body.contains("\"openapi\""), "document is missing the openapi version field");
        assertTrue(looksLikeBalancedJson(body), "document is not syntactically valid JSON:\n" + body);
    }

    /**
     * The full bootstrap path, exercised end to end for the first time:
     * Basic-auth a mint request through the real filter chain, then use the
     * raw secret it hands back as a Bearer token on a second real request.
     * {@code TokensApiTest} only ever calls the controller directly against a
     * {@code SecurityContext} it built by hand, so nothing before this test
     * has proved that a token minted through the real HTTP surface is
     * actually usable on the real HTTP surface afterwards.
     */
    @Test
    void aTokenMintedThroughTheApiWorksOnASubsequentCall() throws Exception {
        HttpRequest mint = HttpRequest.newBuilder(URI.create(baseUrl() + "/api/v1/tokens"))
                .timeout(Duration.ofSeconds(20))
                .header("Authorization", basicAuthHeader())
                .header("Content-Type", "application/json")
                .POST(BodyPublishers.ofString("{\"label\":\"ApiIT smoke token\",\"role\":\"ADMIN\"}"))
                .build();
        HttpResponse<String> mintResponse = client.send(mint, HttpResponse.BodyHandlers.ofString());
        assertEquals(201, mintResponse.statusCode(), mintResponse.body());

        String rawToken = extractJsonStringField(mintResponse.body(), "token");
        assertFalse(rawToken.isBlank(), "mint response carried no raw token: " + mintResponse.body());

        HttpResponse<String> me = get("/api/v1/me", rawToken);
        assertEquals(200, me.statusCode(), me.body());
        assertEquals(ADMIN_USERNAME, extractJsonStringField(me.body(), "userName"),
                "the minted token did not authenticate as " + ADMIN_USERNAME + ": " + me.body());
    }

    // ---------------------------------------------------------------- utils

    /**
     * Pulls a string field's value out of a JSON object body by regex rather
     * than parsing it -- this module carries no JSON library dependency, and
     * adding one just for two assertions is not worth it.
     */
    private static String extractJsonStringField(String json, String field) {
        Matcher m = Pattern.compile("\"" + field + "\"\\s*:\\s*\"([^\"]*)\"").matcher(json);
        if (!m.find()) {
            fail("field \"" + field + "\" not found in: " + json);
        }
        return m.group(1);
    }

    /**
     * Not a real parser: a cheap syntactic sanity check (balanced braces and
     * brackets outside of string literals) that costs no new dependency.
     */
    private static boolean looksLikeBalancedJson(String body) {
        String trimmed = body.trim();
        if (trimmed.isEmpty() || trimmed.charAt(0) != '{' || trimmed.charAt(trimmed.length() - 1) != '}') {
            return false;
        }
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        for (char c : trimmed.toCharArray()) {
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            if (c == '"') {
                inString = true;
            } else if (c == '{' || c == '[') {
                depth++;
            } else if (c == '}' || c == ']') {
                depth--;
                if (depth < 0) {
                    return false;
                }
            }
        }
        return depth == 0 && !inString;
    }
}
