package com.gamma.workflow;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/** The pure SLA / escalation decisions over a {@link GovernedItem}: no store, no engine. */
class SlaDecisionsTest {

    private static final ZoneId LONDON = ZoneId.of("Europe/London");

    private static long at(String local) {
        return LocalDateTime.parse(local).atZone(LONDON).toInstant().toEpochMilli();
    }

    private static SlaPolicy policy() {
        return SlaPolicy.fromComponent("INCIDENT", Map.of(
                "objectType", "INCIDENT",
                "calendar", Map.of("zone", "Europe/London", "workingDays", List.of("MON", "TUE", "WED", "THU", "FRI"),
                        "start", "09:00", "end", "17:00"),
                "targets", List.of(Map.of("priority", "CRITICAL", "responseMinutes", 30, "resolutionMinutes", 240),
                        Map.of("priority", "*", "resolutionMinutes", 2400))));
    }

    private static GovernedItem item(String status, String priority, long createdAt, Map<String, String> attrs) {
        return new GovernedItem("o1", "INCIDENT", status, "HIGH", priority, "ann", createdAt, false, attrs);
    }

    @Test
    void stampCountsAfterHoursAndWeekendInTheBusinessCalendar() {
        // Friday 16:30 + 30m response = Friday 17:00; + 240m resolution runs over the weekend to Monday 12:30.
        Map<String, String> stamp = SlaDecisions.deadlineStamp(
                item("IDENTIFIED", "critical", at("2026-10-09T16:30"), Map.of()), policy());
        assertEquals(Long.toString(at("2026-10-09T17:00")), stamp.get(SlaDecisions.ATTR_RESPONSE_DUE_AT));
        assertEquals(Long.toString(at("2026-10-12T12:30")), stamp.get(SlaDecisions.ATTR_DUE_AT));
        assertEquals("CRITICAL", stamp.get(SlaDecisions.ATTR_SLA_PRIORITY));
        assertEquals("INCIDENT", stamp.get(SlaDecisions.ATTR_SLA_POLICY));
        // Saturday opened: the clock starts Monday 09:00.
        assertEquals(Long.toString(at("2026-10-12T09:30")), SlaDecisions.deadlineStamp(
                item("IDENTIFIED", "CRITICAL", at("2026-10-10T11:00"), Map.of()), policy()).get(SlaDecisions.ATTR_RESPONSE_DUE_AT));
    }

    @Test
    void stampLeavesAnOperatorDueAtABreachedItemAndAnUnchangedPriorityAlone() {
        long created = at("2026-10-05T09:00");
        assertNull(SlaDecisions.deadlineStamp(item("IDENTIFIED", "CRITICAL", created, Map.of("dueAt", "123")), policy()));
        assertNull(SlaDecisions.deadlineStamp(item("IDENTIFIED", "CRITICAL", created, Map.of("slaBreachedAt", "9")), policy()));
        assertNull(SlaDecisions.deadlineStamp(item("IDENTIFIED", "CRITICAL", created,
                Map.of("slaPolicy", "INCIDENT", "slaPriority", "CRITICAL", "dueAt", "5")), policy()));
        // A policy-stamped item whose priority changed is recomputed.
        assertNotNull(SlaDecisions.deadlineStamp(item("IDENTIFIED", "MINOR", created,
                Map.of("slaPolicy", "INCIDENT", "slaPriority", "CRITICAL", "dueAt", "5")), policy()));
    }

    @Test
    void breachDecisionsAreOnceOnly() {
        Workflow wf = Workflow.defaultFor(ObjectType.INCIDENT);
        long now = 10_000_000L;
        assertEquals(500L, SlaDecisions.resolutionBreachDue(item("IDENTIFIED", "MINOR", 0, Map.of("dueAt", "500")), now));
        assertEquals(0L, SlaDecisions.resolutionBreachDue(item("IDENTIFIED", "MINOR", 0, Map.of("dueAt", "500", "slaBreachedAt", "1")), now));
        assertEquals(0L, SlaDecisions.resolutionBreachDue(item("IDENTIFIED", "MINOR", 0, Map.of("dueAt", Long.toString(now + 1))), now));
        assertEquals(500L, SlaDecisions.responseBreachDue(item(wf.initialState(), "MINOR", 0, Map.of("responseDueAt", "500")), wf, now));
        assertEquals(0L, SlaDecisions.responseBreachDue(item("DIAGNOSING", "MINOR", 0, Map.of("responseDueAt", "500")), wf, now));
    }

