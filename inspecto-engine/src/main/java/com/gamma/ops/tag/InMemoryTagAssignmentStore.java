package com.gamma.ops.tag;

import com.gamma.ops.note.NoteTargets;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

/**
 * In-memory {@link TagAssignmentStore} — the lean default, mirroring {@code InMemoryNoteStore}. Guarded
 * on the instance monitor, so it is safe to share across threads.
 *
 * <p>A flat list is the right shape here despite the obvious "index it by tag" temptation: assignment
 * counts in a single Space are small (they are user-applied labels, not machine-generated edges), and the
 * DB store is what a large deployment uses. Two indexes would have to be kept consistent through
 * {@link #rename} and {@link #removeTag} for no measured gain.
 *
 * @since 4.9.0
 */
@com.gamma.api.PublicApi(since = "4.9.0")
public final class InMemoryTagAssignmentStore implements TagAssignmentStore {

    /** Insertion-ordered; reads iterate in reverse for newest-first. Guarded by {@code this}. */
    private final List<TagAssignment> edges = new ArrayList<>();

    @Override
    public synchronized TagAssignment add(TagAssignment assignment) {
        for (TagAssignment e : edges)
            if (same(e, assignment.tag(), assignment.targetKind(), assignment.targetId())) return e;
        edges.add(assignment);
        return assignment;
    }

    @Override
    public synchronized boolean remove(String tag, String targetKind, String targetId) {
        String tk = NoteTargets.require(targetKind);
        String id = targetId == null ? null : targetId.trim();
        return edges.removeIf(e -> same(e, norm(tag), tk, id));
    }

    @Override
    public synchronized List<String> tagsOf(String targetKind, String targetId) {
        String tk = NoteTargets.require(targetKind);
        String id = targetId == null ? null : targetId.trim();
        // Sorted + de-duplicated: the order edges happen to have been added in is not meaningful to a
        // reader, and a stable alphabetical list keeps the UI from reshuffling chips on every refresh.
        TreeSet<String> out = new TreeSet<>();
        for (TagAssignment e : edges)
            if (e.targetKind().equals(tk) && e.targetId().equals(id)) out.add(e.tag());
        return List.copyOf(out);
    }

    @Override
    public synchronized List<TagAssignment> forTag(String tag) {
        String t = norm(tag);
        List<TagAssignment> out = new ArrayList<>();
        for (int i = edges.size() - 1; i >= 0; i--)
            if (edges.get(i).tag().equals(t)) out.add(edges.get(i));
        return out;
    }

    @Override
    public synchronized List<TagAssignment> all() {
        List<TagAssignment> out = new ArrayList<>();
        for (int i = edges.size() - 1; i >= 0; i--) out.add(edges.get(i));
        return out;
    }

    @Override
    public synchronized int rename(String from, String to) {
        String f = norm(from);
        String t = norm(to);
        if (f == null || t == null || f.equals(t)) return 0;
        int moved = 0;
        List<TagAssignment> renamed = new ArrayList<>();
        for (TagAssignment e : edges) {
            if (!e.tag().equals(f)) continue;
            renamed.add(new TagAssignment(t, e.targetKind(), e.targetId(), e.actor(), e.createdAt()));
            moved++;
        }
        edges.removeIf(e -> e.tag().equals(f));
        // Re-add through the dedup path: a target already carrying `to` merges with the incoming edge
        // rather than ending up with two identical rows.
        for (TagAssignment e : renamed) add(e);
        return moved;
    }

    @Override
    public synchronized int removeTag(String tag) {
        String t = norm(tag);
        int before = edges.size();
        edges.removeIf(e -> e.tag().equals(t));
        return before - edges.size();
    }

    @Override
    public synchronized int removeAllForTarget(String targetKind, String targetId) {
        String tk = NoteTargets.require(targetKind);
        String id = targetId == null ? null : targetId.trim();
        int before = edges.size();
        edges.removeIf(e -> e.targetKind().equals(tk) && e.targetId().equals(id));
        return before - edges.size();
    }

    private static boolean same(TagAssignment e, String tag, String kind, String id) {
        return e.tag().equals(tag) && e.targetKind().equals(kind) && e.targetId().equals(id);
    }

    /** Tag names are compared exactly, as {@link Tag} stores them — no case folding. */
    private static String norm(String tag) {
        return tag == null || tag.isBlank() ? null : tag.trim();
    }
}
