package com.gamma.pipeline.exec;

import com.gamma.api.PublicApi;

/**
 * Why a contributed Step failed its batch (S2-3). The engine has already dropped every table the Step
 * created when this is thrown, so the batch fails through the ordinary path with nothing half-applied.
 */
@PublicApi(since = "4.0.0")
public final class StepFailure extends RuntimeException {

    /** The Step threw. */
    public static final String STEP_FAILED = "STEP_FAILED";
    /** The Step ran past its deadline and was stopped (or abandoned). */
    public static final String STEP_TIMEOUT = "STEP_TIMEOUT";
    /** The kind is disabled because an earlier run of it had to be abandoned; replace the pack. */
    public static final String STEP_DISABLED = "STEP_DISABLED";

    private final String code;

    public StepFailure(String code, String message, Throwable cause) {
        super(code + ": " + message, cause);
        this.code = code;
    }

    /** One of {@link #STEP_FAILED}, {@link #STEP_TIMEOUT}, {@link #STEP_DISABLED}. */
    public String code() {
        return code;
    }
}
