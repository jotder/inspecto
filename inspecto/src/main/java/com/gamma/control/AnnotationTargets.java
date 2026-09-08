package com.gamma.control;

import com.gamma.objects.AnnotationKinds;
import com.gamma.pipeline.ComponentRegistry;
import com.gamma.pipeline.ComponentStore;
import com.sun.net.httpserver.HttpExchange;

import java.nio.file.Path;
import java.util.Map;
import java.util.NoSuchElementException;

/**
 * The one existence-and-authorization gate for anything addressed as a {@code (targetKind, targetId)}
 * pair — notes (D10) and tag assignments (D7).
 *
 * <p>Extracted from {@code NoteRoutes} when tags became the second consumer. Both features attach
 * user-authored metadata to the same target vocabulary, so they must answer "may this caller touch that
 * thing?" identically; two copies of this logic would eventually disagree, and the disagreement would be
 * a quiet authorization hole rather than a visible bug.
 *
 * <h3>Authorization, per family (the engine stays identity-agnostic — gating happens at the edge)</h3>
 * <ul>
 *   <li><b>{@code object}</b> — the SEC-7d data-scope + ABAC gate, reused verbatim from
 *       {@link ObjectRoutes#visibleObjectCorrelationId}. An out-of-scope object answers 404 exactly as
 *       {@code /objects/{id}/…} does, so neither surface is a bypass.</li>
 *   <li><b>component kinds</b> — the R3 component-sharing gate, {@link ComponentAccess#requireView}: if
 *       you may see the component you may annotate it. Deliberately <b>not</b> {@code requireEdit} —
 *       commenting on or labelling a saved view is a collaboration act, not an edit of its content
 *       (nothing under {@code registry/} changes), so a view-only sharee may do both.</li>
 * </ul>
 *
 * <p><b>A tag is never an access grant.</b> Because the gate runs per target on both the read and the
 * write path, "everything tagged X" can only ever return targets the caller could already see — tagging
 * cannot widen visibility, which the D7 plan §4 requires and which a capability-based gate would have
 * left as a rule someone has to remember rather than a structural property.
 * <p>⚠ <b>Public since EDG-01 cell 7</b> (2026-09-08), for the same reason the route SPI went public in
 * cell 3a: the {@code /objects}, {@code /notes} and {@code /tags} route families moved into the optional
 * {@code inspecto-ops} module and still need this core helper. Widening the visibility was the honest
 * option — copying it into the module would have left two implementations of one contract to drift.
 */
public final class AnnotationTargets {

    private AnnotationTargets() {}

    /**
     * Existence <em>and</em> authorization for one target. Returns the event correlation id ({@code ""}
     * when there is none), or {@code null} when the target does not exist. Throws 404 when the target
     * exists but the caller may not see it (existence-hiding), and 400 on a kind outside
     * {@link AnnotationKinds#KINDS}.
     */
    public static String gate(ApiContext api, HttpExchange ex, String targetKind, String targetId) {
        if (AnnotationKinds.OBJECT.equals(targetKind))
            return visibleObjectCorrelationId(api, ex, targetId);
        Path root = api.writeRoot() == null ? null : api.writeRoot().resolve("registry");
        ComponentRegistry.Component c;
        try {
            c = root == null ? null : new ComponentStore(root).get(targetKind, targetId).orElse(null);
        } catch (IllegalArgumentException bad) {           // not a writable component type
            throw new ApiException(400, bad.getMessage());
        }
        if (c == null) return null;
        ComponentAccess.requireView(ex, targetKind, targetId, c.content());
        return "";
    }

    /** Whether the caller may see this target at all — the read-side filter for "everything tagged X". */
    public static boolean visible(ApiContext api, HttpExchange ex, String targetKind, String targetId) {
        try {
            return gate(api, ex, targetKind, targetId) != null;
        } catch (ApiException | NoSuchElementException denied) {
            return false;
        }
    }

    /**
     * The {@code object} arm of {@link #gate}, core-side since EDG-01 cell 7 (2026-09-08).
     *
     * <p>It used to call {@code ObjectRoutes.visibleObjectCorrelationId}, a static in a route class that
     * moved to the optional {@code inspecto-ops} module. ⚠ The SEC-7d check stays <b>here</b> rather than
     * travelling with it: {@code ObjectAccess} is declared in {@code inspecto-engine} and cannot see
     * {@code HttpExchange}/{@code Subject}, so the seam hands back plain data and core decides. That also
     * keeps one row-scope implementation for every kind instead of one per module.
     *
     * <p>⛔ Absent the module the kind is not addressable at all, so an {@code object} target is refused —
     * {@code AnnotationKinds.OBJECT} deliberately stays in the static vocabulary on every edition
     * (a role file authored on Standard must still validate on Personal), so the vocabulary listing it is
     * not a promise that a route is behind it.
     */
    private static String visibleObjectCorrelationId(ApiContext api, HttpExchange ex, String id) {
        com.gamma.objects.ObjectAccess objects = api.service().objects().orElse(null);
        if (objects == null)
            throw new ApiException(503, "Operational objects are not installed in this bundle - they are "
                    + "provided by the optional inspecto-ops module (Standard edition and above).");
        Map<String, Object> o = objects.summary(id).orElse(null);
        if (o == null) return null;
        if (!objectVisibleTo(ex, o)) throw new ApiException(404, "no object with id '" + id + "'");
        Object corr = o.get("correlationId");
        return corr == null ? "" : String.valueOf(corr);
    }

    /** {@code caseType} scoping + the row-scope policy check, over {@link com.gamma.objects.ObjectAccess#summary}'s map. */
    @SuppressWarnings("unchecked")
    private static boolean objectVisibleTo(HttpExchange ex, Map<String, Object> o) {
        Map<String, String> attrs = (Map<String, String>) o.getOrDefault("attributes", Map.of());
        Subject s = ApiContext.subject(ex).orElse(null);
        if (s != null && s.scoped()) {
            String caseType = attrs.get("caseType");
            if (caseType != null && !caseType.isBlank() && !s.dataScopes().contains(caseType)) return false;
        }
        Map<String, Object> resource = new java.util.LinkedHashMap<>(attrs);
        resource.put("kind", o.get("kind"));
        resource.put("id", o.get("id"));
        Object owner = o.get("owner");
        if (owner != null && !String.valueOf(owner).isBlank()) resource.put("owner", owner);
        return RowScope.visible(ex, String.valueOf(o.get("kind")), resource);
    }
}
