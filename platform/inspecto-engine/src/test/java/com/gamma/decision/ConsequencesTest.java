package com.gamma.decision;

import com.gamma.decision.ConsequenceProvider.Result;
import com.gamma.objects.ObjectAccess;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class ConsequencesTest {

    /** A recording context: signals emitted, jobs/pipelines known by id. */
    static final class Ctx implements ConsequenceContext {
        final boolean automatic;
        final List<String> signals = new ArrayList<>();
        final List<String> offers = new ArrayList<>();
        boolean disabledJob;
        Map<String, Object> authored;
        Ctx(boolean automatic) { this.automatic = automatic; }
        public String ruleName() { return "r1"; }
        public String actor() { return "alice"; }
        public boolean automatic() { return automatic; }
        public Map<String, Object> rule() { return Map.of(); }
        public Map<String, Object> record() { return Map.of("msisdn", "123"); }
        public Optional<ObjectAccess> objects() { return Optional.empty(); }
        public boolean has(String id) { return false; }
        public void emitSignal(String type, String source, Map<String, Object> payload, String offerTo) {
            signals.add(type + payload);
            offers.add(offerTo);
        }
        public boolean jobDisabled(String id) { return disabledJob; }
        public Optional<String> triggerJob(String id, String by) { return "j1".equals(id) ? Optional.of("run-1") : Optional.empty(); }
        public boolean triggerPipeline(String id) { return "p1".equals(id); }
        public void authorAlertRule(Map<String, Object> body) { authored = body; }
    }

    private static Map<String, Object> c(String action, Map<String, Object> more) {
        var m = new java.util.LinkedHashMap<String, Object>(more);
        m.put("action", action);
        return m;
    }

    @Test
    void builtInsAreRegisteredWithGroupsAndNoModuleActionsLeakIn() {
        Consequences reg = Consequences.builtIns();
        List<String> ids = reg.all().stream().map(ConsequenceProvider::id).toList();
        assertEquals(List.of("emit-signal", "create-alert", "start-job", "trigger-pipeline", "render-widget",
                "generate-report", "route", "tag", "quarantine", "drop"), ids);
        assertFalse(ids.contains("create-incident"), "create-incident is contributed by the ops module");
        assertEquals("routing", reg.find("tag").orElseThrow().group());
        assertEquals(List.of(), reg.find("start-job").orElseThrow().requires(), "a space with no jobs is 'no such job', not an absent module");
        assertTrue(reg.find("zz-nope").isEmpty());
    }

    @Test
    void emitSignalMapsRecordFieldsAndOffers() {
        Ctx ctx = new Ctx(false);
        Result r = Consequences.builtIns().find("emit-signal").orElseThrow().execute(ctx,
                c("emit-signal", Map.of("params", Map.of("type", "fraud.alert", "offerTo", "s2",
                        "payload", Map.of("who", "msisdn")))));
        assertEquals(Result.EXECUTED, r.status());
        assertTrue(ctx.signals.get(0).contains("who=123"), ctx.signals.toString());
        assertEquals("s2", ctx.offers.get(0));
    }

    @Test
    void startJobRunsOrReportsNoSuchJobAndSkipsDisabledOnlyWhenAutomatic() {
        var p = Consequences.builtIns().find("start-job").orElseThrow();
        Map<String, Object> ok = c("start-job", Map.of("target", Map.of("kind", "job", "id", "j1")));
        Result r = p.execute(new Ctx(false), ok);
        assertEquals(Result.EXECUTED, r.status());
        assertEquals("run-1", r.extras().get("runId"));
        assertEquals(Result.SKIPPED, p.execute(new Ctx(false), c("start-job", Map.of("target", Map.of("id", "zz")))).status());
        Ctx disabled = new Ctx(true);
        disabled.disabledJob = true;
        assertEquals(Result.SKIPPED, p.execute(disabled, ok).status());
        Ctx manual = new Ctx(false);
        manual.disabledJob = true;
        assertEquals(Result.EXECUTED, p.execute(manual, ok).status());
    }

    @Test
    void triggerPipelineAndStubsAndRoutingDescribeThemselves() {
        var reg = Consequences.builtIns();
        assertEquals(Result.EXECUTED, reg.find("trigger-pipeline").orElseThrow()
                .execute(new Ctx(false), c("trigger-pipeline", Map.of("target", Map.of("id", "p1")))).status());
        assertEquals(Result.SKIPPED, reg.find("trigger-pipeline").orElseThrow()
                .execute(new Ctx(false), c("trigger-pipeline", Map.of("target", Map.of("id", "nope")))).status());
        Ctx ctx = new Ctx(false);
        assertEquals(Result.EXECUTED, reg.find("render-widget").orElseThrow().execute(ctx, c("render-widget", Map.of())).status());
        assertEquals(1, ctx.signals.size());
        Result routed = reg.find("quarantine").orElseThrow().execute(ctx, c("quarantine", Map.of()));
        assertEquals(Result.SKIPPED, routed.status());
        assertTrue(routed.detail().contains("routing action"));
    }

    @Test
    void createAlertWithHighSeverityAndNoObjectsSaysSoAndStillExecutes() {
        Result r = Consequences.builtIns().find("create-alert").orElseThrow().execute(new Ctx(false),
                c("create-alert", Map.of("params", Map.of("severity", "critical"))));
        assertEquals(Result.EXECUTED, r.status());
        assertTrue(r.detail().contains("not installed"), r.detail());
    }

    @Test
    void createAlertAuthorsAnAlertRuleBodyWithoutItsRuleNameAlias() {
        Ctx ctx = new Ctx(false);
        Result r = Consequences.builtIns().find("create-alert").orElseThrow().execute(ctx,
                c("create-alert", Map.of("params", Map.of("rule", "my-alert", "metric", "error_rate", "window", "1h",
                        "comparator", "gt", "threshold", 0.1, "severity", "warning"))));
        assertEquals(Result.EXECUTED, r.status());
        assertEquals("my-alert", ctx.authored.get("name"));
        // 'rule' is the consequence's alias for the name; as an Alert Rule key it is now refused (MODULE-REORG-P4-2)
        assertFalse(ctx.authored.containsKey("rule"), ctx.authored.toString());
    }

    @Test
    void loadAddsBuiltInsAndNeverLetsAServiceDisplaceOne() {
        // no service file in the engine's own test classpath: load == builtIns, and ids stay unique
        List<String> ids = Consequences.load(getClass().getClassLoader()).all().stream().map(ConsequenceProvider::id).toList();
        assertEquals(ids.size(), ids.stream().distinct().count());
        assertTrue(ids.containsAll(List.of("emit-signal", "drop")));
    }
}
