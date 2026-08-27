package com.gamma.ops.note;

import com.gamma.ops.AnnotationKinds;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * One append-only annotation on a <b>note target</b> — the "Evidence/Notes/Attachments" of the
 * Operational Intelligence Platform (Phase 4 follow-up). A {@link NoteKind#COMMENT} carries free text
 * in {@link #body()}; a {@link NoteKind#ATTACHMENT} references external evidence — the file/URL
 * metadata ({@code name}/{@code contentType}/{@code uri}) rides the extensible {@link #attributes()}
 * bag (the same idiom as {@link com.gamma.ops.OperationalObject}), so one table serves both kinds and
 * the <b>bytes never enter the lean core</b> — only a reference does.
 *
 * <h3>Two orthogonal axes (BACKLOG D10)</h3>
 * <ul>
 *   <li>{@link #kind()} is the <b>note</b> kind — {@code COMMENT} vs {@code ATTACHMENT}.</li>
 *   <li>{@link #targetKind()} is <b>what the note is attached to</b> — {@code "object"} (an
 *       {@link com.gamma.ops.OperationalObject}) or any registry component type such as
 *       {@code "link-analysis-view"}. See {@link AnnotationKinds}.</li>
 * </ul>
 * Never conflate the two. {@link #objectId()} is the target's id on whichever kind is addressed
 * (kept under its historical name so the shipped {@code /objects/{id}/comments} JSON is unchanged);
 * {@link #targetId()} is the kind-agnostic reading of the same component.
 *
 * <p>Like an {@link com.gamma.event.Event} and an {@link com.gamma.ops.link.ObjectLink}, a note is an
 * immutable, append-only fact: created and read, never mutated.
 *
 * @since 4.6.0
 */
@com.gamma.api.PublicApi(since = "4.0.0")
public record ObjectNote(String id, String objectId, String targetKind, NoteKind kind, String author,
                         String body, Map<String, String> attributes, long createdAt) {

    /** Canonical constructor — validates keys, defaults text, makes {@code attributes} immutable. */
    public ObjectNote {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("note id is required");
        if (objectId == null || objectId.isBlank()) throw new IllegalArgumentException("note objectId is required");
        if (kind == null) throw new IllegalArgumentException("note kind is required");
        targetKind = targetKind == null || targetKind.isBlank()
                ? AnnotationKinds.OBJECT : targetKind.trim().toLowerCase(Locale.ROOT);
        author = author == null ? "" : author;
        body = body == null ? "" : body;
        attributes = attributes == null || attributes.isEmpty() ? Map.of() : Map.copyOf(attributes);
    }

    /**
     * Pre-D10 constructor — a note on an {@link com.gamma.ops.OperationalObject}. Kept so the
     * object-targeted call sites (and their tests) read unchanged.
     */
    public ObjectNote(String id, String objectId, NoteKind kind, String author, String body,
                      Map<String, String> attributes, long createdAt) {
        this(id, objectId, AnnotationKinds.OBJECT, kind, author, body, attributes, createdAt);
    }

    /** The target's id, read kind-agnostically (the same component as {@link #objectId()}). */
    public String targetId() {
        return objectId;
    }

    /** A free-text {@link NoteKind#COMMENT} on the object {@code objectId}, stamped now. */
    public static ObjectNote comment(String objectId, String author, String body) {
        return comment(AnnotationKinds.OBJECT, objectId, author, body);
    }

    /** A free-text {@link NoteKind#COMMENT} on {@code (targetKind, targetId)}, stamped now. */
    public static ObjectNote comment(String targetKind, String targetId, String author, String body) {
        return new ObjectNote(newId(), targetId, targetKind, NoteKind.COMMENT, author, body, Map.of(),
                System.currentTimeMillis());
    }

    /**
     * An {@link NoteKind#ATTACHMENT} on the object {@code objectId} referencing external evidence;
     * {@code body} is an optional caption, and {@code name}/{@code contentType}/{@code uri} are stored
     * as attributes.
     */
    public static ObjectNote attachment(String objectId, String author, String name, String contentType,
                                        String uri, String caption) {
        return attachment(AnnotationKinds.OBJECT, objectId, author, name, contentType, uri, caption);
    }

    /** An {@link NoteKind#ATTACHMENT} on {@code (targetKind, targetId)}; see the object-targeted overload. */
    public static ObjectNote attachment(String targetKind, String targetId, String author, String name,
                                        String contentType, String uri, String caption) {
        Map<String, String> attrs = new LinkedHashMap<>();
        if (name != null) attrs.put("name", name);
        if (contentType != null) attrs.put("contentType", contentType);
        if (uri != null) attrs.put("uri", uri);
        return new ObjectNote(newId(), targetId, targetKind, NoteKind.ATTACHMENT, author,
                caption == null ? "" : caption, attrs, System.currentTimeMillis());
    }

    /**
     * JSON-ready view (stable key order) — backs the {@code /objects/{id}/comments|attachments} and
     * {@code /notes/{targetKind}/{targetId}/…} APIs. {@code objectId} is retained verbatim for the
     * shipped object surface; {@code targetKind}/{@code targetId} are additive.
     */
    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("objectId", objectId);
        m.put("targetKind", targetKind);
        m.put("targetId", objectId);
        m.put("kind", kind.name());
        m.put("author", author);
        m.put("body", body);
        m.put("attributes", attributes);
        m.put("createdAt", createdAt);
        return m;
    }

    private static String newId() {
        return "NOTE-" + UUID.randomUUID();
    }
}
