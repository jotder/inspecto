package com.gamma.ops;

import com.gamma.event.Event;
import com.gamma.event.EventLevel;
import com.gamma.event.EventLog;
import com.gamma.event.EventQuery;
import com.gamma.event.EventType;
import com.gamma.event.InMemoryEventStore;
import com.gamma.objects.ObjectType;
import com.gamma.pipeline.ComponentStore;
import com.gamma.util.JsonAttributes;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ASSURE-WORKFLOW-SLA-1 — the authored governance an {@link ObjectService} reads from its registry: a Workflow that
 * hot-reloads and still cannot finish an Incident around the resolution gate, SLA deadlines stamped from a business
 * calendar, and Escalation Rules that fire exactly once per breach.
 */
class GovernanceSweepTest {

    private static final ZoneId LONDON = ZoneId.of("Europe/London");

    @TempDir Path registry;
    private InMemoryEventStore events;
    private InMemoryObjectStore store;
    private ObjectService svc;
    private ComponentStore components;

    @BeforeEach
    void setUp() {
        events = new InMemoryEventStore();
        EventLog.global().installStore(events);
        store = new InMemoryObjectStore();
        svc = new ObjectService(store);
        svc.useGovernance(registry);
        components = new ComponentStore(registry);
    }

    private List<Event> eventsFor(String type, String objectId) {
        return events.query(EventQuery.builder().type(type).limit(10_000).build()).stream()
                .filter(e -> objectId.equals(e.attributes().get("objectId"))).toList();
    }

    private static Map<String, Object> t(String from, String to, String action) {
        return Map.of("from", from, "to", to, "action", action);
    }

