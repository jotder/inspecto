package com.gamma.inspector;

import com.gamma.etl.PipelineConfig;
import com.gamma.pipeline.PipelineGraph;
import com.gamma.pipeline.PipelineLift;
import com.gamma.pipeline.exec.ConsignmentGraphRunner;
import com.gamma.pipeline.exec.PipelineExecutor;
import com.gamma.query.DecisionRuleApplier;
import com.gamma.util.DuckDbUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Branch-aware segment-scoped lift (design {@code branch-aware-segment-lift-design.md}): a {@code route:}
 * on a MULTI-SCHEMA pipeline — a CSV {@code schemas[]} selector or a plugin {@code segments} map — executes
 * ONE schema's route tree per write, never another schema's.
 *
 * <p>S1 pins the defects as they stand today.
 */
class SegmentScopedRouteTest {

    static final DecisionRuleApplier.Result NO_RULES =
            new DecisionRuleApplier.Result(List.of(), List.of());

    /**
     * D1 as it stood: a selector batch writes with {@code writeScope = ""}, so no segment key is known and
     * the seed is the FIRST {@code transform.route} node's upstream — {@code map_alpha} — whatever schema the
     * batch actually is. A {@code beta} batch would be routed by {@code route_alpha} and written through
     * {@code sink_alpha__d*}, whose store/table/schema name alpha.
     */
    @Test
    void aSelectorBatchWithNoWriteKeyIsSeededAtTheFirstSchemasMap(@TempDir Path dir) throws Exception {
        PipelineConfig cfg = PipelineConfig.load(selectorRoutePipeline(dir, false).toString());

        assertNull(ConsignmentIngestStrategy.segmentWrite(cfg, ""), "a selector write carries no segment scope");
        PipelineGraph lifted = ConsignmentIngestStrategy.admittedLift(cfg, NO_RULES, null);
        assertNotNull(lifted, "the route engages the graph lane");
        assertEquals("map_alpha", ConsignmentIngestStrategy.seedOfWrite(lifted, null),
                "with no key the seed is the first schema's map — the wrong one for every non-first schema");
    }

    /**
     * The walk is already segment-scoped by the seed: seeded at {@code map_SMS} over the WHOLE lift, the
     * executor's no-live-inbound skip leaves every CALL node untouched, and only SMS's branch sinks commit.
     */
    @Test
    void aSegmentSeededWalkTouchesNoOtherSegmentsRouteTree(@TempDir Path dir) throws Exception {
        PipelineConfig cfg = PipelineConfig.load(segmentRoutePipeline(dir, false).toString());
        PipelineGraph whole = PipelineLift.lift(cfg);
        assertTrue(whole.byId().containsKey("route_CALL") && whole.byId().containsKey("route_SMS"));

        List<String> written = new ArrayList<>();
        PipelineExecutor.ExecResult r;
        try (Connection conn = DuckDbUtil.openConnection(dir.resolve("walk.duckdb").toFile())) {
            try (Statement st = conn.createStatement()) {
                st.execute("CREATE TABLE transformed_SMS (ID VARCHAR, EVENT_TYPE VARCHAR, EVENT_DATE DATE,"
                        + " __src_id INTEGER)");
                st.execute("INSERT INTO transformed_SMS VALUES ('S1','SMS',DATE '2020-04-03',0),"
                        + " ('B9','SMS',DATE '2020-04-03',0)");
            }
            r = ConsignmentGraphRunner.run(new ConsignmentGraphRunner.Input(conn, whole, "map_SMS",
                            "transformed_SMS", "b1", dir.toString(), "base", dir.resolve("commit.log"), "SMS"),
                    (sink, table) -> written.add(sink.id()), () -> { });
        }
        assertFalse(r.produced().containsKey("route_CALL"), "CALL's route never ran: " + r.produced().keySet());
        assertTrue(r.produced().containsKey("route_SMS"));
        assertEquals(List.of("sink_SMS__d0", "sink_SMS__d1"), written.stream().sorted().toList(),
                "only SMS's branch sinks were written");
    }

    // ── fixtures (shared with the later slices' tests) ────────────────────────

