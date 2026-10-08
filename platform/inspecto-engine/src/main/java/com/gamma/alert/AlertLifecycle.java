package com.gamma.alert;

import java.util.Locale;
import java.util.Optional;

/**
 * The Alert lifecycle: {@code OPEN -> ACKNOWLEDGED -> RESOLVED} (with a direct {@code OPEN -> RESOLVED} for "resolve
 * without acknowledging"), {@code RESOLVED} terminal. It was {@code Workflow.defaultFor(ObjectType.ALERT)} while an
 * Alert was an operational object; an Alert is owned by the Alert store now, so the lifecycle lives here and no
 * {@code ObjectType} value names it. Actions are matched case-insensitively, states upper-case, as a {@code Workflow}
 * does.
 */
public final class AlertLifecycle {

    public static final String OPEN = "OPEN";
    public static final String ACKNOWLEDGED = "ACKNOWLEDGED";
    public static final String RESOLVED = "RESOLVED";

    private AlertLifecycle() {}

    /** The state a fired Alert starts in. */
    public static String initialState() {
        return OPEN;
    }

    /** The state reached from {@code state} by {@code action} ({@code ack} / {@code resolve}), if that move is legal. */
    public static Optional<String> apply(String state, String action) {
        if (state == null || action == null) return Optional.empty();
        String from = state.trim().toUpperCase(Locale.ROOT);
        return switch (action.trim().toLowerCase(Locale.ROOT)) {
            case "ack" -> OPEN.equals(from) ? Optional.of(ACKNOWLEDGED) : Optional.empty();
            case "resolve" -> OPEN.equals(from) || ACKNOWLEDGED.equals(from) ? Optional.of(RESOLVED) : Optional.empty();
            default -> Optional.empty();
        };
    }

    /** {@code true} when {@code state} is terminal. */
    public static boolean isTerminal(String state) {
        return state != null && RESOLVED.equals(state.trim().toUpperCase(Locale.ROOT));
    }
}
