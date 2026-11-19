package com.gamma.service;

import com.gamma.etl.ConsignmentEvent;
import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.util.IngestActivity;
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
 * {@code BASIC-ELT-PROFILE-1} BE-1a: the engine's ordered registry of named commit steps. Steps run in their stated order,
 * a re-registration of a name replaces it, a step that throws stops neither the others nor the publishing thread; a COMMIT
 * step runs per Consignment inside its ingest, a RUN step once per run (a run of N Consignments, by Run now and by the poll
 * cycle) outside every ingest scope.
 */
class CollectorServiceCommitStepsTest {

    private static final String[] NAMES = {"t.a", "t.b", "t.c", "t.boom", "t.run"};
    private final List<String> seen = Collections.synchronizedList(new ArrayList<>());

    @AfterEach
    void forget() {
        for (String n : NAMES) CollectorService.removeCommitStep(n);
    }

    /** A pipeline named {@code name} under {@code root} with one CSV per entry of {@code files} waiting in its inbox. */
    static Path pipeline(Path root, String name, String... files) throws Exception {
        Files.createDirectories(root.resolve("inbox"));
        Path schema = root.resolve("schema.toon");
        Files.writeString(schema, PipelineConfigBatchTest.miniSchema());
        for (String f : files) inbox(root, f);
        String toon = """
                name: %1$s
                active: true
                dirs:
                  poll: %2$s/inbox
                  database: %2$s/db
                  backup: %2$s/backup
                  temp: %2$s/temp
                  quarantine: %2$s/quarantine
                  markers: %2$s/markers
                  status_dir: %2$s/status
                  log_dir: %2$s/logs
                output:
                  format: CSV
                processing:
                  threads: 1
                  file_pattern: "glob:**/*.csv"
                  duplicate_check:
                    enabled: true
                    marker_extension: .processed
                  schema_file: "%3$s"
                  csv_settings:
                    delimiter: ","
                    has_header: true
                    date_formats[1]: "%%Y-%%m-%%d"
                    timestamp_formats[1]: "%%Y-%%m-%%d"
                """.formatted(name, root.toString().replace('\\', '/'), schema.toString().replace('\\', '/'));
        Path p = root.resolve(name.toLowerCase() + "_pipeline.toon");
        Files.writeString(p, toon);
        return p;
    }

    static void inbox(Path root, String file) throws Exception {
        Files.writeString(root.resolve("inbox").resolve(file), "ID,AMT,EVENT_DATE\n" + file + ",10,2020-01-01\n");
    }

    private CollectorService.CommitStep record(String name, String pipeline) {
        return (svc, e) -> {
            if (pipeline.equalsIgnoreCase(e.pipeline()) && "SUCCESS".equals(e.status())) seen.add(name);
        };
    }

    @Test
    void stepsRunInTheirOrderAReRegistrationReplacesAndAThrowStopsNothing(@TempDir Path dir) throws Exception {
        CollectorService.commitStep("t.b", 20, CollectorService.CommitPhase.COMMIT, record("t.b-first", "ordered"));
        CollectorService.commitStep("t.c", 10, CollectorService.CommitPhase.COMMIT, record("t.c", "ordered"));
        CollectorService.commitStep("t.a", 10, CollectorService.CommitPhase.COMMIT, record("t.a", "ordered"));
        CollectorService.commitStep("t.boom", 1, CollectorService.CommitPhase.COMMIT, (svc, e) -> {
            throw new IllegalStateException("a step that fails");
        });
        // the same name again: replaces the first, takes the new order
        CollectorService.commitStep("t.b", 5, CollectorService.CommitPhase.COMMIT, record("t.b", "ordered"));
        try (CollectorService svc = new CollectorService(List.of(pipeline(dir, "ORDERED", "one.csv")), 3600, 1)) {
            assertEquals(1, svc.runPipeline("ordered").orElseThrow().total());
        }
        assertEquals(List.of("t.b", "t.a", "t.c"), seen, "ascending order, a tie by name; the replaced step never ran; "
                + "the step that threw stopped neither the next step nor the run");
    }

    @Test
    void aRunStepRunsOncePerRunOutsideIngestAfterEveryCommitStep(@TempDir Path dir) throws Exception {
        List<Integer> ingestAt = Collections.synchronizedList(new ArrayList<>());
        List<ConsignmentEvent> runEvents = Collections.synchronizedList(new ArrayList<>());
        CollectorService.commitStep("t.a", 10, CollectorService.CommitPhase.COMMIT, (svc, e) -> {
            if (!"coalesced".equalsIgnoreCase(e.pipeline())) return;
            seen.add("commit");
            ingestAt.add(IngestActivity.inFlight());
        });
        CollectorService.commitStep("t.run", 20, CollectorService.CommitPhase.RUN, (svc, e) -> {
            if (!"coalesced".equalsIgnoreCase(e.pipeline())) return;
            seen.add("run");
            ingestAt.add(IngestActivity.inFlight());
            runEvents.add(e);
        });
        try (CollectorService svc = new CollectorService(List.of(pipeline(dir, "COALESCED", "a.csv", "b.csv", "c.csv")), 3600, 1)) {
            // one file per Consignment (the default batch): Run now commits three times
            assertEquals(1, svc.runPipeline("coalesced").orElseThrow().total());
            assertEquals(List.of("commit", "commit", "commit", "run"), seen, "the RUN step once, after the run's last commit");
            assertTrue(ingestAt.subList(0, 3).stream().allMatch(n -> n > 0), "a COMMIT step runs inside its Consignment's ingest: " + ingestAt);
            assertEquals(0, ingestAt.get(3), "the RUN step runs outside every ingest scope (an index build waits for ingest)");
            assertEquals("SUCCESS", runEvents.get(0).status());

            // the poll cycle's run of the same Pipeline: two more files, the RUN step once more
            seen.clear();
            inbox(dir, "d.csv");
            inbox(dir, "e.csv");
            assertEquals(1, svc.runAllOnce().total());
            assertEquals(List.of("commit", "commit", "run"), seen, "the poll's run: once, after its last commit");

            // a run that commits nothing runs no RUN step
            seen.clear();
            svc.runPipeline("coalesced").orElseThrow();
            assertEquals(List.of(), seen);
        }
    }
}
