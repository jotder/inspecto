package com.gamma.control;

import com.gamma.spi.auth.ApiException;

/**
 * Test-scope split-package peek at {@link ApiException}'s package-private status and code. Published in la-core's test-jar so the
 * Postgres module's tests can assert WHICH refusal the store raises without the control plane that serialises it.
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
