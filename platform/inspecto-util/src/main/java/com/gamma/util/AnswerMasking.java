package com.gamma.util;

/**
 * The masked-answer stamp (AGENT-KIT-1 W6 S0, D-AK13, design
 * {@code docs/superpower/2026-11-26-agent-data-answers-masking-design.md} §3.1): the proof that a request's masking step ran.
 * Masking stays at the compute site, where the lineage is known; what is shared is this proof.
 *
 * <p>The host opens a {@link Scope} around the handler of every agent call to an agent data route
 * ({@code AgentGate.DATA_ROUTES}); a masking step calls {@link #applied} once it has decided what to mask, on the request's
 * thread. When the handler answers 2xx and nothing stamped the scope, the host answers 500 {@code masking not proven}
 * instead: a route that forgot masking fails closed rather than leaking. A Space whose masking mode is {@code none} still
 * stamps (zero masked), so the seam holds there too (D-AK14).
 *
 * <p>Outside a scope (every person's request, every non-data route) {@link #applied} does nothing. A stamp made on another
 * thread is not seen, so masking moved off the request thread fails closed, never open. Scopes nest: an in-process replay
 * opens its own and restores the outer one when it closes.
 */
public final class AnswerMasking {

    private AnswerMasking() {}

    private static final ThreadLocal<Scope> CURRENT = new ThreadLocal<>();

    /** Test seam: when set, {@link #applied} records nothing, so a route's masking step acts as if it were missing. */
    private static volatile boolean ignoreStampsForTest;

    /** One request's proof. Not thread-safe: it lives on the request's thread. */
    public static final class Scope implements AutoCloseable {
        private final Scope outer;
        private int masked = -1;
        private String basis;

        private Scope(Scope outer) {
            this.outer = outer;
        }

        /** Whether a masking step stamped this scope. */
        public boolean stamped() {
            return masked >= 0;
        }

        /** The masked column count over every stamp, or {@code -1} when none was set. */
        public int maskedColumns() {
            return masked;
        }

        /** The last stamp's basis (the Dataset or Reference it masked), or {@code null}. */
        public String basis() {
            return basis;
        }

        @Override
        public void close() {
            if (CURRENT.get() != this) return;   // closed out of order: leave the current one alone
            if (outer == null) CURRENT.remove();
            else CURRENT.set(outer);
        }
    }

    /** Thrown by {@link #requireProvenBeforeSend} when a 2xx answer is about to leave an unstamped scope. */
    public static final class NotProven extends RuntimeException {
        public NotProven() {
            super("masking not proven", null, false, false);
        }
    }

    /** Open a scope on this thread; close it (try-with-resources) once the answer is decided. */
    public static Scope open() {
        Scope s = new Scope(CURRENT.get());
        CURRENT.set(s);
        return s;
    }

    /**
     * Stamp the current scope: a masking step ran over this answer. {@code basis} names what it masked by (the Dataset or
     * Reference id); {@code maskedColumns} is how many columns it masked, zero included. A no-op outside a scope.
     */
    public static void applied(String basis, int maskedColumns) {
        Scope s = CURRENT.get();
        if (s == null || ignoreStampsForTest) return;
        s.masked = Math.max(0, s.masked) + Math.max(0, maskedColumns);
        s.basis = basis;
    }

    /**
     * Called by the response writers before they send headers: a 2xx answer from an unstamped scope is refused before a byte
     * leaves ({@link NotProven}), so a handler that writes its own answer cannot pass the seam either. A no-op outside a scope.
     */
    public static void requireProvenBeforeSend(int status) {
        Scope s = CURRENT.get();
        if (s != null && status >= 200 && status < 300 && !s.stamped()) throw new NotProven();
    }

    /** Test seam: make every masking step's stamp a no-op ({@code true}), or restore it ({@code false}). */
    public static void ignoreStampsForTest(boolean ignore) {
        ignoreStampsForTest = ignore;
    }
}
