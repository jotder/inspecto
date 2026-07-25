package com.gamma.control;

import com.gamma.ops.note.NoteTargets;
import com.gamma.pipeline.ComponentRegistry;
import com.gamma.pipeline.ComponentStore;
import com.sun.net.httpserver.HttpExchange;

import java.nio.file.Path;
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
 */
final class AnnotationTargets {

    private AnnotationTargets() {}

    /**
     * Existence <em>and</em> authorization for one target. Returns the event correlation id ({@code ""}
     * when there is none), or {@code null} when the target does not exist. Throws 404 when the target
     * exists but the caller may not see it (existence-hiding), and 400 on a kind outside
     * {@link NoteTargets#KINDS}.
     */
    static String gate(ApiContext api, HttpExchange ex, String targetKind, String targetId) {
        if (NoteTargets.OBJECT.equals(targetKind))
            return ObjectRoutes.visibleObjectCorrelationId(api, ex, targetId);
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
    static boolean visible(ApiContext api, HttpExchange ex, String targetKind, String targetId) {
        try {
            return gate(api, ex, targetKind, targetId) != null;
        } catch (ApiException | NoSuchElementException denied) {
            return false;
        }
    }

    /** Map the engine's fail-closed signals onto the house statuses: unknown kind 400, absent target 404. */
    static <T> T mapErrors(java.util.function.Supplier<T> body) {
        try {
            return body.get();
        } catch (NoSuchElementException notFound) {
            throw new ApiException(404, notFound.getMessage());
        } catch (IllegalArgumentException bad) {
            throw new ApiException(400, bad.getMessage());
        }
    }
}
