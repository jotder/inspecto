package com.gamma.pipeline.exec;

import com.gamma.etl.PipelineConfig;
import com.gamma.pipeline.PipelineGraph;
import com.gamma.pipeline.PipelineLift;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Arming plan S1: engagement is computed off the LIFTED graph of a real flat config, not off a
 * flag — these pin that {@link PipelineLift#lift} and {@link ConsignmentGraphRunner#engages} agree
 * with authored intent for the two shapes that matter:
 *
 * <ul>
 *   <li>an authored {@code route:} block (two branches paired to two sinks by database) ENGAGES —
 *       this is the config S2 will divert to the runner;</li>
 *   <li>a plain {@code sinks[2]} fan-out (no route) must NOT engage — multi-destination shipped as
 *       flat-path fan-out in {@code writeAndTrace} (2026-08-02) and stays there. If this test ever
 *       fails, the lift is emitting a second data-fed sink NODE for what is N destinations of ONE
 *       branch, and S2's trigger would divert a config whose semantics the runner does not carry.</li>
 * </ul>
 */
class ConsignmentGraphRunnerLiftEngagementTest {

    @Test
    void anAuthoredRouteBlockLiftsToAnEngagingGraph(@TempDir Path dir) throws Exception {
        PipelineConfig cfg = PipelineConfig.load(writePipeline(dir, "ROUTE_S1", """
            sinks[2]{database,format}:
              "%1$s/emea",CSV
              "%1$s/apac",CSV
            route:
              mode: case
              branches[2]{key,where,database}:
                emea,"ID LIKE 'E%%'","%1$s/emea"
                apac,"ID LIKE 'A%%'","%1$s/apac"
            """));
        assertNotNull(cfg.routeConfig(), "fixture authored a route: block");

        PipelineGraph lifted = PipelineLift.lift(cfg);
        assertEquals(2, ConsignmentGraphRunner.dataFedSinkCount(lifted),
                "each route branch feeds its paired sink");
        assertTrue(ConsignmentGraphRunner.engages(lifted),
                "an authored route: is exactly what the branch-aware executor exists for");
    }

    @Test
    void aPlainMultiDestinationFanOutDoesNotEngage(@TempDir Path dir) throws Exception {
        PipelineConfig cfg = PipelineConfig.load(writePipeline(dir, "FANOUT_S1", """
            sinks[2]{database,format}:
              "%1$s/hot",CSV
              "%1$s/cold",CSV
            """));
        assertNull(cfg.routeConfig(), "no route: authored");

        PipelineGraph lifted = PipelineLift.lift(cfg);
        assertFalse(ConsignmentGraphRunner.engages(lifted),
                "sinks[2] is N destinations of ONE branch — flat-path fan-out, never the runner");
    }

    /** The ConsignmentIngestorSinksTest fixture shape, with the tail block parameterised. */
    private static String writePipeline(Path dir, String name, String tail) throws Exception {
        Path schema = dir.resolve("mini_schema.toon");
        Files.writeString(schema, com.gamma.etl.PipelineConfigBatchTest.miniSchema());
        String d = dir.toString().replace("\\", "/");
        String toon = """
            name: %s
            active: false
            dirs:
              poll: %s/inbox
              database: %s/db
              backup: %s/backup
              temp: %s/temp
              quarantine: %s/quarantine
              markers: %s/markers
              status_dir: %s/status
            output:
              format: CSV
            processing:
              threads: 1
              schema_file: "%s"
              csv_settings:
                delimiter: ","
                skip_header_lines: 0
            """.formatted(name, d, d, d, d, d, d, d, schema.toString().replace("\\", "/"))
                + tail.formatted(d);
        Path p = dir.resolve(name.toLowerCase() + "_pipeline.toon");
        Files.writeString(p, toon);
        return p.toString();
    }
}
