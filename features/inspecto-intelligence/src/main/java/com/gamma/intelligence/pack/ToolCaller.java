package com.gamma.intelligence.pack;

import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Who is invoking a tool right now: the control-plane capabilities of the caller, bound for the duration of one
 * session turn or one direct dispatch ({@code ASSURE-INTELLIGENCE-BUNDLE-1} round 2, 2026-09-29).
 *
 * <p>The tools read it ({@link ToolCapabilities#enforcing}); the in-session gate inside the external eoiagent
 * platform is NOT relied on. {@link #UNRESTRICTED} (capabilities {@code null}) is the Personal edition, where no
 * Subject exists and nothing is checked anywhere, and it is also what internal system callers bind. <b>Nothing
 * bound means refuse</b>: a tool invoked on a thread that no caller was bound on fails closed.
 */
public record ToolCaller(Set<String> capabilities) {

    /** No Subject (Personal) or an internal system caller: every capability check passes. */
    public static final ToolCaller UNRESTRICTED = new ToolCaller(null);

    private static final ThreadLocal<ToolCaller> CURRENT = new ThreadLocal<>();

    public ToolCaller {
        capabilities = capabilities == null ? null : Set.copyOf(capabilities);
    }

    /** A caller for {@code capabilities}; {@code null} means no Subject, i.e. {@link #UNRESTRICTED}. */
    public static ToolCaller of(Set<String> capabilities) {
        return capabilities == null ? UNRESTRICTED : new ToolCaller(capabilities);
    }

    /** Run {@code body} with {@code caller} bound on this thread, restoring whatever was bound before. */
    public static <T> T with(ToolCaller caller, Supplier<T> body) {
        ToolCaller previous = CURRENT.get();
        CURRENT.set(caller);
        try {
            return body.get();
        } finally {
            if (previous == null) CURRENT.remove(); else CURRENT.set(previous);
        }
    }

    /** The bound caller, or {@code fallback} when none is bound. */
    public static ToolCaller currentOr(ToolCaller fallback) {
        ToolCaller c = CURRENT.get();
        return c == null ? fallback : c;
    }

    static Optional<ToolCaller> current() {
        return Optional.ofNullable(CURRENT.get());
    }

    boolean holds(String capability) {
        return capabilities == null || capabilities.contains(capability);
    }
}