    /** A CSV {@code schemas[2]} selector (alpha: 3 columns, beta: 4) with a two-branch route on {@code ID}. */
    static Path selectorRoutePipeline(Path dir, boolean active) throws Exception {
        String d = dir.toString().replace("\\", "/");
        Path sa = dir.resolve("alpha_schema.toon");
        Path sb = dir.resolve("beta_schema.toon");
        Files.writeString(sa, com.gamma.etl.PipelineConfigBatchTest.miniSchema());
        Files.writeString(sb, """
            partitionKey: EVENT_DATE
            raw:
              name: beta
              format: CSV
              fields[4]{name,selector,type}:
                ID,"0",VARCHAR
                AMT,"1",DOUBLE
                EVENT_DATE,"2",DATE
                NOTE,"3",VARCHAR
            mapping:
              canonicalName: beta
              rawName: beta
              rules[4]{targetColumn,sourceExpression,transformType}:
                ID,ID,DIRECT
                AMT,AMT,DIRECT
                EVENT_DATE,EVENT_DATE,DIRECT
                NOTE,NOTE,DIRECT
            """);
        Path toon = dir.resolve("sel_route_pipeline.toon");
        Files.writeString(toon, """
            name: SEL_ROUTE
            active: %4$s
            dirs:
              poll: %1$s/inbox
              database: %1$s/db
              backup: %1$s/backup
              temp: %1$s/temp
              quarantine: %1$s/quarantine
              markers: %1$s/markers
              status_dir: %1$s/status
            output:
              format: CSV
            sinks[2]{database,format}:
              "%1$s/db_bulk",CSV
              "%1$s/db_normal",CSV
            route:
              mode: case
              default: normal
              branches[2]{key,where,database}:
                bulk,"ID LIKE 'B%%'","%1$s/db_bulk"
                normal,"true","%1$s/db_normal"
            processing:
              threads: 1
              file_pattern: "glob:**/*.csv"
              schemas[2]{column_count,file_pattern,schema_file,table}:
                3, "", "%2$s", alpha
                4, "", "%3$s", beta
              csv_settings:
                delimiter: ","
                skip_header_lines: 0
                date_formats[1]: "%%Y-%%m-%%d"
                timestamp_formats[1]: "%%Y-%%m-%%d"
            """.formatted(d, sa.toString().replace("\\", "/"), sb.toString().replace("\\", "/"), active));
        return toon;
    }

    /** A plugin {@code segments} pipeline (CALL / SMS via the stub ingester) with a two-branch route on {@code ID}. */
    static Path segmentRoutePipeline(Path dir, boolean active) throws Exception {
        String d = dir.toString().replace("\\", "/");
        Path call = dir.resolve("call_schema.toon");
        Path sms = dir.resolve("sms_schema.toon");
        Files.writeString(call, segmentSchema("call"));
        Files.writeString(sms, segmentSchema("sms"));
        Path toon = dir.resolve("seg_route_pipeline.toon");
        Files.writeString(toon, """
            name: SEG_ROUTE
            active: %5$s
            dirs:
              poll: %1$s/inbox
              database: %1$s/db
              backup: %1$s/backup
              temp: %1$s/temp
              quarantine: %1$s/quarantine
              markers: %1$s/markers
              status_dir: %1$s/status
            output:
              format: CSV
            sinks[2]{database,format}:
              "%1$s/db_bulk",CSV
              "%1$s/db_normal",CSV
            route:
              mode: case
              default: normal
              branches[2]{key,where,database}:
                bulk,"ID LIKE 'B%%'","%1$s/db_bulk"
                normal,"true","%1$s/db_normal"
            processing:
              threads: 1
              file_pattern: "glob:**/*.bin"
              ingester: %2$s
              segments:
                CALL: %3$s
                SMS: %4$s
              csv_settings:
                delimiter: ","
                skip_header_lines: 0
                date_formats[1]: "%%Y-%%m-%%d"
                timestamp_formats[1]: "%%Y-%%m-%%d"
            """.formatted(d, ConsignmentIngestorPluginTest.StubEventIngester.class.getName(),
                call.toString().replace("\\", "/"), sms.toString().replace("\\", "/"), active));
        return toon;
    }

    private static String segmentSchema(String name) {
        return """
                partitions[1]{column,source,type}:
                  record_type,EVENT_TYPE,VARCHAR
                raw:
                  name: %1$s
                  format: CSV
                  fields[3]{name,selector,type}:
                    ID,"0",VARCHAR
                    EVENT_TYPE,"1",VARCHAR
                    EVENT_DATE,"2",DATE
                mapping:
                  canonicalName: %1$s
                  rawName: %1$s
                  rules[3]{targetColumn,sourceExpression,transformType}:
                    ID,ID,DIRECT
                    EVENT_TYPE,EVENT_TYPE,DIRECT
                    EVENT_DATE,EVENT_DATE,DIRECT
                """.formatted(name);
    }
}
