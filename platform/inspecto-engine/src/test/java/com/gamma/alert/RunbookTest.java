package com.gamma.alert;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** The {@code runbook} kind's structural validator, and the Alert Rule's {@code runbook:} link. */
class RunbookTest {

    private static Map<String, Object> valid() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("title", "IRSF runbook");
        m.put("summary", "What to do");
        m.put("steps", List.of(Map.of("text", "Open the evidence", "link", Map.of("kind", "dataset", "id", "fraud_irsf")),
                Map.of("text", "Bar the line")));
        m.put("ownerRole", "fraud-analyst");
        m.put("tags", List.of("fraud"));
        return m;
    }

    @Test
    void parsesTitleOrderedStepsLinksOwnerRoleAndTags() {
        Runbook r = Runbook.fromMap("fraud_irsf", valid());
        assertEquals("IRSF runbook", r.title());
        assertEquals(2, r.steps().size());
        assertEquals("Open the evidence", r.steps().get(0).text());
        assertEquals("dataset", r.steps().get(0).linkKind());
        assertEquals("fraud_irsf", r.steps().get(0).linkId());
        assertNull(r.steps().get(1).linkKind());
        assertEquals("fraud-analyst", r.ownerRole());
        assertEquals(List.of("fraud"), r.tags());
    }

    @Test
    void refusesMissingTitleEmptyStepsUnknownKeysAndBadLinks() {
        Map<String, Object> noTitle = valid(); noTitle.remove("title");
        assertTrue(assertThrows(IllegalArgumentException.class, () -> Runbook.fromMap("x", noTitle)).getMessage().contains("title"));
        Map<String, Object> noSteps = valid(); noSteps.put("steps", List.of());
        assertThrows(IllegalArgumentException.class, () -> Runbook.fromMap("x", noSteps));
        Map<String, Object> unknown = valid(); unknown.put("automate", "yes");
        assertTrue(assertThrows(IllegalArgumentException.class, () -> Runbook.fromMap("x", unknown)).getMessage().contains("automate"));
        Map<String, Object> badKind = valid();
        badKind.put("steps", List.of(Map.of("text", "t", "link", Map.of("kind", "pipeline", "id", "p"))));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> Runbook.fromMap("x", badKind)).getMessage().contains("link.kind"));
        Map<String, Object> blankText = valid(); blankText.put("steps", List.of(Map.of("text", " ")));
        assertThrows(IllegalArgumentException.class, () -> Runbook.fromMap("x", blankText));
    }

    @Test
    void keepsAnAuthorAnnotationAndTheR3Envelope() {
        Map<String, Object> m = valid();
        m.put("x-note", "kept");
        m.put("owner", "alice");
        assertDoesNotThrow(() -> Runbook.fromMap("x", m));
    }

    @Test
    void anAlertRuleCarriesItsRunbookThroughTheStoredShape() {
        AlertRule rule = AlertRule.fromMap(Map.of("name", "r", "metric", "error_rate", "threshold", 1,
                "window", "1h", "runbook", " fraud_irsf "));
        assertEquals("fraud_irsf", rule.runbook());
        assertTrue(rule.extra().isEmpty(), "runbook is modelled, not an unknown key");
        assertEquals("fraud_irsf", rule.toMap().get("runbook"));
        assertNull(AlertRule.fromMap(Map.of("name", "r", "metric", "error_rate", "threshold", 1, "window", "1h")).runbook());
    }
}
