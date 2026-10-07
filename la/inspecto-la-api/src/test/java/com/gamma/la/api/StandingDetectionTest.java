package com.gamma.la.api;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** LD-2/LD-3: the pure re-decision of a standing-detection sweep's authority (no PDP registered: ABSTAIN). */
class StandingDetectionTest {

    private static final Map<String, Object> MASK = Map.of("mode", "typed", "columns", List.of());

    private static StandingDetection.Authority authority() {
        return new StandingDetection.Authority("sweep:case-a", "alice", Set.of("canManageIncidents"), null, Map.of(),
                "calls_ds", MASK, "alice", "2026-10-04T00:00:00Z");
    }

    private static StandingDetection.Live live(Map<String, Object> dataset, boolean lead, Map<String, Object> mask) {
        return new StandingDetection.Live("alice", "calls_ds", dataset, lead, mask, null, Map.of("id", "case-a"));
    }

    private static Map<String, Object> shared(Object... shares) {
        return Map.of("owner", "bob", "shares", List.of(shares));
    }

    private static Map<String, Object> share(String type, String id) {
        return Map.of("subjectType", type, "subjectId", id, "access", "view");
    }

    private static String code(StandingDetection.Verdict v) {
        return v.allowed() ? "ALLOWED" : v.code();
    }

    @Test
    void anUnrestrictedOrUserSharedDatasetIsAllowed() {
        assertTrue(StandingDetection.decide(authority(), live(Map.of("view", "v"), true, MASK)).allowed());
        assertTrue(StandingDetection.decide(authority(), live(shared(share("user", "alice")), true, MASK)).allowed());
    }

    @Test
    void everyChangeSinceEnableRefusesWithItsOwnCode() {
        assertEquals("NOT_ENABLED", code(StandingDetection.decide(null, live(Map.of(), true, MASK))));
        assertEquals("DATASET_GONE", code(StandingDetection.decide(authority(), live(null, true, MASK))));
        assertEquals("DATASET_NOT_SHARED", code(StandingDetection.decide(authority(), live(shared(share("user", "carol")), true, MASK))));
        assertEquals("ROLE_SHARE_ONLY", code(StandingDetection.decide(authority(), live(shared(share("role", "analyst")), true, MASK))));
        assertEquals("NOT_LEAD", code(StandingDetection.decide(authority(), live(Map.of(), false, MASK))));
        assertEquals("UNDECIDABLE", code(StandingDetection.decide(authority(), live(Map.of(), true, null))),
                "a masking basis that cannot be computed is a refusal, not a pass");
        assertEquals("UNDECIDABLE", code(StandingDetection.decide(authority(), null)));
        assertEquals("BINDING_CHANGED", code(StandingDetection.decide(authority(),
                new StandingDetection.Live("mallory", "calls_ds", Map.of(), true, MASK, null, Map.of()))), "a new owner");
        assertEquals("BINDING_CHANGED", code(StandingDetection.decide(authority(),
                new StandingDetection.Live("alice", "other_ds", Map.of(), true, MASK, null, Map.of()))), "a new Dataset");
    }

    @Test
    void aBindingWithNoOwnerCannotBeDecided() {
        StandingDetection.Authority blank = new StandingDetection.Authority("sweep:x", " ", Set.of(), null, Map.of(),
                "calls_ds", MASK, "x", "t");
        assertEquals("NO_OWNER", code(StandingDetection.decide(blank, live(Map.of(), true, MASK))));
    }

    @Test
    void theAdminHatchIsNeverHonouredEvenWhenRecorded() {
        StandingDetection.Authority admin = new StandingDetection.Authority("sweep:case-a", "alice",
                Set.of("canConfigureAccess"), null, Map.of(), "calls_ds", MASK, "alice", "t");
        assertEquals("DATASET_NOT_SHARED", code(StandingDetection.decide(admin, live(shared(), true, MASK))),
                "a capability the sweep cannot confirm live would exceed the owner's live authority");
    }

    @Test
    void onlyTighteningStopsASweep() {
        Map<String, Object> typedNone = Map.of("mode", "typed", "columns", List.of());
        assertNull(StandingDetection.tightened(typedNone, typedNone));
        assertNull(StandingDetection.tightened(Map.of("mode", "all", "columns", List.of("a:msisdn")), typedNone),
                "loosening: the sweep returns aggregates only, so it keeps running");
        assertTrue(StandingDetection.tightened(typedNone, Map.of("mode", "all", "columns", List.of())).contains("rose"));
        assertTrue(StandingDetection.tightened(typedNone, Map.of("mode", "typed", "columns", List.of("caller:msisdn")))
                .contains("caller:msisdn"), "a newly masked column");
        assertTrue(StandingDetection.tightened(typedNone,
                Map.of("mode", "typed", "columns", List.of("(untraceable lineage):unknown"))).contains("untraceable"));
        assertTrue(StandingDetection.tightened(typedNone, Map.of("mode", "bogus", "columns", List.of())).contains("rose"),
                "an unknown mode counts as the strictest");
        assertEquals("MASKING_TIGHTENED", code(StandingDetection.decide(authority(), live(Map.of(), true,
                Map.of("mode", "all", "columns", List.of())))));
    }

    @Test
    void theAuthorityRoundTripsThroughItsBindingAndAMalformedOneIsAbsent() {
        Map<String, Object> binding = new java.util.LinkedHashMap<>(Map.of("rule", "r"));
        binding.put(StandingDetection.KEY, authority().toMap());
        assertEquals(authority(), StandingDetection.Authority.from(binding).orElseThrow());
        assertFalse(StandingDetection.Authority.from(Map.of("rule", "r")).isPresent());
        assertFalse(StandingDetection.Authority.from(Map.of(StandingDetection.KEY, "yes")).isPresent());
        assertFalse(StandingDetection.Authority.from(Map.of(StandingDetection.KEY, Map.of("ownerId", "alice"))).isPresent(),
                "no masking snapshot, so no basis to compare: fail closed");
    }

    @Test
    void theSyntheticSubjectHoldsExactlyWhatWasRecordedAndIsTaggedAsTheSweep() {
        var s = StandingDetection.synthetic(new StandingDetection.Authority("sweep:case-a", "alice",
                Set.of("canManageIncidents"), Set.of("fraud"), Map.of("space", "s1"), "ds", MASK, "alice", "t"));
        assertEquals("alice", s.id());
        assertEquals(Set.of("canManageIncidents"), s.capabilities());
        assertEquals(Set.of("fraud"), s.dataScopes());
        assertEquals("sweep:case-a", s.attributes().get("sweepPrincipal"));
        assertEquals("s1", s.attributes().get("space"));
    }
}
