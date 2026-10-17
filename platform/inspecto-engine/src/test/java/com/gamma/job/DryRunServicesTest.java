package com.gamma.job;

import com.gamma.alert.Alert;
import com.gamma.alert.AlertAccess;
import com.gamma.notify.InMemoryNotificationStore;
import com.gamma.notify.MailAccess;
import com.gamma.notify.MailAttachment;
import com.gamma.notify.Notification;
import com.gamma.notify.NotificationAccess;
import com.gamma.notify.NotificationStore;
import com.gamma.objects.IncidentAccess;
import com.gamma.objects.FakeObjectAccess;
import com.gamma.objects.ObjectAccess;
import com.gamma.workflow.ObjectType;
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

    private static PlatformServices granted(NotificationStore feed, FakeObjectAccess objects,
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
        // ⚠ The seam's test double since EDG-01 cell 7 — core cannot construct an ObjectService. What
        // this test asserts is unchanged: the dry-run stand-in must reach NOTHING, so an empty recorder
        // proves it exactly as an empty store did.
        FakeObjectAccess objects = new FakeObjectAccess();
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
        assertEquals(List.of(), objects.opened, "dry run opens no incident");
        assertEquals(List.of(), evaluations, "dry run never reaches the real evaluator");
        assertTrue(log.lines.stream().anyMatch(l -> l.contains("would emit notification") && l.contains("title=Breach")));
        assertTrue(log.lines.stream().anyMatch(l -> l.contains("would open incident") && l.contains("scope=orders")));
        assertTrue(log.lines.stream().anyMatch(l -> l.contains("would evaluate this space's Alert Rules")));
    }

    @Test
    void readOnlyServicesPassThroughAndGrantsStayHonest() {
        PlatformServices dry = DryRunServices.wrap(
                granted(new InMemoryNotificationStore(), new FakeObjectAccess(),
                        new ArrayList<>()),
                new CapturingLog());

        assertEquals(42L, dry.get(Clock.class).now(), "read-only service is the real one");
        assertEquals(4, dry.granted().size());

        PlatformServices narrow = DryRunServices.wrap(
                new PlatformServiceRegistry().grant(Set.of()), new CapturingLog());
        assertTrue(narrow.find(NotificationAccess.class).isEmpty(), "dry run never widens a grant");
    }

    /** A {@code mail} binding that records every call — any entry is a message that really went out. */
    static final class RecordingMail implements MailAccess {
        final List<String> sent = new ArrayList<>();
        @Override public boolean send(List<String> to, List<String> cc, String subject, String body,
                                      List<MailAttachment> attachments) {
            sent.add(subject);
            return true;
        }
    }

    @Test
    void mailRecordsInsteadOfSendingUnderDryRun() throws Exception {
        // PLATFORM-SERVICES-DRYRUN-GAP-1: `mail` used to pass through a dry run unchanged.
        RecordingMail mail = new RecordingMail();
        PlatformServiceRegistry registry = new PlatformServiceRegistry();
        registry.register("mail", MailAccess.class, mail);
        CapturingLog log = new CapturingLog();

        boolean sent = DryRunServices.wrap(registry.grant(Set.of("mail")), log).get(MailAccess.class)
                .send(List.of("ann@example.test", "bob@example.test"), List.of("cat@example.test"),
                        "Daily report", "the confidential body");

        assertEquals(List.of(), mail.sent, "a dry run never reaches the real mail service");
        assertFalse(sent, "a dry run never claims a send");
        assertTrue(log.lines.stream().anyMatch(l -> l.contains("would send mail")
                && l.contains("recipients=3") && l.contains("subject=Daily report")), log.lines.toString());
        assertTrue(log.lines.stream().noneMatch(l -> l.contains("@example.test") || l.contains("confidential")),
                "recipients by COUNT, never the addresses or the body: " + log.lines);
    }

    @Test
    void objectWritesRecordInsteadOfActWhileReadsPassThrough() {
        // PLATFORM-SERVICES-DRYRUN-GAP-1: `objects` used to pass through a dry run unchanged.
        FakeObjectAccess objects = new FakeObjectAccess();
        String existing = objects.open(ObjectType.INCIDENT, "seed", "d", "HIGH", "orders", Map.of("rule", "r1"));
        PlatformServiceRegistry registry = new PlatformServiceRegistry();
        registry.register("objects", ObjectAccess.class, objects);
        CapturingLog log = new CapturingLog();
        ObjectAccess dry = DryRunServices.wrap(registry.grant(Set.of("objects")), log).get(ObjectAccess.class);

        // Reads answer from the real service.
        assertTrue(dry.hasActive(ObjectType.INCIDENT, "orders"));
        assertTrue(dry.hasActiveMatching(ObjectType.INCIDENT, "orders", Map.of("rule", "r1")));
        assertEquals(Map.of("r1", existing), dry.activeAttributeIndex(ObjectType.INCIDENT, "orders", "rule"));
        assertTrue(dry.summary(existing).isPresent());

        // Writes reach nothing.
        String opened = dry.open(ObjectType.CASE, "Cluster", "d", "HIGH", "orders", Map.of("rule", "r1"));
        boolean moved = dry.transition(existing, "resolve", "alice");
        dry.link(existing, opened, "ESCALATED_FROM", "alice");
        dry.linkSubject(existing, "alert", "a1", "ESCALATED_FROM", "alice");
        dry.addTag("hot", "incident", existing, "alice");
        dry.ensureTag("hot");
        assertFalse(dry.saveAttributes(existing, Map.of("k", "v"), "alice", "refresh"));

        assertEquals(1, objects.opened.size(), "only the seeded object exists");
        assertEquals(List.of(), objects.transitioned, "dry run transitions nothing");
        assertEquals(List.of(), objects.linked);
        assertEquals(List.of(), objects.subjectLinked);
        assertEquals(List.of(), objects.tagsEnsured);
        assertEquals(List.of(), objects.tagsOf("incident", existing));
        assertFalse(moved, "a dry run never claims a transition");
        assertTrue(opened.startsWith("dry-run:"), "a synthetic id, clearly marked: " + opened);
        assertTrue(dry.hasActive(ObjectType.INCIDENT, "orders"), "the real object is still active");
        assertTrue(log.lines.stream().anyMatch(l -> l.contains("would open object") && l.contains("kind=CASE")),
                log.lines.toString());
        assertTrue(log.lines.stream().anyMatch(l -> l.contains("would transition object") && l.contains("action=resolve")),
                log.lines.toString());
    }
}
