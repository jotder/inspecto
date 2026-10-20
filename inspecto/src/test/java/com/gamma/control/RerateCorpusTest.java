package com.gamma.control;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** {@link RerateCorpus}: deterministic, planted and benign sets disjoint, synthetic ids only, the planted counts the golden pins. */
class RerateCorpusTest {

    @Test
    void theSeededGeneratorIsDeterministic() {
        RerateCorpus a = new RerateCorpus().generate(), b = new RerateCorpus().generate();
        assertEquals(a.files(true), b.files(true));
        assertEquals(a.files(false), b.files(false));
        assertEquals(a.planted, b.planted);
        assertEquals(a.plantedTariff, b.plantedTariff);
        assertEquals(a.archetypes, b.archetypes);
    }

    @Test
    void plantedAndBenignAreDisjointAndEveryArchetypeOwnsItsCalls() {
        RerateCorpus c = new RerateCorpus().generate();
        for (String k : c.planted.keySet()) assertFalse(c.benign.contains(k), k + " is both planted and benign");
        Set<String> seen = new HashSet<>();
        for (Map.Entry<String, Set<String>> e : c.archetypes.entrySet())
            for (String id : e.getValue()) assertTrue(seen.add(id), id + " belongs to two archetypes");
        assertEquals(c.usage().size(), seen.size(), "every call belongs to exactly one archetype");
        assertEquals(seen.size(), c.usage().stream().map(RerateCorpus.Usage::id).distinct().count(), "ids are unique");
        // each defect plan is owned by one archetype family: a planted call never sits on a plan another family uses for a different defect
        assertEquals(12, c.archetypes.get("B_AGREE").size(), "the twelve calls the starter control flags for ONE duplicate row");
        assertEquals(5, c.archetypes.get("A_STALE").size());
        assertEquals(9, c.archetypes.get("C_RANGE").size());
        assertEquals(8, c.archetypes.get("J_TOLERANCE").size());
    }

    @Test
    void thePlantedCountsAreThePinnedOnes() {
        RerateCorpus c = new RerateCorpus().generate();
        Map<String, Long> byClass = new java.util.TreeMap<>();
        for (String v : c.planted.values()) byClass.merge(v.substring(0, v.lastIndexOf('|')), 1L, Long::sum);
        assertEquals(Map.of("rate_mismatch|leakage", 41L, "rate_mismatch|overbilled", 59L, "ambiguous_tariff|data_quality", 9L, "no_tariff|data_quality", 11L,
                "unratable_call|data_quality", 3L), byClass);
        assertEquals(37, c.plantedTariff.size());
        assertEquals(4, c.outsideWindow.size(), "one second before the day, midnight after it, day 2's stale-version call and a wrong-plan subscriber's correct call");
    }

    @Test
    void everyCallIsInsideTheDayExceptTheFourDeliberateOnes() {
        RerateCorpus c = new RerateCorpus().generate();
        Set<String> outside = new HashSet<>();
        for (RerateCorpus.Usage u : c.usage()) {
            boolean in = !u.ts().isBefore(RerateCorpus.DAY.atStartOfDay()) && u.ts().isBefore(RerateCorpus.DAY.plusDays(1).atStartOfDay());
            if (!in) outside.add(u.id());
        }
        assertEquals(c.outsideWindow, outside);
    }

    @Test
    void everyNameIsFictionalAndEveryRateIsAMadeUpDecimal() {
        RerateCorpus c = new RerateCorpus().generate();
        for (RerateCorpus.Usage u : c.usage()) {
            assertTrue(u.plan().startsWith("PLAN_"), u.plan());
            assertTrue(u.subscriber().matches("SUB[0-9]{4}"), u.subscriber());
            assertTrue(u.id().startsWith("XWP_") == u.subscriber().compareTo("SUB9000") > 0, u.id() + ": a plan-inference call belongs to a SUB9xxx subscriber and no other call does");
            assertTrue(u.charge() == null || u.charge().scale() <= 4, u.id());
        }
        for (RerateCorpus.Tariff t : c.tariffs()) {
            assertTrue(t.plan().startsWith("PLAN_"), t.plan());
            if (t.rate() != null) assertTrue(new java.math.BigDecimal(t.rate()).scale() <= 4, t.id());
        }
    }
}
