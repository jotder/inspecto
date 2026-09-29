package com.gamma.intelligence;

import java.util.Map;
import java.util.Set;

/**
 * A request to open an embedded-intelligence session (AGT-5, P0) — the wire shape of
 * {@code POST /agent/sessions}. {@code role} is the caller's product role (mapped onto the
 * eoiagent platform {@code Role} inside the optional {@code inspecto-intelligence} module);
 * {@code page} is the current UI page context (id + entity ids + filters), or empty when the
 * caller has none. {@code goalKind} (AGT-5 P1 slice B) optionally pins the session's goal kind
 * (e.g. {@code INVESTIGATION}); {@code null}/blank leaves the eoiagent default ({@code QA}). The
 * value is validated against the known kinds inside the intelligence module, which knows the enum.
 *
 * <p>{@code capabilities} are the authenticated caller's control-plane capabilities. Every tool the session
 * runs is checked against them — the same check {@code POST /agent/tools/{name}} makes (2026-09-29). It is
 * {@code null} when no Subject is attached (Personal, which checks nothing anywhere).
 */
public record AgentSessionRequest(String role, Map<String, Object> page, String goalKind,
                                  Set<String> capabilities) {

    public AgentSessionRequest {
        page = page == null ? Map.of() : Map.copyOf(page);
        capabilities = capabilities == null ? null : Set.copyOf(capabilities);
    }

    /** No Subject (Personal): unrestricted tools. */
    public AgentSessionRequest(String role, Map<String, Object> page, String goalKind) {
        this(role, page, goalKind, null);
    }

    /** Back-compat convenience — a session with no pinned goal kind (the eoiagent {@code QA} default). */
    public AgentSessionRequest(String role, Map<String, Object> page) {
        this(role, page, null);
    }
}
