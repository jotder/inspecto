package com.gamma.notify;

import com.gamma.event.AuditAttrs;
import com.gamma.event.Event;
import com.gamma.event.EventType;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/** ses-sns-adapter-design §8: the windowed security triggers T1–T4 and the SECURITY delivery guarantees. */
class SecurityTriggersTest {

    private final List<Event> fired = new CopyOnWriteArrayList<>();
    private final AtomicLong now = new AtomicLong(1_000_000L);

    private SecurityTriggers triggers(int threshold) {
        long tenMin = 600_000L;
        return new SecurityTriggers(fired::add, now::get,
                new SecurityTriggers.Rule(SecurityTriggers.T1, "t1", threshold, tenMin),
                new SecurityTriggers.Rule(SecurityTriggers.T2, "t2", threshold, tenMin),
                new SecurityTriggers.Rule(SecurityTriggers.T3, "t3", threshold, 3_600_000L));
    }

    private static Event denied(int status, String actor, String ip, String path) {
        return Event.builder(EventType.ACCESS_DENIED).actor(actor).ip(ip)
                .attr(AuditAttrs.HTTP_PATH, path).attr(AuditAttrs.HTTP_STATUS, status).build();
    }

    @Test
    void thresholdMinusOneDoesNotFire() {
        SecurityTriggers t = triggers(20);
        for (int i = 0; i < 19; i++) t.accept(denied(403, "mallory", "10.0.0.1", "/jobs"));
        assertTrue(fired.isEmpty());
    }

    @Test
    void theThresholdFiresExactlyOncePerWindowThenAgainInTheNext() {
        SecurityTriggers t = triggers(20);
        for (int i = 0; i < 60; i++) t.accept(denied(403, "mallory", "10.0.0.1", "/jobs"));
        assertEquals(1, fired.size(), "once per key per window, however many more arrive");
        Event e = fired.get(0);
        assertEquals(EventType.SECURITY_TRIGGERED, e.type());
        assertEquals("t1", e.attributes().get("trigger"));
        assertEquals("mallory", e.attributes().get("key"));

        now.addAndGet(600_000L);
        for (int i = 0; i < 20; i++) t.accept(denied(403, "mallory", "10.0.0.1", "/jobs"));
        assertEquals(2, fired.size(), "the next window fires again");
    }

    @Test
    void hitsOutsideTheSlidingWindowDoNotCount() {
        SecurityTriggers t = triggers(20);
        for (int i = 0; i < 19; i++) t.accept(denied(403, "mallory", null, "/jobs"));
        now.addAndGet(600_001L);
        t.accept(denied(403, "mallory", null, "/jobs"));
        assertTrue(fired.isEmpty());
    }

    @Test
    void t2CountsByIpAndT3ByAdapterNotByActor() {
        SecurityTriggers t = triggers(3);
        for (int i = 0; i < 3; i++) t.accept(denied(401, "anonymous-" + i, "192.0.2.7", "/jobs"));
        for (int i = 0; i < 3; i++)
            t.accept(denied(403, "anonymous", "198.51.100." + i, "/api/v1/public/delivery-status/sendgrid"));
        assertEquals(2, fired.size());
        assertEquals("t2", fired.get(0).attributes().get("trigger"));
        assertEquals("192.0.2.7", fired.get(0).attributes().get("key"));
        assertEquals("t3", fired.get(1).attributes().get("trigger"));
        assertEquals("sendgrid", fired.get(1).attributes().get("key"));
    }

    @Test
    void tenThousandDistinctKeysHoldMemorySteady() {
        SecurityTriggers t = triggers(50);
        for (int i = 0; i < 3 * SecurityTriggers.MAX_KEYS; i++)
            t.accept(denied(401, null, "k" + i, "/jobs"));
        assertEquals(SecurityTriggers.MAX_KEYS, t.trackedKeys(), "the LRU bound is honoured");
        assertTrue(fired.isEmpty());
    }

    @Test
    void t4FiresOnEveryRolesWriteButNotOnAHeldOrRefusedOne() {
        SecurityTriggers t = triggers(20);
        Event write = Event.builder(EventType.AUDIT).actor("admin").attr(AuditAttrs.HTTP_METHOD, "PUT")
                .attr(AuditAttrs.HTTP_PATH, "/access/roles").attr(AuditAttrs.HTTP_STATUS, 200).build();
        t.accept(write);
        t.accept(write);
        t.accept(Event.builder(EventType.AUDIT).actor("admin").attr(AuditAttrs.HTTP_METHOD, "PUT")
                .attr(AuditAttrs.HTTP_PATH, "/access/roles").attr(AuditAttrs.HTTP_STATUS, 202).build());
        t.accept(Event.builder(EventType.AUDIT).actor("admin").attr(AuditAttrs.HTTP_METHOD, "PUT")
                .attr(AuditAttrs.HTTP_PATH, "/access/policies").attr(AuditAttrs.HTTP_STATUS, 200).build());
        assertEquals(2, fired.size());
        assertEquals("t4", fired.get(0).attributes().get("trigger"));
    }

