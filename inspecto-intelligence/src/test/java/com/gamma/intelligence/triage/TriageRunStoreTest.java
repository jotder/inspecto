package com.gamma.intelligence.triage;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class TriageRunStoreTest {

    private static TriageRun sample(String id) {
        return new TriageRun(id, "incident:" + id, Map.of("type", "pipeline.batch.failed"),
                List.of(), List.of(), "open", List.of(), Instant.now());
    }

    @Test
    void recentReturnsNewestFirstCappedAtLimit() {
        TriageRunStore store = new TriageRunStore();
        store.add(sample("c1"));
        store.add(sample("c2"));
        store.add(sample("c3"));

        List<TriageRun> recent = store.recent(2);
        assertEquals(2, recent.size());
        assertEquals("c3", recent.get(0).id());
        assertEquals("c2", recent.get(1).id());
    }

    @Test
    void evictsOldestWhenOverCapacity() {
        TriageRunStore store = new TriageRunStore(2);
        store.add(sample("c1"));
        store.add(sample("c2"));
        store.add(sample("c3"));

        assertEquals(2, store.size());
        assertTrue(store.byId("c1").isEmpty(), "the oldest Triage Run is evicted");
        assertTrue(store.byId("c3").isPresent());
    }

    @Test
    void byIdReturnsEmptyForUnknownId() {
        TriageRunStore store = new TriageRunStore();
        store.add(sample("c1"));
        assertTrue(store.byId("nope").isEmpty());
    }

    @Test
    void toViewExposesEveryField() {
        Map<String, Object> view = sample("c1").toView();
        assertEquals("c1", view.get("id"));
        assertEquals("incident:c1", view.get("incidentRef"));
        assertEquals("open", view.get("outcome"));
        assertNotNull(view.get("createdAt"));
    }
}
