package com.gamma.control;

import com.gamma.audit.Event;
import com.gamma.audit.EventType;
import com.gamma.notify.*;
import com.gamma.pipeline.ComponentStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

/** OBJECT-READ-MASKING-RESIDUALS-1 (2): notification content (in-app + every channel) is always masked. */
class NotificationContentMaskingTest {

    private static final String RAW = "99970001401";

    private static final class Recording implements NotificationChannel {
        final List<Notification> sent = new CopyOnWriteArrayList<>();
        @Override public String id() { return NotificationPreferences.WEBHOOK; }
        @Override public void deliver(Notification n) { sent.add(n); }
    }

    private static Event event(String dataset) {
        Event.Builder b = Event.builder(EventType.ALERT_FIRED).pipeline("calls")
                .message("Sum above 5 for msisdn=" + RAW + ", region=EU")
                .attr("rule", "r1").attr("key", "msisdn=" + RAW + ", region=EU").attr("key.msisdn", RAW).attr("key.region", "EU");
        if (dataset != null) b.attr("dataset", dataset);
        return b.build();
    }

    /** Returns {feed notification, channel notification}. */
    private static Notification[] run(Path root, Event e) throws Exception {
        new ComponentStore(root.resolve("registry")).write("dataset", "calls", Map.of("columns", List.of(
                Map.of("name", "msisdn", "classification", "MSISDN"),
                Map.of("name", "region", "classification", "NONE"))));
        NotificationRule rule = new NotificationRule("custom", EventType.ALERT_FIRED, null, "ops",
                "Alert {{attributes.key}}", "{{message}} / {{attributes.key}}",
                "k:{{attributes.rule}}", true);
        NotificationStore store = new InMemoryNotificationStore();
        Recording ch = new Recording();
        NotificationPreferences prefs = new NotificationPreferences();
        prefs.set("ops", Map.of(NotificationPreferences.IN_APP, true, NotificationPreferences.WEBHOOK, true));
        NotificationService svc = new NotificationService(store,
                new NotificationRules(List.of(), () -> List.of(rule)), prefs, List.of(ch));
        svc.contentMasking(NotificationContentMasking.of(() -> root));
        svc.onEvent(e);
        svc.close();
        assertEquals(1, ch.sent.size());
        return new Notification[]{store.recent(10).get(0), ch.sent.get(0)};
    }

    @Test
    void aClassifiedKeyReachesNeitherTheFeedNorAChannelRaw(@TempDir Path root) throws Exception {
        for (Notification n : run(root, event("calls"))) {
            String text = n.title() + "|" + n.body();
            assertFalse(text.contains(RAW), text);
            assertTrue(n.title().matches("Alert msisdn=masked:[0-9a-f]{16}, region=EU"), n.title());   // region untouched
            assertTrue(n.body().matches(".*msisdn=masked:[0-9a-f]{16}, region=EU / msisdn=masked:[0-9a-f]{16}, region=EU"), n.body());
        }
    }

    @Test
    void anEventNamingNoDatasetFailsClosed(@TempDir Path root) throws Exception {
        Notification[] out = run(root, event(null));
        for (Notification n : out) {
            assertFalse((n.title() + n.body()).contains(RAW), n.body());
        }
        Event m = NotificationContentMasking.mask(event(null), root);
        assertTrue(m.attributes().get("key.region").startsWith("masked:"), "every key.* is masked when the dataset is unknown");
    }

    @Test
    void aMaskerThatThrowsDropsTheNotification(@TempDir Path root) throws Exception {
        NotificationStore store = new InMemoryNotificationStore();
        Recording ch = new Recording();
        NotificationService svc = new NotificationService(store, NotificationRules.defaults(), new NotificationPreferences(), List.of(ch));
        svc.contentMasking(e -> { throw new IllegalStateException("registry unreadable"); });
        svc.onEvent(Event.builder(EventType.BATCH_FAILED).pipeline("p").correlationId("b").message(RAW).build());
        svc.close();
        assertTrue(store.recent(10).isEmpty());
        assertTrue(ch.sent.isEmpty());
    }
}
