package com.gamma.expectation;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An Expectation draft with no {@code kind} is refused with the kind message (the route answers 422), not a
 * {@code NullPointerException} from {@code Set.of(...).contains(null)} - which the first live golden run through the
 * Claude Code plugin hit three times as an HTTP 500 (2026-11-24).
 */
class ExpectationKindRequiredTest {

    @Test
    void aMissingKindIsAnIllegalArgumentNamingTheKinds() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> Expectation.fromMap(Map.of(
                "name", "shipment_id_present", "target", "shipments", "column", "shipment_id")));
        assertTrue(e.getMessage().startsWith("expectation.kind must be one of"), e.getMessage());
    }

    @Test
    void aNullBaselineMeasureIsRefusedNotAFault() {
        java.util.List<Object> measures = new java.util.ArrayList<>();
        measures.add(null);
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> Expectation.fromMap(Map.of(
                "name", "x", "kind", "baseline", "target", "shipments", "measures", measures, "maxIncrease", 50)));
        assertTrue(e.getMessage().startsWith("expectation.measures entries must be one of"), e.getMessage());
    }

    @Test
    void anUnknownKindIsRefusedTheSameWay() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> Expectation.fromMap(Map.of(
                "name", "x", "kind", "not_empty", "target", "shipments", "column", "shipment_id")));
        assertTrue(e.getMessage().startsWith("expectation.kind must be one of"), e.getMessage());
    }
}