    private static Map<String, Object> incidentWorkflow(List<Map<String, Object>> moves, List<String> terminal) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("objectType", "INCIDENT");
        m.put("initial", "NEW");
        m.put("terminal", terminal);
        m.put("transitions", moves);
        return m;
    }

    private static long at(String local) {
        return LocalDateTime.parse(local).atZone(LONDON).toInstant().toEpochMilli();
    }

    private OperationalObject stored(String id, String status, String priority, long createdAt, Map<String, String> attrs) {
        return store.create(new OperationalObject(id, ObjectType.INCIDENT, id, "d", status, "HIGH", priority, null,
                "alice", "corr", attrs, createdAt, createdAt, 0L, 0L));
    }

    private static Map<String, String> postmortem() {
        return Map.of("postmortem", JsonAttributes.toPayloadJson(Map.of(
                "timeline", List.of(Map.of("time", "10:00", "text", "detected")),
                "causeAnalysis", List.of("root cause"),
                "actions", List.of(Map.of("text", "patch")))),
                ObjectService.ATTR_DUE_AT, Long.toString(System.currentTimeMillis() + 3_600_000));
    }

    // ── Workflow: hot reload + gates ───────────────────────────────────────────────────────────────

    @Test
    void aSavedWorkflowTakesEffectWithoutARestartAndAnEditOrDeleteDoesToo() throws Exception {
        assertEquals("IDENTIFIED", svc.workflow(ObjectType.INCIDENT).initialState(), "built-in before any file");
        components.write("workflow", "incident", incidentWorkflow(List.of(
                t("NEW", "RESOLVED", "resolve"), t("RESOLVED", "ARCHIVED", "archive"), t("NEW", "ARCHIVED", "archive")),
                List.of("ARCHIVED")));
        assertEquals("NEW", svc.workflow(ObjectType.INCIDENT).initialState(), "picked up on the next read");
        assertEquals("NEW", svc.open(ObjectType.INCIDENT, "t", "d", "HIGH", null, Map.of()).status());

        components.write("workflow", "incident", incidentWorkflow(List.of(
                t("NEW", "TRIAGE", "triage"), t("TRIAGE", "RESOLVED", "resolve"), t("RESOLVED", "ARCHIVED", "archive"),
                t("NEW", "ARCHIVED", "archive")), List.of("ARCHIVED")));
        assertTrue(svc.workflow(ObjectType.INCIDENT).states().contains("TRIAGE"), "an edit is picked up too");

        components.delete("workflow", "incident");
        assertEquals("IDENTIFIED", svc.workflow(ObjectType.INCIDENT).initialState(), "delete falls back to the built-in");
    }

    @Test
    void aHandPlantedWorkflowThatSkipsTheGateIsNotServed() throws Exception {
        // written straight to disk, past the save-time validator: NEW -close-> CLOSED goes around RESOLVED
        components.write("workflow", "incident", incidentWorkflow(List.of(
                t("NEW", "CLOSED", "close"), t("NEW", "RESOLVED", "resolve"), t("RESOLVED", "CLOSED", "close")),
                List.of("CLOSED")));
        assertEquals("IDENTIFIED", svc.workflow(ObjectType.INCIDENT).initialState(),
                "an invalid file is skipped at load — the built-in stays in force");
    }

    @Test
    void aBrokenEditKeepsTheLastValidAuthoredWorkflowInForce() throws Exception {
        components.write("workflow", "incident", incidentWorkflow(List.of(
                t("NEW", "RESOLVED", "resolve"), t("RESOLVED", "ARCHIVED", "archive"), t("NEW", "ARCHIVED", "archive")),
                List.of("ARCHIVED")));
        assertEquals("NEW", svc.workflow(ObjectType.INCIDENT).initialState());
        // a hand edit that fails validation (no terminal state, a dead end) — not served, and NOT a fallback either
        components.write("workflow", "incident", incidentWorkflow(List.of(t("NEW", "STUCK", "go")), List.of()));
        assertEquals("NEW", svc.workflow(ObjectType.INCIDENT).initialState(), "the last valid version stays in force");
        assertTrue(svc.workflow(ObjectType.INCIDENT).states().contains("RESOLVED"));
    }

    @Test
    void aCustomTerminalStateReachedThroughResolvedStillNeedsTheDispositionAndPostmortem() throws Exception {
        components.write("workflow", "incident", incidentWorkflow(List.of(
                t("NEW", "RESOLVED", "resolve"), t("RESOLVED", "CLOSED", "close"), t("NEW", "ARCHIVED", "archive"),
                t("RESOLVED", "ARCHIVED", "archive"), t("CLOSED", "NEW", "reopen")), List.of("CLOSED", "ARCHIVED")));
        OperationalObject bare = svc.open(ObjectType.INCIDENT, "no postmortem", "d", "HIGH", null, Map.of());
        IllegalStateException gaps = assertThrows(IllegalStateException.class,
                () -> svc.transition(bare.id(), "resolve", "mallory", "CONFIRMED"));
        assertTrue(gaps.getMessage().contains("timeline"), gaps.getMessage());

        OperationalObject o = svc.open(ObjectType.INCIDENT, "ready", "d", "HIGH", null, postmortem());
        IllegalStateException noDisposition = assertThrows(IllegalStateException.class,
                () -> svc.transition(o.id(), "resolve", "mallory"));
        assertTrue(noDisposition.getMessage().contains("disposition"), noDisposition.getMessage());
        svc.transition(o.id(), "resolve", "alice", "CONFIRMED");
        assertEquals("CLOSED", svc.transition(o.id(), "close", "alice").status());
        // archiving an undecided one is the only ungated finish, and it is stamped ARCHIVED_UNDECIDED
        assertEquals("ARCHIVED_UNDECIDED", svc.transition(bare.id(), "archive", "alice").attributes()
                .get(ObjectService.ATTR_DISPOSITION));
    }

    // ── SLA policy ─────────────────────────────────────────────────────────────────────────────────

    private void officePolicy() throws Exception {
        components.write("sla-policy", "incident", Map.of("objectType", "INCIDENT",
                "calendar", Map.of("zone", "Europe/London", "workingDays", List.of("MON", "TUE", "WED", "THU", "FRI"),
                        "start", "09:00", "end", "17:00", "holidays", List.of("2026-12-25")),
                "targets", List.of(Map.of("priority", "CRITICAL", "responseMinutes", 30, "resolutionMinutes", 120),
                        Map.of("priority", "*", "resolutionMinutes", 480))));
    }

    @Test
    void theSweepStampsDeadlinesFromThePolicyInWorkingTime() throws Exception {
        officePolicy();
        // Thursday 24 Dec 16:00, CRITICAL: response 16:30; resolution = 1h Thu + (Fri holiday, weekend) + 1h Mon → 10:00
        stored("c1", "IDENTIFIED", "CRITICAL", at("2026-12-24T16:00"), Map.of());
        svc.sweepIncidentSla(at("2026-12-24T16:05"));
        Map<String, String> a = store.get("c1").orElseThrow().attributes();
        assertEquals(Long.toString(at("2026-12-28T10:00")), a.get(ObjectService.ATTR_DUE_AT));
        assertEquals(Long.toString(at("2026-12-24T16:30")), a.get(ObjectService.ATTR_RESPONSE_DUE_AT));
        assertEquals("CRITICAL", a.get(ObjectService.ATTR_SLA_PRIORITY));
    }

    @Test
    void anOperatorSetDueAtIsLeftAloneAndAPriorityChangeRecomputesAPolicyStamp() throws Exception {
        officePolicy();
        long created = at("2026-10-05T09:00");   // a Monday
        stored("own", "IDENTIFIED", "CRITICAL", created, Map.of(ObjectService.ATTR_DUE_AT, "123"));
        stored("pol", "IDENTIFIED", "MINOR", created, Map.of());
        svc.sweepIncidentSla(created + 60_000);
        assertEquals("123", store.get("own").orElseThrow().attributes().get(ObjectService.ATTR_DUE_AT));
        assertEquals(Long.toString(at("2026-10-05T17:00")), store.get("pol").orElseThrow().attributes().get(ObjectService.ATTR_DUE_AT));
        svc.patch("pol", "CRITICAL", null, null, null);
        svc.sweepIncidentSla(created + 120_000);
        assertEquals(Long.toString(at("2026-10-05T11:00")), store.get("pol").orElseThrow().attributes().get(ObjectService.ATTR_DUE_AT));
    }

    @Test
    void aResponseBreachFiresOnceWhileTheIncidentSitsInItsInitialState() throws Exception {
        officePolicy();
        long created = at("2026-10-05T09:00");
        stored("slow", "IDENTIFIED", "CRITICAL", created, Map.of());
        stored("quick", "DIAGNOSING", "CRITICAL", created, Map.of());
        svc.sweepIncidentSla(at("2026-10-05T09:45"));
        svc.sweepIncidentSla(at("2026-10-05T09:50"));
        List<Event> slow = eventsFor(EventType.OBJECT_SLA_BREACH, "slow");
        assertEquals(1, slow.size());
        assertEquals("response", slow.get(0).attributes().get("target"));
        assertEquals(0, eventsFor(EventType.OBJECT_SLA_BREACH, "quick").size(), "left the initial state: responded");
    }

    // ── Escalation Rules ───────────────────────────────────────────────────────────────────────────

    @Test
    void anEscalationRuleFiresOncePerBreachHoweverOftenTheSweepRuns() throws Exception {
        components.write("escalation-rule", "page-duty", Map.of("objectType", "INCIDENT", "on", "breach",
                "reassign", "duty-manager", "notify", true, "raisePriority", true));
        long now = System.currentTimeMillis();
        stored("late", "IDENTIFIED", "MINOR", now - 7_200_000, Map.of(ObjectService.ATTR_DUE_AT, Long.toString(now - 60_000)));
        stored("fine", "IDENTIFIED", "MINOR", now - 7_200_000, Map.of(ObjectService.ATTR_DUE_AT, Long.toString(now + 3_600_000)));
        for (int i = 0; i < 3; i++) svc.sweepIncidentSla(now + i);

        OperationalObject late = store.get("late").orElseThrow();
        assertEquals("duty-manager", late.assignee());
        assertEquals("MAJOR", late.priority(), "one step up, once");
        assertEquals("true", late.attributes().get("escalated"));
        List<Event> escalated = eventsFor(EventType.OBJECT_ESCALATED, "late");
        assertEquals(1, escalated.size(), "idempotent across sweeps");
        assertEquals(EventLevel.WARN, escalated.get(0).level(), "notify: true is the notifiable level");
        assertEquals("escalation-rule:page-duty", escalated.get(0).attributes().get("actor"));
        assertEquals(1, eventsFor(EventType.OBJECT_ASSIGNED, "late").size(), "the reassignment is audited");
        assertEquals(0, eventsFor(EventType.OBJECT_ESCALATED, "fine").size());
        assertEquals("alice", store.get("fine").orElseThrow().assignee());
    }

    @Test
    void anAgeRuleFiresOnceAndRaisingPriorityStopsAtTheTop() throws Exception {
        components.write("escalation-rule", "old", Map.of("objectType", "INCIDENT", "on", "age", "afterMinutes", 60,
                "raisePriority", true));
        long now = System.currentTimeMillis();
        stored("aged", "DIAGNOSING", "CRITICAL", now - 7_200_000, Map.of());
        stored("young", "DIAGNOSING", "LOW", now - 60_000, Map.of());
        svc.sweepIncidentSla(now);
        svc.sweepIncidentSla(now + 1);
        assertEquals("CRITICAL", store.get("aged").orElseThrow().priority(), "never past CRITICAL");
        assertEquals(1, eventsFor(EventType.OBJECT_ESCALATED, "aged").size());
        assertEquals(EventLevel.INFO, eventsFor(EventType.OBJECT_ESCALATED, "aged").get(0).level());
        assertEquals("LOW", store.get("young").orElseThrow().priority());
    }

    @Test
    void escalationSkipsAResolvedIncidentAndHonoursItsPriorityFilter() throws Exception {
        components.write("escalation-rule", "crit", Map.of("objectType", "INCIDENT", "on", "breach",
                "priority", "CRITICAL", "reassign", "boss"));
        long now = System.currentTimeMillis();
        Map<String, String> due = Map.of(ObjectService.ATTR_DUE_AT, Long.toString(now - 1));
        stored("minor", "IDENTIFIED", "MINOR", now - 10_000, due);
        stored("done", "RESOLVED", "CRITICAL", now - 10_000, due);
        stored("crit", "IDENTIFIED", "CRITICAL", now - 10_000, due);
        svc.sweepIncidentSla(now);
        assertEquals("alice", store.get("minor").orElseThrow().assignee());
        assertEquals("alice", store.get("done").orElseThrow().assignee());
        assertEquals("boss", store.get("crit").orElseThrow().assignee());
    }

    @Test
    void theRegistryLoadsAtMostTheBoundedNumberOfRules() throws Exception {
        for (int i = 0; i < GovernanceRegistry.MAX_RULES + 5; i++)
            components.write("escalation-rule", String.format("r%03d", i), Map.of("objectType", "INCIDENT", "on", "age",
                    "afterMinutes", 1, "notify", true));
        assertEquals(GovernanceRegistry.MAX_RULES, new GovernanceRegistry(registry).snapshot().escalationRules().size());
        assertTrue(Files.isDirectory(registry.resolve("escalation-rules")));
    }

    @Test
    void escalationRuleParsingIsFailClosed() {
        for (Map<String, Object> bad : List.of(
                Map.<String, Object>of("objectType", "INCIDENT", "on", "breach"),                       // does nothing
                Map.<String, Object>of("objectType", "INCIDENT", "on", "sometimes", "notify", true),
                Map.<String, Object>of("objectType", "INCIDENT", "on", "age", "notify", true),          // no afterMinutes
                Map.<String, Object>of("objectType", "INCIDENT", "on", "breach", "target", "lunch", "notify", true),
                Map.<String, Object>of("objectType", "INCIDENT", "on", "breach", "reassign", "../etc", "notify", true),
                Map.<String, Object>of("on", "breach", "notify", true)))
            assertThrows(IllegalArgumentException.class, () -> com.gamma.objects.EscalationRule.fromComponent("x", new HashMap<>(bad)),
                    bad.toString());
    }

    // ── Cases are swept like Incidents (operator 2026-10-03) ───────────────────────────────────────

    private OperationalObject storedCase(String id, String status, String priority, long createdAt) {
        return store.create(new OperationalObject(id, ObjectType.CASE, id, "d", status, "HIGH", priority, null,
                "alice", "corr", Map.of(), createdAt, createdAt, 0L, 0L));
    }

    @Test
    void aCaseIsStampedBreachedAndEscalatedByItsOwnPolicyAndRulesOnly() throws Exception {
        components.write("sla-policy", "case", Map.of("objectType", "CASE",
                "calendar", Map.of("zone", "Europe/London", "workingDays", List.of("MON", "TUE", "WED", "THU", "FRI"),
                        "start", "09:00", "end", "17:00"),
                "targets", List.of(Map.of("priority", "CRITICAL", "responseMinutes", 30, "resolutionMinutes", 120))));
        components.write("escalation-rule", "case-late", Map.of("objectType", "CASE", "on", "breach",
                "reassign", "lead", "notify", true, "raisePriority", true));
        long created = at("2026-10-05T09:00");   // a Monday
        storedCase("k1", "OPEN", "CRITICAL", created);
        storedCase("done", "CLOSED", "CRITICAL", created);
        stored("inc", "IDENTIFIED", "CRITICAL", created, Map.of());   // no INCIDENT policy or rule: untouched

        svc.sweepIncidentSla(created + 60_000);
        Map<String, String> a = store.get("k1").orElseThrow().attributes();
        assertEquals(Long.toString(at("2026-10-05T11:00")), a.get(ObjectService.ATTR_DUE_AT));
        assertEquals(Long.toString(at("2026-10-05T09:30")), a.get(ObjectService.ATTR_RESPONSE_DUE_AT));

        svc.sweepIncidentSla(at("2026-10-05T09:45"));   // response breached: the Case still sits in OPEN
        assertEquals(1, eventsFor(EventType.OBJECT_SLA_BREACH, "k1").size());
        assertEquals("response", eventsFor(EventType.OBJECT_SLA_BREACH, "k1").get(0).attributes().get("target"));

        int breached = svc.sweepIncidentSla(at("2026-10-05T12:00"));
        svc.sweepIncidentSla(at("2026-10-05T12:05"));
        assertEquals(1, breached, "the resolution breach, once");
        assertEquals(2, eventsFor(EventType.OBJECT_SLA_BREACH, "k1").size(), "one response + one resolution, never repeated");
        OperationalObject k1 = store.get("k1").orElseThrow();
        assertEquals("lead", k1.assignee());
        assertEquals("true", k1.attributes().get("escalated"));
        assertEquals(1, eventsFor(EventType.OBJECT_ESCALATED, "k1").size(), "the rule fires once per breach");
        assertEquals(0, eventsFor(EventType.OBJECT_SLA_BREACH, "done").size(), "a closed Case's clock is stopped");
        assertNull(store.get("inc").orElseThrow().attributes().get(ObjectService.ATTR_DUE_AT));
    }
}
