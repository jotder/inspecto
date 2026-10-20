package com.gamma.control;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** {@link BypassCorpus}: deterministic, planted and benign sets disjoint, synthetic ids only. */
class BypassCorpusTest {

    @Test
    void theSeededGeneratorIsDeterministic() {
        BypassCorpus a = new BypassCorpus().generate(), b = new BypassCorpus().generate();
        assertEquals(a.files(true), b.files(true));
        assertEquals(a.filesRequiredOnly(), b.filesRequiredOnly());
        assertEquals(a.archetypes, b.archetypes);
        assertEquals(a.boxLike, b.boxLike);
    }

    @Test
    void theArchetypesOwnDisjointNumberBlocks() {
        BypassCorpus c = new BypassCorpus().generate();
        Set<String> seen = new HashSet<>();
        for (Map.Entry<String, Set<String>> e : c.archetypes.entrySet())
            for (String m : e.getValue()) assertTrue(seen.add(m), m + " belongs to two archetypes (second: " + e.getKey() + ")");
        assertTrue(c.boxLike.stream().noneMatch(c.ambiguous::contains), "planted and pinned-ambiguous are disjoint");
        Set<String> benign = new HashSet<>(seen);
        benign.removeAll(c.boxLike);
        benign.removeAll(c.ambiguous);
        assertTrue(benign.stream().noneMatch(c.boxLike::contains), "planted and benign are disjoint");
        assertEquals(60, c.archetypes.get("P1").size());
        assertEquals(200, c.archetypes.get("P2").size());
        assertEquals(20, c.archetypes.get("P3").size());
        assertEquals(80, c.archetypes.get("L7").size());
        for (String clause : BypassCorpus.CLAUSES) {
            assertEquals(1, c.archetypes.get(BypassCorpus.atClause(clause)).size(), clause);
            assertEquals(1, c.archetypes.get(BypassCorpus.insideClause(clause)).size(), clause);
        }
    }

    @Test
    void everyRowFallsInsideTheOneDay() {
        BypassCorpus c = new BypassCorpus().generate();
        Set<String> outside = new HashSet<>();
        for (BypassCorpus.Call call : c.calls()) {
            assertFalse(call.ts().isBefore(BypassCorpus.DAY.atStartOfDay()), call.id());
            if (!call.ts().isBefore(BypassCorpus.DAY.plusDays(1).atStartOfDay())) outside.add(call.id());
        }
        assertEquals(c.outsideWindow, outside, "exactly one deliberate row at midnight of the next day");
        assertEquals(1, outside.size());
    }

    @Test
    void everyNumberIsFictionalAndEveryDeviceIsInAReservedBlock() {
        BypassCorpus c = new BypassCorpus().generate();
        for (BypassCorpus.Call call : c.calls()) {
            assertTrue(call.a().startsWith("99971") || call.a().startsWith("28700"), "a_number " + call.a());
            if (!call.service().equals("DATA"))
                assertTrue(call.b().startsWith("99972") || call.b().startsWith("99971") || call.b().startsWith("99981") || call.b().startsWith("99982") || call.b().startsWith("287"),
                        "b_number " + call.b());
            assertTrue(call.imei().startsWith("990000"), "imei " + call.imei());
            assertTrue(call.imsi().startsWith("00101"), "imsi " + call.imsi());
            assertEquals(11, call.direction().equals("MO") ? call.a().length() : call.b().length(), call.id());
        }
    }
}
