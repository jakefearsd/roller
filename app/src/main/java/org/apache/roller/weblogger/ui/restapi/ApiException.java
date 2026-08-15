package org.apache.roller.weblogger.ui.restapi;

import java.util.List;

/**
 * The one exception the API layer throws for an expected failure. Every
 * response body is built from it by ApiExceptionHandler; no controller
 * assembles its own.
 */
public class ApiException extends RuntimeException {

    private static final long serialVersionUID = 1L;
    private static final String TYPE_BASE = "https://roller.invalid/problems/";

    private final int status;
    private final String type;
    private final String title;
    private final transient List<ApiProblem.FieldError> errors;

    public ApiException(int status, String type, String title, String detail) {
        this(status, type, title, detail, null);
    }

    public ApiException(int status, String type, String title, String detail,
                        List<ApiProblem.FieldError> errors) {
        super(detail);
        this.status = status;
        this.type = type;
        this.title = title;
        this.errors = errors;
    }

    public static ApiException notFound(String detail) {
        return new ApiException(404, TYPE_BASE + "not-found", "Not found", detail);
    }

    public static ApiException unauthorized(String detail) {
        return new ApiException(401, TYPE_BASE + "unauthorized", "Unauthorized", detail);
    }

    public static ApiException forbidden(String detail) {
        return new ApiException(403, TYPE_BASE + "forbidden", "Forbidden", detail);
    }

    public static ApiException badRequest(String detail) {
        return new ApiException(400, TYPE_BASE + "invalid-request", "Invalid request", detail);
    }

    public static ApiException validation(String detail, List<ApiProblem.FieldError> errors) {
        return new ApiException(400, TYPE_BASE + "invalid-request", "Invalid request", detail, errors);
    }

    public static ApiException conflict(String detail) {
        return new ApiException(409, TYPE_BASE + "conflict", "Conflict", detail);
    }

    public static ApiException quotaExceeded(String detail) {
        return new ApiException(413, TYPE_BASE + "quota-exceeded", "Upload quota exceeded", detail);
    }

    public static ApiException throttled(String detail) {
        return new ApiException(429, TYPE_BASE + "throttled", "Too many requests", detail);
    }

    public int getStatus() {
        return status;
    }

    public ApiProblem toProblem(String instance) {
        return new ApiProblem(type, title, status, getMessage(), instance, errors);
    }
}
