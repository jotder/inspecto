package com.gamma.config.spec;

/**
 * An {@link IllegalArgumentException} that names its {@link FindingCodes} code and the field it refuses
 * (REFUSAL-CODES-1, 2026-11-27). Engine code that cannot see the HTTP layer throws it where a plain
 * {@code IllegalArgumentException} would go; a route that maps it to a 422 through
 * {@code RouteErrors.unprocessable} keeps the code, so the caller reads it at {@code error.details.findings[0].code}
 * instead of parsing the message. Mappers that still build a plain {@code ApiException} (BiSpecRunner, KpiRoutes,
 * QueryRoutes, ShareRoutes, RuleRoutes) drop it — REFUSAL-CODES-REST-1. A catch that knows nothing of it still
 * works: it is an {@code IllegalArgumentException}.
 */
public final class CodedArgumentException extends IllegalArgumentException {

    private final String code;
    private final String fieldPath;

    /**
     * @param code      a {@link FindingCodes} constant
     * @param fieldPath where in the request or config the refused value sits; may be blank
     * @param message   what is wrong, naming the value
     */
    public CodedArgumentException(String code, String fieldPath, String message) {
        super(message);
        this.code = code;
        this.fieldPath = fieldPath == null ? "" : fieldPath;
    }

    public String code() { return code; }

    public String fieldPath() { return fieldPath; }
}
