package com.gamma.job;

import com.gamma.alert.Alert;
import com.gamma.alert.AlertAccess;
import com.gamma.notify.InMemoryNotificationStore;
import com.gamma.notify.Notification;
import com.gamma.notify.NotificationAccess;
import com.gamma.notify.NotificationStore;
import com.gamma.ops.IncidentAccess;
import com.gamma.ops.InMemoryObjectStore;
import com.gamma.ops.ObjectQuery;
import com.gamma.ops.ObjectService;
import com.gamma.ops.ObjectType;
import com.gamma.util.RunLog;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The dry-run contract for Platform Services (§3.4): mutating services record instead of act — the
 * would-be effect lands in the RunLog, nothing lands in the backing store — while grants stay honest
 * and read-only services pass through untouched.
 */
class DryRunServicesTest {

    /** A read-only stand-in service to prove pass-through. */
    interface Clock { long now(); }

    /** Captures {@code info} lines: "message | k=v k=v". */
    private static final class CapturingLog implements RunLog {
        final List<String> lines = new ArrayList<>();
        @Override public void info(String message, Object... kv) {
            StringBuilder b = new StringBuilder(message);
            for (int i = 0; i + 1 < kv.length; i += 2) b.append(' ').append(kv[i]).append('=').append(kv[i + 1]);
            lines.add(b.toString());
        }
        @Override public void warn(String message, Object... kv) { info(message, kv); }
        @Override public void error(String message, Throwable t, Object... kv) { info(message, kv); }
    }

    private static PlatformServices granted(NotificationStore feed, ObjectService objects,
                                            List<String> evaluations) {
        PlatformServiceRegistry registry = new PlatformServiceRegistry();
        registry.register("notifications", NotificationAccess.class, n -> {
            feed.add(n);
            return Optional.of(n);
        });
        registry.register("incidents", IncidentAccess.class, IncidentAccess.over(() -> objects));
        registry.register("alerts", AlertAccess.class, () -> {
            evaluations.add("evaluated");
            return List.of(new Alert("r1", "error", "orders", "failed_batches", 5, ">", 3, "1h", 0L, "m"));
        });
        registry.register("clock", Clock.class, () -> 42L);
        return registry.grant(Set.of("notifications", "incidents", "alerts", "clock"));
    }

    @Test
    void mutatingServicesRecordInsteadOfActUnderDryRun() {
        NotificationStore feed = new InMemoryNotificationStore();
        ObjectService objects = new ObjectService(new InMemoryObjectStore());
        List<String> evaluations = new ArrayList<>();
        CapturingLog log = new CapturingLog();
        PlatformServices dry = DryRunServices.wrap(granted(feed, objects, evaluations), log);

        Optional<Notification> emitted = dry.get(NotificationAccess.class)
                .notify(Notification.create("job", "JOB_RUN", "run:1", "Breach", "b", "k1"));
        Optional<?> opened = dry.get(IncidentAccess.class)
                .openIncident("t", "m", "critical", "orders", Map.of("rule", "r1"), "rule");
        // Evaluating is not a read — it fires Alerts and advances cooldowns — so the stand-in must not
        // reach the real evaluator at all, and its empty result must not be read as "nothing breached".
        List<Alert> fired = dry.get(AlertAccess.class).evaluateRules();

        assertTrue(emitted.isEmpty() && opened.isEmpty() && fired.isEmpty(), "stand-ins act on nothing");
        assertEquals(0, feed.recent(10).size(), "dry run stores no notification");
        assertEquals(0, objects.query(ObjectQuery.builder().objectType(ObjectType.INCIDENT).build()).size(),
                "dry run opens no incident");
        assertEquals(List.of(), evaluations, "dry run never reaches the real evaluator");
        assertTrue(log.lines.stream().anyMatch(l -> l.contains("would emit notification") && l.contains("title=Breach")));
        assertTrue(log.lines.stream().anyMatch(l -> l.contains("would open incident") && l.contains("scope=orders")));
        assertTrue(log.lines.stream().anyMatch(l -> l.contains("would evaluate this space's Alert Rules")));
    }

    @Test
    void readOnlyServicesPassThroughAndGrantsStayHonest() {
        PlatformServices dry = DryRunServices.wrap(
                granted(new InMemoryNotificationStore(), new ObjectService(new InMemoryObjectStore()),
                        new ArrayList<>()),
                new CapturingLog());

        assertEquals(42L, dry.get(Clock.class).now(), "read-only service is the real one");
        assertEquals(4, dry.granted().size());

        PlatformServices narrow = DryRunServices.wrap(
                new PlatformServiceRegistry().grant(Set.of()), new CapturingLog());
        assertTrue(narrow.find(NotificationAccess.class).isEmpty(), "dry run never widens a grant");
    }
}
