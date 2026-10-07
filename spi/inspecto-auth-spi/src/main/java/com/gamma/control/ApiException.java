package com.gamma.control;

/** Maps to an HTTP status + JSON {@code {"error": …}} body (legacy) or the v1 error object; an
 *  optional machine-readable {@link ErrorCodes} value overrides the status-derived default. */
@com.gamma.api.PublicApi(since = "4.0.0")
public final class ApiException extends RuntimeException {
    final int status;
    /** v1 error code, or {@code null} ⇒ {@link ErrorCodes#defaultFor(int)}. */
    final String errorCode;

    public ApiException(int status, String message) {
        this(status, null, message);
    }

    public ApiException(int status, String errorCode, String message) {
        super(message);
        this.status = status;
        this.errorCode = errorCode;
    }
}
