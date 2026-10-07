package com.gamma.alert;

import com.gamma.catalog.ConfigSource;
import com.gamma.catalog.SemanticModel;
import com.gamma.enrich.EnrichmentConfig;
import com.gamma.etl.PipelineConfig;
import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.etl.StatusStore;
import com.gamma.event.Event;
import com.gamma.event.EventLog;
import com.gamma.event.EventType;
import com.gamma.notify.Notification;
import com.gamma.signal.Signal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

/**
 * DUCKLE-C1 residual 2 — <b>owner-routed alerting</b>, the rule half: an Alert Rule carries an owner (the
 * authoring Subject's id, {@link AlertRule#UNOWNED} = {@code appUser} where none), and the events a breach
 * emits address the notification layer to that owner — while an unowned rule's events carry no recipient at
 * all, which is what keeps them a broadcast. The delivery half is {@code NotificationServiceTest}'s.
 */
class AlertOwnerRoutingTest {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @Test
    void anAbsentOrBlankOwnerIsUnownedAndANamedOneIsKept() {
        Map<String, Object> body = new LinkedHashMap<>(Map.of("name", "r", "metric", "error_rate",
                "threshold", 0.1, "window", "1h"));
        AlertRule none = AlertRule.fromMap(body);
        assertEquals(AlertRule.UNOWNED, none.owner());
        assertFalse(none.isOwned());
        assertFalse(none.toMap().containsKey("owner"),
                "the placeholder is never stored: `owner` is also the R3 envelope key, and appUser would claim it");

        body.put("owner", "  ");
        assertEquals("appUser", AlertRule.fromMap(body).owner(), "blank is unowned, not a Subject named ''");

        body.put("owner", " alice ");
        AlertRule owned = AlertRule.fromMap(body);
        assertEquals("alice", owned.owner());
        assertTrue(owned.isOwned());
        assertEquals("alice", owned.toMap().get("owner"), "the owner round-trips through the stored shape");
        assertEquals("alice", AlertRule.fromMap(owned.toMap()).owner());
    }

    @Test
    void anOwnedRulesBreachIsAddressedToItsOwner(@TempDir Path dir) throws Exception {
        List<Event> events = fire(dir, "alice");
        Event fired = only(events, EventType.ALERT_FIRED);
        assertEquals("alice", fired.attributes().get(Notification.RECIPIENT_ATTR));
        Signal s = Signal.fromEvent(only(events, EventType.SIGNAL));
        assertEquals("alert-rule.fired", s.type());
        assertEquals("alice", s.payload().get("owner"));
    }

    @Test
    void anUnownedRulesBreachCarriesNoRecipientSoItStaysABroadcast(@TempDir Path dir) throws Exception {
        List<Event> events = fire(dir, null);
        Event fired = only(events, EventType.ALERT_FIRED);
        assertFalse(fired.attributes().containsKey(Notification.RECIPIENT_ATTR),
                "appUser is the unowned placeholder, never an addressee: " + fired.attributes());
        assertEquals("appUser", Signal.fromEvent(only(events, EventType.SIGNAL)).payload().get("owner"));
    }

    /** Evaluate one breached ledger rule owned by {@code owner} and return every event it emitted. */
    private static List<Event> fire(Path dir, String owner) throws Exception {
        PipelineConfig cfg = PipelineConfig.load(PipelineConfigBatchTest.writePipeline(dir, "").toString());
        Map<String, String> row = new LinkedHashMap<>();
        row.put("status", "SUCCESS");
        row.put("total_input_rows", "1000");
        row.put("total_output_rows", "900");
        row.put("duration_ms", "100");
        row.put("end_time", TS.format(LocalDateTime.now().minusMinutes(5)));
        AlertRule rule = new AlertRule("owned-rate", "error_rate", "gt", 0.05, "1h", "WARNING", null,
                null, null, null, null, null, null, null, null, 0, owner);
        AlertService svc = new AlertService(List.of(rule), configs(cfg), store(List.of(row)));
        List<Event> events = new CopyOnWriteArrayList<>();
        Consumer<Event> sub = events::add;
        EventLog.current().addSubscriber(sub);
        try {
            assertEquals(1, svc.evaluateAll().size());
        } finally {
            EventLog.current().removeSubscriber(sub);
        }
        return events;
    }

    private static Event only(List<Event> events, String type) {
        List<Event> of = events.stream().filter(e -> type.equals(e.type())).toList();
        assertEquals(1, of.size(), type + " events: " + of);
        return of.get(0);
    }

    private static StatusStore store(List<Map<String, String>> batches) {
        return new StatusStore() {
            @Override public Set<String> committedBatches(PipelineConfig cfg) { return Set.of(); }
            @Override public List<Map<String, String>> batches(PipelineConfig cfg) { return batches; }
            @Override public List<Map<String, String>> files(PipelineConfig cfg) { return List.of(); }
            @Override public List<Map<String, String>> lineage(PipelineConfig cfg, String batchId) { return List.of(); }
            @Override public List<Map<String, String>> quarantine(PipelineConfig cfg) { return List.of(); }
        };
    }

    private static ConfigSource configs(PipelineConfig cfg) {
        return new ConfigSource() {
            @Override public List<PipelineConfig> pipelines() { return List.of(cfg); }
            @Override public List<EnrichmentConfig> enrichments() { return List.of(); }
            @Override public List<SemanticModel> semantics() { return List.of(); }
        };
    }
}
