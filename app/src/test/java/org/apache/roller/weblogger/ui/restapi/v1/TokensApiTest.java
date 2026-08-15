package org.apache.roller.weblogger.ui.restapi.v1;

import org.apache.roller.weblogger.business.ApiTokenManager;
import org.apache.roller.weblogger.business.UserManager;
import org.apache.roller.weblogger.business.Weblogger;
import org.apache.roller.weblogger.pojos.ApiToken;
import org.apache.roller.weblogger.pojos.User;
import org.apache.roller.weblogger.ui.restapi.ApiExceptionHandler;
import org.apache.roller.weblogger.ui.restapi.auth.ApiPrincipal;
import org.apache.roller.weblogger.util.TokenGenerator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.sql.Timestamp;
import java.util.List;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Token-mints-token is a privilege-escalation path: it turns any leaked token
 * into a permanent one, and into one whose scope the thief chooses. Minting is
 * therefore Basic-only, and this is the test that says so -- the security
 * chain permits Basic on this path, so nothing else would catch a regression
 * here.
 */
class TokensApiTest {

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private MockMvc mockMvc(TokensApi controller) {
        return MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    private void authenticateWithABearerToken() {
        var auth = new UsernamePasswordAuthenticationToken("agent", null, List.of());
        auth.setDetails(new ApiPrincipal("agent", null, ApiToken.Role.ADMIN));
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    /**
     * Basic auth's principal is a {@code UserDetails}, and carries no
     * {@code ApiPrincipal} in its details -- that absence is exactly what
     * lets {@code TokensApi} tell a Basic-authenticated caller apart from a
     * Bearer-authenticated one.
     */
    private void authenticateWithBasicAuth() {
        var principal = org.springframework.security.core.userdetails.User
                .withUsername("owner").password("n/a").authorities(List.of()).build();
        var auth = new UsernamePasswordAuthenticationToken(principal, null, List.of());
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    private Weblogger mockedWeblogger() {
        Weblogger weblogger = mock(Weblogger.class);
        ApiTokenManager tokenManager = mock(ApiTokenManager.class);
        when(weblogger.getApiTokenManager()).thenReturn(tokenManager);
        when(weblogger.getUserManager()).thenReturn(mock(UserManager.class));
        return weblogger;
    }

    @Test
    void aBearerAuthenticatedCallerCannotMintAToken() throws Exception {
        authenticateWithABearerToken();

        mockMvc(new TokensApi(mockedWeblogger()))
                .perform(post("/v1/tokens")
                        .contentType("application/json")
                        .content("{\"label\":\"escalation\",\"role\":\"ADMIN\"}"))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"));
    }

    /**
     * Revoking an id the caller does not own answers 404, not 403 -- a 403
     * would confirm the id exists and let one user enumerate another's tokens.
     */
    @Test
    void revokingSomeoneElsesTokenIs404() throws Exception {
        authenticateWithABearerToken();

        Weblogger weblogger = mockedWeblogger();
        when(weblogger.getApiTokenManager().revoke(any(), anyString())).thenReturn(false);

        mockMvc(new TokensApi(weblogger))
                .perform(delete("/v1/tokens/{id}", "someone-elses-id"))
                .andExpect(status().isNotFound());
    }

    /**
     * The bootstrap path: how every token, including the first one, comes
     * into existence. Basic-authenticated (no ApiPrincipal), a valid role,
     * a mocked issueToken returning a known raw secret.
     *
     * <p>The assertion that matters most is the last one: the raw secret
     * must appear exactly once, in {@code token}, and never inside {@code
     * info} -- a token echoed into both would be a real leak that a looser
     * assertion (e.g. just checking {@code token} is present) would miss.
     */
    @Test
    void mintingATokenReturnsTheSecretOnceAndNeverInTheMetadataView() throws Exception {
        authenticateWithBasicAuth();

        Weblogger weblogger = mockedWeblogger();
        User owner = new User();
        owner.setId("user-1");
        owner.setUserName("owner");
        when(weblogger.getUserManager().getUserByUserName("owner")).thenReturn(owner);

        String rawToken = "rlr_knownRawSecretForTest";
        when(weblogger.getApiTokenManager()
                .issueToken(any(), anyString(), any(), any(), any()))
                .thenReturn(rawToken);

        ApiToken persisted = new ApiToken();
        persisted.setId("token-1");
        persisted.setUser(owner);
        persisted.setLabel("seo-agent");
        persisted.setTokenSha256(TokenGenerator.sha256Hex(rawToken));
        persisted.setScopeWeblog("testblog");
        persisted.setScopeRole(ApiToken.Role.POST);
        persisted.setCreated(new Timestamp(System.currentTimeMillis()));
        when(weblogger.getApiTokenManager().getTokens(owner)).thenReturn(List.of(persisted));

        String response = mockMvc(new TokensApi(weblogger))
                .perform(post("/v1/tokens")
                        .contentType("application/json")
                        .content("{\"label\":\"seo-agent\",\"weblog\":\"testblog\",\"role\":\"post\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        tools.jackson.databind.JsonNode body = new tools.jackson.databind.ObjectMapper().readTree(response);
        assertEquals(rawToken, body.get("token").asString());
        assertEquals("token-1", body.get("info").get("id").asString());
        assertEquals("seo-agent", body.get("info").get("label").asString());
        assertEquals("POST", body.get("info").get("scopeRole").asString());

        // The raw secret appears exactly once in the whole body -- nowhere
        // in the metadata view, under any field name.
        assertEquals(1, countOccurrences(response, rawToken));
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        int index = 0;
        while ((index = haystack.indexOf(needle, index)) != -1) {
            count++;
            index += needle.length();
        }
        return count;
    }

    @Test
    void anUnknownRoleIsRejected() throws Exception {
        mockMvc(new TokensApi(mockedWeblogger()))
                .perform(post("/v1/tokens")
                        .contentType("application/json")
                        .content("{\"label\":\"x\",\"role\":\"SUPERUSER\"}"))
                .andExpect(status().isBadRequest());
    }
}
