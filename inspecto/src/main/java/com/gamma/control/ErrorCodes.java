package com.gamma.control;

/**
 * Machine-readable error codes for the v1 error object (docs/superpower/api-contract-design.md §5).
 * A throwing site may pick a specific code via {@link ApiException}; every other error gets the
 * status-derived default. Codes are part of the v1 contract: additive only, never renamed.
 */
public final class ErrorCodes {
    private ErrorCodes() {}

    public static final String MALFORMED_REQUEST        = "MALFORMED_REQUEST";
    public static final String NOT_FOUND                = "NOT_FOUND";
    public static final String METHOD_NOT_ALLOWED       = "METHOD_NOT_ALLOWED";
    public static final String PATH_JAIL_VIOLATION      = "PATH_JAIL_VIOLATION";
    public static final String CONFLICT                 = "CONFLICT";
    /** 409 — an {@code If-Match} write precondition failed (optimistic-lock, W3). */
    public static final String CONFLICT_STALE_VERSION   = "CONFLICT_STALE_VERSION";
    public static final String CONFIG_VALIDATION_FAILED = "CONFIG_VALIDATION_FAILED";
    public static final String INTERNAL                 = "INTERNAL";
    /** 503 — config writes are disabled ({@code -Dassist.write.root} unset). */
    public static final String CONTROL_PLANE_READ_ONLY  = "CONTROL_PLANE_READ_ONLY";
    /** 503 — an optional module (e.g. the assist agent) is not on the classpath. */
    public static final String CAPABILITY_UNAVAILABLE   = "CAPABILITY_UNAVAILABLE";
    /** 401 — missing/invalid credentials (Standard edition; the security module, W6). */
    public static final String UNAUTHENTICATED          = "UNAUTHENTICATED";
    /** 403 — an authenticated subject lacks the capability a route requires (Standard edition, W6). */
    public static final String PERMISSION_DENIED        = "PERMISSION_DENIED";

    /** The contract's default code for a status ({@code errorCode} is never absent on a v1 error). */
    static String defaultFor(int status) {
        return switch (status) {
            case 400 -> MALFORMED_REQUEST;
            case 401 -> UNAUTHENTICATED;
            case 403 -> PATH_JAIL_VIOLATION;   // the core's structural 403; the security module also throws PERMISSION_DENIED explicitly
            case 404 -> NOT_FOUND;
            case 405 -> METHOD_NOT_ALLOWED;
            case 409 -> CONFLICT;
            case 422 -> CONFIG_VALIDATION_FAILED;
            case 503 -> CAPABILITY_UNAVAILABLE;
            default  -> INTERNAL;
        };
    }
}
