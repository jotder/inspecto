package com.gamma.util;

/**
 * How many processes share this deployment's state — {@code -Dinspecto.topology=single|partitioned} (D12).
 *
 * <p><b>What it changes.</b> Exactly one thing today: whether an operational store is allowed to
 * <b>degrade</b>. On a single node, a store whose durable backend cannot be opened falls back to memory and
 * logs — deliberate, and it stays. Across N processes sharing one database that same fallback is
 * <b>silent split-brain</b>: two nodes each holding their own in-memory truth and neither aware of the other.
 * So in {@link Mode#PARTITIONED} a degradation is a <b>boot failure</b> instead. See {@link StoreHealth}.
 *
 * <p>⚠ {@code partitioned} covers Standard's two-node T4 standby as well as Enterprise's N pods — it is
 * "more than one process shares this state", not "a Kubernetes cluster". D2 refused the cluster engine, and
 * D12 chose this name over {@code mode=cluster} for exactly that reason.
 *
 * <p>⛔ <b>An unrecognised value fails the boot rather than widening.</b> {@code -Dinspecto.topology=partitoned}
 * (sic) must not quietly mean {@code single} — that would turn a typo into the silent degradation this flag
 * exists to prevent, which is the worst possible reading of an unreadable value. This mirrors the bind-flag
 * rule already stated in {@code editions.md}: an unresolvable value fails the boot.
 *
 * <p><b>Read per call, not cached.</b> The value is read from a system property each time. It is consulted at
 * store-open time only — a handful of calls per space per boot — so there is nothing to gain by caching, and a
 * cached static would be unsettable per test, which is how a flag ends up with no negative coverage.
 *
 * @since 2026-09-11 (scale-out phase A, D12)
 */
public final class Topology {

    private Topology() {}

    /** The system property. One switch for the whole deployment, never one per store. */
    public static final String PROPERTY = "inspecto.topology";

    /** The two legal values of {@link #PROPERTY}. */
    public enum Mode {
        /** One process owns this state. A store that cannot open its backend degrades and logs. */
        SINGLE,
        /** More than one process shares this state. A store that cannot open its backend FAILS THE BOOT. */
        PARTITIONED
    }

    /**
     * The configured mode; {@link Mode#SINGLE} when the property is unset or blank.
     *
     * @throws IllegalStateException if the property is set to anything other than {@code single} or
     *                               {@code partitioned} — ⛔ never defaulted, see the class doc
     */
    public static Mode mode() {
        String raw = System.getProperty(PROPERTY);
        if (raw == null || raw.isBlank()) return Mode.SINGLE;
        String v = raw.trim().toLowerCase();
        if ("single".equals(v)) return Mode.SINGLE;
        if ("partitioned".equals(v)) return Mode.PARTITIONED;
        throw new IllegalStateException("-D" + PROPERTY + "=" + raw.trim()
                + " is not a topology. Expected 'single' (one process owns this state) or 'partitioned' "
                + "(several processes share it, and a store that cannot open its backend must fail the boot "
                + "rather than degrade to memory). Refusing to guess, because guessing 'single' would "
                + "re-enable the silent degradation this flag exists to prevent.");
    }

    /** True when several processes share this deployment's state, so no store may degrade. */
    public static boolean partitioned() {
        return mode() == Mode.PARTITIONED;
    }
}
