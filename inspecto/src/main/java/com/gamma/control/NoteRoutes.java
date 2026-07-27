package com.gamma.control;

import com.gamma.ops.note.NoteKind;
import com.gamma.ops.note.NoteService;
import com.gamma.ops.note.ObjectNote;
import com.sun.net.httpserver.HttpExchange;

import java.util.Map;

/**
 * Kind-addressed notes ({@code /notes/{targetKind}/{targetId}/…}, BACKLOG D10) — the generalisation of
 * {@code /objects/{id}/comments|attachments} to any {@code (kind, id)} target. The driving case is a
 * per-view comment thread on a saved {@code link-analysis-view}; the shipped {@code /objects/…} routes
 * in {@link ObjectRoutes} are untouched and keep working.
 *
 * <h3>Target vocabulary</h3>
 * {@link AnnotationKinds#KINDS} = {@code "object"} + {@link ComponentStore#WRITABLE_TYPES} (the same strings
 * the Exchange kind axis and {@code BundleRoutes.OWN_STORE_KINDS} use). An unknown kind is a 400; a
 * known kind with an unresolvable id is a 404 — a note is never written against a target that is not
 * there.
 *
 * <h3>Authorization (per family, at the edge — the engine stays identity-agnostic)</h3>
 * <ul>
 *   <li><b>{@code object}</b> — the SEC-7d data-scope + ABAC {@link RowScope} gate, reused verbatim from
 *       {@link ObjectRoutes#visibleObjectCorrelationId}. An out-of-scope object answers 404 exactly as
 *       {@code /objects/{id}/…} does, so this surface is <em>not</em> a bypass.</li>
 *   <li><b>component kinds</b> — the R3 component-sharing gate, {@link ComponentAccess#requireView}: if
 *       you may see the component you may read and write its notes. Deliberately <b>not</b>
 *       {@code canAuthorWorkbench}/{@code requireEdit}: commenting on a saved view is a collaboration
 *       act, not an edit of the component's content (nothing under {@code registry/} changes), so a
 *       view-only sharee may comment. Notes live in the ops note store, not in the component file.</li>
 * </ul>
 * Both families run through one gate — {@link AnnotationTargets#gate} is the
 * {@link NoteService.TargetResolver}, so existence and authorization cannot diverge between the read and
 * the write path. That gate is shared with the D7 tag-assignment routes; see {@link AnnotationTargets}.
 */
final class NoteRoutes implements RouteModule {

    @Override
    public void register(ApiContext api) {
        api.get("/notes/([^/]+)/([^/]+)", (e, m) ->
                notesOf(api, e, ApiContext.name(m), ApiContext.param(m, 2), null));
        api.get("/notes/([^/]+)/([^/]+)/comments", (e, m) ->
                notesOf(api, e, ApiContext.name(m), ApiContext.param(m, 2), NoteKind.COMMENT));
        api.get("/notes/([^/]+)/([^/]+)/attachments", (e, m) ->
                notesOf(api, e, ApiContext.name(m), ApiContext.param(m, 2), NoteKind.ATTACHMENT));
        api.post("/notes/([^/]+)/([^/]+)/comments", (e, m) ->
                addComment(api, e, ApiContext.name(m), ApiContext.param(m, 2), api.body(e)));
        api.post("/notes/([^/]+)/([^/]+)/attachments", (e, m) ->
                addAttachment(api, e, ApiContext.name(m), ApiContext.param(m, 2), api.body(e)));
    }

    /** {@code GET /notes/{targetKind}/{targetId}[/comments|/attachments]} — a target's notes, newest-first. */
    private Object notesOf(ApiContext api, HttpExchange ex, String targetKind, String targetId, NoteKind kind) {
        return AnnotationTargets.mapErrors(() -> notes(api, ex).notesOf(targetKind, targetId, kind).stream()
                .map(ObjectNote::toMap).toList());
    }

    /** {@code POST /notes/{targetKind}/{targetId}/comments} — body {@code {body, author?}}. */
    private Object addComment(ApiContext api, HttpExchange ex, String targetKind, String targetId,
                              Map<String, Object> body) {
        String text = ApiContext.str(body, "body");
        if (text == null) throw new ApiException(400, "body must include 'body'");
        return AnnotationTargets.mapErrors(() -> notes(api, ex)
                .comment(targetKind, targetId, ApiContext.str(body, "author"), text).toMap());
    }

    /**
     * {@code POST /notes/{targetKind}/{targetId}/attachments} — an evidence reference (metadata only);
     * body {@code {name, uri, contentType?, author?, caption?}}.
     */
    private Object addAttachment(ApiContext api, HttpExchange ex, String targetKind, String targetId,
                                 Map<String, Object> body) {
        String name = ApiContext.str(body, "name");
        String uri = ApiContext.str(body, "uri");
        if (name == null || uri == null) throw new ApiException(400, "body must include 'name' and 'uri'");
        return AnnotationTargets.mapErrors(() -> notes(api, ex).attach(targetKind, targetId, ApiContext.str(body, "author"),
                name, ApiContext.str(body, "contentType"), uri, ApiContext.str(body, "caption")).toMap());
    }

    // ── the one gate (shared with the D7 tag routes) ─────────────────────────────────────────────────────────────────

    /** A request-scoped {@link NoteService} over the engine's note store, gated by {@link AnnotationTargets#gate}. */
    private NoteService notes(ApiContext api, HttpExchange ex) {
        return new NoteService(api.service().objects().noteStore(),
                (kind, id) -> AnnotationTargets.gate(api, ex, kind, id));
    }
}
