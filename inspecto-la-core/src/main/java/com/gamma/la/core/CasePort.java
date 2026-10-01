package com.gamma.la.core;

import com.gamma.control.ApiContext;
import com.sun.net.httpserver.HttpExchange;

import java.util.Map;
import java.util.Optional;

/**
 * The Case PORT of Link Analysis (LA-24, LA separation D-1 step 5b/6): the host-bound part of sharing an Investigation with
 * a Case team — reading a Case summary and asking whether the caller may see it. The Case rules themselves (kind, closed,
 * owner/assignee) stay in {@code inspecto-la-api}. The bridge ({@code inspecto-geo-link}'s {@code HostCasePort}) implements
 * this from the host's {@code ObjectAccess} and {@code AnnotationTargets}, registered through
 * {@code META-INF/services/com.gamma.la.core.CasePort}. Find the active one through {@link CasePorts}.
 *
 * <p><b>Unbound ⇒ "Case management is not installed"</b>: the existing behaviour when the ops module is missing — a link
 * can be stored but grants nothing and every response says why; sharing falls back to owner-only.
 */
public interface CasePort {

    /** Whether the host has Case management (the optional ops module) installed. */
    boolean available(ApiContext api);

    /** The summary of the object {@code ref} (a Case when its {@code kind} is {@code case}), or empty when unknown. */
    Optional<Map<String, Object>> summary(ApiContext api, String ref);

    /** Whether the caller may see this object (SEC-7d scope + row policy). */
    boolean visibleTo(HttpExchange ex, Map<String, Object> summary);
}
