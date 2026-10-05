package com.gamma.job;

import com.gamma.linkindex.LinkIndexAccess;
import com.gamma.linkindex.LinkIndexAccess.Outcome;
import com.gamma.util.RunLog;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** la.index.build (LA-DAILY-INGEST-1, T5): a clock over a build the Link Analysis side decides - fails closed,
 *  dry run builds nothing, a refusal fails the Run in words, output is aggregate-only. */
class LaIndexBuildJobTest {

    private static final Map<String, String> PARAMS = Map.of("dataset", "xdr_daily", "source_col", "a_party",
            "target_col", "b_party", "owner", "analyst-1", "attr_cols", "cell, lac", "allow_full", "true",
            "timeout_seconds", "5");

    private static Map<String, Object> savedJob() {
        Map<String, Object> m = new HashMap<>(PARAMS);
        m.put("name", "xdr_index");
        m.put("type", "la.index.build");
        return Map.of("job", m);
    }

    private static Job jobWithout(String key) {
        Map<String, Object> m = new HashMap<>(savedJob());
        @SuppressWarnings("unchecked") Map<String, Object> inner = new HashMap<>((Map<String, Object>) m.get("job"));
        inner.remove(key);
        return new LaIndexBuildJob(JobConfig.fromMap(Map.of("job", inner)));
    }

    private static Job job() {
        return new LaIndexBuildJob(JobConfig.fromMap(savedJob()));
    }

    private static PlatformServices grantOf(LinkIndexAccess access) {
        PlatformServiceRegistry registry = new PlatformServiceRegistry();
        registry.register("link-index", LinkIndexAccess.class, access);
        return registry.grant(java.util.Set.of("link-index"));
    }

    private static Outcome outcome(String result, String mode, String code) {
        return new Outcome(result, mode, code, "msg", 12L, 7L, 2);
    }

    @Test
    void failsClosedWithoutTheLinkIndexService() {
        var boom = assertThrows(IllegalStateException.class,
                () -> job().run(new Ctx(false, PlatformServices.none(), PARAMS)));
        assertTrue(boom.getMessage().contains("link-index"), boom.getMessage());
    }

    @Test
    void aDryRunBuildsNothingAndSaysSo() throws Exception {
        var calls = new ArrayList<LinkIndexAccess.Request>();
        Ctx ctx = new Ctx(true, grantOf(r -> { calls.add(r); return outcome("BUILT", "append", null); }), PARAMS);

        JobResult r = job().run(ctx);

        assertTrue(r.success());
        assertTrue(calls.isEmpty(), "a dry run must not build");
        assertTrue(r.message().contains("nothing built"), r.message());
        assertTrue(ctx.signals.isEmpty(), "no completion Signal for a build that did not happen");
    }

    @Test
    void aBuiltIndexPassesTheConfiguredMappingAndEmitsAnAggregateOnlySignal() throws Exception {
        var seen = new AtomicReference<LinkIndexAccess.Request>();
        Ctx ctx = new Ctx(false, grantOf(r -> { seen.set(r); return outcome("BUILT", "append", null); }), PARAMS);

        JobResult r = job().run(ctx);

        assertTrue(r.success(), r.message());
        LinkIndexAccess.Request q = seen.get();
        assertEquals("xdr_index", q.job());
        assertEquals("xdr_daily", q.dataset());
        assertEquals(List.of("cell", "lac"), q.attrCols());
        assertEquals("analyst-1", q.owner());
        assertTrue(q.allowFull());
        assertEquals(5_000L, q.timeoutMs());
        assertEquals(List.of("la.index.build.completed"), ctx.signals);
        assertFalse(ctx.payloads.get(0).toString().contains("a_party"), "the Signal names no column");
        assertEquals(12L, ctx.payloads.get(0).get("edges"));
    }

    @Test
    void upToDateIsSuccessAndAllowFullDefaultsToFalse() throws Exception {
        var seen = new AtomicReference<LinkIndexAccess.Request>();
        Map<String, String> p = new HashMap<>(PARAMS);
        p.remove("allow_full");
        Ctx ctx = new Ctx(false, grantOf(r -> { seen.set(r); return outcome("UP_TO_DATE", "none", null); }), p);

        assertTrue(jobWithout("allow_full").run(ctx).success());
        assertFalse(seen.get().allowFull(), "a full build is never started unless the Job says so");
    }

    @Test
    void everyRefusalFailureAndUnfinishedBuildFailsTheRunWithItsReason() throws Exception {
        for (String result : List.of("REFUSED", "FAILED", "RUNNING")) {
            Ctx ctx = new Ctx(false, grantOf(r -> new Outcome(result, "full", "FULL_NOT_ALLOWED", "why", 0, 0, 0)), PARAMS);
            JobResult r = job().run(ctx);
            assertFalse(r.success(), result);
            assertTrue(r.message().contains("why"), r.message());
            assertEquals(List.of("la.index.build.completed"), ctx.signals, "the refusal is still announced");
            assertEquals("FULL_NOT_ALLOWED", ctx.payloads.get(0).get("code"));
        }
    }

