package com.gamma.service;

import com.gamma.etl.ConsignmentEvent;
import com.gamma.etl.ConsignmentEventBus;
import com.gamma.etl.PipelineConfigBatchTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link PipelineScheduler}'s event-trigger FILTERS, driven directly — the last of
 * {@code SPEC-NOPROOF-1}'s six Musts.
 *
 * <p>🔴 <b>The row said "PipelineScheduler has no test class at all, recorded only in prose". That is
 * literally true and materially misleading, and the correction matters more than the row.</b> No test class
 * is <i>named</i> for the scheduler, but {@code CollectorServiceTriggerTest} already covers its behaviour
 * end-to-end through the public seam: no-trigger rides every cycle, {@code schedule:{every}} gates by
 * cadence, {@code cron} is not due at start, {@code manual} is off the loop, an {@code event} trigger fires
 * on its upstream's commit, and the {@code on: dataset} fence holds. A class-name search says "untested";
 * the behaviour is covered under a collaborator's name. ⚠ Same shape as the {@code AbsentExchangeRoutes}
 * miss earlier in this programme — <b>grep the behaviour, not the identifier</b>.
 *
 * <p><b>So this class does not duplicate that. It covers the filters those end-to-end tests cannot reach
 * cheaply</b>, by constructing the scheduler with a recording {@code Consumer<String>} in place of the run
 * path — no ETL, no output files, no poll loop. Two of them are the ones that would hurt:
 * <ul>
 *   <li>a <b>non-SUCCESS</b> commit must trigger nothing. Without this, a FAILED upstream would fan its
 *       failure out as downstream runs over data that was never committed;</li>
 *   <li>the <b>self-loop guard</b>: a pipeline whose {@code from} names itself must not re-trigger on its
 *       own commit. Without it, one commit becomes an unbounded run loop.</li>
 * </ul>
 *
 * <p><b>Mutation-proven 2026-09-09:</b> removing the {@code SUCCESS} filter and the self-loop guard
 * together fails <b>exactly 2 of 6</b> — their own two tests and nothing else. The third test is the
 * control that makes those refusals meaningful: a matching SUCCESS commit DOES fire, so the two
 * "triggers nothing" assertions cannot pass merely because the harness never fires.
 *
 * <p>⛔ The scheduler's own {@code dueThisTick}/{@code cronDue} stay untouched here: they are private, and
 * {@code runOne} calls real ETL through {@code MultiCollectorProcessor}, so reaching them would need
 * reflection or a production change for a test's benefit. Their semantics are what
 * {@code CollectorServiceTriggerTest} already proves from the outside, which is the better place for them.
 */
class PipelineSchedulerEventTriggerTest {

    /** A parseable single-schema pipeline with an optional top-level trigger block. */
    private static Path pipeline(Path root, String name, String triggerBlock) throws Exception {
        Files.createDirectories(root);
        Path schema = root.resolve("schema.toon");
        Files.writeString(schema, PipelineConfigBatchTest.miniSchema());
        String dirs = root.toString().replace('\\', '/');
        String toon = """
                name: %1$s
                active: %5$s
                %2$sdirs:
                  poll: %3$s/inbox
                  database: %3$s/db
                  backup: %3$s/backup
                  temp: %3$s/temp
                  quarantine: %3$s/quarantine
                  markers: %3$s/markers
                  status_dir: %3$s/status
                  log_dir: %3$s/logs
                output:
                  format: CSV
                processing:
                  threads: 1
                  file_pattern: "glob:**/*.csv"
                  duplicate_check:
                    enabled: true
                    marker_extension: .processed
                  schema_file: "%4$s"
                  csv_settings:
                    delimiter: ","
                    has_header: true
                    date_formats[1]: "%%Y-%%m-%%d"
                    timestamp_formats[1]: "%%Y-%%m-%%d"
                """.formatted(name, triggerBlock == null ? "" : triggerBlock, dirs,
                schema.toString().replace('\\', '/'), "true");
        Path p = root.resolve(name.toLowerCase() + "_pipeline.toon");
        Files.writeString(p, toon);
        return p;
    }

    /** The scheduler under test, wired so a "run" is a recorded id rather than an ETL pass. */
    private record Harness(PipelineScheduler scheduler, List<String> ran, Set<String> paused,
                           ExecutorService workers) implements AutoCloseable {
        public void close() { workers.shutdownNow(); }

        /** Let the injected executor drain, so an event's hand-off has actually happened. */
        void settle() throws Exception {
            workers.shutdown();
            assertTrue(workers.awaitTermination(10, TimeUnit.SECONDS), "trigger workers did not drain");
        }
    }

    private static Harness harness(List<Path> configs) {
        ConfigRegistry registry = new ConfigRegistry();
        registry.rebuild(configs);
        List<String> ran = Collections.synchronizedList(new ArrayList<>());
        Set<String> paused = Collections.synchronizedSet(new HashSet<>());
        ExecutorService workers = Executors.newSingleThreadExecutor();
        Consumer<String> runPipeline = ran::add;
        PipelineScheduler scheduler = new PipelineScheduler(
                configs, registry, paused, Collections.synchronizedSet(new HashSet<>()),
                new PipelineRunGuard(), new ReentrantLock(), new ConsignmentEventBus(),
                workers, 2, 2, 1, 1000L, runPipeline, () -> {});
        return new Harness(scheduler, ran, paused, workers);
    }

