package com.gamma.consignment;

import com.gamma.api.PublicApi;

/**
 * What a {@link ConsignmentProcessor} reports. Failure is signalled by throwing — the Job framework's existing
 * convention, which already converts a thrown exception into a {@code FAILED} run — so this only distinguishes
 * "did the work" from "there was nothing to do".
 *
 * <p><b>This answers §14.4's open question (distinct type, or reuse {@code JobResult}?): distinct, and only
 * just.</b> {@code JobResult} carries {@code durationMs}, which the framework measures and a processor cannot
 * know; reusing it would force every author to pass a number that is either wrong or a placeholder — a contract
 * that lies. The adapter converts this into a {@code JobResult}, supplying the duration it actually timed.
 */
@PublicApi(since = "4.0.0")
public record ProcessorResult(String status, String message) {

    /** The work was done. */
    public static ProcessorResult ok(String message) {
        return new ProcessorResult("SUCCESS", message);
    }

    /** There was nothing to do — not a failure, and not a silent success either. */
    public static ProcessorResult skipped(String message) {
        return new ProcessorResult("SKIPPED", message);
    }

    public boolean success() {
        return "SUCCESS".equals(status);
    }
}
