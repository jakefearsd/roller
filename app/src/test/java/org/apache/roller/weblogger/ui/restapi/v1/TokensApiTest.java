package org.apache.roller.weblogger.ui.restapi.v1;

import org.apache.roller.weblogger.business.ApiTokenManager;
import org.apache.roller.weblogger.business.UserManager;
import org.apache.roller.weblogger.business.Weblogger;
import org.apache.roller.weblogger.pojos.ApiToken;
import org.apache.roller.weblogger.ui.restapi.ApiExceptionHandler;
import org.apache.roller.weblogger.ui.restapi.auth.ApiPrincipal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.util.List;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

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

    @Test
    void anUnknownRoleIsRejected() throws Exception {
        mockMvc(new TokensApi(mockedWeblogger()))
                .perform(post("/v1/tokens")
                        .contentType("application/json")
                        .content("{\"label\":\"x\",\"role\":\"SUPERUSER\"}"))
                .andExpect(status().isBadRequest());
    }
}
