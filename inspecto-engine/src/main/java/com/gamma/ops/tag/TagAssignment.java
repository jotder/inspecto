package com.gamma.ops.tag;

import com.gamma.ops.AnnotationKinds;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * One edge in the cross-entity tag graph (BACKLOG D7): "{@code tag} is applied to
 * {@code (targetKind, targetId)}".
 *
 * <p>Distinct from {@link Tag}, which is the <b>label itself</b> in the registry. A {@code Tag} exists
 * independently of anything it labels — that separation is deliberate and predates this record. A
 * {@code TagAssignment} is the association, and the reason D7 exists: tags used to live <i>inside</i> the
 * tagged object as a CSV string in its {@code attributes} map, which made "everything tagged X" a fan-out
 * over every store and made a rename impossible to propagate (each CSV copy would have to be rewritten,
 * so a renamed tag silently split in two).
 *
 * <p><b>Addressing follows D10.</b> Targets are {@code (targetKind, targetId)} pairs drawn from
 * {@link AnnotationKinds} — the same vocabulary notes use. That is intentional and load-bearing: §2 of the D7
 * plan requires tags and notes to address components identically rather than inventing a second scheme.
 * The class lives under {@code ops.note} only because notes got there first; read it as "annotation
 * targets". Widening {@code ComponentStore.WRITABLE_TYPES} widens both features at once.
 *
 * <p>Identity is the triple {@code (tag, targetKind, targetId)} — assigning the same tag twice is the
 * same edge, not a second one, which is what makes {@link TagAssignmentStore#add} idempotent.
 *
 * @since 4.9.0
 */
@com.gamma.api.PublicApi(since = "4.0.0")
public record TagAssignment(String tag, String targetKind, String targetId, String actor, long createdAt) {

    public TagAssignment {
        if (tag == null || tag.isBlank())
            throw new IllegalArgumentException("tag assignment requires a tag name");
        // Commas are rejected by Tag itself because the legacy object-attribute storage was
        // comma-separated. Keep rejecting them: the CSV path still ships (ObjectService.ATTR_TAGS), so a
        // comma in a name would be one store's valid label and another's two labels.
        if (tag.indexOf(',') >= 0)
            throw new IllegalArgumentException("tag name must not contain a comma: '" + tag + "'");
        if (targetId == null || targetId.isBlank())
            throw new IllegalArgumentException("tag assignment requires a target id");
        tag = tag.trim();
        targetKind = AnnotationKinds.require(targetKind);
        targetId = targetId.trim();
        actor = actor == null || actor.isBlank() ? "system" : actor.trim();
        if (createdAt <= 0) createdAt = System.currentTimeMillis();
    }

    /** A new assignment stamped now. */
    public static TagAssignment of(String tag, String targetKind, String targetId, String actor) {
        return new TagAssignment(tag, targetKind, targetId, actor, System.currentTimeMillis());
    }

    /** Whether this edge points at the given target. Both sides are already normalised. */
    public boolean targets(String kind, String id) {
        return targetKind.equals(kind == null ? null : kind.trim().toLowerCase(Locale.ROOT))
                && targetId.equals(id == null ? null : id.trim());
    }

    /** Stable-key JSON view, matching the {@code toMap()} idiom of {@code ObjectNote} and {@code Tag}. */
    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("tag", tag);
        m.put("targetKind", targetKind);
        m.put("targetId", targetId);
        m.put("actor", actor);
        m.put("createdAt", createdAt);
        return m;
    }
}
