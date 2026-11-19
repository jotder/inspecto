package com.gamma.job;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamma.consignment.ConsignmentOutput;
import com.gamma.consignment.ConsignmentOutput.State;
import com.gamma.consignment.ConsignmentOutputStores;
import com.gamma.consignment.DbConsignmentOutputStore;
import com.gamma.etl.EditionFeatures;
import com.gamma.pipeline.ComponentStore;
import com.gamma.util.DuckDbUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code BASIC-ELT-PROFILE-1} B6: the {@code compact} maintenance task's {@code mode: arrivals} ({@link ArrivalCompactor})
 * as a Job - one import Dataset's files merged in arrival order with its merge map, the merged-away files marked
 * {@code COMPACTED_AWAY} in the Consignment output registry (so a reprocess is refused, not doubled), the Dataset's
 * retention on Standard only, the import's claim, the refusals, and {@code mode: partitions} still the default. The Add
 * data side (the commit step, Compact now) is pinned over real HTTP by {@code ControlApiDatasetCompactionTest}.
 */
class ArrivalCompactorTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @AfterEach
    void reset() {
        ConsignmentOutputStores.use(null);
        ArrivalCompactor.claims(null);
        EditionFeatures.overrideForTest(null);
        System.clearProperty("assist.write.root");
    }

    private static JobConfig job(Map<String, String> params) {
        return new JobConfig("compact_arrivals", "maintenance", null, null, true, false, params, null, null);
    }

    private static JobConfig arrivals(String dataset) {
        return job(Map.of("task", "compact", "mode", "arrivals", "dataset", dataset));
    }

    /** One arrival's Parquet file: {@code rows} rows all on {@code day}, received {@code daysAgo} days ago. */
    private static Path arrival(Path db, String name, String day, int rows, int daysAgo) throws Exception {
        Files.createDirectories(db);
        Path file = db.resolve(name);
        File seed = DuckDbUtil.tempDbFile("arrival_seed_");
        try (Connection c = DuckDbUtil.openConnection(seed); Statement st = c.createStatement()) {
            st.execute("COPY (SELECT i AS id, TIMESTAMP '" + day + " 10:00:00' AS booked_at FROM range(" + rows + ") t(i)) TO '"
                    + file.toString().replace('\\', '/') + "' (FORMAT PARQUET)");
        } finally {
            DuckDbUtil.deleteTempDb(seed);
        }
        Files.setLastModifiedTime(file, FileTime.from(Instant.now().minus(daysAgo, ChronoUnit.DAYS)));
        return file;
    }

    /** An import Dataset entry as Add data writes it (the keys the compaction reads). */
    private static void importDataset(Path writeRoot, String id, Map<String, Object> extra) throws Exception {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("origin", "import");
        entry.put("importPipeline", id);
        entry.put(ArrivalCompactor.EVENT_DATE_KEY, "booked_at");
        entry.putAll(extra);
        new ComponentStore(writeRoot.resolve("registry")).write("dataset", id, entry);
    }

    private static ConsignmentOutput live(String consignment, Path path) {
        return new ConsignmentOutput(consignment, null, "imp", null, null, path.toString(), 1L, 100L, "2026-09-01T10:00:00Z", State.LIVE);
    }

    private static long rows(Path db) throws Exception {
        DuckDbUtil.loadDriver();
        try (Connection c = DuckDbUtil.openInMemory(DuckDbUtil.spillDirUnder(db.getParent()), List.of(db.toAbsolutePath().normalize()));
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT count(*) FROM read_parquet('" + db.toString().replace('\\', '/') + "/*.parquet')")) {
            rs.next();
            return rs.getLong(1);
        }
    }

    @Test
    void aJobMergesAnImportDatasetInArrivalOrderAndMarksTheMergedFilesCompactedAway(@TempDir Path writeRoot, @TempDir Path data)
            throws Exception {
        System.setProperty("assist.write.root", writeRoot.toString());
        importDataset(writeRoot, "imp", Map.of());
        Path db = data.resolve("imp").resolve("database");
        // names in the reverse of arrival order: the order is when each came, never the name
        Path first = arrival(db, "c_out.parquet", "2026-09-01", 2, 3);
        Path second = arrival(db, "b_out.parquet", "2026-09-02", 3, 2);
        Path third = arrival(db, "a_out.parquet", "2026-09-03", 4, 1);
        Path elsewhere = data.resolve("other").resolve("database").resolve("x_out.parquet");
        List<String> claimed = new ArrayList<>(), released = new ArrayList<>();
        ArrivalCompactor.claims(id -> {
            claimed.add(id);
            return () -> released.add(id);
        });
        try (DbConsignmentOutputStore store = DbConsignmentOutputStore.open("jdbc:duckdb:")) {
            store.record(List.of(live("c1", first), live("c2", second), live("c3", third), live("c9", elsewhere)));
            ConsignmentOutputStores.use(store);

            JobResult r = new MaintenanceJob(arrivals("imp"), data.toString()).run();
            assertTrue(r.success(), r.message());
            assertTrue(r.message().contains("COMPACTED"), r.message());
            assertEquals(1, ArrivalCompactor.parquetFiles(db).size(), "every arrival in one file");
            assertEquals(9, rows(db), "no row lost or doubled");
            List<ArrivalCompactor.Segment> segs = new ArrayList<>(ArrivalCompactor.merged(data, "imp").values());
            assertEquals(List.of("c_out.parquet", "b_out.parquet", "a_out.parquet"), segs.stream().map(ArrivalCompactor.Segment::file).toList());
            assertEquals(List.of(0L, 2L, 5L), segs.stream().map(ArrivalCompactor.Segment::firstRow).toList());
            assertEquals(List.of(2L, 3L, 4L), segs.stream().map(ArrivalCompactor.Segment::rows).toList());
            assertTrue(segs.get(1).coversFrom().startsWith("2026-09-02"), segs.toString());

            for (String c : List.of("c1", "c2", "c3"))
                assertEquals(State.COMPACTED_AWAY, store.outputs(c).get(0).state(), c + " was merged away: a reprocess must refuse it");
            assertEquals(State.LIVE, store.outputs("c9").get(0).state(), "another Dataset's file is not touched");
            assertEquals(List.of("imp"), claimed);
            assertEquals(List.of("imp"), released, "the claim is given back");

            JobResult again = new MaintenanceJob(arrivals("imp"), data.toString()).run();
            assertTrue(again.message().contains("NOT_NEEDED"), "one file and no retention: " + again.message());
        }
    }

    /** The Job applies the Dataset's retention where the edition has it (Standard and up), never on Basic. */
    @Test
    void aJobAppliesTheRetentionOnStandardOnlyAndMarksTheDroppedArrivals(@TempDir Path writeRoot, @TempDir Path data) throws Exception {
        System.setProperty("assist.write.root", writeRoot.toString());
        importDataset(writeRoot, "imp", Map.of(ArrivalCompactor.RETENTION, Map.of("arrivals", 1)));
        Path db = data.resolve("imp").resolve("database");
        arrival(db, "a_out.parquet", "2026-09-01", 2, 3);
        arrival(db, "b_out.parquet", "2026-09-02", 3, 2);

        EditionFeatures.overrideForTest(Set.of());   // Basic: the policy is ignored, every row stays
        assertTrue(new MaintenanceJob(arrivals("imp"), data.toString()).run().success());
        assertEquals(5, rows(db), "Basic never drops rows");

        try (DbConsignmentOutputStore store = DbConsignmentOutputStore.open("jdbc:duckdb:")) {
            Path newest = arrival(db, "c_out.parquet", "2026-09-03", 4, 0);
            store.record(List.of(live("c3", newest)));
            ConsignmentOutputStores.use(store);
            EditionFeatures.overrideForTest(Set.of(EditionFeatures.PIPELINE_AUTOMATION));
            JobResult kept = new MaintenanceJob(arrivals("imp"), data.toString()).run();
            assertTrue(kept.message().contains("rows removed by the retention policy"), kept.message());
            assertEquals(4, rows(db), "keep 1 arrival: only the newest one's rows");
            assertEquals(List.of("c_out.parquet"), ArrivalCompactor.merged(data, "imp").keySet().stream().toList());
            assertEquals(State.COMPACTED_AWAY, store.outputs("c3").get(0).state(), "kept, but in the merged file now");
        }
    }

    @Test
    void aJobLeavesADatasetSomeoneElseHolds(@TempDir Path writeRoot, @TempDir Path data) throws Exception {
        System.setProperty("assist.write.root", writeRoot.toString());
        importDataset(writeRoot, "imp", Map.of());
        Path db = data.resolve("imp").resolve("database");
        arrival(db, "a_out.parquet", "2026-09-01", 2, 2);
        arrival(db, "b_out.parquet", "2026-09-02", 3, 1);
        ArrivalCompactor.claims(id -> null);   // an import holds it

        JobResult r = new MaintenanceJob(arrivals("imp"), data.toString()).run();
        assertTrue(r.success(), r.message());
        assertTrue(r.message().contains("being imported"), r.message());
        assertEquals(2, ArrivalCompactor.parquetFiles(db).size(), "left for the next run");
        assertFalse(Files.exists(data.resolve("imp").resolve(ArrivalCompactor.MAP)));
    }

    @Test
    void aJobRefusesADatasetNoImportWroteAMissingOneAndNoDataset(@TempDir Path writeRoot, @TempDir Path data) throws Exception {
        System.setProperty("assist.write.root", writeRoot.toString());
        new ComponentStore(writeRoot.resolve("registry")).write("dataset", "authored", Map.of("physicalRef", "authored"));
        Path db = data.resolve("authored").resolve("database");
        arrival(db, "a_out.parquet", "2026-09-01", 2, 2);
        arrival(db, "b_out.parquet", "2026-09-02", 3, 1);

        IllegalArgumentException notImport = assertThrows(IllegalArgumentException.class,
                () -> new MaintenanceJob(arrivals("authored"), data.toString()).run());
        assertTrue(notImport.getMessage().contains("was not written by an import"), notImport.getMessage());
        assertEquals(2, ArrivalCompactor.parquetFiles(db).size(), "nothing merged");

        IllegalArgumentException missing = assertThrows(IllegalArgumentException.class,
                () -> new MaintenanceJob(arrivals("ghost"), data.toString()).run());
        assertTrue(missing.getMessage().contains("no Dataset 'ghost'"), missing.getMessage());

        IllegalArgumentException unnamed = assertThrows(IllegalArgumentException.class,
                () -> new MaintenanceJob(job(Map.of("task", "compact", "mode", "arrivals")), data.toString()).run());
        assertTrue(unnamed.getMessage().contains("requires param 'dataset'"), unnamed.getMessage());
    }

    /** {@code mode: partitions} (PartitionCompactor) stays the default; any other mode is refused, never guessed. */
    @Test
    void partitionsStaysTheDefaultModeAndAnUnknownModeIsRefused(@TempDir Path data) throws Exception {
        Path absent = data.resolve("not_yet");
        for (Map<String, String> p : List.of(Map.of("task", "compact", "dir", absent.toString()),
                Map.of("task", "compact", "mode", "partitions", "dir", absent.toString()))) {
            JobResult r = new MaintenanceJob(job(p), data.toString()).run();
            assertTrue(r.message().startsWith("compact: directory not present"), p + " -> " + r.message());
        }
        IllegalArgumentException bad = assertThrows(IllegalArgumentException.class,
                () -> new MaintenanceJob(job(Map.of("task", "compact", "mode", "days", "dir", absent.toString())), data.toString()).run());
        assertTrue(bad.getMessage().contains("partitions or arrivals"), bad.getMessage());
    }

    /** A crash between the swap and the registry marking: the heal that finishes the swap marks the inputs too. */
    @Test
    void theHealThatFinishesASwapMarksItsInputs(@TempDir Path data) throws Exception {
        Path db = Files.createDirectories(data.resolve("ds").resolve("database"));
        Path input = arrival(db, "a_out.parquet", "2026-09-01", 2, 1);
        Files.writeString(db.resolve("compacted_1.parquet"), "x");
        Map<String, Object> map = Map.of("merged", "compacted_1.parquet", "arrivals", List.of(Map.of("file", "a_out.parquet", "rows", 2)));
        Files.writeString(data.resolve("ds").resolve(ArrivalCompactor.MAP + ".pending"), JSON.writeValueAsString(
                Map.of("merged", "compacted_1.parquet", "inputs", List.of("a_out.parquet", "compacted_1.parquet"), "map", map)));
        try (DbConsignmentOutputStore store = DbConsignmentOutputStore.open("jdbc:duckdb:")) {
            store.record(List.of(live("c1", input)));
            ConsignmentOutputStores.use(store);
            ArrivalCompactor.heal(data, "ds");
            assertFalse(Files.exists(input));
            assertEquals(State.COMPACTED_AWAY, store.outputs("c1").get(0).state());
        }
    }
}
