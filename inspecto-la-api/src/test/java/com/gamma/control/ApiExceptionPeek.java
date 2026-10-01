package com.gamma.control;

/**
 * Test-scope split-package peek at {@link ApiException}'s package-private status and code (the same technique the module's
 * sibling tests use for package-private control-plane members): Link Analysis tests assert WHICH refusal a port-less route
 * raises without needing the control plane that serialises it.
 */
public final class ApiExceptionPeek {

    private ApiExceptionPeek() {}

    public static int status(ApiException e) {
        return e.status;
    }

    public static String code(ApiException e) {
        return e.errorCode;
    }
}
