package com.gamma.service;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** SE-1b (D-SE12): the facts one Space lock computation gives the activation holds. */
class ActivationFactsTest {

    private static Map<String, Object> file(String path, String kind, String id, String outside) {
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("path", path);
        f.put("kind", kind);
        f.put("id", id);
        if (outside != null) f.put("outside", Map.of("change", outside, "at", "2026-11-13T00:00:00Z"));
        return f;
    }

    private static Map<String, Object> link(String from, String fromKind, String key, String value, String status) {
        Map<String, Object> l = new LinkedHashMap<>();
        l.put("from", from);
        l.put("fromKind", fromKind);
        l.put("key", key);
        l.put("value", value);
        l.put("toKind", "dataset");
        l.put("status", status);
        return l;
    }

    @Test
    void aCleanSpaceHasNoFacts() {
        ActivationFacts f = ActivationFacts.of(List.of(file("a_pipeline.toon", "pipeline", "a", null)),
                List.of(link("a_pipeline.toon", "pipeline", "collector.dataset", "ds", "RESOLVED")));
        assertSame(ActivationFacts.NONE, f);
        assertNull(f.of("pipeline", "a"));
    }

    @Test
    void aDanglingLinkBelongsToTheFileThatHoldsIt() {
        ActivationFacts f = ActivationFacts.of(
                List.of(file("orders/Orders_pipeline.toon", "pipeline", "Orders", null),
                        file("j_job.toon", "job", "nightly", null),
                        file("e_enrich.toon", "enrichment", "e", null)),
                List.of(link("orders/Orders_pipeline.toon", "pipeline", "collector.dataset", "gone", "DANGLING"),
                        link("j_job.toon", "job", "dataset", "gone2", "DANGLING"),
                        link("e_enrich.toon", "enrichment", "triggers.on_pipeline", "x", "DANGLING")));
        ActivationFacts.Entry p = f.of("pipeline", "orders");
        assertNotNull(p, "the lookup is case-insensitive, as ids are");
        assertEquals("gone", p.dangling().get(0).get("value"));
        assertEquals("collector.dataset", p.dangling().get(0).get("key"));
        assertEquals("gone2", f.of("job", "nightly").dangling().get(0).get("value"));
        assertNull(f.of("enrichment", "e"), "only the two kinds the schedulers run");
        assertEquals(2, f.byObject().size());
    }

    @Test
    void anOutsideMarkIsAFactAndAnAcceptedFileHasNone() {
        ActivationFacts f = ActivationFacts.of(
                List.of(file("a_pipeline.toon", "pipeline", "a", "modified"), file("b_pipeline.toon", "pipeline", "b", null)),
                List.of());
        assertEquals(List.of(Map.of("path", "a_pipeline.toon", "change", "modified")), f.of("pipeline", "a").outside());
        assertTrue(f.of("pipeline", "a").dangling().isEmpty());
        assertNull(f.of("pipeline", "b"));
    }

    @Test
    void aPipelineNameReadsSpacesAsUnderscores() {
        ActivationFacts f = ActivationFacts.of(List.of(file("a_pipeline.toon", "pipeline", "my_flow", "added")), List.of());
        assertNotNull(f.of("pipeline", "My Flow"));
    }
}
