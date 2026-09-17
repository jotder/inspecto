package com.gamma.control;

import com.gamma.objects.ObjectAccess;
import com.gamma.objects.ObjectType;
import com.gamma.service.ObjectEngineProvider;
import com.gamma.service.SpaceRoot;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Core-test-scope fake {@link ObjectEngineProvider} — {@code EDITION-GATED-TESTS-IN-WRONG-HOME-1}'s
 * prerequisite for the three remaining moved tests (`ControlApiReconPromoteTest` here; see that class'
 * own note for why `ControlApiScopedObjectsTest` and `ControlApiAccessDeciderTest`'s row-scope case stay
 * in {@code inspecto-ops} instead).
 *
 * <p>Registered via {@code META-INF/services/com.gamma.service.ObjectEngineProvider} in this module's test
 * resources, so a default {@code mvn -o clean test} on {@code inspecto} discovers it and
 * {@code CollectorService.objects()} is non-empty — without pulling in the real {@code inspecto-ops}
 * {@code ObjectService}/DuckDB stores.
 *
 * <p>⚠ <b>Deliberately minimal, not a mirror of the real engine.</b> It implements exactly the
 * {@link ObjectAccess} contract a core route can use — in-memory, single Space, no persistence — plus one
 * test-only escape hatch ({@link FakeObjects#archive}) so a test can drive an object to a <b>terminal</b>
 * state, because {@code ReconRoutes.promote}'s dedupe is expressed entirely in terms of "non-terminal", and
 * a fake that could not produce a terminal object would be unable to pin that half of the contract at all.
 */
public final class FakeObjectEngineProvider implements ObjectEngineProvider {

    @Override
    public ObjectEngine open(SpaceRoot root, String dataDir) {
        return new Engine();
    }

    private static final class Engine implements ObjectEngine {
        private final FakeObjects access = new FakeObjects();

        @Override
        public ObjectAccess access() {
            return access;
        }

        @Override
        public void loadConfigs(List<java.nio.file.Path> configPaths) {
            // no ops config kinds to load — nothing this fake needs to honour
        }

        @Override
        public int adoptedTagAssignments() {
            return 0;
        }

        @Override
        public void sweepIncidentSla(long now) {
            // no SLA machinery in the fake
        }

        @Override
        public List<com.gamma.util.BrowsableStore> browsableStores() {
            return List.of();
        }

        @Override
        public void close() {
            // nothing held open
        }
    }

    /**
     * The in-memory {@link ObjectAccess}. Package-visible (not just to {@code Engine}) so test fixtures in
     * this package can downcast the seam back down — the same shape {@code TestOpsEngine} uses in
     * {@code inspecto-ops} to reach the real engine.
     */
    static final class FakeObjects implements ObjectAccess {

        /** One fake managed object: id, kind, scope (correlation id), attributes and terminal status. */
        record Fake(String id, ObjectType kind, String title, String description, String severity,
                    String scope, Map<String, String> attributes, String status, boolean terminal) {
            Fake archived() {
                return new Fake(id, kind, title, description, severity, scope, attributes, "ARCHIVED", true);
            }
        }

        private final Map<String, Fake> objects = new ConcurrentHashMap<>();

        @Override
        public boolean hasActive(ObjectType kind, String scope) {
            return hasActiveMatching(kind, scope, Map.of());
        }

        @Override
        public boolean hasActiveMatching(ObjectType kind, String scope, Map<String, String> matchAttributes) {
            return objects.values().stream().anyMatch(o -> matches(o, kind, scope, matchAttributes));
        }

        @Override
        public Map<String, String> activeAttributeIndex(ObjectType kind, String scope, String attribute) {
            if (attribute == null || attribute.isBlank()) return Map.of();
            Map<String, String> out = new LinkedHashMap<>();
            for (Fake o : objects.values()) {
                if (o.terminal() || o.kind() != kind || !java.util.Objects.equals(o.scope(), scope)) continue;
                String value = o.attributes().get(attribute);
                if (value != null && !value.isBlank()) out.putIfAbsent(value, o.id());
            }
            return out;
        }

        private static boolean matches(Fake o, ObjectType kind, String scope, Map<String, String> matchAttributes) {
            if (o.terminal() || o.kind() != kind || !java.util.Objects.equals(o.scope(), scope)) return false;
            if (matchAttributes == null || matchAttributes.isEmpty()) return true;
            for (Map.Entry<String, String> e : matchAttributes.entrySet()) {
                if (!e.getValue().equals(o.attributes().get(e.getKey()))) return false;
            }
            return true;
        }

        @Override
        public String open(ObjectType kind, String title, String description, String severity,
                           String scope, Map<String, String> attributes) {
            String id = kind.name() + "-" + UUID.randomUUID();
            objects.put(id, new Fake(id, kind, title, description, severity, scope,
                    attributes == null ? Map.of() : Map.copyOf(attributes), "IDENTIFIED", false));
            return id;
        }

        @Override
        public void link(String fromId, String toId, String relationship, String actor) {
            // not exercised by the moved test; no fake link store to keep it in
        }

        @Override
        public void addTag(String tag, String targetKind, String targetId, String actor) {
            // not exercised
        }

        @Override
        public void ensureTag(String name) {
            // not exercised
        }

        @Override
        public List<String> tagsOf(String targetKind, String targetId) {
            return List.of();
        }

        @Override
        public List<String> targetIdsForTag(String tag, String targetKind) {
            return List.of();
        }

        @Override
        public Optional<Map<String, Object>> summary(String objectId) {
            return Optional.ofNullable(objects.get(objectId)).map(FakeObjects::flatten);
        }

        @Override
        public List<Map<String, Object>> findByStatus(ObjectType kind, String status) {
            return objects.values().stream()
                    .filter(o -> o.kind() == kind && status.equals(o.status()))
                    .map(FakeObjects::flatten).toList();
        }

        @Override
        public Optional<java.util.function.Consumer<com.gamma.event.Event>> eventSubscriber() {
            return Optional.empty();
        }

        private static Map<String, Object> flatten(Fake o) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("kind", o.kind().name().toLowerCase(java.util.Locale.ROOT));
            m.put("id", o.id());
            m.put("correlationId", o.scope());
            m.put("attributes", o.attributes());
            return m;
        }

        // ── test-only surface, beyond ObjectAccess ───────────────────────────────────────

        /** Every object opened so far — the fake's answer to a full-table scan. */
        List<Fake> all() {
            return List.copyOf(objects.values());
        }

        Optional<Fake> get(String id) {
            return Optional.ofNullable(objects.get(id));
        }

        /** Drive {@code id} to the fake's one terminal state, mirroring the real engine's {@code ARCHIVED}. */
        void archive(String id) {
            objects.computeIfPresent(id, (k, v) -> v.archived());
        }
    }
}
