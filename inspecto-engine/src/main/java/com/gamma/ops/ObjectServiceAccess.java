package com.gamma.ops;

import com.gamma.event.Event;
import com.gamma.objects.ObjectAccess;
import com.gamma.objects.ObjectType;
import com.gamma.objects.TagAssignment;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * The domain's implementation of the core {@link ObjectAccess} seam (EDG-01 cell 7, 2026-09-08).
 *
 * <p><b>Why an adapter rather than {@code ObjectService implements ObjectAccess}.</b> The two surfaces
 * collide by erasure: {@code ObjectService.open(ObjectType, String, String, String, String, Map)} returns
 * an {@code OperationalObject} and {@code link(String, String, String, String)} returns an
 * {@code ObjectLink}, while the SPI must return a {@code String} id and {@code void} — same parameters,
 * different return types, which Java rejects. Renaming the SPI methods to dodge that would have pushed
 * awkward names ({@code openObject}, {@code linkObjects}) into core to work around a detail of the module,
 * so the adaptation lives here instead, on the side of the boundary that owns the richer API.
 *
 * <p>⚠ This class travels with {@code com.gamma.ops} into the optional {@code inspecto-ops} module. Core
 * never names it — it obtains an {@link ObjectAccess} and knows nothing more.
 */
final class ObjectServiceAccess implements ObjectAccess {

    private final ObjectService service;

    ObjectServiceAccess(ObjectService service) {
        this.service = service;
    }

    @Override
    public boolean hasActive(ObjectType kind, String scope) {
        return !service.active(kind, scope).isEmpty();
    }

    /**
     * ⚠ Every entry must match, which is what makes a <b>compound</b> dedupe key expressible — the gap and
     * conservation-imbalance bridges key on {@code rule} plus {@code node}/{@code expected}. An empty map
     * matches any active object of that kind in scope, degenerating to {@link #hasActive}.
     */
    @Override
    public boolean hasActiveMatching(ObjectType kind, String scope, Map<String, String> matchAttributes) {
        if (matchAttributes == null || matchAttributes.isEmpty()) return hasActive(kind, scope);
        return service.active(kind, scope).stream().anyMatch(o -> {
            Map<String, String> attrs = o.attributes();
            for (Map.Entry<String, String> e : matchAttributes.entrySet()) {
                if (!e.getValue().equals(attrs.get(e.getKey()))) return false;
            }
            return true;
        });
    }

    @Override
    public String open(ObjectType kind, String title, String description, String severity,
                       String scope, Map<String, String> attributes) {
        return service.open(kind, title, description, severity, scope, attributes).id();
    }

    @Override
    public void link(String fromId, String toId, String relationship, String actor) {
        service.link(fromId, toId, relationship, actor);
    }

    @Override
    public void addTag(String tag, String targetKind, String targetId, String actor) {
        service.tagAssignments().add(TagAssignment.of(tag, targetKind, targetId, actor));
    }

    @Override
    public List<String> tagsOf(String targetKind, String targetId) {
        return service.tagAssignments().tagsOf(targetKind, targetId);
    }

    @Override
    public List<String> targetIdsForTag(String tag, String targetKind) {
        return service.tagAssignments().forTag(tag).stream()
                .filter(a -> targetKind.equals(a.targetKind()))
                .map(TagAssignment::targetId)
                .toList();
    }

    /** The flat projection core's SEC-7d gate reads — deliberately not the whole record. */
    @Override
    public Optional<Map<String, Object>> summary(String objectId) {
        return service.get(objectId).map(o -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", o.id());
            m.put("correlationId", o.correlationId());
            m.put("owner", o.owner());
            m.put("assignee", o.assignee());
            m.put("attributes", o.attributes());
            return m;
        });
    }

    /**
     * The gap / conservation-imbalance → ALERT promotion, which core registers on its {@code EventLog}
     * instead of constructing {@code new EventObjectBridge(...)} itself.
     *
     * <p>⛔ Absent this module there is simply no subscriber, so the events are still recorded and nothing
     * promotes them — which is exactly the amended EDITIONS {@code SP-CTL-02} contract for Personal.
     */
    @Override
    public Optional<Consumer<Event>> eventSubscriber() {
        EventObjectBridge bridge = new EventObjectBridge(service);
        return Optional.of(bridge::onEvent);
    }
}
