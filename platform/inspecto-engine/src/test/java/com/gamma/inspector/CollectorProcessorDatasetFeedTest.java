package com.gamma.inspector;

import com.gamma.etl.PipelineConfig;
import com.gamma.event.EventLog;
import com.gamma.pipeline.ComponentStore;
import com.gamma.pipeline.SpaceConfigRoot;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.MDC;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code PROCESSOR-RELEASE-READINESS-1} gap G6 — a {@code connector: dataset} feed driven through real poll
 * cycles ({@link CollectorProcessor#run}): the producer's Parquet snapshot is resolved through the registry,
 * copied into the feed's own inbox, parsed and written to the feed's sink. A refresh (a new timestamp-named
 * snapshot, which is how {@code MaterializeTask} publishes) is ingested on the next cycle while the
 * already-seen snapshot is not — the refresh semantics {@link DatasetCollectorConnectorFactory} claims come
 * "for free" from marker dedup. And a destructive {@code post_action} on the feed never touches the
 * producer's files, at cycle level, not only at the connector's {@code post()}.
 *
 * <p>Hermetic: the registry, data root and every pipeline dir live under {@code @TempDir}, registered as a
 * throwaway Space.
 */
class CollectorProcessorDatasetFeedTest {

    private static final String SPACE = "g6-dataset-feed";

    @AfterEach
    void forgetSpace() {
        MDC.remove(EventLog.SPACE_MDC_KEY);
        SpaceConfigRoot.forget(SPACE);
    }

    @Test
    void datasetFeedIngestsTheSnapshotThenOnlyTheRefreshAndNeverTouchesTheProducer(@TempDir Path dir)
            throws Exception {
        Path config = Files.createDirectories(dir.resolve("config"));
        Path data = Files.createDirectories(dir.resolve("data"));
        new ComponentStore(config.resolve("registry"))
                .write("dataset", "g6_rollup", Map.of("physicalRef", "g6_rollup"));
        Path snapshots = Files.createDirectories(data.resolve("g6_rollup"));
        Path first = snapshot(snapshots, "g6_rollup_20260801T000000.parquet", "('APAC', 1.5), ('EMEA', 2.5)");

        PipelineConfig cfg = feed(dir);
        SpaceConfigRoot.register(SPACE, config);
        SpaceConfigRoot.registerDataRoot(SPACE, data);
        MDC.put(EventLog.SPACE_MDC_KEY, SPACE);

        CollectorProcessor.run(cfg);
        assertEquals(List.of("APAC|1.5", "EMEA|2.5"), rows(cfg), "the snapshot's rows reach the feed's sink");

        // Refresh: the producer publishes a NEW timestamp-named snapshot beside the old one.
        Path second = snapshot(snapshots, "g6_rollup_20260802T000000.parquet", "('NA', 4.0)");
        CollectorProcessor.run(cfg);
        assertEquals(List.of("APAC|1.5", "EMEA|2.5", "NA|4.0"), rows(cfg),
                "the refresh is ingested and the already-seen snapshot is not re-ingested");

        CollectorProcessor.run(cfg);
        assertEquals(3, rows(cfg).size(), "a cycle with no new snapshot adds nothing");

        assertTrue(Files.exists(first) && Files.exists(second),
                "post_action DELETE on the feed must never remove the PRODUCER's snapshots");
    }

    private static Path snapshot(Path dir, String name, String values) throws Exception {
        Path p = dir.resolve(name);
        try (Connection c = DriverManager.getConnection("jdbc:duckdb:"); Statement s = c.createStatement()) {
            s.execute("COPY (SELECT * FROM (VALUES " + values + ") t(region, sum_gross)) TO '"
                    + p.toString().replace('\\', '/') + "' (FORMAT PARQUET)");
        }
        return p;
    }

    private static PipelineConfig feed(Path dir) throws Exception {
        String d = dir.toString().replace('\\', '/');
        Path schema = dir.resolve("g6_feed_schema.toon");
        Files.writeString(schema, """
                partitions[1]{column,source,type}:
                  sales_region,REGION,VARCHAR
                raw:
                  name: G6_ROLLUP
                  format: PARQUET
                  fields[2]{name,selector,type}:
                    REGION,"region",VARCHAR
                    SUM_GROSS,"sum_gross",DOUBLE
                mapping:
                  canonicalName: g6_feed
                  rawName: G6_ROLLUP
                  fields[2]:
                    - name: REGION
                      from: REGION
                      fn: keep
                    - name: SUM_GROSS
                      from: SUM_GROSS
                      fn: keep
                """);
        Path p = dir.resolve("g6_feed_pipeline.toon");
        Files.writeString(p, """
                name: G6_DATASET_FEED
                version: 1
                collector:
                  connector: dataset
                  dataset: datasets/g6_rollup
                  post_action:
                    on_success: DELETE
                dirs:
                  poll: %1$s/feed/inbox
                  database: %1$s/feed/db
                  backup: %1$s/feed/backup
                  temp: %1$s/feed/temp
                  errors: %1$s/feed/errors
                  quarantine: %1$s/feed/quarantine
                  markers: %1$s/feed/markers
                  status_dir: %1$s/feed/status
                  log_dir: %1$s/feed/logs
                output:
                  format: PARQUET
                processing:
                  threads: 1
                  file_pattern: "glob:**/*.parquet"
                  duplicate_check:
                    enabled: true
                    marker_extension: .processed
                  schema_file: "%2$s"
                parsing:
                  frontend: parquet
                """.formatted(d, schema.toString().replace('\\', '/')));
        return PipelineConfig.load(p.toString());
    }

    /** {@code REGION|SUM_GROSS} of every committed sink row, sorted (never the {@code .staging} scratch). */
    private static List<String> rows(PipelineConfig cfg) throws Exception {
        Path db = Path.of(cfg.dirs().database());
        List<String> files;
        try (var w = Files.walk(db)) {
            files = w.filter(p -> p.toString().endsWith(".parquet") && !p.toString().contains(".staging"))
                    .map(p -> "'" + p.toAbsolutePath().toString().replace('\\', '/') + "'").sorted().toList();
        }
        assertFalse(files.isEmpty(), "no sink output under " + db);
        List<String> out = new ArrayList<>();
        try (Connection c = DriverManager.getConnection("jdbc:duckdb:");
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT REGION || '|' || SUM_GROSS::VARCHAR FROM read_parquet(["
                     + String.join(",", files) + "]) ORDER BY 1")) {
            while (rs.next()) out.add(rs.getString(1));
        }
        return out;
    }
}
