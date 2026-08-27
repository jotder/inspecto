package com.gamma.ops.note;

import com.gamma.ops.AnnotationKinds;

import java.util.List;

/**
 * Persistence seam for {@link ObjectNote}s — the evidence/notes store of the Operational Intelligence
 * Platform (Phase 4 follow-up). Append-only, like {@link com.gamma.event.EventStore} and
 * {@link com.gamma.ops.link.LinkStore}: notes are immutable facts, so there is no {@code update}.
 * {@link InMemoryNoteStore} is the lean default; {@link DbNoteStore} is durable JDBC over the bundled
 * DuckDB (or a Postgres URL), selected by the same {@code -Dobjects.backend} deployment toggle.
 *
 * <h3>Contract</h3>
 * <ul>
 *   <li>{@link #forTarget(String, String, NoteKind)} returns one target's notes <b>newest-first</b>; a
 *       {@code null} {@code kind} means "all note kinds". Reads are scoped to the
 *       {@code (targetKind, targetId)} pair (BACKLOG D10) — never to the id alone, so two families
 *       can share an id without bleeding into each other.</li>
 *   <li>Implementations must be thread-safe.</li>
 * </ul>
 *
 * @since 4.6.0
 */
@com.gamma.api.PublicApi(since = "4.0.0")
public interface NoteStore extends AutoCloseable {

    /** Append a note and return it. */
    ObjectNote add(ObjectNote note);

    /** One target's notes, newest-first; {@code kind} {@code null} returns every note kind. */
    List<ObjectNote> forTarget(String targetKind, String targetId, NoteKind kind);

    /** An {@link AnnotationKinds#OBJECT} target's notes, newest-first — the pre-D10 shorthand. */
    default List<ObjectNote> forObject(String objectId, NoteKind kind) {
        return forTarget(AnnotationKinds.OBJECT, objectId, kind);
    }

    /**
     * Delete <b>every</b> note of every kind for one target, returning how many rows went. The one
     * exception to this store's append-only contract, and deliberately narrow: it exists so a retention
     * purge can cascade (MNT-14 G2), because {@link com.gamma.ops.ObjectStore#delete} does not.
     *
     * <p>This covers attachments too — an attachment is an {@link ObjectNote} row with an attachment
     * {@link NoteKind}, not a separate store, so there is nothing else to clean up.
     *
     * <p>Scoped to the {@code (targetKind, targetId)} pair like every read here, never the id alone.
     *
     * @since 4.10.0 widened in a MAJOR release rather than added as a throwing {@code default}: a default
     *        that silently did nothing would orphan rows, which is the exact failure this method prevents.
     */
    int deleteForTarget(String targetKind, String targetId);

    /** Release resources (e.g. the DuckDB connection). Idempotent; no-op for in-memory. */
    @Override
    default void close() {}
}
