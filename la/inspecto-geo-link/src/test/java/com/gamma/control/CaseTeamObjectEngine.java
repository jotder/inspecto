package com.gamma.control;

import com.gamma.objects.ObjectAccess;
import com.gamma.workflow.ObjectType;
import com.gamma.service.ObjectEngineProvider;
import com.gamma.service.SpaceRoot;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * LA-24 test stand-in for the optional {@code inspecto-ops} module — which this module must never depend on.
 * Registered for this module's tests, but INERT: {@link #open} answers null (so {@code CollectorService.objects()}
 * is empty, exactly as a bundle without ops) unless a test has armed {@link #CASES} first. Only
 * {@link ObjectAccess#summary} is modelled — the one call Case-team sharing makes.
 */
public final class CaseTeamObjectEngine implements ObjectEngineProvider {

    /** Armed by a test: case id → the flat summary map. Null = ops absent. */
    static volatile Map<String, Map<String, Object>> CASES;

    static Map<String, Map<String, Object>> arm() {
        CASES = new ConcurrentHashMap<>();
        return CASES;
    }

    static Map<String, Object> caseOf(String id, String owner, String assignee, boolean closed) {
        return Map.of("kind", "case", "id", id, "correlationId", id, "owner", owner, "assignee", assignee,
                "attributes", Map.of(), "closed", closed);
    }

    @Override
    public ObjectEngine open(SpaceRoot root, String dataDir) {
        Map<String, Map<String, Object>> cases = CASES;
        if (cases == null) return null;
        ObjectAccess access = new ObjectAccess() {
            public Optional<Map<String, Object>> summary(String id) { return Optional.ofNullable(cases.get(id)); }
            public boolean hasActive(ObjectType kind, String scope) { return false; }
            public boolean hasActiveMatching(ObjectType kind, String scope, Map<String, String> m) { return false; }
            public Map<String, String> activeAttributeIndex(ObjectType kind, String scope, String a) { return Map.of(); }
            public String open(ObjectType k, String t, String d, String s, String sc, Map<String, String> a) {
                throw new UnsupportedOperationException();
            }
            public boolean transition(String id, String action, String actor) { return false; }
            public void link(String f, String t, String r, String a) { }
            public void addTag(String t, String k, String i, String a) { }
            public void ensureTag(String n) { }
            public List<String> tagsOf(String k, String i) { return List.of(); }
            public List<String> targetIdsForTag(String t, String k) { return List.of(); }
            public List<Map<String, Object>> findByStatus(ObjectType kind, String status) { return List.of(); }
        };
        return new ObjectEngine() {
            public ObjectAccess access() { return access; }
            public void loadConfigs(List<Path> configPaths) { }
            public int adoptedTagAssignments() { return 0; }
            public void sweepIncidentSla(long now) { }
            public List<com.gamma.util.BrowsableStore> browsableStores() { return List.of(); }
            public void close() { }
        };
    }
}
