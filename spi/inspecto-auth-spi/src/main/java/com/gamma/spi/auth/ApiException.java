package com.gamma.spi.auth;

/** Maps to an HTTP status + JSON {@code {"error": …}} body (legacy) or the v1 error object; an
 *  optional machine-readable {@link ErrorCodes} value overrides the status-derived default. */
@com.gamma.api.PublicApi(since = "4.0.0")
public final class ApiException extends RuntimeException {
    public final int status;
    /** v1 error code, or {@code null} ⇒ {@link ErrorCodes#defaultFor(int)}. */
    public final String errorCode;
    /** A stable finding code for this one refusal, or {@code null}. When set, the response carries one ERROR
     *  finding {@code {severity, fieldPath, message, code}} at {@code error.details.findings[0]}; the envelope's
     *  {@code errorCode} is unchanged (REFUSAL-CODES-1). */
    public final String findingCode;
    /** Where in the request the refused value sits (the finding's {@code fieldPath}), or {@code null}. */
    public final String fieldPath;

    public ApiException(int status, String message) {
        this(status, null, message);
    }

    public ApiException(int status, String errorCode, String message) {
        this(status, errorCode, message, null, null);
    }

    public ApiException(int status, String errorCode, String message, String findingCode, String fieldPath) {
        super(message);
        this.status = status;
        this.errorCode = errorCode;
        this.findingCode = findingCode;
        this.fieldPath = fieldPath;
    }
}
