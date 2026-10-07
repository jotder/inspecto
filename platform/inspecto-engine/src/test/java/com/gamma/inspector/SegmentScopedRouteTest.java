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
 * <p>S1 pinned the defects; S3 fixed the seed (D1) and the admission (D2).
 */
class SegmentScopedRouteTest {

    static final DecisionRuleApplier.Result NO_RULES =
            new DecisionRuleApplier.Result(List.of(), List.of());

    /**
     * D1, fixed in S3. A selector batch writes with {@code writeScope = ""}, so before S3 no key was known
     * and the seed was the FIRST {@code transform.route} node's upstream — {@code map_alpha} — whatever the
     * batch's schema: a {@code beta} batch was routed by {@code route_alpha} and written through
     * {@code sink_alpha__d*}. The batch's selected table now names its key, so a beta batch seeds
     * {@code map_beta} and the admitted graph carries beta's tree only.
     */
    @Test
    void aSelectorBatchIsSeededAtItsOwnSchemasMap(@TempDir Path dir) throws Exception {
        PipelineConfig cfg = PipelineConfig.load(selectorRoutePipeline(dir, false).toString());

        assertNull(ConsignmentIngestStrategy.segmentWrite(cfg, ""), "a selector write carries no segment scope");
        String key = ConsignmentIngestStrategy.selectorWrite(cfg, "beta");
        assertEquals("beta", key);
        PipelineGraph lifted = ConsignmentIngestStrategy.admittedLift(cfg, NO_RULES, key);
        assertNotNull(lifted, "the route engages the graph lane");
        assertEquals("map_beta", ConsignmentIngestStrategy.seedOfWrite(lifted, key));
        assertTrue(lifted.byId().containsKey("route_beta"));
        assertFalse(lifted.byId().containsKey("route_alpha"),
                "the admitted graph is beta's subtree — alpha's route tree is not in it");
        assertNull(ConsignmentIngestStrategy.selectorWrite(cfg, "gamma"), "an undeclared table names no key");
        assertNull(ConsignmentIngestStrategy.selectorWrite(cfg, null));
    }

    /** D2, fixed in S3: a per-segment route write is admitted on that segment's slice, not the whole lift. */
    @Test
    void aSegmentRouteWriteIsAdmittedOnItsOwnSlice(@TempDir Path dir) throws Exception {
        PipelineConfig cfg = PipelineConfig.load(segmentRoutePipeline(dir, false).toString());

        PipelineGraph lifted = ConsignmentIngestStrategy.admittedLift(cfg, NO_RULES,
                ConsignmentIngestStrategy.segmentWrite(cfg, "SMS"));
        assertNotNull(lifted);
        assertEquals("map_SMS", ConsignmentIngestStrategy.seedOfWrite(lifted, "SMS"));
        assertTrue(lifted.nodes().stream().noneMatch(n -> n.id().contains("CALL")),
                "no CALL node in the SMS write's graph: " + lifted.byId().keySet());
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

    /**
     * S4: an ACTIVE multi-schema route ARMS at registration when its predicates bind in every schema — the
     * blanket clause-(4) refusal is gone (re-inserting it turns both loads here red).
     */
    @Test
    void anActiveMultiSchemaRouteArmsWhenItsPredicatesBindEverywhere(@TempDir Path dir) throws Exception {
        assertTrue(PipelineConfig.load(segmentRoutePipeline(dir.resolve("seg"), true).toString()).active());
        assertTrue(PipelineConfig.load(selectorRoutePipeline(dir.resolve("sel"), true).toString()).active());
    }

    /** S4 / Q3: a predicate reading a column only SOME schemas map refuses arming, naming them. */
    @Test
    void anActiveMultiSchemaRouteOnAOneSchemaColumnRefusesArming(@TempDir Path dir) throws Exception {
        Path toon = selectorRoutePipeline(dir, true);
        Files.writeString(toon, Files.readString(toon).replace("\"ID LIKE 'B%'\"", "\"NOTE = 'bulk'\""));
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> PipelineConfig.load(toon.toString()));
        assertTrue(e.getMessage().contains("does not bind in schema(s) [alpha]"), e.getMessage());
        assertTrue(e.getMessage().contains("binds in [beta]"), e.getMessage());
    }

