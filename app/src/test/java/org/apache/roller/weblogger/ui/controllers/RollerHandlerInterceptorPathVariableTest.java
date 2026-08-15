package org.apache.roller.weblogger.ui.controllers;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.servlet.HandlerMapping;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The JSP UI passes the weblog as a {@code weblog} request parameter and has
 * no {@code {handle}} URI template variable on any of its routes; the REST
 * API carries it as a {@code {handle}} template variable, and a REST
 * controller's own handler method reads that variable directly (see e.g. a
 * future {@code /v1/weblogs/{handle}/...} controller). One resolution helper
 * serves both, so there is a single authorization path rather than a
 * parallel one for the API -- which only works if this helper's answer
 * always agrees with what the REST handler itself will act on. The path
 * variable therefore wins whenever both are present: a REST route's {@code
 * weblog=} query parameter is not meaningful vocabulary for that route (it
 * is JSP vocabulary that happens to share a name), and preferring it would
 * let a caller point RollerHandlerInterceptor's permission check and
 * ApiScopeInterceptor's scope ceiling at a different weblog than the one the
 * handler method actually receives and acts on.
 */
class RollerHandlerInterceptorPathVariableTest {

    @Test
    void thePathVariableWinsWhenBothArePresent() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setParameter("weblog", "fromparam");
        request.setAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE,
                Map.of("handle", "frompath"));

        assertEquals("frompath", RollerHandlerInterceptor.resolveWeblogHandle(request));
    }

    @Test
    void thePathVariableIsUsedWhenTheParameterIsAbsent() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE,
                Map.of("handle", "frompath"));

        assertEquals("frompath", RollerHandlerInterceptor.resolveWeblogHandle(request));
    }

    /** The JSP admin UI's whole shape: no {@code {handle}} route exists, so the parameter must still work. */
    @Test
    void theRequestParameterIsUsedWhenThereIsNoPathVariable() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setParameter("weblog", "fromparam");

        assertEquals("fromparam", RollerHandlerInterceptor.resolveWeblogHandle(request));
    }

    /** A blank path variable is treated as absent, the same way a blank parameter always has been. */
    @Test
    void aBlankPathVariableFallsThroughToTheParameter() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setParameter("weblog", "fromparam");
        request.setAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE,
                Map.of("handle", "   "));

        assertEquals("fromparam", RollerHandlerInterceptor.resolveWeblogHandle(request));
    }

    @Test
    void aBlankParameterFallsThroughWhenThereIsNoPathVariable() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setParameter("weblog", "   ");

        assertEquals(null, RollerHandlerInterceptor.resolveWeblogHandle(request));
    }

    @Test
    void nullWhenNeitherIsPresent() {
        assertEquals(null, RollerHandlerInterceptor.resolveWeblogHandle(new MockHttpServletRequest()));
    }
}