    @Test
    void aMissingRequiredParameterIsRejectedBeforeAnythingRuns() {
        Map<String, String> p = new HashMap<>(PARAMS);
        p.remove("owner");
        var ownerless = jobWithout("owner");
        var boom = assertThrows(IllegalArgumentException.class,
                () -> ownerless.run(new Ctx(false, grantOf(r -> outcome("BUILT", "full", null)), PARAMS)));
        assertTrue(boom.getMessage().contains("owner"), boom.getMessage());
    }

    @Test
    void theDryRunStandInBuildsNothing() {
        var calls = new ArrayList<LinkIndexAccess.Request>();
        PlatformServices dry = DryRunServices.wrap(grantOf(r -> { calls.add(r); return outcome("BUILT", "full", null); }),
                new Ctx(true, PlatformServices.none(), PARAMS).log());
        Outcome o = dry.find(LinkIndexAccess.class).orElseThrow().build(
                new LinkIndexAccess.Request("j", "d", "s", "t", null, null, null, null, List.of(), "o", true, 1L));
        assertEquals(Outcome.DRY_RUN, o.result());
        assertTrue(calls.isEmpty());
    }

    @Test
    void theAccessFailsClosedWhenTheLinkAnalysisModuleOrWriteRootIsAbsent(@TempDir Path root) {
        var req = new LinkIndexAccess.Request("j", "d", "s", "t", null, null, null, null, List.of(), "o", false, 1L);
        assertThrows(IllegalStateException.class, () -> LinkIndexAccess.over(() -> null, () -> root, () -> null).build(req));
        assertThrows(IllegalStateException.class,
                () -> LinkIndexAccess.over(() -> (w, d, q) -> outcome("BUILT", "full", null), () -> null, () -> null).build(req));
        assertEquals("BUILT", LinkIndexAccess.over(() -> (w, d, q) -> outcome("BUILT", "full", null), () -> root, () -> null)
                .build(req).result());
    }

    @Test
    void isRegisteredWithTheLinkIndexGrantAndAnAggregateSignal(@TempDir Path dir) throws Exception {
        try (var s = new com.gamma.util.Scheduler();
             JobService js = new JobService(List.of(), new com.gamma.etl.ConsignmentEventBus(), s, null,
                     dir.resolve("audit").toString())) {
            JobTypeDescriptor d = js.jobTypes().stream().filter(t -> "la.index.build".equals(t.id())).findFirst()
                    .orElseThrow(() -> new AssertionError("la.index.build is not registered"));
            assertEquals(List.of("link-index"), d.requires());
            assertEquals(List.of("la.index.build.completed"), d.emits());
            assertTrue(d.parameters().stream().anyMatch(p -> p.name().equals("owner") && p.required()));
        }
    }

    private static final class Ctx implements JobContext {
        private final boolean dryRun;
        private final PlatformServices services;
        private final Map<String, String> params;
        final List<String> signals = new ArrayList<>();
        final List<Map<String, Object>> payloads = new ArrayList<>();

        Ctx(boolean dryRun, PlatformServices services, Map<String, String> params) {
            this.dryRun = dryRun;
            this.services = services;
            this.params = params;
        }

        @Override public String runId()                 { return "run-1"; }
        @Override public String spaceId()               { return "default"; }
        @Override public TriggerInfo trigger()          { return TriggerInfo.parse("manual"); }
        @Override public Map<String, String> config()   { return Map.of(); }
        @Override public Map<String, String> params()   { return params; }
        @Override public boolean dryRun()               { return dryRun; }
        @Override public PlatformServices services()    { return services; }
        @Override public com.gamma.signal.SignalEmitter signals() {
            return (type, severity, payload) -> { signals.add(type); payloads.add(payload); };
        }
        @Override public ArtifactRecorder artifacts() {
            throw new UnsupportedOperationException("la.index.build records no artifacts");
        }
        @Override public RunLog log() {
            return new RunLog() {
                @Override public void info(String m, Object... kv)  { }
                @Override public void warn(String m, Object... kv)  { }
                @Override public void error(String m, Throwable t, Object... kv) { }
            };
        }
    }

    /** T5 owner-spoofing class: trigger args / signal bind / manual params can resolve a different `owner`; it is ignored. */
    @Test
    void aResolvedOwnerFromArgsOrBindIsIgnoredTheAuthoredOneIsUsed() throws Exception {
        var seen = new AtomicReference<LinkIndexAccess.Request>();
        Map<String, String> p = new HashMap<>(PARAMS);
        p.put("owner", "victim");
        p.put("dataset", "other_ds");
        p.put("source_col", "x");
        p.put("allow_full", "false");
        Ctx ctx = new Ctx(false, grantOf(r -> { seen.set(r); return outcome("BUILT", "append", null); }), p);
        assertTrue(job().run(ctx).success());
        assertEquals("analyst-1", seen.get().owner());
    assertEquals("xdr_daily", seen.get().dataset());
        assertTrue(seen.get().allowFull(), "allow_full comes from the saved config");
    }
}
