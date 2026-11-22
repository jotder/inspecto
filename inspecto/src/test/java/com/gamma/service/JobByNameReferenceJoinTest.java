package com.gamma.service;

import com.gamma.job.JobConfig;
import com.gamma.job.JobRun;
import com.gamma.util.DuckDbUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.*;

/**
 * LOOKUP-1: a pipeline Job's {@code transform.join} binds {@code reference/<pipeline>} BY NAME in a running server.
 * {@code JobService.pipelineConfigs} existed and {@code PipelineJobRunner} read it, but the hosting
 * {@link CollectorService} never set it — so every by-name join in a Job failed with "no such pipeline is loaded",
 * while the same join against a file path worked. This boots the service with a Reference Pipeline and a fact
 * Pipeline, lands both, runs the Job as the server runs it, and checks the join read the Reference.
 */
class JobByNameReferenceJoinTest {

    @Test
    void aJobsJoinResolvesAReferenceByPipelineName(@TempDir Path dir) throws Exception {
        String d = dir.toString().replace('\\', '/');
        Path cfgDir = Files.createDirectories(dir.resolve("config"));

        // the Reference: dialling prefixes → operator
        Files.writeString(cfgDir.resolve("prefixes_schema.toon"), """
                raw:
                  name: PREFIXES
                  format: CSV
                  fields[2]{name,selector,type}:
                    prefix,"0",VARCHAR
                    operator,"1",VARCHAR
                mapping:
                  canonicalName: prefixes
                  rawName: PREFIXES
                  fields[2]:
                    - name: prefix
                      from: prefix
                      fn: keep
                    - name: operator
                      from: operator
                      fn: keep
                """);
        Path refPipe = cfgDir.resolve("prefixes_pipeline.toon");
        Files.writeString(refPipe, pipeline("prefixes", d, "prefixes_schema.toon", "produces: reference\n", ""));

        // the facts: call records, enriched at rest by the Job
        Files.writeString(cfgDir.resolve("calls_schema.toon"), """
                partitionKey: call_date
                raw:
                  name: CALLS
                  format: CSV
                  fields[3]{name,selector,type}:
                    id,"0",VARCHAR
                    msisdn,"1",VARCHAR
                    call_date,"2",DATE
                mapping:
                  canonicalName: calls
                  rawName: CALLS
                  fields[3]:
                    - name: call_date
                      from: call_date
                      fn: keep
                    - name: id
                      from: id
                      fn: keep
                    - name: msisdn
                      from: msisdn
                      fn: keep
                """);
        Path factPipe = cfgDir.resolve("calls_pipeline.toon");
        Files.writeString(factPipe, pipeline("calls", d, "calls_schema.toon", "output_store: calls_enriched\n", """
                steps[1]:
                  - join:
                      reference: reference/prefixes
                      key: prefix
                      match: longest_prefix
                      on[1]: msisdn
                      columns[1]: operator
                """));

        Files.createDirectories(dir.resolve("data/inbox/prefixes"));
        Files.writeString(dir.resolve("data/inbox/prefixes/PREFIXES.csv"), "prefix,operator\n44,UK\n4477,UK mobile\n");
        Files.createDirectories(dir.resolve("data/inbox/calls"));
        Files.writeString(dir.resolve("data/inbox/calls/CALLS.csv"), "id,msisdn,call_date\n1,447700900123,2026-11-01\n2,441234567890,2026-11-01\n3,999,2026-11-02\n");

        String priorRoots = System.getProperty("assist.safety.roots");
        System.setProperty("assist.safety.roots", d);
        CollectorService svc = null;
        try {
            JobConfig job = JobConfig.fromMap(Map.of("job", Map.of("name", "calls_join", "type", "pipeline",
                    "pipeline_config", factPipe.toString().replace('\\', '/'),
                    "data_dir", d + "/data")));   // the stores' root — a Space supplies it; a bare service does not
            svc = new CollectorService(List.of(refPipe, factPipe), List.of(), List.of(job), 3600, 1, null);
            // land both through the service, so its registry records the files as readable — as the server does
            assertTrue(svc.runPipeline("prefixes").isPresent(), "the Reference lands");
            assertTrue(svc.runPipeline("calls").isPresent(), "the facts land");
            var jobs = svc.jobServiceOrCreate();
            assertTrue(jobs.trigger("calls_join"), "the Job is known and starts");

            Optional<JobRun> last = Optional.empty();
            for (int i = 0; i < 300 && (last.isEmpty() || "RUNNING".equals(last.get().status())); i++) {
                Thread.sleep(100);
                last = jobs.lastRunOf("calls_join");
            }
            assertTrue(last.isPresent(), "the Job ran");
            assertEquals("SUCCESS", last.get().status(), last.get().message());

            Map<String, String> operator = new TreeMap<>();
            java.io.File db = DuckDbUtil.tempDbFile("jbn_");
            try (Connection c = DuckDbUtil.openConnection(db); Statement st = c.createStatement();
                 ResultSet rs = st.executeQuery("SELECT id, msisdn_operator FROM read_parquet('" + d
                         + "/data/calls_enriched/**/*.parquet')")) {
                while (rs.next()) operator.put(rs.getString(1), rs.getString(2));
            }
            DuckDbUtil.deleteTempDb(db);
            assertEquals("UK mobile", operator.get("1"), "the longest prefix (4477) of the Reference read by name");
            assertEquals("UK", operator.get("2"));
            assertNull(operator.get("3"));
        } finally {
            if (svc != null) svc.close();
            if (priorRoots != null) System.setProperty("assist.safety.roots", priorRoots);
            else System.clearProperty("assist.safety.roots");
        }
    }

    private static String pipeline(String name, String d, String schema, String extraTop, String steps) {
        return """
                name: %1$s
                active: true
                %4$s
                dirs:
                  poll:       %2$s/data/inbox/%1$s
                  database:   %2$s/data/%1$s/database
                  backup:     %2$s/data/%1$s/backup
                  temp:       %2$s/data/%1$s/temp
                  errors:     %2$s/data/%1$s/errors
                  quarantine: %2$s/data/%1$s/quarantine
                  markers:    %2$s/data/%1$s/markers
                  status_dir: %2$s/data/%1$s/status
                  log_dir:    %2$s/data/%1$s/logs

                output:
                  format: PARQUET

                processing:
                  threads: 1
                  file_pattern: "glob:**/*.csv"
                  schema_file: %3$s
                  csv_settings:
                    delimiter: ","
                    date_formats[1]: "%%Y-%%m-%%d"

                %5$s
                """.formatted(name, d, schema, extraTop, steps);
    }
}
