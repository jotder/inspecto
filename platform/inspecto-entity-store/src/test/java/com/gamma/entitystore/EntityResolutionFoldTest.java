package com.gamma.entitystore;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import com.gamma.entitystore.EntityFactLog;
import com.gamma.entitystore.EntityRegistry;

/**
 * LA-17 slice 2 (design §8.1) — the union-find fold over {@code identity.asserted} / {@code identity.retracted} facts.
 * The properties: merges are transitive, a retraction splits exactly what it joined, the result is independent of
 * assertion order, and an old position still reads as it was.
 */
class EntityResolutionFoldTest {

    private static EntityFactLog.Log assertIds(EntityFactLog log, EntityFactLog.Log head, String a, String b) throws Exception {
        return log.append(head, "an", "same SIM", "identity.asserted", null, Map.of("a", a, "b", b, "via", "analyst"));
    }

    private static EntityFactLog.Log retract(EntityFactLog log, EntityFactLog.Log head, long seq) throws Exception {
        return log.append(head, "an", "wrong", "identity.retracted", null, Map.of("assertionSeq", seq));
    }

    @Test
    void mergesAreTransitiveAndTheGroupIdIsTheSmallestKey(@TempDir Path root) throws Exception {
        EntityFactLog log = new EntityFactLog(root);
        EntityFactLog.Log h = assertIds(log, log.read(), "msisdn:+442", "imsi:9");        // seq 1
        h = assertIds(log, h, "msisdn:+442", "account:ZED");                                // seq 2
        h = assertIds(log, h, "wallet:W1", "handset:h1");                                   // seq 3 — separate
        h = assertIds(log, h, "account:ZED", "wallet:W1");                                  // seq 4 — bridges both
        h = log.append(h, "an", "open", "list.created", "wl", Map.of("title", "T", "purpose", "watch",
                "entityType", "msisdn", "normaliser", "e164"));                              // seq 5 — not identity

        Map<String, EntityRegistry.Group> g = EntityRegistry.resolve(log.read().facts(), h.headSeq());
        assertEquals(List.of("account:ZED"), List.copyOf(g.keySet()), "one group, id = smallest member");
        EntityRegistry.Group one = g.get("account:ZED");
        assertEquals(List.of("account:ZED", "handset:h1", "imsi:9", "msisdn:+442", "wallet:W1"), List.copyOf(one.members()));
        assertEquals(List.of(1L, 2L, 3L, 4L), List.copyOf(one.assertions()), "every joining assertion is listed");
        assertTrue(EntityRegistry.fold(log.read().facts(), h.headSeq()).containsKey("wl"), "lists fold beside identities");
        assertEquals(1, EntityRegistry.fold(log.read().facts(), h.headSeq()).size(), "identity facts are not lists");
    }

    @Test
    void aRetractionSplitsTheGroupItJoinedAndCannotRetractTwice(@TempDir Path root) throws Exception {
        EntityFactLog log = new EntityFactLog(root);
        EntityFactLog.Log h = assertIds(log, log.read(), "imsi:1", "msisdn:+441");   // 1
        h = assertIds(log, h, "msisdn:+441", "wallet:W");                             // 2
        h = retract(log, h, 1);                                                        // 3
        h = retract(log, h, 1);                                                        // 4 — ignored by the fold

        Map<String, EntityRegistry.Group> g = EntityRegistry.resolve(h.facts(), h.headSeq());
        assertEquals(List.of("msisdn:+441"), List.copyOf(g.keySet()), "imsi:1 left; it is in no live assertion");
        assertEquals(List.of("msisdn:+441", "wallet:W"), List.copyOf(g.get("msisdn:+441").members()));
        assertEquals(List.of(2L), List.copyOf(g.get("msisdn:+441").assertions()));
        EntityRegistry.Assertion first = EntityRegistry.assertions(h.facts(), h.headSeq()).get(1L);
        assertEquals(3, first.retractedBy(), "the FIRST retraction wins");
    }

    @Test
    void anOldPositionReadsAsItWas(@TempDir Path root) throws Exception {
        EntityFactLog log = new EntityFactLog(root);
        EntityFactLog.Log h = assertIds(log, log.read(), "imsi:1", "msisdn:+441");   // 1
        h = assertIds(log, h, "msisdn:+441", "wallet:W");                             // 2
        h = retract(log, h, 2);                                                        // 3

        assertEquals(0, EntityRegistry.resolve(h.facts(), 0).size(), "nothing at seq 0");
        assertEquals(List.of("imsi:1", "msisdn:+441"), List.copyOf(EntityRegistry.resolve(h.facts(), 1).get("imsi:1").members()));
        assertEquals(3, EntityRegistry.resolve(h.facts(), 2).get("imsi:1").members().size(), "merged at seq 2");
        assertEquals(2, EntityRegistry.resolve(h.facts(), 3).get("imsi:1").members().size(), "split again at seq 3");
        assertTrue(EntityRegistry.assertions(h.facts(), 2).get(2L).live(), "the retraction is not yet in force at seq 2");
    }

    @Test
    void theGroupsDoNotDependOnAssertionOrder(@TempDir Path r1, @TempDir Path r2) throws Exception {
        EntityFactLog l1 = new EntityFactLog(r1), l2 = new EntityFactLog(r2);
        EntityFactLog.Log h1 = assertIds(l1, l1.read(), "c:3", "b:2");
        h1 = assertIds(l1, h1, "b:2", "a:1");
        h1 = assertIds(l1, h1, "d:4", "e:5");
        EntityFactLog.Log h2 = assertIds(l2, l2.read(), "e:5", "d:4");
        h2 = assertIds(l2, h2, "a:1", "b:2");
        h2 = assertIds(l2, h2, "b:2", "c:3");

        Map<String, EntityRegistry.Group> g1 = EntityRegistry.resolve(h1.facts(), 3), g2 = EntityRegistry.resolve(h2.facts(), 3);
        assertEquals(List.of("a:1", "d:4"), List.copyOf(g1.keySet()));
        assertEquals(g1.keySet(), g2.keySet());
        for (String id : g1.keySet()) assertEquals(g1.get(id).members(), g2.get(id).members());
    }
}
