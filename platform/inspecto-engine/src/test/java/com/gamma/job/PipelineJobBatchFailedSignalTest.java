package com.gamma.job;

import com.gamma.audit.Event;
import com.gamma.etl.ConsignmentEventBus;
import com.gamma.event.EventLog;
import com.gamma.pipeline.PipelineEdge;
import com.gamma.pipeline.PipelineGraph;
import com.gamma.pipeline.PipelineNode;
import com.gamma.pipeline.PipelineStore;
import com.gamma.signal.Signal;
import com.gamma.util.DuckDbUtil;
import com.gamma.util.Scheduler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;

import static org.junit.jupiter.api.Assertions.*;

/**
 * X5 D9 = c, end to end through {@link JobService}: a pipeline job's Run that ENDS FAILED raises exactly one
 * {@code pipeline.batch.failed} — alone, never a {@code ConsignmentEvent}, so no {@code pipeline.commit} — and the
 * decision is the Run's recorded status: a cancelled Run raises nothing, an attempt a retry recovered raises
 * nothing, a Run whose every attempt failed raises one, and a Run refused before its walk raises one with no
 * {@code failedStep}.
 */
class PipelineJobBatchFailedSignalTest {

    private static final String OWNER = "x5-d9-js.test.jar";
    private static final String STEP = "transform.x5_d9_js";

    /** How the contributed Step behaves on its Nth call. */
    private static final AtomicInteger CALLS = new AtomicInteger();
    private static volatile int failFirst;
    private static volatile boolean transientFailure;
    private static final AtomicReference<JobService> CANCEL_FROM_STEP = new AtomicReference<>();

    @TempDir Path tmp;
    private final List<Signal> signals = new CopyOnWriteArrayList<>();
    private final BiConsumer<EventLog, Event> tap = (l, e) -> {
        try {
            Signal s = Signal.fromEvent(e);
            if (s.source() != null && s.source().id() != null && s.source().id().startsWith("d9js_")) signals.add(s);
        } catch (RuntimeException notASignal) { /* not a Signal */ }
    };

    @BeforeEach
    void register() {
        CALLS.set(0);
        failFirst = 0;
        transientFailure = false;
        CANCEL_FROM_STEP.set(null);
        com.gamma.pipeline.PipelineNodeTypes.register(new com.gamma.pipeline.PipelineNodeType() {
            @Override public String type() { return STEP; }
            @Override public java.util.Optional<com.gamma.pipeline.ExecutionMode> mode() {
                return java.util.Optional.of(com.gamma.pipeline.ExecutionMode.EXECUTED);
            }
        }, OWNER);
        com.gamma.pipeline.exec.StepExecutors.register(new com.gamma.pipeline.exec.StepExecutor() {
            @Override public String type() { return STEP; }
            @Override public void execute(com.gamma.pipeline.exec.StepContext ctx) throws Exception {
                if (CALLS.incrementAndGet() > failFirst) {          // passes every row on
                    com.gamma.pipeline.exec.StepInput in = ctx.in();
                    com.gamma.pipeline.exec.StepOutput out = ctx.emit("data");
                    while (in.next()) out.copyRow(in);
                    return;
                }
                JobService js = CANCEL_FROM_STEP.get();
                if (js != null) js.interruptRuns();                // the Space closes under the Run
                if (transientFailure) throw new java.io.UncheckedIOException(new java.io.IOException("share unavailable"));
                throw new IllegalStateException("scoring service unavailable");
            }
        }, OWNER, com.gamma.pipeline.exec.StepExecutors.Grant.NONE);
        JobService.pause = d -> { };
        EventLog.addTap(tap);
    }

    @AfterEach
    void deregister() {
        EventLog.removeTap(tap);
        JobService.pause = d -> Thread.sleep(d.toMillis());
        com.gamma.pipeline.exec.StepExecutors.deregister(OWNER);
        com.gamma.pipeline.PipelineNodeTypes.deregister(OWNER);
    }

    /** Runs job {@code name} once (with {@code retries}) over the boom pipeline, or over {@code flow} when given. */
    private JobRun fire(String name, String flow, int retries, boolean cancelFromStep, List<String> busEvents)
            throws Exception {
        String dataDir = tmp.resolve("data").toString();
        seedParquet(dataDir);
        PipelineStore store = new PipelineStore(tmp.resolve("flows"));
        store.write("d9_flow", new PipelineGraph("d9_flow", true,
                List.of(PipelineNode.of("src", "acquisition", Map.of("source_store", "events")),
                        PipelineNode.of("boom", STEP, Map.of()),
                        new PipelineNode("out", "sink.persistent", "O", null, Map.of("store", "d9_out"), null)),
                List.of(PipelineEdge.data("src", "boom"), PipelineEdge.data("boom", "out"))));
        ConsignmentEventBus bus = new ConsignmentEventBus();
        bus.subscribe(ev -> busEvents.add(ev.status()));
        try (Scheduler s = new Scheduler();
             JobService js = new JobService(List.of(), bus, s, null, tmp.resolve("audit").toString(), null,
                     store, dataDir)) {
            if (cancelFromStep) CANCEL_FROM_STEP.set(js);
            js.upsertJob(new JobConfig(name, JobType.PIPELINE, null, null, true, false,
                    Map.of("flow", flow, "data_dir", dataDir, "consignment_id", name + "-c1",
                            "retries", String.valueOf(retries), "retry_backoff_seconds", "1")));
            js.submit(name, "schedule");
            long until = System.nanoTime() + 30_000_000_000L;
            JobRun r = null;
            while (System.nanoTime() < until) {
                r = js.lastRunOf(name).orElse(null);
                if (r != null && !"RUNNING".equals(r.status())) break;
                Thread.sleep(20);
            }
            Thread.sleep(100);
            return r;
        }
    }