    @Test
    void stopSetCoversClosedTerminalResolvedAndArchived() {
        Workflow wf = Workflow.defaultFor(ObjectType.INCIDENT);
        assertEquals(true, SlaDecisions.stopped(item("RESOLVED", "MINOR", 0, Map.of()), wf));
        assertEquals(true, SlaDecisions.stopped(item("archived", "MINOR", 0, Map.of()), wf));
        assertEquals(false, SlaDecisions.stopped(item("DIAGNOSING", "MINOR", 0, Map.of()), wf));
        assertEquals(true, SlaDecisions.stopped(new GovernedItem("x", "INCIDENT", "DIAGNOSING", null, null, null, 0, true, Map.of()), wf));
    }

    @Test
    void escalationFiresOncePerRulePerBreachAndHonoursTheMatcher() {
        EscalationRule rule = EscalationRule.fromComponent("r1", Map.of("objectType", "INCIDENT", "on", "breach", "notify", true));
        GovernedItem breached = item("IDENTIFIED", "MINOR", 0, Map.of("slaBreachedAt", "77"));
        SlaDecisions.Firing f = SlaDecisions.escalationFor(breached, rule, 1_000, (t, r) -> true);
        assertEquals("77", f.marker());
        assertEquals("r1@77", f.ledger());
        // The ledger now holds it: the next sweep decides nothing.
        GovernedItem fired = item("IDENTIFIED", "MINOR", 0, Map.of("slaBreachedAt", "77", "escalations", "r1@77"));
        assertNull(SlaDecisions.escalationFor(fired, rule, 1_000, (t, r) -> true));
        // No breach yet: nothing to answer.
        assertNull(SlaDecisions.escalationFor(item("IDENTIFIED", "MINOR", 0, Map.of()), rule, 1_000, (t, r) -> true));
        // A rule that narrows (priority sugar) consults the matcher with the context row.
        EscalationRule narrow = EscalationRule.fromComponent("r2", Map.of("objectType", "INCIDENT", "on", "breach",
                "priority", "CRITICAL", "notify", true));
        assertNull(SlaDecisions.escalationFor(breached, narrow, 1_000, (t, r) -> false));
        assertEquals("r1@77,r2@77", SlaDecisions.escalationFor(
                item("IDENTIFIED", "MINOR", 0, Map.of("slaBreachedAt", "77", "escalations", "r1@77")), narrow, 1_000, (t, r) -> true).ledger());
    }

    @Test
    void ageRuleFiresAtItsAgeAndTheContextHasNoDeadlineSentinel() {
        EscalationRule age = EscalationRule.fromComponent("a1", Map.of("objectType", "INCIDENT", "on", "age",
                "afterMinutes", 10, "raisePriority", true));
        assertNull(SlaDecisions.escalationFor(item("IDENTIFIED", "MINOR", 0, Map.of()), age, 9 * 60_000L, (t, r) -> true));
        assertEquals("age", SlaDecisions.escalationFor(item("IDENTIFIED", "MINOR", 0, Map.of()), age, 10 * 60_000L, (t, r) -> true).marker());
        Map<String, Object> row = SlaDecisions.escalationContext(item("identified", " minor ", 0, Map.of()), 120_000L);
        assertEquals((long) Integer.MAX_VALUE, row.get("minutesToDue"));
        assertEquals("IDENTIFIED", row.get("status"));
        assertEquals("minor", row.get("priority"));
        assertEquals(2L, row.get("ageMinutes"));
    }
}
