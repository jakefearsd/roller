package org.apache.roller.weblogger.ui.restapi;

import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import static org.junit.jupiter.api.Assertions.*;

class ApiExceptionHandlerTest {

    private final ApiExceptionHandler handler = new ApiExceptionHandler();

    @Test
    void anApiExceptionBecomesProblemJsonCarryingItsStatusAndInstance() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/weblogs/nope");
        ResponseEntity<ApiProblem> response =
                handler.handleApiException(ApiException.notFound("No weblog 'nope'."), request);

        assertEquals(HttpStatus.NOT_FOUND.value(), response.getStatusCode().value());
        assertEquals(MediaType.APPLICATION_PROBLEM_JSON, response.getHeaders().getContentType());
        ApiProblem body = response.getBody();
        assertNotNull(body);
        assertEquals(404, body.status());
        assertEquals("No weblog 'nope'.", body.detail());
        assertEquals("/api/v1/weblogs/nope", body.instance(),
                "instance must be the path the client actually called, /api prefix included");
    }

    /**
     * An unexpected exception must not leak its message or stack to a client.
     * The detail is fixed text; the real cause goes to the log.
     */
    @Test
    void anUnexpectedExceptionBecomesAnOpaque500() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/weblogs/x/entries");
        ResponseEntity<ApiProblem> response = handler.handleUnexpected(
                new IllegalStateException("connection pool exhausted at com.example.Secret"), request);

        assertEquals(500, response.getStatusCode().value());
        ApiProblem body = response.getBody();
        assertNotNull(body);
        assertFalse(body.detail().contains("connection pool"),
                "internal detail must never reach the client");
        assertFalse(body.detail().contains("com.example"),
                "internal detail must never reach the client");
    }

    /**
     * The third promised behavior: a bean-validation failure becomes a 400
     * problem+json whose errors array names every invalid field. Covers a
     * field with a message and one with a null getDefaultMessage(), which
     * Spring produces for a rejected value that carries no message.
     */
    @Test
    void aValidationFailureBecomesA400ListingEachFieldError() throws NoSuchMethodException {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/weblogs/x/entries");

        BeanPropertyBindingResult bindingResult = new BeanPropertyBindingResult(new Object(), "target");
        bindingResult.addError(new FieldError("target", "title", "must not be blank"));
        bindingResult.addError(new FieldError("target", "slug", null));
        MethodParameter methodParameter = new MethodParameter(
                ApiExceptionHandlerTest.class.getDeclaredMethod("dummyValidationTarget", String.class), 0);
        MethodArgumentNotValidException ex =
                new MethodArgumentNotValidException(methodParameter, bindingResult);

        ResponseEntity<ApiProblem> response = handler.handleValidation(ex, request);

        assertEquals(400, response.getStatusCode().value());
        assertEquals(MediaType.APPLICATION_PROBLEM_JSON, response.getHeaders().getContentType());
        ApiProblem body = response.getBody();
        assertNotNull(body);
        assertNotNull(body.errors());
        assertEquals(2, body.errors().size());
        assertTrue(body.errors().stream()
                        .anyMatch(fe -> "title".equals(fe.field()) && "must not be blank".equals(fe.message())),
                "title's field error must carry its message");
        assertTrue(body.errors().stream()
                        .anyMatch(fe -> "slug".equals(fe.field()) && fe.message() == null),
                "a field error with no default message must still be reported, with a null message");
    }

    @SuppressWarnings("unused")
    private void dummyValidationTarget(String arg) { }
}
