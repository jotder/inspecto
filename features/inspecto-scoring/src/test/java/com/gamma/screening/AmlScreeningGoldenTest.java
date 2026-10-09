package com.gamma.screening;

import com.gamma.entitystore.EntityFactLog;
import com.gamma.entitystore.EntityTypes;
import com.gamma.etl.ConsignmentEventBus;
import com.gamma.etl.PipelineConfig;
import com.gamma.inspector.CollectorProcessor;
import com.gamma.job.JobConfig;
import com.gamma.job.JobRun;
import com.gamma.job.JobService;
import com.gamma.util.Scheduler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code PACK-AML-1}: the {@code aml} template's {@code screening.run} Job and Report Template, consumed through their
 * shipped surfaces only. The Entity Lists cannot ship inside a template (they are facts in the Space's own log), so the
 * test creates them the way an operator would, enables the shipped Job, and screens the committed party register.
 * Exact: four planted parties hit, two look-alikes do not, a second run raises nothing new.
 */
class AmlScreeningGoldenTest {

    private static final Path TEMPLATE = Path.of("..", "..", "spaces", "_templates", "aml").toAbsolutePath().normalize();

    @AfterEach
    void clear() {
        System.clearProperty("assist.write.root");
        System.clearProperty("data.dir");
    }

    private static void list(Path root, String id, List<String> entries) throws Exception {
        EntityFactLog log = new EntityFactLog(root);
        log.append(log.read(), "analyst-1", "test", "list.created", id,
                Map.of("title", id, "purpose", "block", "entityType", "subscriber", "normaliser", "default"));
        log.append(log.read(), "analyst-1", "test", "list.member.added", id,
                Map.of("keys", entries.stream().map(v -> EntityTypes.normalise("default", v)).toList()));
    }

    @Test
    void theShippedScreeningJobHitsExactlyThePlantedPartiesAndNoLookAlike(@TempDir Path tmp) throws Exception {
        Path space = com.gamma.pack.AmlPackGoldenTestSupport.copy(tmp);
        Path cfg = space.resolve("config"), data = space.resolve("data");
        Files.createDirectories(cfg);
        Files.writeString(cfg.resolve("link-analysis.toon"), "masking_mode: none\n");
        PipelineConfig pc = PipelineConfig.load(cfg.resolve("aml_parties/aml_parties_pipeline.toon").toString());
        Path inbox = Files.createDirectories(Path.of(pc.dirs().poll()));
        try (Stream<Path> f = Files.list(space.resolve("data/samples/aml_parties"))) {
            for (Path p : f.toList()) Files.copy(p, inbox.resolve(p.getFileName()));
        }
        CollectorProcessor.run(pc);
        list(cfg, "aml_sanctions", List.of("Zorvath Kellander", "NID-7700123"));
        list(cfg, "aml_pep", List.of("Mirela Tarkan"));

        Path jobFile = cfg.resolve("jobs/aml_screening_job.toon");
        assertTrue(Files.readString(jobFile).contains("enabled: false"), "ships off until the Entity Lists exist");
        Files.writeString(jobFile, Files.readString(jobFile).replace("enabled: false", "enabled: true"));
        JobConfig job = JobConfig.load(jobFile.toString());
        assertEquals("screening.run", job.type());

        System.setProperty("assist.write.root", cfg.toString());
        System.setProperty("data.dir", data.toString());
        try (Scheduler s = new Scheduler();
             JobService js = new JobService(List.of(job), new ConsignmentEventBus(), s, null,
                     space.resolve("audit").toString(), null, null, data.toString())) {
            js.start();
            JobRun first = run(js, null);
            assertEquals("SUCCESS", first.status(), first.message());
            assertTrue(first.message().contains("4 new hit(s)"), first.message());

            Map<String, String> hits = new TreeMap<>();
            for (Map<String, Object> h : ScreeningHits.list(cfg))
                hits.put(String.valueOf(h.get("subjectKey")), h.get("listId") + "/" + h.get("method"));
            assertEquals(Map.of("P900001", "aml_sanctions/name", "P900002", "aml_sanctions/name",
                    "P900003", "aml_sanctions/identifier", "P900004", "aml_pep/name"), hits,
                    "reordered name, exact name, identifier only, PEP name - the look-alikes P900005 and P900006 do not hit");

            JobRun second = run(js, first.runId());
            assertEquals("SUCCESS", second.status(), second.message());
            assertTrue(second.message().contains("0 new hit(s)"), "never the same hit twice: " + second.message());
        }
    }

    @Test
    void theReportTemplateLoadsWithNoProblem(@TempDir Path tmp) throws Exception {
        Path space = com.gamma.pack.AmlPackGoldenTestSupport.copy(tmp);
        com.gamma.regreporting.ReportTemplate.Catalog catalog =
                com.gamma.regreporting.ReportTemplate.load(space.resolve("config"));
        assertEquals(List.of(), catalog.problems());
        com.gamma.regreporting.ReportTemplate t = catalog.get("aml-str");
        assertNotNull(t, "the pack's template is in the Space's catalog");
        assertEquals("xml", t.format());
        assertEquals(List.of("activityPeriod", "narrative", "reportingEntity", "typology"),
                t.inputKeys().stream().sorted().toList());
    }

    private static JobRun run(JobService js, String previous) throws Exception {
        assertTrue(js.triggerRun("aml_screening", null).isPresent());
        long deadline = System.nanoTime() + 20_000_000_000L;
        JobRun r = null;
        while (System.nanoTime() < deadline) {
            r = js.lastRunOf("aml_screening").orElse(null);
            if (r != null && !"RUNNING".equals(r.status()) && !r.runId().equals(previous)) return r;
            Thread.sleep(50);
        }
        fail("no finished run, last " + r);
        return null;
    }
}