    /**
     * S5 / D4: a PARKED segments batch drains. Disabling {@code sink_SMS__d1} (the SMS segment's normal
     * branch) arms — the parkable ids carry the schema suffix — and parks exactly that branch while CALL's
     * two branches and SMS's bulk branch commit; the drain then writes the parked rows under SMS's own home
     * ({@code <normal db>/SMS/…}) and finalises the whole batch once.
     */
    @Test
    void aParkedSegmentsBatchDrainsIntoItsOwnSegmentsHome(@TempDir Path dir) throws Exception {
        Path toon = segmentRoutePipeline(dir, true);
        String armed = Files.readString(toon);
        Files.writeString(toon, withDisabled(armed, "sink_SMS__d1"));
        PipelineConfig cfg = PipelineConfig.load(toon.toString());
        Path inbox = Files.createDirectories(Path.of(cfg.dirs().poll()));
        Files.writeString(inbox.resolve("feed.bin"),
                "CALL,B1,2020-04-03\nCALL,C2,2020-04-03\nSMS,B3,2020-04-03\nSMS,S4,2020-04-03\n");

        CollectorProcessor.run(cfg);

        assertEquals(List.of("B1"), ids(dir.resolve("db_bulk/CALL")));
        assertEquals(List.of("C2"), ids(dir.resolve("db_normal/CALL")));
        assertEquals(List.of("B3"), ids(dir.resolve("db_bulk/SMS")));
        assertEquals(List.of(), ids(dir.resolve("db_normal/SMS")), "the disabled branch parked, wrote nothing");

        String batchId = soleManifestId(dir);
        Files.writeString(toon, armed);   // re-enable
        DrainCommand.Result r = DrainCommand.run(toon.toString(), batchId);

        assertEquals(List.of("sink_SMS__d1"), r.drainedBranches());
        assertEquals(List.of("S4"), ids(dir.resolve("db_normal/SMS")), "drained into SMS's own home");
        assertEquals(List.of("B1"), ids(dir.resolve("db_bulk/CALL")), "nothing already committed is rewritten");
        String mf;
        try (java.util.stream.Stream<Path> w = Files.walk(dir.resolve("status"))) {
            mf = Files.readString(w.filter(p -> p.getFileName().toString().equals(batchId + ".json"))
                    .findFirst().orElseThrow()).replace("\\\\", "/");
        }
        assertTrue(mf.contains("\"SUCCESS\"") && !mf.contains("\"parkedAt\""), mf);
        assertTrue(mf.contains("db_bulk/CALL") && mf.contains("db_normal/SMS"),
                "one commit tail lists every segment's outputs: " + mf);
    }

    /** S5 / D4, selector twin: a parked {@code beta} batch drains under beta's table dir. */
    @Test
    void aParkedSelectorBatchDrainsItsOwnSchema(@TempDir Path dir) throws Exception {
        Path toon = selectorRoutePipeline(dir, true);
        String armed = Files.readString(toon);
        Files.writeString(toon, withDisabled(armed, "sink_beta__d1"));
        PipelineConfig cfg = PipelineConfig.load(toon.toString());
        Path inbox = Files.createDirectories(Path.of(cfg.dirs().poll()));
        Files.writeString(inbox.resolve("b.csv"), "ID,AMT,EVENT_DATE,NOTE\nB1,1.0,2020-04-03,x\nN2,2.0,2020-04-03,y\n");

        CollectorProcessor.run(cfg);
        assertEquals(List.of("B1"), ids(dir.resolve("db_bulk/beta")));
        assertEquals(List.of(), ids(dir.resolve("db_normal/beta")));

        String batchId = soleManifestId(dir);
        Files.writeString(toon, armed);
        assertEquals(List.of("sink_beta__d1"), DrainCommand.run(toon.toString(), batchId).drainedBranches());
        assertEquals(List.of("N2"), ids(dir.resolve("db_normal/beta")));
        assertFalse(Files.exists(dir.resolve("db_normal/alpha")), "never written as the first schema");
    }

    private static String withDisabled(String toon, String step) {
        return toon.replaceFirst("(?m)^(  threads: 1\\n)", "$1  disabled_steps[1]: " + step + "\n");
    }

    private static String soleManifestId(Path dir) throws Exception {
        try (java.util.stream.Stream<Path> w = Files.walk(dir.resolve("status"))) {
            String name = w.filter(p -> p.getFileName().toString().endsWith(".json")).findFirst()
                    .orElseThrow(() -> new AssertionError("no manifest")).getFileName().toString();
            return name.substring(0, name.length() - ".json".length());
        }
    }

    /** The first CSV column (the record id) of every data row under {@code root}, sorted; empty if absent. */
    static List<String> ids(Path root) throws Exception {
        if (!Files.exists(root)) return List.of();
        try (java.util.stream.Stream<Path> w = Files.walk(root)) {
            return w.filter(Files::isRegularFile)
                    .flatMap(p -> {
                        try { return Files.readAllLines(p).stream().skip(1); }
                        catch (Exception e) { throw new RuntimeException(e); }
                    })
                    .map(l -> l.split(",", 2)[0]).sorted().toList();
        }
    }

    // ── fixtures (shared with the later slices' tests) ────────────────────────

    /** A CSV {@code schemas[2]} selector (alpha: 3 columns, beta: 4) with a two-branch route on {@code ID}. */
    static Path selectorRoutePipeline(Path dir, boolean active) throws Exception {
        Files.createDirectories(dir);
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
        Files.createDirectories(dir);
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