    @Test
    void securityCannotBeOptedOutAndMailsOnlyAdministratorsThroughTheDigest() throws IOException {
        NotificationStore store = new InMemoryNotificationStore();
        NotificationPreferences prefs = new NotificationPreferences();
        prefs.set("security", Map.of(NotificationPreferences.IN_APP, false, NotificationPreferences.EMAIL, false));
        assertTrue(prefs.enabled("security", NotificationPreferences.IN_APP), "default layer is locked on");
        List<String> mailedTo = new CopyOnWriteArrayList<>();
        NotificationChannel email = new NotificationChannel() {
            public String id() { return NotificationPreferences.EMAIL; }
            public void deliver(Notification n) { }
            public void deliver(Notification n, String target) { mailedTo.add(target); }
            public void deliver(Notification n, String target, String id) { mailedTo.add(target); }
        };
        NotificationPreferenceOverrides overrides = NotificationPreferenceOverrides.inMemory();
        overrides.apply("root", "root@x.com", true,
                Map.of("security", Map.of(NotificationPreferences.EMAIL, false, NotificationPreferences.IN_APP, false)));
        overrides.apply("user", "user@x.com", false, Map.of());
        assertTrue(overrides.enabled(prefs, "security", NotificationPreferences.EMAIL, "root", "root@x.com"),
                "the override layer cannot turn it off either");

        NotificationService svc = new NotificationService(store, NotificationRules.defaults(), prefs, List.of(email));
        svc.preferenceOverrides(overrides);
        List<Event> events = new CopyOnWriteArrayList<>();
        SecurityTriggers wired = new SecurityTriggers(e -> { events.add(e); svc.onEvent(e); }, now::get,
                new SecurityTriggers.Rule("t1", "t1", 2, 600_000L),
                new SecurityTriggers.Rule("t2", "t2", 2, 600_000L),
                new SecurityTriggers.Rule("t3", "t3", 2, 600_000L));
        for (int i = 0; i < 2; i++) wired.accept(denied(401, null, "192.0.2.1", "/x"));
        for (int i = 0; i < 2; i++) wired.accept(denied(401, null, "192.0.2.2", "/x"));
        assertEquals(2, events.size());
        long deadline = System.currentTimeMillis() + 5000;
        while (store.recent(10).size() < 2 && System.currentTimeMillis() < deadline) Thread.onSpinWait();
        assertEquals(2, store.recent(10).size(), "stored in-app despite every opt-out");
        assertEquals("security", store.recent(10).get(0).category());
        assertTrue(mailedTo.isEmpty(), "buffered in the digest, not sent per firing");
        svc.flushDigest("security-digest:root");
        svc.flushDigest("security-digest:user");
        assertEquals(List.of("root@x.com"), mailedTo, "one digest, to the administrator only");
        svc.close();
    }

    /** Two services on one shared log (the default Space's EventLog.global()) must not both count the same row. */
    @Test
    void onlyOneEvaluatorPerLogFiresAndTheNextTakesOverAfterDetach() {
        Object log = new Object();
        long tenMin = 600_000L;
        java.util.function.Supplier<SecurityTriggers> make = () -> new SecurityTriggers(log, fired::add, now::get,
                new SecurityTriggers.Rule(SecurityTriggers.T1, "t1", 3, tenMin),
                new SecurityTriggers.Rule(SecurityTriggers.T2, "t2", 3, tenMin),
                new SecurityTriggers.Rule(SecurityTriggers.T3, "t3", 3, 3_600_000L));
        SecurityTriggers first = make.get(), second = make.get();
        for (int i = 0; i < 3; i++) {
            Event e = denied(403, "mallory", null, "/jobs");
            first.accept(e);
            second.accept(e);
        }
        assertEquals(1, fired.size(), "one evaluator owns the log");
        first.detach();
        for (int i = 0; i < 3; i++) second.accept(denied(403, "eve", null, "/jobs"));
        assertEquals(2, fired.size(), "after the owner detaches the next evaluator takes over");
        second.detach();
    }
}
