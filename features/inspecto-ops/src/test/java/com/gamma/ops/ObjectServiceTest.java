package com.gamma.ops;

import com.gamma.workflow.ObjectType;

import com.gamma.audit.Event;
import com.gamma.event.EventLog;
import com.gamma.audit.EventQuery;
import com.gamma.audit.EventType;
import com.gamma.event.InMemoryEventStore;
import com.gamma.ops.link.ObjectLink;
import com.gamma.ops.note.NoteKind;
import com.gamma.ops.note.ObjectNote;
import com.gamma.objects.RcaTemplate;
import com.gamma.util.JsonAttributes;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The {@link ObjectService} orchestrator: opening an object in its initial state, walking the
 * workflow, the {@link EventType#OBJECT_OPENED}/{@link EventType#OBJECT_ACTIVITY} trail emitted onto
 * the shared {@link EventLog} (so the Event Viewer shows an object's history), illegal-move + unknown-id
 * guards, and the {@code active()} dedup helper. Events are filtered by the fresh object id so the
 * process-wide log shared across tests can't pollute the assertions.
 */
class ObjectServiceTest {

    private static List<Event> activityFor(InMemoryEventStore store, String type, String objectId) {
        return store.query(EventQuery.builder().type(type).limit(10_000).build()).stream()
                .filter(e -> objectId.equals(e.attributes().get("objectId"))).toList();
    }

    @Test
    void openWalkAndEmitActivity() {
        InMemoryEventStore events = new InMemoryEventStore();
        EventLog.global().installStore(events);
        ObjectService svc = new ObjectService(new InMemoryObjectStore());

        OperationalObject open = svc.open(ObjectType.ALERT, "disk full", "msg", "CRITICAL", "pipeX",
                Map.of("rule", "r1"));
        assertEquals("OPEN", open.status());
        assertEquals(1, activityFor(events, EventType.OBJECT_OPENED, open.id()).size());

        OperationalObject acked = svc.ack(open.id(), "alice");
        assertEquals("ACKNOWLEDGED", acked.status());
        assertEquals(0, acked.closedAt());

        OperationalObject resolved = svc.resolve(open.id(), "bob");
        assertEquals("RESOLVED", resolved.status());
        assertTrue(resolved.isClosed(), "resolve is terminal → closedAt set");

        List<Event> activity = activityFor(events, EventType.OBJECT_ACTIVITY, open.id());
        assertEquals(2, activity.size(), "ack + resolve each recorded");
        assertEquals("RESOLVED", activity.get(0).attributes().get("to"), "newest-first: resolve");
        assertEquals("bob", activity.get(0).attributes().get("actor"));
        assertEquals("ack", activity.get(1).attributes().get("action"));
    }

    @Test
    void illegalTransitionRejected() {
        ObjectService svc = new ObjectService(new InMemoryObjectStore());
        OperationalObject o = svc.open(ObjectType.ALERT, "t", "d", "INFO", null, Map.of());
        svc.resolve(o.id(), null);   // OPEN -> RESOLVED (terminal)
        assertThrows(IllegalStateException.class, () -> svc.ack(o.id(), null),
                "cannot ack a resolved alert");
    }

    @Test
    void unknownIdThrows() {
        ObjectService svc = new ObjectService(new InMemoryObjectStore());
        assertThrows(NoSuchElementException.class, () -> svc.transition("nope", "ack", null));
        assertThrows(NoSuchElementException.class, () -> svc.transitionTo("nope", "RESOLVED", null));
        assertTrue(svc.get("nope").isEmpty());
    }

    @Test
    void activeExcludesTerminalForDedup() {
        ObjectService svc = new ObjectService(new InMemoryObjectStore());
        OperationalObject a = svc.open(ObjectType.ALERT, "t", "d", "INFO", "pipe", Map.of("rule", "r"));
        assertEquals(1, svc.active(ObjectType.ALERT, "pipe").size());
        svc.resolve(a.id(), null);
        assertTrue(svc.active(ObjectType.ALERT, "pipe").isEmpty(), "resolved is no longer active");
    }

    @Test
    void activePushesOpenOnlyIntoTheStoreQuery() {
        // A recording proxy: every query the service sends, and every row the store hands back.
        InMemoryObjectStore real = new InMemoryObjectStore();
        List<ObjectQuery> queries = new java.util.ArrayList<>();
        List<Object> rowsRead = new java.util.ArrayList<>();
        ObjectStore store = (ObjectStore) java.lang.reflect.Proxy.newProxyInstance(
                ObjectStore.class.getClassLoader(), new Class<?>[]{ObjectStore.class}, (p, m, args) -> {
                    Object r;
                    try { r = m.invoke(real, args); }
                    catch (java.lang.reflect.InvocationTargetException e) { throw e.getCause(); }
                    if (m.getName().equals("query")) { queries.add((ObjectQuery) args[0]); rowsRead.addAll((List<?>) r); }
                    return r;
                });
        ObjectService svc = new ObjectService(store);
        for (int i = 0; i < 3; i++)
            svc.resolve(svc.open(ObjectType.ALERT, "t", "d", "INFO", "pipe", Map.of("rule", "r")).id(), null);
        OperationalObject open = svc.open(ObjectType.ALERT, "t", "d", "INFO", "pipe", Map.of("rule", "r"));
        queries.clear(); rowsRead.clear();

        List<OperationalObject> active = svc.active(ObjectType.ALERT, "pipe");
        assertEquals(List.of(open.id()), active.stream().map(OperationalObject::id).toList());
        assertTrue(queries.stream().allMatch(ObjectQuery::openOnly), "open-only reaches the store");
        assertEquals(1, rowsRead.size(), "the 3 resolved alerts are never read");
    }

    @Test
    void transitionToValidatesNeighbour() {
        ObjectService svc = new ObjectService(new InMemoryObjectStore());
        OperationalObject o = svc.open(ObjectType.ALERT, "t", "d", "INFO", null, Map.of());
        assertEquals("ACKNOWLEDGED", svc.transitionTo(o.id(), "ACKNOWLEDGED", "x").status());
        // RESOLVED is reachable from ACKNOWLEDGED; OPEN is not reachable from ACKNOWLEDGED.
        assertThrows(IllegalStateException.class, () -> svc.transitionTo(o.id(), "OPEN", "x"));
    }

    // ── Phase 3: INCIDENT lifecycle + SLA sweep ────────────────────────────────────────

    /** A complete I1 postmortem blob (timeline + cause analysis + actions), paired with {@code ATTR_DUE_AT} and the
     *  Disposition WS-10 requires — everything an Incident needs to resolve. */
    private static Map<String, String> completePostmortemAttrs(long dueAt) {
        Map<String, Object> postmortem = Map.of(
                "timeline", List.of(Map.of("time", "10:00", "text", "detected")),
                "causeAnalysis", List.of("root cause found"),
                "actions", List.of(Map.of("done", false, "text", "patch job", "owner", "alice", "due", "")));
        return Map.of(
                "postmortem", JsonAttributes.toPayloadJson(postmortem),
                ObjectService.ATTR_DUE_AT, Long.toString(dueAt),
                ObjectService.ATTR_DISPOSITION, "CONFIRMED");
    }

    /** WS-10: an Incident resolves only with a Disposition from the ladder — given with the move or already set. */
    @Test
    void anIncidentResolvesOnlyWithADispositionFromTheLadder() {
        ObjectService svc = new ObjectService(new InMemoryObjectStore());
        Map<String, String> noDisposition = new java.util.HashMap<>(completePostmortemAttrs(System.currentTimeMillis() + 60_000));
        noDisposition.remove(ObjectService.ATTR_DISPOSITION);
        OperationalObject o = svc.open(ObjectType.INCIDENT, "late feed", "d", "HIGH", "MAJOR", null, null, "c", noDisposition);

        IllegalStateException missing = assertThrows(IllegalStateException.class,
                () -> svc.transition(o.id(), "resolve", "alice"));
        assertTrue(missing.getMessage().contains("disposition"), missing.getMessage());
        assertThrows(IllegalArgumentException.class, () -> svc.transition(o.id(), "resolve", "alice", "MAYBE"),
                "off the ladder");
        assertEquals("IDENTIFIED", svc.get(o.id()).orElseThrow().status(), "a refused resolve writes nothing");
        assertNull(svc.get(o.id()).orElseThrow().attributes().get(ObjectService.ATTR_DISPOSITION));

        OperationalObject resolved = svc.transition(o.id(), "resolve", "alice", "accepted_risk");
        assertEquals("RESOLVED", resolved.status());
        assertEquals("ACCEPTED_RISK", resolved.attributes().get(ObjectService.ATTR_DISPOSITION), "normalised to the ladder's spelling");

        // reopening un-decides the outcome: the stale Disposition is cleared, so a re-resolve needs a fresh one
        OperationalObject reopened = svc.transition(o.id(), "reopen", "alice");
        assertEquals("", reopened.attributes().get(ObjectService.ATTR_DISPOSITION));
        IllegalStateException stale = assertThrows(IllegalStateException.class,
                () -> svc.transitionTo(o.id(), "RESOLVED", "alice"));
        assertTrue(stale.getMessage().contains("disposition"), stale.getMessage());
        assertEquals("RESOLVED", svc.transitionTo(o.id(), "RESOLVED", "alice", "RECOVERED").status());
    }

    /**
     * SEC-IMPORT-OPS-CONFIGS-1 (defence in depth): the resolution gate is keyed on what DECIDES an Incident, not on
     * the literal RESOLVED — a replaced workflow whose terminal state is CLOSED cannot finish an Incident without a
     * Disposition or a postmortem. The default lifecycle's ARCHIVED (the mail Trash, reachable from anywhere) stays
     * the one ungated terminal move, so a custom workflow buys nothing the default does not already allow.
     */
    @Test
    void aCustomTerminalStateCannotFinishAnIncidentWithoutTheResolutionGate() {
        ObjectService svc = new ObjectService(new InMemoryObjectStore());
        svc.registerWorkflow(new com.gamma.workflow.Workflow(ObjectType.INCIDENT, "IDENTIFIED",
                java.util.Set.of(new com.gamma.workflow.Workflow.Transition("IDENTIFIED", "CLOSED", "close"),
                        new com.gamma.workflow.Workflow.Transition("CLOSED", "IDENTIFIED", "reopen")),
                java.util.Set.of("CLOSED")));
        Map<String, String> noDisposition = new java.util.HashMap<>(completePostmortemAttrs(System.currentTimeMillis() + 60_000));
        noDisposition.remove(ObjectService.ATTR_DISPOSITION);
        OperationalObject o = svc.open(ObjectType.INCIDENT, "late feed", "d", "HIGH", "MAJOR", null, null, "c", noDisposition);

        IllegalStateException missing = assertThrows(IllegalStateException.class,
                () -> svc.transition(o.id(), "close", "mallory"));
        assertTrue(missing.getMessage().contains("disposition"), missing.getMessage());
        assertThrows(IllegalStateException.class, () -> svc.transitionTo(o.id(), "CLOSED", "mallory"), "the generic move too");
        assertEquals("IDENTIFIED", svc.get(o.id()).orElseThrow().status(), "a refused close writes nothing");

        OperationalObject bare = svc.open(ObjectType.INCIDENT, "no postmortem", "d", "HIGH", "MAJOR", null, null, "c", Map.of());
        IllegalStateException gaps = assertThrows(IllegalStateException.class,
                () -> svc.transition(bare.id(), "close", "mallory", "CONFIRMED"));
        assertTrue(gaps.getMessage().contains("timeline") && gaps.getMessage().contains("SLA"), gaps.getMessage());

        OperationalObject closed = svc.transition(o.id(), "close", "alice", "CONFIRMED");
        assertEquals("CLOSED", closed.status());
        assertTrue(closed.isClosed());
        assertEquals("CONFIRMED", closed.attributes().get(ObjectService.ATTR_DISPOSITION));
        // leaving the custom terminal state un-decides the outcome, as a reopen from RESOLVED does
        assertEquals("", svc.transition(o.id(), "reopen", "alice").attributes().get(ObjectService.ATTR_DISPOSITION));
    }

    /** ...and the default lifecycle is unchanged: archive-from-anywhere records no outcome and is not gated. */
    @Test
    void archivingAnUndecidedIncidentStaysUngated() {
        ObjectService svc = new ObjectService(new InMemoryObjectStore());
        OperationalObject o = svc.open(ObjectType.INCIDENT, "noise", "d", "HIGH", "MAJOR", null, null, "c", Map.of());
        assertEquals("ARCHIVED", svc.transition(o.id(), "archive", "alice").status());
        assertThrows(IllegalArgumentException.class, () -> svc.transition(
                svc.open(ObjectType.INCIDENT, "n2", "d", "HIGH", "MAJOR", null, null, "c", Map.of()).id(),
                "archive", "alice", "CONFIRMED"), "a Disposition still rides only a deciding move");
    }

    /** D-A (operator 2026-09-29): archiving an undecided Incident stamps ARCHIVED_UNDECIDED, and says so in the audit event. */
    @Test
    void archivingAnUndecidedIncidentStampsArchivedUndecided() {
        InMemoryEventStore events = new InMemoryEventStore();
        EventLog.global().installStore(events);
        ObjectService svc = new ObjectService(new InMemoryObjectStore());
        OperationalObject o = svc.open(ObjectType.INCIDENT, "noise", "d", "HIGH", "MAJOR", null, null, "c", Map.of());
        assertEquals("IDENTIFIED", o.status());
        OperationalObject archived = svc.transition(o.id(), "archive", "alice");
        assertEquals("ARCHIVED_UNDECIDED", archived.attributes().get(ObjectService.ATTR_DISPOSITION));
        assertEquals("ARCHIVED_UNDECIDED", activityFor(events, EventType.OBJECT_ACTIVITY, o.id()).get(0)
                .attributes().get("disposition"), "the stamp is on the transition's audit event");

        // a reopen from ARCHIVED clears it, as a reopen clears any Disposition
        assertEquals("", svc.transition(o.id(), "reopen", "alice").attributes().get(ObjectService.ATTR_DISPOSITION));
    }

    /** D-A: an Incident archived after a resolve keeps its real Disposition. */
    @Test
    void archivingAResolvedIncidentKeepsItsDisposition() {
        ObjectService svc = new ObjectService(new InMemoryObjectStore());
        OperationalObject o = svc.open(ObjectType.INCIDENT, "late feed", "d", "HIGH", "MAJOR", null, null, "c",
                completePostmortemAttrs(System.currentTimeMillis() + 60_000));
        svc.transition(o.id(), "resolve", "alice", "RECOVERED");
        assertEquals("RECOVERED", svc.transition(o.id(), "archive", "alice").attributes().get(ObjectService.ATTR_DISPOSITION));
    }

    /** D-A: ARCHIVED_UNDECIDED is stamped, never chosen — a resolve that sends it is refused and writes nothing. */
    @Test
    void aResolveCannotChooseArchivedUndecided() {
        ObjectService svc = new ObjectService(new InMemoryObjectStore());
        Map<String, String> noDisposition = new java.util.HashMap<>(completePostmortemAttrs(System.currentTimeMillis() + 60_000));
        noDisposition.remove(ObjectService.ATTR_DISPOSITION);
        OperationalObject o = svc.open(ObjectType.INCIDENT, "x", "d", "HIGH", "MAJOR", null, null, "c", noDisposition);
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> svc.transition(o.id(), "resolve", "alice", "archived_undecided"));
        assertTrue(refused.getMessage().contains("cannot be chosen"), refused.getMessage());
        assertEquals("IDENTIFIED", svc.get(o.id()).orElseThrow().status());
    }

    /** A Disposition rides only an Incident's resolve: a Case keeps its Disposition in its Findings. */
    @Test
    void aDispositionIsRefusedOnAnyOtherMove() {
        ObjectService svc = new ObjectService(new InMemoryObjectStore());
        OperationalObject inc = svc.open(ObjectType.INCIDENT, "x", "d", "HIGH", "MAJOR", null, null, "c", Map.of());
        assertThrows(IllegalArgumentException.class, () -> svc.transition(inc.id(), "accept", "a", "CONFIRMED"));
        OperationalObject cs = svc.open(ObjectType.CASE, "y", "d", "HIGH", "MAJOR", null, null, "c", Map.of());
        svc.transition(cs.id(), "investigate", "a");
        IllegalArgumentException onCase = assertThrows(IllegalArgumentException.class,
                () -> svc.transition(cs.id(), "resolve", "a", "CONFIRMED"));
        assertTrue(onCase.getMessage().contains("Findings"), onCase.getMessage());
        assertEquals("RESOLVED", svc.transition(cs.id(), "resolve", "a").status(), "a Case still resolves without one (soft, §6.2)");
    }

    @Test
    void incidentLifecycleWalk() {
        // The mail lifecycle (GLOSSARY §9): IDENTIFIED → DIAGNOSING → RESOLVED → ARCHIVED, with reopen.
        ObjectService svc = new ObjectService(new InMemoryObjectStore());
        OperationalObject o = svc.open(ObjectType.INCIDENT, "bad rows", "investigate", "HIGH", "P1",
                null, "alice", "pipeC", completePostmortemAttrs(System.currentTimeMillis() + 3_600_000));
        assertEquals("IDENTIFIED", o.status());
        assertEquals("P1", o.priority(), "the fuller open() carries priority");
        assertEquals("alice", o.assignee(), "the fuller open() carries assignee");

        assertEquals("DIAGNOSING", svc.transition(o.id(), "accept", "alice").status());
        OperationalObject resolved = svc.transition(o.id(), "resolve", "alice");
        assertEquals("RESOLVED", resolved.status());
        assertEquals(0, resolved.closedAt(), "RESOLVED is not terminal for an INCIDENT");
        OperationalObject archived = svc.transition(o.id(), "archive", "bob");
        assertEquals("ARCHIVED", archived.status());
        assertTrue(archived.isClosed(), "ARCHIVED is terminal → closedAt set");
        OperationalObject reopened = svc.transition(o.id(), "reopen", "carol");
        assertEquals("DIAGNOSING", reopened.status());
        assertFalse(reopened.isClosed(), "reopen clears closedAt — the incident is live again");
        assertThrows(IllegalStateException.class, () -> svc.transition(o.id(), "accept", null),
                "accept is only legal from IDENTIFIED");
    }

    // ── I1: incident resolution hard gate (backend follow-up to the UI soft-warn) ─────

    @Test
    void resolveBlockedWithoutPostmortemSections() {
        ObjectService svc = new ObjectService(new InMemoryObjectStore());
        OperationalObject o = svc.open(ObjectType.INCIDENT, "bad rows", "investigate", "HIGH", "P1",
                null, "alice", "pipeC", Map.of());
        svc.transition(o.id(), "accept", "alice");
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> svc.transition(o.id(), "resolve", "alice"),
                "no postmortem/dueAt set — resolution must be blocked");
        assertTrue(ex.getMessage().contains("timeline"), ex.getMessage());
        assertTrue(ex.getMessage().contains("cause analysis"), ex.getMessage());
        assertTrue(ex.getMessage().contains("corrective actions"), ex.getMessage());
        assertTrue(ex.getMessage().contains("SLA"), ex.getMessage());
        assertEquals("DIAGNOSING", svc.get(o.id()).orElseThrow().status(), "status must not have changed");
    }

    @Test
    void resolveBlockedWithPartialPostmortem() {
        ObjectService svc = new ObjectService(new InMemoryObjectStore());
        // dueAt set, but no postmortem sections at all.
        OperationalObject o = svc.open(ObjectType.INCIDENT, "bad rows", "d", "HIGH", "pipeC",
                Map.of(ObjectService.ATTR_DUE_AT, Long.toString(System.currentTimeMillis() + 3_600_000)));
        svc.transition(o.id(), "accept", null);
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> svc.transition(o.id(), "resolve", null));
        assertFalse(ex.getMessage().contains("SLA"), "dueAt was set, so SLA gap must not be reported");
        assertTrue(ex.getMessage().contains("timeline"));
    }

    @Test
    void resolveAllowedOncePostmortemComplete() {
        ObjectService svc = new ObjectService(new InMemoryObjectStore());
        OperationalObject o = svc.open(ObjectType.INCIDENT, "bad rows", "investigate", "HIGH", "P1",
                null, "alice", "pipeC", completePostmortemAttrs(System.currentTimeMillis() + 3_600_000));
        svc.transition(o.id(), "accept", "alice");
        assertEquals("RESOLVED", svc.transition(o.id(), "resolve", "alice").status());
    }

    @Test
    void resolutionGateOnlyAppliesToIncidents() {
        // ALERT and CASE also reach a RESOLVED status via "resolve" — the I1 gate is INCIDENT-only.
        ObjectService svc = new ObjectService(new InMemoryObjectStore());
        OperationalObject alert = svc.open(ObjectType.ALERT, "t", "d", "INFO", null, Map.of());
        assertEquals("RESOLVED", svc.resolve(alert.id(), null).status());
    }

    @Test
    void slaSweepBreachesOverdueUnresolvedIncidents() {
        InMemoryEventStore events = new InMemoryEventStore();
        EventLog.global().installStore(events);
        ObjectService svc = new ObjectService(new InMemoryObjectStore());

        long now = System.currentTimeMillis();
        OperationalObject overdue = svc.open(ObjectType.INCIDENT, "overdue", "d", "HIGH", "pipeD",
                Map.of(ObjectService.ATTR_DUE_AT, Long.toString(now - 60_000)));
        OperationalObject future = svc.open(ObjectType.INCIDENT, "not yet", "d", "LOW", "pipeE",
                Map.of(ObjectService.ATTR_DUE_AT, Long.toString(now + 3_600_000)));
        OperationalObject noSla = svc.open(ObjectType.INCIDENT, "no sla", "d", "LOW", "pipeF", Map.of());

        assertEquals(1, svc.sweepIncidentSla(now), "only the overdue incident breaches");
        assertEquals(1, activityFor(events, EventType.OBJECT_SLA_BREACH, overdue.id()).size());
        assertTrue(svc.get(overdue.id()).orElseThrow().attributes().containsKey(ObjectService.ATTR_SLA_BREACHED_AT));
        assertFalse(svc.get(future.id()).orElseThrow().attributes().containsKey(ObjectService.ATTR_SLA_BREACHED_AT));
        assertFalse(svc.get(noSla.id()).orElseThrow().attributes().containsKey(ObjectService.ATTR_SLA_BREACHED_AT));

        // idempotent: a second sweep at the same instant does not re-breach or re-emit
        assertEquals(0, svc.sweepIncidentSla(now));
        assertEquals(1, activityFor(events, EventType.OBJECT_SLA_BREACH, overdue.id()).size());
    }

    @Test
    void slaSweepIgnoresResolvedAndClosedIncidents() {
        ObjectService svc = new ObjectService(new InMemoryObjectStore());
        long now = System.currentTimeMillis();
        OperationalObject o = svc.open(ObjectType.INCIDENT, "fixed in time", "d", "HIGH", "pipeG",
                completePostmortemAttrs(now - 60_000));
        svc.transition(o.id(), "accept", "a");
        svc.transition(o.id(), "resolve", "a");   // RESOLVED — the SLA clock has stopped
        assertEquals(0, svc.sweepIncidentSla(now), "a resolved incident past its due time does not breach");
        svc.transition(o.id(), "archive", "a");    // ARCHIVED — still no breach
        assertEquals(0, svc.sweepIncidentSla(now));
    }

    /** An Incident written straight to the store — the fast seeding path, and the only way to hold a state
     *  with no {@code closedAt} (what a later workflow file or an import leaves behind). */
    private static OperationalObject stored(String id, String status, long createdAt, Map<String, String> attrs) {
        return new OperationalObject(id, ObjectType.INCIDENT, id, "d", status, "HIGH", "LOW", null, null, "corr",
                attrs, createdAt, createdAt, 0L, 0L);
    }

    /** IMPORT-RESIDUALS-1 (4): every terminal state of the Incident's registered Workflow stops the SLA clock. */
    @Test
    void slaSweepSkipsEveryWorkflowTerminalStateButStillBreachesOpenOnes() {
        com.gamma.workflow.Workflow custom = new com.gamma.workflow.Workflow(ObjectType.INCIDENT, "IDENTIFIED",
                java.util.Set.of(new com.gamma.workflow.Workflow.Transition("IDENTIFIED", "DIAGNOSING", "accept"),
                        new com.gamma.workflow.Workflow.Transition("DIAGNOSING", "WONTFIX", "dismiss"),
                        new com.gamma.workflow.Workflow.Transition("DIAGNOSING", "ARCHIVED", "archive")),
                java.util.Set.of("WONTFIX", "ARCHIVED"));
        InMemoryObjectStore store = new InMemoryObjectStore();
        ObjectService svc = new ObjectService(store, Map.of(ObjectType.INCIDENT, custom));
        long now = System.currentTimeMillis();
        Map<String, String> overdue = Map.of(ObjectService.ATTR_DUE_AT, Long.toString(now - 60_000));
        store.create(stored("custom-terminal", "WONTFIX", now - 120_000, overdue));   // closedAt 0 — the gap
        store.create(stored("archived", "ARCHIVED", now - 120_000, overdue));
        store.create(stored("resolved", "RESOLVED", now - 120_000, overdue));
        store.create(stored("open", "DIAGNOSING", now - 120_000, overdue));

        assertEquals(1, svc.sweepIncidentSla(now), "only the Incident still being worked breaches");
        assertTrue(svc.get("open").orElseThrow().attributes().containsKey(ObjectService.ATTR_SLA_BREACHED_AT));
        for (String id : List.of("custom-terminal", "archived", "resolved"))
            assertFalse(svc.get(id).orElseThrow().attributes().containsKey(ObjectService.ATTR_SLA_BREACHED_AT), id);
    }

    /** The sweep reads every Incident: one newest-first MAX_LIMIT page never reached the OLDEST overdue one. */
    @Test
    void slaSweepBreachesTheOldestOverdueIncidentBeyondOneQueryPage() {
        InMemoryObjectStore store = new InMemoryObjectStore();
        ObjectService svc = new ObjectService(store);
        long now = System.currentTimeMillis();
        store.create(stored("oldest", "IDENTIFIED", 1_000L, Map.of(ObjectService.ATTR_DUE_AT, Long.toString(now - 60_000))));
        for (int i = 0; i < ObjectQuery.MAX_LIMIT + 5; i++)
            store.create(stored("newer-" + i, "IDENTIFIED", 2_000L + i, Map.of()));
        assertEquals(1, svc.sweepIncidentSla(now));
        assertTrue(svc.get("oldest").orElseThrow().attributes().containsKey(ObjectService.ATTR_SLA_BREACHED_AT));
    }

    /** ARCHIVED stops the SLA clock even in a workflow where it is NOT terminal (the sweep's documented stop set). */
    @Test
    void slaSweepSkipsArchivedEvenWhereTheWorkflowDoesNotMakeItTerminal() {
        com.gamma.workflow.Workflow custom = new com.gamma.workflow.Workflow(ObjectType.INCIDENT, "IDENTIFIED",
                java.util.Set.of(new com.gamma.workflow.Workflow.Transition("IDENTIFIED", "ARCHIVED", "archive"),
                        new com.gamma.workflow.Workflow.Transition("ARCHIVED", "CLOSED", "close")),
                java.util.Set.of("CLOSED"));
        assertFalse(custom.isTerminal("ARCHIVED"));
        InMemoryObjectStore store = new InMemoryObjectStore();
        ObjectService svc = new ObjectService(store, Map.of(ObjectType.INCIDENT, custom));
        long now = System.currentTimeMillis();
        Map<String, String> overdue = Map.of(ObjectService.ATTR_DUE_AT, Long.toString(now - 60_000));
        store.create(stored("archived", "ARCHIVED", now - 120_000, overdue));
        store.create(stored("open", "IDENTIFIED", now - 120_000, overdue));
        assertEquals(1, svc.sweepIncidentSla(now));
        assertFalse(svc.get("archived").orElseThrow().attributes().containsKey(ObjectService.ATTR_SLA_BREACHED_AT));
        assertTrue(svc.get("open").orElseThrow().attributes().containsKey(ObjectService.ATTR_SLA_BREACHED_AT));
    }

    /**
     * An object inserted mid-walk with a createdAt BEHIND the cursor shifts the page boundary by one, so the
     * next OFFSET page re-serves the last object already seen. One walk yields each id once, and the sweep
     * emits exactly one breach for that boundary object.
     */
    @Test
    void aBackdatedInsertMidWalkNeitherDoubleCountsNorDoubleBreaches() {
        InMemoryEventStore events = new InMemoryEventStore();
        EventLog.global().installStore(events);
        long now = System.currentTimeMillis();
        int n = ObjectQuery.MAX_LIMIT + 3;
        String boundary = "inc-" + (ObjectQuery.MAX_LIMIT - 1);   // the last object of the first page
        java.util.concurrent.atomic.AtomicBoolean armed = new java.util.concurrent.atomic.AtomicBoolean();
        InMemoryObjectStore inner = new InMemoryObjectStore();
        ObjectStore store = new ObjectStore() {
            @Override public OperationalObject create(OperationalObject obj) { return inner.create(obj); }
            @Override public java.util.Optional<OperationalObject> get(String id) { return inner.get(id); }
            @Override public List<OperationalObject> query(ObjectQuery q) {
                if (q.offset() > 0 && armed.getAndSet(false))   // between page 1 and page 2: a backdated import
                    inner.create(stored("backdated-" + System.nanoTime(), "IDENTIFIED", 0L, Map.of()));
                return inner.query(q);
            }
            @Override public List<OperationalObject> findByAttributes(ObjectType t, Map<String, String> a, int l) {
                return inner.findByAttributes(t, a, l);
            }
            @Override public OperationalObject update(OperationalObject obj) { return inner.update(obj); }
            @Override public void delete(String id) { inner.delete(id); }
        };
        for (int i = 0; i < n; i++)
            inner.create(stored("inc-" + i, "IDENTIFIED", 1_000L + i, boundary.equals("inc-" + i)
                    ? Map.of(ObjectService.ATTR_DUE_AT, Long.toString(now - 60_000)) : Map.of()));
        ObjectService svc = new ObjectService(store);

        armed.set(true);
        List<String> ids = new java.util.ArrayList<>();
        for (OperationalObject o : svc.allMatching(ObjectQuery.builder().objectType(ObjectType.INCIDENT).build()))
            ids.add(o.id());
        assertEquals(n, ids.size(), "the shifted boundary object is not yielded twice");
        assertEquals(n, new java.util.HashSet<>(ids).size());

        armed.set(true);
        assertEquals(1, svc.sweepIncidentSla(now));
        assertEquals(1, activityFor(events, EventType.OBJECT_SLA_BREACH, boundary).size());
    }

    // ── ASSURE-IMPACT-LEDGER-RESIDUALS-1 (1): rollups read every object, not one MAX_LIMIT page ──────────

    private static final int BEYOND_ONE_PAGE = ObjectQuery.MAX_LIMIT + 7;

    /** Seed {@link #BEYOND_ONE_PAGE} Incidents sharing ONE createdAt (the worst case for offset paging), each
     *  with a EUR impact of 1, the last one resolved. */
    private static void seedBeyondOnePage(ObjectStore store, long createdAt) {
        Map<String, String> eur = Map.of("impact", "{\"confirmed\":\"1\",\"currency\":\"EUR\"}");
        for (int i = 0; i < BEYOND_ONE_PAGE; i++)
            store.create(stored(String.format("inc-%05d", i), i == BEYOND_ONE_PAGE - 1 ? "RESOLVED" : "IDENTIFIED",
                    createdAt, eur));
    }

    @SuppressWarnings("unchecked")
    private static void assertCountsEveryObject(ObjectService svc) {
        Map<String, Object> a = svc.analytics(ObjectType.INCIDENT);
        assertEquals(BEYOND_ONE_PAGE, a.get("total"), "every object, not the first MAX_LIMIT");
        assertEquals(BEYOND_ONE_PAGE, a.get("backlog"));
        assertEquals(Map.of("IDENTIFIED", BEYOND_ONE_PAGE - 1, "RESOLVED", 1), a.get("byStatus"));
        Map<String, Object> eur = (Map<String, Object>) ((Map<String, Object>)
                ((Map<String, Object>) a.get("impact")).get("byCurrency")).get("EUR");
        assertEquals(BEYOND_ONE_PAGE, eur.get("count"));
        assertEquals(0, new java.math.BigDecimal(BEYOND_ONE_PAGE).compareTo((java.math.BigDecimal) eur.get("confirmed")));
        java.util.Set<String> ids = new java.util.HashSet<>();
        for (OperationalObject o : svc.allMatching(ObjectQuery.builder().objectType(ObjectType.INCIDENT).build()))
            assertTrue(ids.add(o.id()), "no object visited twice across pages: " + o.id());
        assertEquals(BEYOND_ONE_PAGE, ids.size());
    }

    @Test
    void analyticsCountsEveryObjectBeyondOneQueryPageInMemory() {
        InMemoryObjectStore store = new InMemoryObjectStore();
        seedBeyondOnePage(store, 1_000L);
        assertCountsEveryObject(new ObjectService(store));
    }

    /** The durable store pages by SQL OFFSET; identical createdAt makes the id tiebreak load-bearing. */
    @Test
    void analyticsCountsEveryObjectBeyondOneQueryPageInTheDbStore() throws Exception {
        try (DbObjectStore store = DbObjectStore.open("jdbc:duckdb:", null, null)) {
            seedBeyondOnePage(store, 1_000L);
            assertCountsEveryObject(new ObjectService(store));
        }
    }

    // ── Phase 4: correlation links + graph ──────────────────────────────────────────

    @Test
    void linkEmitsEventAndIsIdempotent() {
        InMemoryEventStore events = new InMemoryEventStore();
        EventLog.global().installStore(events);
        ObjectService svc = new ObjectService(new InMemoryObjectStore());
        OperationalObject c = svc.open(ObjectType.CASE, "investigation", "d", "HIGH", "corr", Map.of());
        OperationalObject i = svc.open(ObjectType.INCIDENT, "bad rows", "d", "HIGH", "corr", Map.of());

        ObjectLink link = svc.link(c.id(), i.id(), "contains", "alice");
        assertEquals("CONTAINS", link.relationship());
        assertEquals("CASE", link.fromType());
        assertEquals("INCIDENT", link.toType());
        assertEquals(1, svc.linksOf(c.id()).size());
        assertEquals(1, svc.linksOf(i.id()).size(), "link is incident from both ends");
        assertEquals(1, activityFor(events, EventType.OBJECT_LINKED, c.id()).stream()
                .filter(e -> i.id().equals(e.attributes().get("to"))).count());

        // idempotent: re-linking the same edge returns the existing one, no duplicate
        svc.link(c.id(), i.id(), "CONTAINS", "bob");
        assertEquals(1, svc.linksOf(c.id()).size(), "duplicate edge not added");
    }

    @Test
    void linkRequiresBothEndpoints() {
        ObjectService svc = new ObjectService(new InMemoryObjectStore());
        OperationalObject c = svc.open(ObjectType.CASE, "case", "d", "HIGH", null, Map.of());
        assertThrows(NoSuchElementException.class, () -> svc.link(c.id(), "missing", "contains", null));
        assertThrows(NoSuchElementException.class, () -> svc.link("missing", c.id(), "contains", null));
    }

    @Test
    void graphTraversesToDepth() {
        ObjectService svc = new ObjectService(new InMemoryObjectStore());
        OperationalObject c = svc.open(ObjectType.CASE, "case", "d", "HIGH", null, Map.of());
        OperationalObject i = svc.open(ObjectType.INCIDENT, "incident", "d", "HIGH", null, Map.of());
        OperationalObject a = svc.open(ObjectType.ALERT, "alert", "d", "HIGH", null, Map.of());
        svc.link(c.id(), i.id(), "CONTAINS", null);       // CASE — INCIDENT
        svc.link(i.id(), a.id(), "ESCALATED_FROM", null); // INCIDENT — ALERT

        // depth 1 from the case reaches the incident (not the alert)
        Map<String, Object> g1 = svc.graph(c.id(), 1);
        assertEquals(2, ((List<?>) g1.get("nodes")).size());
        assertEquals(1, ((List<?>) g1.get("edges")).size());

        // depth 2 reaches the alert too
        Map<String, Object> g2 = svc.graph(c.id(), 2);
        assertEquals(3, ((List<?>) g2.get("nodes")).size());
        assertEquals(2, ((List<?>) g2.get("edges")).size());

        assertThrows(NoSuchElementException.class, () -> svc.graph("missing", 2));
    }

    // ── Phase 4 follow-up: comments / attachments / RCA ──────────────────────────────

    @Test
    void commentsAndAttachmentsRecordedAndQueryable() {
        InMemoryEventStore events = new InMemoryEventStore();
        EventLog.global().installStore(events);
        ObjectService svc = new ObjectService(new InMemoryObjectStore());
        OperationalObject caseObj = svc.open(ObjectType.CASE, "investigation", "d", "HIGH", "corr", Map.of());

        ObjectNote c = svc.comment(caseObj.id(), "alice", "starting investigation");
        assertEquals(NoteKind.COMMENT, c.kind());
        ObjectNote a = svc.attach(caseObj.id(), "bob", "trace.log", "text/plain", "s3://x/trace.log", "tail -100");
        assertEquals(NoteKind.ATTACHMENT, a.kind());
        assertEquals("trace.log", a.attributes().get("name"));

        assertEquals(2, svc.notesOf(caseObj.id(), null).size());
        assertEquals(1, svc.notesOf(caseObj.id(), NoteKind.COMMENT).size());
        assertEquals(1, svc.notesOf(caseObj.id(), NoteKind.ATTACHMENT).size());
        assertEquals(2, activityFor(events, EventType.OBJECT_NOTE, caseObj.id()).size());

        assertThrows(NoSuchElementException.class, () -> svc.comment("missing", "x", "y"));
    }

    @Test
    void applyRcaSeedsOneCommentPerSection() {
        ObjectService svc = new ObjectService(new InMemoryObjectStore());
        OperationalObject caseObj = svc.open(ObjectType.CASE, "investigation", "d", "HIGH", null, Map.of());
        RcaTemplate template = RcaTemplate.fromMap(Map.of("name", "incident",
                "sections", List.of("Summary", "Root cause", "Remediation")));

        List<ObjectNote> seeded = svc.applyRca(caseObj.id(), template, "alice");
        assertEquals(3, seeded.size());
        assertEquals("## Summary", seeded.get(0).body(), "section order preserved in the returned list");
        assertEquals(3, svc.notesOf(caseObj.id(), NoteKind.COMMENT).size());
        assertThrows(NoSuchElementException.class, () -> svc.applyRca("missing", template, "x"));
    }

    /**
     * D10: the object path now runs through the kind-agnostic {@code NoteService}, so it must still
     * stamp {@code targetKind: object} and stay invisible to any other target family sharing the id.
     */
    @Test
    void objectNotesStayOnTheObjectTargetKind() {
        ObjectService svc = new ObjectService(new InMemoryObjectStore());
        OperationalObject caseObj = svc.open(ObjectType.CASE, "investigation", "d", "HIGH", null, Map.of());

        ObjectNote c = svc.comment(caseObj.id(), "alice", "hi");
        assertEquals(com.gamma.objects.AnnotationKinds.OBJECT, c.targetKind());
        assertEquals(1, svc.noteStore().forTarget(com.gamma.objects.AnnotationKinds.OBJECT, caseObj.id(), null).size());
        assertTrue(svc.noteStore().forTarget("link-analysis-view", caseObj.id(), null).isEmpty(),
                "an object note is not readable as a view note");
    }

    // ── INCIDENT-KPI-MTTR-1: MTTR is a different number from cycle time ────────────────

    /**
     * The distinction the row exists for. For an INCIDENT the only TERMINAL state is {@code ARCHIVED}, so
     * {@code closedAt} — and therefore the long-standing {@code cycleTime} — measures time to ARCHIVE. An
     * Incident resolved promptly and archived much later would report a large "cycle time" that reads like
     * a slow fix. MTTR is measured from the {@code resolvedAt} stamp instead.
     */
    @Test
    void mttrMeasuresResolutionWhileCycleTimeMeasuresArchival() {
        EventLog.global().installStore(new InMemoryEventStore());
        ObjectService svc = new ObjectService(new InMemoryObjectStore());

        // The I1 gate blocks RESOLVED until the postmortem's four sections are present — reuse the
        // existing complete blob rather than working around the gate.
        OperationalObject inc = svc.open(ObjectType.INCIDENT, "late feed", "msg", "WARNING", "pipeA",
                completePostmortemAttrs(System.currentTimeMillis() + 3_600_000L));
        assertEquals(0L, inc.closedAt(), "a fresh Incident is not closed");
        assertNull(inc.attributes().get(ObjectService.ATTR_RESOLVED_AT), "nor resolved");

        svc.transition(inc.id(), "accept", "alice");          // IDENTIFIED -> DIAGNOSING
        OperationalObject resolved = svc.transitionTo(inc.id(), "RESOLVED", "alice");
        assertEquals("RESOLVED", resolved.status());
        long stamp = Long.parseLong(resolved.attributes().get(ObjectService.ATTR_RESOLVED_AT));
        assertTrue(stamp >= resolved.createdAt(), "the resolution cannot precede the opening");
        assertEquals(0L, resolved.closedAt(),
                "RESOLVED is NOT terminal for an Incident, so closedAt must still be unset — this is "
                        + "exactly why closedAt cannot serve as the MTTR anchor");

        Map<String, Object> before = svc.analytics(ObjectType.INCIDENT);
        assertEquals(1, mapOf(before, "mttr").get("count"), "the resolved Incident counts toward MTTR");
        assertEquals(0, mapOf(before, "cycleTime").get("count"), "but not toward cycle time — it is not closed");

        svc.transition(inc.id(), "archive", "alice");         // RESOLVED -> ARCHIVED (terminal)
        Map<String, Object> after = svc.analytics(ObjectType.INCIDENT);
        assertEquals(1, mapOf(after, "cycleTime").get("count"), "archiving is what closes it");
        assertEquals(1, mapOf(after, "mttr").get("count"), "and MTTR is unchanged by the tidy-up");
    }

    /** A reopened object measures to the resolution that stuck, not to the one that did not. */
    @Test
    void reopeningAndResolvingAgainMovesTheResolutionStamp() throws Exception {
        EventLog.global().installStore(new InMemoryEventStore());
        ObjectService svc = new ObjectService(new InMemoryObjectStore());
        OperationalObject inc = svc.open(ObjectType.INCIDENT, "late feed", "msg", "WARNING", "pipeA",
                completePostmortemAttrs(System.currentTimeMillis() + 3_600_000L));

        long first = Long.parseLong(svc.transitionTo(inc.id(), "RESOLVED", "alice")
                .attributes().get(ObjectService.ATTR_RESOLVED_AT));
        svc.transition(inc.id(), "reopen", "alice");          // RESOLVED -> DIAGNOSING
        Thread.sleep(2);                                      // so the second stamp is distinguishable
        long second = Long.parseLong(svc.transitionTo(inc.id(), "RESOLVED", "alice", "CONFIRMED") // reopen cleared it
                .attributes().get(ObjectService.ATTR_RESOLVED_AT));

        assertTrue(second > first,
                "the first resolution did not hold, so measuring to it would report a fix that was not one");
    }

    /** An object with no recorded resolution is EXCLUDED from the mean, never counted as zero. */
    @Test
    void objectsWithNoRecordedResolutionDoNotDragTheMeanToZero() {
        EventLog.global().installStore(new InMemoryEventStore());
        ObjectService svc = new ObjectService(new InMemoryObjectStore());
        svc.open(ObjectType.INCIDENT, "still open", "msg", "WARNING", "pipeA", Map.of());
        OperationalObject done = svc.open(ObjectType.INCIDENT, "fixed", "msg", "WARNING", "pipeA",
                completePostmortemAttrs(System.currentTimeMillis() + 3_600_000L));
        svc.transitionTo(done.id(), "RESOLVED", "alice");

        Map<String, Object> mttr = mapOf(svc.analytics(ObjectType.INCIDENT), "mttr");
        assertEquals(1, mttr.get("count"), "only the resolved one is in the denominator");
        assertNotNull(mttr.get("definition"), "a KPI must be published with its definition, never implied");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> mapOf(Map<String, Object> analytics, String key) {
        return (Map<String, Object>) analytics.get(key);
    }

}
