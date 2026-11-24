package com.gamma.asn.schema;

/**
 * Structured parse or compile diagnostic with 1-based source coordinates (0 when unknown).
 */
public record Diagnostic(int line, int column, String severity, String message) {
}
