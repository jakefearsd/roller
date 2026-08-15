package org.apache.roller.weblogger.ui.controllers;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.servlet.HandlerMapping;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The UI passes the weblog as a request parameter; REST carries it as a URI
 * template variable. One resolution helper serves both, so there is a single
 * authorization path rather than a parallel one for /api/**.
 */
class RollerHandlerInterceptorPathVariableTest {

    @Test
    void theRequestParameterWinsWhenPresent() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setParameter("weblog", "fromparam");
        request.setAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE,
                Map.of("handle", "frompath"));

        assertEquals("fromparam", RollerHandlerInterceptor.resolveWeblogHandle(request));
    }

    @Test
    void thePathVariableIsUsedWhenTheParameterIsAbsent() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE,
                Map.of("handle", "frompath"));

        assertEquals("frompath", RollerHandlerInterceptor.resolveWeblogHandle(request));
    }

    @Test
    void aBlankParameterFallsThroughRatherThanWinning() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setParameter("weblog", "   ");
        request.setAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE,
                Map.of("handle", "frompath"));

        assertEquals("frompath", RollerHandlerInterceptor.resolveWeblogHandle(request));
    }

    @Test
    void nullWhenNeitherIsPresent() {
        assertEquals(null, RollerHandlerInterceptor.resolveWeblogHandle(new MockHttpServletRequest()));
    }
}
