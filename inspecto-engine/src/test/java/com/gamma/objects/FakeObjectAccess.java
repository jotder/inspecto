package com.gamma.objects;

import com.gamma.event.Event;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * An in-memory {@link ObjectAccess} for core's own tests (EDG-01 cell 7, 2026-09-08).
 *
 * <p>Core can no longer construct an {@code ObjectService} — it lives in the optional
 * {@code inspecto-ops} module — so the tests of core classes that <em>consume</em> the seam
 * ({@code AlertService}, {@code ReconRunJob}, {@code DryRunServices}) needed a double.
 *
 * <p>⚠ This is a gain, not a workaround: those tests previously leaned on a real engine and so only
 * ever exercised the Standard shape. Against this fake they assert the behaviour core is actually
 * responsible for — that it dedupes before opening, and opens with the right kind, scope and
 * attributes — which is the same on every edition. Tests that genuinely assert PERSISTENCE moved into
 * the module, where a real engine exists.
 *
 * <p>It honours the two contracts a consumer can observe: {@link #hasActiveMatching} requires every
 * supplied attribute to match, and {@link #open} returns a fresh id each time.
 */
public final class FakeObjectAccess implements ObjectAccess {

    /** One recorded {@link #open} call, in order. */
    public record Opened(ObjectType kind, String title, String description, String severity,
                         String scope, Map<String, String> attributes, String id) {}

    /** One recorded {@link #link} call, in order. */
    public record Linked(String fromId, String toId, String relationship, String actor) {}

    public final List<Opened> opened = new ArrayList<>();
    public final List<Linked> linked = new ArrayList<>();
    public final List<String> tagsEnsured = new ArrayList<>();

    private final Map<String, List<String>> tagsByTarget = new LinkedHashMap<>();

    /** Ids whose objects should read as terminal — i.e. no longer suppressing a duplicate. */
    private final List<String> closed = new ArrayList<>();

    /** Mark a previously opened object terminal, so the active-object convention stops suppressing. */
    public void close(String id) {
        closed.add(id);
    }

    private List<Opened> active(ObjectType kind, String scope) {
        return opened.stream()
                .filter(o -> o.kind() == kind)
                .filter(o -> scope == null ? o.scope() == null : scope.equals(o.scope()))
                .filter(o -> !closed.contains(o.id()))
                .toList();
    }

    @Override
    public boolean hasActive(ObjectType kind, String scope) {
        return !active(kind, scope).isEmpty();
    }

    @Override
    public boolean hasActiveMatching(ObjectType kind, String scope, Map<String, String> matchAttributes) {
        if (matchAttributes == null || matchAttributes.isEmpty()) return hasActive(kind, scope);
        return active(kind, scope).stream().anyMatch(o -> {
            for (Map.Entry<String, String> e : matchAttributes.entrySet()) {
                if (!e.getValue().equals(o.attributes().get(e.getKey()))) return false;
            }
            return true;
        });
    }

    @Override
    public String open(ObjectType kind, String title, String description, String severity,
                       String scope, Map<String, String> attributes) {
        String id = UUID.randomUUID().toString();
        opened.add(new Opened(kind, title, description, severity, scope,
                attributes == null ? Map.of() : Map.copyOf(attributes), id));
        return id;
    }

    @Override
    public void link(String fromId, String toId, String relationship, String actor) {
        linked.add(new Linked(fromId, toId, relationship, actor));
    }

    @Override
    public void addTag(String tag, String targetKind, String targetId, String actor) {
        tagsByTarget.computeIfAbsent(targetKind + '/' + targetId, k -> new ArrayList<>()).add(tag);
    }

    @Override
    public void ensureTag(String name) {
        if (!tagsEnsured.contains(name)) tagsEnsured.add(name);
    }

    @Override
    public List<String> tagsOf(String targetKind, String targetId) {
        return List.copyOf(tagsByTarget.getOrDefault(targetKind + '/' + targetId, List.of()));
    }

    @Override
    public List<String> targetIdsForTag(String tag, String targetKind) {
        List<String> out = new ArrayList<>();
        tagsByTarget.forEach((key, tags) -> {
            if (key.startsWith(targetKind + '/') && tags.contains(tag)) out.add(key.substring(targetKind.length() + 1));
        });
        return out;
    }

    @Override
    public Optional<Map<String, Object>> summary(String objectId) {
        return opened.stream().filter(o -> o.id().equals(objectId)).findFirst().map(FakeObjectAccess::flatten);
    }

    @Override
    public List<Map<String, Object>> findByStatus(ObjectType kind, String status) {
        // The fake has no workflow, so "open" means not explicitly closed — enough for the one consumer.
        return active(kind, null).stream().filter(o -> o.kind() == kind).map(FakeObjectAccess::flatten).toList();
    }

    private static Map<String, Object> flatten(Opened o) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("kind", o.kind().name().toLowerCase(java.util.Locale.ROOT));
        m.put("id", o.id());
        m.put("correlationId", o.scope());
        m.put("owner", null);
        m.put("assignee", null);
        m.put("attributes", o.attributes());
        return m;
    }

    /** No promotion subscriber: the fake is the seam, not the module's event bridge. */
    @Override
    public Optional<Consumer<Event>> eventSubscriber() {
        return Optional.empty();
    }
}
