package com.gamma.la.core;

/**
 * A graph-run operation the service refuses. {@link #kind()} maps onto the route layer's answer: NOT_FOUND → 404,
 * FORBIDDEN → 403, TERMINAL → 409 (the run already finished), REJECTED → 503 (the run queue is full).
 */
public final class GraphRunException extends RuntimeException {

    public enum Kind { NOT_FOUND, FORBIDDEN, TERMINAL, REJECTED }

    private final Kind kind;

    public GraphRunException(Kind kind, String message) {
        super(message);
        this.kind = kind;
    }

    public Kind kind() {
        return kind;
    }
}
