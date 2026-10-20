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
    void theB2ExtensionAddsOnlyTheTwoScoreArchetypesAndIsDeterministic() {
        BypassCorpus a = new BypassCorpus().generateB2(), b = new BypassCorpus().generateB2(), base = new BypassCorpus().generate();
        assertEquals(a.files(true), b.files(true));
        assertEquals(a.filesOnDay(1, a.archetypes.get("L2")), b.filesOnDay(1, b.archetypes.get("L2")));
        assertEquals(2, a.agreementTrap.size());
        Set<String> added = new HashSet<>(a.allPlanted());
        added.removeAll(base.allPlanted());
        Set<String> planted = new HashSet<>(a.agreementTrap);
        planted.addAll(a.roundClockEdge);
        assertEquals(planted, added, "B2 adds exactly X1 and X2");
        assertEquals(1, a.roundClockEdge.size());
        assertTrue(a.agreementTrap.stream().noneMatch(a.boxLike::contains) && a.agreementTrap.stream().noneMatch(a.ambiguous::contains));
        for (Map.Entry<String, Set<String>> e : base.archetypes.entrySet()) assertEquals(e.getValue(), a.archetypes.get(e.getKey()), e.getKey());
        assertEquals(base.boxLike, a.boxLike);
    }

    @Test
    void aLaterDayShiftsEveryRowAndRelocatesOnlyTheNamedLines() {
        BypassCorpus c = new BypassCorpus().generateB2();
        Set<String> moved = Set.of(c.archetypes.get("L2").iterator().next());
        Map<String, String> day2 = c.filesOnDay(1, moved);
        assertEquals(Set.of("voice_cdr/VOICE_CDR_20260702.csv"), day2.keySet());
        String[] rows = day2.values().iterator().next().split("\n");
        assertEquals(c.calls().size() + 1, rows.length, "a header and every row");
        Set<String> cellsOfMoved = new HashSet<>();
        for (int i = 1; i < rows.length; i++) {
            String[] f = rows[i].split(",", -1);
            assertTrue(f[0].startsWith("D1V") && f[1].startsWith("2026-07-0"), rows[i]);
            assertFalse(f[1].startsWith("2026-07-01"), "every row moved a day on: " + rows[i]);
            if (moved.contains(f[4]) && f[2].equals("MO")) cellsOfMoved.add(f[8]);
        }
        assertEquals(12, cellsOfMoved.size(), "the relocated line is served by the twelve neighbouring cells in turn");
        assertTrue(c.cellSite().contains("C3011,"), "the neighbouring cells are in the Reference feed from day one");
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
