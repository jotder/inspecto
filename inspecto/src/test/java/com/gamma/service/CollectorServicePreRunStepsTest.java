package com.gamma.service;

import com.gamma.service.CollectorService.PreRun;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code BASIC-ELT-PROFILE-1} BE-1b: the engine's ordered registry of named pre-run steps, the other side of the commit
 * steps. Steps run in their stated order before the run reads its inbox, a re-registration of a name replaces it, a step
 * that throws stops neither the others nor the run; each step's after-run is told the outcome, last first, after the run's
 * commit steps; a SKIP keeps the run from starting; a scheduled run says so; a dry run runs none.
 */
class CollectorServicePreRunStepsTest {

    private static final String[] NAMES = {"p.a", "p.b", "p.c", "p.boom", "p.skip", "p.after"};
    private static final String[] COMMITS = {"p.commit", "p.run"};
    private final List<String> seen = Collections.synchronizedList(new ArrayList<>());

    @AfterEach
    void forget() {
        for (String n : NAMES) CollectorService.removePreRunStep(n);
        for (String n : COMMITS) CollectorService.removeCommitStep(n);
    }

    private static Path pipeline(Path root, String name, String... files) throws Exception {
        return CollectorServiceCommitStepsTest.pipeline(root, name, files);
    }

    private static void inbox(Path root, String file) throws Exception {
        CollectorServiceCommitStepsTest.inbox(root, file);
    }

    private CollectorService.PreRunStep record(String name, String pipeline) {
        return (svc, p, scheduled) -> {
            if (pipeline.equalsIgnoreCase(p)) seen.add(name);
            return PreRun.GO;
        };
    }

    @Test
    void stepsRunInTheirOrderAReRegistrationReplacesAndAThrowStopsNothing(@TempDir Path dir) throws Exception {
        CollectorService.preRunStep("p.b", 20, record("p.b-first", "ordered"));
        CollectorService.preRunStep("p.c", 10, record("p.c", "ordered"));
        CollectorService.preRunStep("p.a", 10, record("p.a", "ordered"));
        CollectorService.preRunStep("p.boom", 1, (svc, p, scheduled) -> {
            throw new IllegalStateException("a step that fails");
        });
        // the same name again: replaces the first, takes the new order
        CollectorService.preRunStep("p.b", 5, record("p.b", "ordered"));
        try (CollectorService svc = new CollectorService(List.of(pipeline(dir, "ORDERED", "one.csv")), 3600, 1)) {
            assertEquals(1, svc.runPipeline("ordered").orElseThrow().total());
            assertEquals(1, svc.statusStore().files(svc.configFor("ordered").orElseThrow()).size(), "the run itself went ahead");
        }
        assertEquals(List.of("p.b", "p.a", "p.c"), seen, "ascending order, a tie by name; the replaced step never ran; "
                + "the step that threw stopped neither the next step nor the run");
    }

    @Test
    void aStepRunsBeforeTheReadAndItsAfterRunAfterTheCommitStepsToldTheOutcome(@TempDir Path dir) throws Exception {
        List<Boolean> scheduledSeen = Collections.synchronizedList(new ArrayList<>());
        // the pre-run step drops the run's only file: the run reads it only if the step ran first
        CollectorService.preRunStep("p.a", 10, (svc, p, scheduled) -> {
            if (!"before".equalsIgnoreCase(p)) return PreRun.GO;
            try {
                inbox(dir, "dropped-" + scheduledSeen.size() + ".csv");
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
            seen.add("pre");
            scheduledSeen.add(scheduled);
            return PreRun.then(ok -> seen.add("after:" + ok));
        });
        CollectorService.commitStep("p.commit", 10, CollectorService.CommitPhase.COMMIT, (svc, e) -> {
            if ("before".equalsIgnoreCase(e.pipeline()) && "SUCCESS".equals(e.status())) seen.add("commit");
        });
        CollectorService.commitStep("p.run", 20, CollectorService.CommitPhase.RUN, (svc, e) -> {
            if ("before".equalsIgnoreCase(e.pipeline())) seen.add("run");
        });
        try (CollectorService svc = new CollectorService(List.of(pipeline(dir, "BEFORE")), 3600, 1)) {
            svc.runPipeline("before").orElseThrow();
            assertEquals(List.of("pre", "commit", "run", "after:true"), seen,
                    "the step's file was read by the run (one commit), then the RUN step, then the step's after-run");

            seen.clear();
            assertEquals(1, svc.runAllOnce().total());
            assertEquals(List.of("pre", "commit", "run", "after:true"), seen, "the poll cycle's run: the same order");
            assertEquals(List.of(false, true), scheduledSeen, "a person's run is not scheduled; the poll cycle's is");

            // a dry run changes nothing: no pre-run step
            seen.clear();
            svc.runPipeline("before", false, true).orElseThrow();
            assertEquals(List.of(), seen);
        }
    }

    @Test
    void aSkipKeepsTheRunFromStartingAndTellsTheStepsBeforeIt(@TempDir Path dir) throws Exception {
        CollectorService.preRunStep("p.a", 10, (svc, p, scheduled) -> {
            if (!"skipped".equalsIgnoreCase(p)) return PreRun.GO;
            seen.add("a");
            return PreRun.then(ok -> seen.add("a.after:" + ok));
        });
        CollectorService.preRunStep("p.skip", 20, (svc, p, scheduled) -> "skipped".equalsIgnoreCase(p) ? PreRun.SKIP : PreRun.GO);
        CollectorService.preRunStep("p.c", 30, record("c", "skipped"));
        CollectorService.commitStep("p.commit", 10, CollectorService.CommitPhase.COMMIT, (svc, e) -> {
            if ("skipped".equalsIgnoreCase(e.pipeline())) seen.add("commit");
        });
        try (CollectorService svc = new CollectorService(List.of(pipeline(dir, "SKIPPED", "waiting.csv")), 3600, 1)) {
            assertEquals(0, svc.runPipeline("skipped").orElseThrow().total(), "a skipped run is an empty result");
            assertEquals(List.of("a", "a.after:false"), seen, "no later step, no commit; the step before is told it did not run");
            assertTrue(Files.exists(dir.resolve("inbox").resolve("waiting.csv")), "the file waits for the next run");
        }
    }
}