    private static ConsignmentEvent commit(String pipeline, String status) {
        return new ConsignmentEvent(pipeline, "b1", status, List.of(), 10L, 5L, 0);
    }

    // ── the two that would hurt ───────────────────────────────────────────────────────────────────

    @Test
    void aNonSuccessCommitTriggersNothing(@TempDir Path dir) throws Exception {
        Path up = pipeline(dir.resolve("up"), "UP_STREAM", "trigger:\n  type: manual\n");
        Path down = pipeline(dir.resolve("down"), "DOWN_STREAM",
                "trigger:\n  type: event\n  on: commit\n  from: up_stream\n");

        try (Harness h = harness(List.of(up, down))) {
            h.scheduler.onUpstreamCommit(commit("up_stream", "FAILED"));
            h.settle();
            assertEquals(List.of(), h.ran,
                    "a FAILED upstream must trigger NOTHING — otherwise a failure fans out as downstream "
                            + "runs over data that was never committed");
        }
    }

    @Test
    void aTriggerThatNamesItselfDoesNotRetriggerOnItsOwnCommit(@TempDir Path dir) throws Exception {
        Path self = pipeline(dir.resolve("self"), "LOOPER",
                "trigger:\n  type: event\n  on: commit\n  from: looper\n");

        try (Harness h = harness(List.of(self))) {
            h.scheduler.onUpstreamCommit(commit("looper", "SUCCESS"));
            h.settle();
            assertEquals(List.of(), h.ran,
                    "the self-loop guard: a pipeline triggering on its own commit would turn one commit "
                            + "into an unbounded run loop");
        }
    }

    // ── the control: the same wiring DOES fire when it should ─────────────────────────────────────

    @Test
    void aMatchingSuccessCommitDoesFire(@TempDir Path dir) throws Exception {
        Path up = pipeline(dir.resolve("up"), "UP_STREAM", "trigger:\n  type: manual\n");
        Path down = pipeline(dir.resolve("down"), "DOWN_STREAM",
                "trigger:\n  type: event\n  on: commit\n  from: up_stream\n");

        try (Harness h = harness(List.of(up, down))) {
            h.scheduler.onUpstreamCommit(commit("up_stream", "SUCCESS"));
            h.settle();
            assertEquals(List.of("down_stream"), h.ran,
                    "the control that makes the two refusals above meaningful — without it they could pass "
                            + "because the harness never fires at all");
        }
    }

    // ── the remaining filters ─────────────────────────────────────────────────────────────────────

    @Test
    void aPausedPipelineDoesNotFireOnAnEvent(@TempDir Path dir) throws Exception {
        Path up = pipeline(dir.resolve("up"), "UP_STREAM", "trigger:\n  type: manual\n");
        Path down = pipeline(dir.resolve("down"), "DOWN_STREAM",
                "trigger:\n  type: event\n  on: commit\n  from: up_stream\n");

        try (Harness h = harness(List.of(up, down))) {
            h.paused.add("down_stream");
            h.scheduler.onUpstreamCommit(commit("up_stream", "SUCCESS"));
            h.settle();
            assertEquals(List.of(), h.ran, "a paused pipeline must stay paused for EVENT triggers too");
        }
    }

    /**
     * {@code triggerMatches} is case-insensitive and tolerates a namespace prefix, so an operator may write
     * {@code from: orders}, {@code ORDERS} or {@code flows/orders}. Nothing held it to that.
     */
    @Test
    void theTriggerFromToleratesCaseAndANamespacePrefix(@TempDir Path dir) throws Exception {
        Path up = pipeline(dir.resolve("up"), "UP_STREAM", "trigger:\n  type: manual\n");
        Path shouty = pipeline(dir.resolve("a"), "SHOUTY",
                "trigger:\n  type: event\n  on: commit\n  from: UP_STREAM\n");
        Path prefixed = pipeline(dir.resolve("b"), "PREFIXED",
                "trigger:\n  type: event\n  on: commit\n  from: flows/up_stream\n");

        try (Harness h = harness(List.of(up, shouty, prefixed))) {
            h.scheduler.onUpstreamCommit(commit("up_stream", "SUCCESS"));
            h.settle();
            List<String> ran = new ArrayList<>(h.ran);
            Collections.sort(ran);
            assertEquals(List.of("prefixed", "shouty"), ran,
                    "both spellings must match the same upstream: " + ran);
        }
    }

    /**
     * The mirror of the {@code on: dataset} fence {@code CollectorServiceTriggerTest} already covers from
     * the other side: a {@code on: commit} pipeline must NOT fire on a Dataset write of the same name.
     */
    @Test
    void aCommitTriggeredPipelineDoesNotFireOnADatasetWrite(@TempDir Path dir) throws Exception {
        Path down = pipeline(dir.resolve("down"), "DOWN_STREAM",
                "trigger:\n  type: event\n  on: commit\n  from: orders\n");

        try (Harness h = harness(List.of(down))) {
            h.scheduler.onDatasetWrite("orders");
            h.settle();
            assertEquals(List.of(), h.ran,
                    "the two namespaces are distinct: a commit-triggered pipeline must ignore a Dataset "
                            + "write, exactly as a dataset-triggered one ignores a pipeline commit");
        }
    }
}
