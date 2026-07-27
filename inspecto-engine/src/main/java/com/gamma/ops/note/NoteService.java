package com.gamma.ops.note;

import com.gamma.ops.AnnotationKinds;

import com.gamma.event.Event;
import com.gamma.event.EventLevel;
import com.gamma.event.EventLog;
import com.gamma.event.EventType;

import java.util.List;
import java.util.NoSuchElementException;

/**
 * Kind-agnostic note CRUD (BACKLOG D10) — the one path every note producer goes through, whatever the
 * note hangs off. It replaces {@code ObjectService.require(objectId)} as the existence gate: the
 * {@link TargetResolver} answers "does this {@code (targetKind, targetId)} exist?" per family, so
 * {@code ObjectService} owns only the {@code object} family and a component-backed caller (e.g. a
 * comment on a saved {@code link-analysis-view}) plugs in without becoming a special case.
 *
 * <h3>Fail-closed</h3>
 * An unknown {@code targetKind} throws {@link IllegalArgumentException} ({@link AnnotationKinds#require});
 * a known kind whose id does not resolve throws {@link NoSuchElementException}. A note is never
 * written for a target that does not exist — no orphans.
 *
 * <p>Every write emits an {@link EventType#OBJECT_NOTE} event on {@link EventLog#current()}, exactly as
 * the pre-D10 object path did. Authorization is <b>not</b> here: it stays at the HTTP edge, where the
 * caller-facing rules live (the engine is identity-agnostic).
 *
 * @since 4.9.0
 */
@com.gamma.api.PublicApi(since = "4.9.0")
public final class NoteService {

    private static final String SOURCE = NoteService.class.getName();

    /**
     * Per-kind target existence gate. Returns the correlation id to stamp on the note's event
     * ({@code ""} when the target has none), or <b>{@code null} when the target does not exist</b> —
     * the one signal that makes the write fail closed.
     */
    @FunctionalInterface
    public interface TargetResolver {
        String correlationIdOrNull(String targetKind, String targetId);
    }

    private final NoteStore notes;
    private final TargetResolver targets;

    public NoteService(NoteStore notes, TargetResolver targets) {
        this.notes = notes;
        this.targets = targets;
    }

    /** Add a free-text comment to {@code (targetKind, targetId)}. */
    public ObjectNote comment(String targetKind, String targetId, String author, String body) {
        String kind = AnnotationKinds.require(targetKind);
        return add(ObjectNote.comment(kind, targetId, author, body), author, require(kind, targetId));
    }

    /** Attach an external-evidence reference (metadata only) to {@code (targetKind, targetId)}. */
    public ObjectNote attach(String targetKind, String targetId, String author, String name,
                             String contentType, String uri, String caption) {
        String kind = AnnotationKinds.require(targetKind);
        return add(ObjectNote.attachment(kind, targetId, author, name, contentType, uri, caption), author,
                require(kind, targetId));
    }

    /**
     * A target's notes, newest-first; {@code kind} {@code null} returns every note kind. Reads gate on
     * existence too, so a probe for a bogus id cannot be answered with a bland empty list.
     */
    public List<ObjectNote> notesOf(String targetKind, String targetId, NoteKind kind) {
        String tk = AnnotationKinds.require(targetKind);
        require(tk, targetId);
        return notes.forTarget(tk, targetId, kind);
    }

    /** Append an already-built note against an existing target (used for multi-note seeding, e.g. RCA). */
    public ObjectNote append(ObjectNote note, String actor, String correlationId) {
        return add(note, actor, correlationId);
    }

    /** The correlation id of an existing target, or {@link NoSuchElementException} — the D10 gate. */
    public String require(String targetKind, String targetId) {
        String tk = AnnotationKinds.require(targetKind);
        if (targetId == null || targetId.isBlank())
            throw new NoSuchElementException("no " + tk + " with id '" + targetId + "'");
        String correlationId = targets.correlationIdOrNull(tk, targetId);
        if (correlationId == null)
            throw new NoSuchElementException("no " + tk + " with id '" + targetId + "'");
        return correlationId;
    }

    private ObjectNote add(ObjectNote note, String actor, String correlationId) {
        ObjectNote stored = notes.add(note);
        EventLog.current().emit(Event.builder(EventType.OBJECT_NOTE)
                .level(EventLevel.INFO)
                .source(SOURCE)
                .correlationId(correlationId == null || correlationId.isBlank() ? null : correlationId)
                // The object phrasing is the shipped one — only non-object targets name their kind.
                .message(stored.kind() + " on "
                        + (AnnotationKinds.OBJECT.equals(stored.targetKind()) ? "" : stored.targetKind() + " ")
                        + stored.objectId()
                        + (actor == null || actor.isBlank() ? "" : " by " + actor))
                .attr("objectId", stored.objectId())
                .attr("targetKind", stored.targetKind())
                .attr("targetId", stored.objectId())
                .attr("noteId", stored.id())
                .attr("noteKind", stored.kind().name())
                .attr("author", actor));
        return stored;
    }
}
