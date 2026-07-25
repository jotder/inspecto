package com.gamma.ops.tag;

import java.util.List;

/**
 * Persistence seam for {@link TagAssignment}s — the cross-entity tag graph (BACKLOG D7).
 *
 * <p>Unlike the other {@code ops} stores ({@code NoteStore}, {@code LinkStore}, {@code EventStore}) this
 * one is <b>not</b> append-only: untagging is a normal, expected user action, so {@link #remove} really
 * deletes the edge. An assignment is a piece of mutable organisation, not an immutable fact about what
 * happened.
 *
 * <h3>Contract</h3>
 * <ul>
 *   <li>{@link #add} is <b>idempotent</b> — the identity of an edge is {@code (tag, targetKind, targetId)},
 *       so re-applying a tag returns the existing edge rather than creating a duplicate. Callers may
 *       therefore retry safely, and the UI need not check before applying.</li>
 *   <li>{@link #tagsOf} and {@link #forTag} are the two directions of the same relation, and both are
 *       required: one drives "what is this labelled?", the other "what is labelled X?" — the latter is the
 *       entire reason D7 chose a central store over per-entity fields.</li>
 *   <li>{@link #rename} propagates across every kind at once. Under the old per-entity CSV shape this was
 *       impossible, which is the architectural argument recorded in the D7 plan §2.</li>
 *   <li>Reads are scoped to the {@code (targetKind, targetId)} pair, never the id alone, so two families
 *       sharing an id cannot bleed into each other (the D10 rule).</li>
 *   <li>Implementations must be thread-safe.</li>
 * </ul>
 *
 * @since 4.9.0
 */
@com.gamma.api.PublicApi(since = "4.9.0")
public interface TagAssignmentStore extends AutoCloseable {

    /** Apply a tag. Idempotent: re-applying returns the edge already stored, unchanged. */
    TagAssignment add(TagAssignment assignment);

    /** Remove one edge. Returns {@code true} if it existed — {@code false} is "already absent", not an error. */
    boolean remove(String tag, String targetKind, String targetId);

    /** The tag names on one target, alphabetical. Empty when untagged. */
    List<String> tagsOf(String targetKind, String targetId);

    /**
     * Every target carrying {@code tag}, newest-first. Callers <b>must</b> filter the result for
     * visibility before returning it to a user — a tag is an organisational label and never an access
     * grant (D7 plan §4), so this seam deliberately does no authorisation of its own.
     */
    List<TagAssignment> forTag(String tag);

    /** Every assignment, newest-first — for bundle export and the rename/backfill paths. */
    List<TagAssignment> all();

    /**
     * Rename a tag across every kind, returning the number of edges moved. Merges if {@code to} already
     * exists on a target: the triple is the identity, so two edges collapsing into one is correct, not a
     * conflict.
     */
    int rename(String from, String to);

    /** Drop every edge for one tag (used when the tag itself is deleted). Returns the count removed. */
    int removeTag(String tag);

    /** Release resources (e.g. the DuckDB connection). Idempotent; no-op for in-memory. */
    @Override
    default void close() {}
}