    private List<Signal> batchFailed() {
        return signals.stream().filter(s -> "pipeline.batch.failed".equals(s.type())).toList();
    }

    @Test
    void aFailedRunRaisesExactlyOneSignalNamingTheStepAndNoConsignmentEvent() throws Exception {
        failFirst = 99;
        List<String> busEvents = new CopyOnWriteArrayList<>();
        JobRun r = fire("d9js_fail", "d9_flow", 0, false, busEvents);
        assertEquals("FAILED", r.status(), r.message());
        assertEquals(1, batchFailed().size(), "one Signal per failed Run: " + signals);
        Signal sig = batchFailed().get(0);
        assertEquals("boom", sig.payload().get("failedStep"), sig.payload().toString());
        assertEquals(com.gamma.pipeline.exec.StepReceipt.STEP_FAILED, sig.payload().get("code"));
        assertEquals("d9js_fail-c1", sig.correlationId());
        assertEquals("d9_flow", sig.subject().id());
        assertTrue(busEvents.isEmpty(), "no ConsignmentEvent on a failure: " + busEvents);
        assertTrue(signals.stream().noneMatch(s -> "pipeline.commit".equals(s.type())), "so no pipeline.commit");
    }

    @Test
    void aTransientAttemptARetryRecoversRaisesNothing() throws Exception {
        failFirst = 1;
        transientFailure = true;
        JobRun r = fire("d9js_flaky", "d9_flow", 2, false, new CopyOnWriteArrayList<>());
        assertEquals("SUCCESS", r.status(), r.message());
        assertEquals(2, CALLS.get(), "the first attempt failed, the retry passed");
        assertTrue(batchFailed().isEmpty(), "a recovered attempt is not a failed Run: " + signals);
    }

    @Test
    void aRunWhoseEveryAttemptFailsRaisesOneSignalForTheFinalAttempt() throws Exception {
        failFirst = 99;
        transientFailure = true;
        JobRun r = fire("d9js_down", "d9_flow", 2, false, new CopyOnWriteArrayList<>());
        assertEquals("FAILED", r.status(), r.message());
        assertEquals(3, CALLS.get(), "1 attempt + 2 retries");
        assertEquals(1, batchFailed().size(), "one Signal, for the final attempt: " + signals);
        assertEquals("boom", batchFailed().get(0).payload().get("failedStep"), "the final attempt's Step");
    }

    @Test
    void aCancelledRunRaisesNothing() throws Exception {
        failFirst = 99;
        JobRun r = fire("d9js_cancel", "d9_flow", 0, true, new CopyOnWriteArrayList<>());
        assertEquals("CANCELLED", r.status(), r.message());
        assertTrue(batchFailed().isEmpty(), "a planned stop is not a failure: " + signals);
    }

    @Test
    void aRunRefusedBeforeItsWalkRaisesOneSignalWithNoFailedStep() throws Exception {
        JobRun r = fire("d9js_refused", "no_such_flow", 0, false, new CopyOnWriteArrayList<>());
        assertEquals("FAILED", r.status(), r.message());
        assertEquals(1, batchFailed().size(), signals.toString());
        Signal sig = batchFailed().get(0);
        assertNull(sig.payload().get("failedStep"), "no Step owns a refusal: " + sig.payload());
        assertNull(sig.payload().get("code"));
        assertTrue(String.valueOf(sig.payload().get("error")).startsWith("pipeline job"), sig.payload().toString());
        assertEquals("d9js_refused-c1", sig.correlationId(), "the Consignment it would have been");
        assertEquals(0, CALLS.get(), "nothing walked");
    }

    private static void seedParquet(String dataDir) throws Exception {
        Path dir = Path.of(dataDir, "events");
        Files.createDirectories(dir);
        File db = DuckDbUtil.tempDbFile("d9seed_");
        try (Connection c = DuckDbUtil.openConnection(db); Statement st = c.createStatement()) {
            st.execute("COPY (SELECT * FROM (VALUES (1,150),(2,50)) t(id,amt)) TO '"
                    + dir.resolve("seed.parquet").toString().replace("\\", "/") + "' (FORMAT PARQUET)");
        } finally {
            DuckDbUtil.deleteTempDb(db);
        }
    }
}
