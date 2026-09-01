package com.gamma.config.spec;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.gamma.api.PublicApi;

/**
 * One validation result — the structured unit returned by config validation.
 *
 * <p>Replaces the bare {@code List<String>} that {@code ConfigValidator} returns today: a UI can
 * anchor a {@code WARNING} to a specific form field via {@link #fieldPath}, and an LLM can read the
 * {@link #message} to self-correct a draft.
 *
 * <p><b>{@code fieldPath} is the ONE canonical way to cite a config key</b>: dotted segments plus
 * {@code [n]} list indices — {@code "collector.duplicate.mode"}, {@code "route.branches[1].where"},
 * {@code "processing.schemas[0].schema_file"}. A {@link #message} names the actual entities and
 * values that are wrong; the key citation lives here, so a retired spelling is a visible one-owner
 * defect rather than prose fused into every message. {@code fieldPath} is blank for findings that
 * span fields (cross-field rules may name the rule's primary field, or leave it blank).
 *
 * <p>The two diagnostic fields are ADDITIVE and OPTIONAL (2026-09-01, authoring-residuals R1):
 * {@code null} means "not provided" and is omitted from serialized JSON entirely (class-level
 * {@code NON_NULL}), so every pre-existing envelope stays byte-identical until a producer opts in.
 *
 * @param severity  ERROR (cannot run) or WARNING (suspicious but legal)
 * @param fieldPath dotted path the finding is anchored to (never {@code null}; may be blank)
 * @param message   human-readable explanation of what is WRONG — names the actual entities and
 *                  values (never {@code null})
 * @param code      stable {@code ERR_}/{@code WARN_}-prefixed SCREAMING_SNAKE diagnostic code from
 *                  {@link FindingCodes}; {@code null}/blank = uncoded (absent from JSON)
 * @param guidance  what to DO about it — structurally separate from what is wrong;
 *                  {@code null}/blank = none (absent from JSON)
 */
@PublicApi(since = "4.0.0")
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Finding(Severity severity, String fieldPath, String message, String code, String guidance) {

    public Finding {
        severity = severity == null ? Severity.ERROR : severity;
        fieldPath = fieldPath == null ? "" : fieldPath;
        message = message == null ? "" : message;
        code = (code == null || code.isBlank()) ? null : code;
        guidance = (guidance == null || guidance.isBlank()) ? null : guidance;
    }

    /** The pre-R1 shape — an uncoded finding with no guidance. Every existing caller compiles as-is. */
    public Finding(Severity severity, String fieldPath, String message) {
        this(severity, fieldPath, message, null, null);
    }

    /** An ERROR finding anchored to {@code fieldPath}. */
    public static Finding error(String fieldPath, String message) {
        return new Finding(Severity.ERROR, fieldPath, message);
    }

    /** A WARNING finding anchored to {@code fieldPath}. */
    public static Finding warning(String fieldPath, String message) {
        return new Finding(Severity.WARNING, fieldPath, message);
    }

    /** This finding carrying a {@link FindingCodes} code and its remediation guidance. */
    public Finding coded(String code, String guidance) {
        return new Finding(severity, fieldPath, message, code, guidance);
    }
}
