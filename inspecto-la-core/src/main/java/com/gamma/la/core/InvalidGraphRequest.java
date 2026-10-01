package com.gamma.la.core;

/**
 * A graph-engine request that cannot be run as asked: an unknown algorithm, an unknown / mistyped / out-of-range
 * parameter, or a missing node id. A caller's mistake, not a server fault — the route layer (D-4 step 6) maps it to
 * {@code 422}. Raised BEFORE any work starts, so a refusal never leaves a half-run behind.
 *
 * <p>An id that is well-formed but names no node of the input is NOT this exception: like the browser's functions
 * (and the parity fixtures), every algorithm answers that with its empty result.
 */
public final class InvalidGraphRequest extends IllegalArgumentException {

    public enum Reason { UNKNOWN_ALGORITHM, UNKNOWN_PARAM, MISSING_PARAM, BAD_TYPE, OUT_OF_RANGE }

    private final Reason reason;
    private final String param;

    public InvalidGraphRequest(Reason reason, String param, String message) {
        super(message);
        this.reason = reason;
        this.param = param;
    }

    public Reason reason() {
        return reason;
    }

    /** The offending parameter (or algorithm id for {@link Reason#UNKNOWN_ALGORITHM}); may be null. */
    public String param() {
        return param;
    }
}
