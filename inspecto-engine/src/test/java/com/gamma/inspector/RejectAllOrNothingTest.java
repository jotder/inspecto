package com.gamma.inspector;

import com.gamma.etl.PipelineConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code processing.reject_mode: all_or_nothing} (X4 deferral, 2026-09-28) over the four delimited-CSV ingest
 * paths, end to end through {@link CollectorProcessor#run}: the Java parse loop, the native single-member stream,
 * the native multi-member UNION, and the native CHUNKED path (which all_or_nothing routes to the single stream).
 * The plugin lane's two modes are pinned in {@link StreamingPluginIngestStrategyTest}.
 *
 * <p>Each path's "bad" file carries one two-column line against a three-field schema — a rejected record.
 */
class RejectAllOrNothingTest {

    private static final String BAD  = "ID,AMT,EVENT_DATE\nb1,1.0,2020-04-03\nshort,9.0\nb2,2.0,2020-04-03\n";
    private static final String GOOD = "ID,AMT,EVENT_DATE\ng1,3.0,2020-04-03\ng2,4.0,2020-04-03\n";

    /** path → engine + the processing section it needs. */
    private static PipelineConfig pipeline(Path dir, String path, String mode) throws Exception {
        String engine = path.startsWith("java") ? "java" : "duckdb";
        String section = (mode == null ? "" : "  reject_mode: " + mode + "\n")
                + switch (path) {
                    case "duckdb-union", "java-union" -> "  batch:\n    max_files: 10\n";
                    case "duckdb-chunked" -> "  chunking:\n    max_file_bytes: 30\n    target_chunk_bytes: 30\n";
                    default -> "";
                };
        Path toon = PipelineConfigBatchTestRef.writePipeline(dir, section);
        Files.writeString(toon, Files.readString(toon).replace(
                "    delimiter: \",\"", "    delimiter: \",\"\n    engine: " + engine));
        PipelineConfig cfg = PipelineConfig.load(toon.toString());
        Files.createDirectories(Path.of(cfg.dirs().poll()));
        return cfg;
    }

    private static void drop(PipelineConfig cfg, String name, String content) throws Exception {
        Files.writeString(Path.of(cfg.dirs().poll()).resolve(name), content);
    }

    /** Every data line of every CSV output under the database dir. */
    private static List<String> landed(PipelineConfig cfg) throws Exception {
        List<String> rows = new ArrayList<>();
        Path db = Path.of(cfg.dirs().database());
        if (!Files.exists(db)) return rows;
        try (Stream<Path> w = Files.walk(db)) {
            for (Path p : w.filter(Files::isRegularFile).sorted().toList()) {
                List<String> lines = Files.readAllLines(p);
                rows.addAll(lines.subList(Math.min(1, lines.size()), lines.size()));
            }
        }
        return rows;
    }

    private static long withPrefix(List<String> rows, String prefix) {
        return rows.stream().filter(r -> r.startsWith(prefix)).count();
    }

    // ── a clean file lands ──────────────────────────────────────────────────────────

    @ParameterizedTest
    @ValueSource(strings = {"java", "duckdb", "duckdb-union", "duckdb-chunked"})
    void aCleanFileLandsUnderAllOrNothing(String path, @TempDir Path dir) throws Exception {
        PipelineConfig cfg = pipeline(dir, path, "all_or_nothing");
        drop(cfg, "good.csv", GOOD);

        CollectorProcessor.run(cfg);

        assertEquals(2, withPrefix(landed(cfg), "g"), path + ": " + landed(cfg));
        assertTrue(Files.exists(Path.of(cfg.dirs().backup(), "good.csv")), "committed and backed up");
    }

    // ── a file with one rejected record lands nothing ───────────────────────────────

    @ParameterizedTest
    @ValueSource(strings = {"java", "duckdb", "duckdb-union", "duckdb-chunked", "java-union"})
    void aFileWithOneRejectedRecordLandsNothingAndIsQuarantinedWithItsSidecar(String path, @TempDir Path dir)
            throws Exception {
        PipelineConfig cfg = pipeline(dir, path, "all_or_nothing");
        drop(cfg, "bad.csv", BAD);
        boolean union = path.endsWith("union");
        if (union) drop(cfg, "good.csv", GOOD);

        CollectorProcessor.run(cfg);

        List<String> rows = landed(cfg);
        assertEquals(0, withPrefix(rows, "b"), path + ": not one row of the rejecting file landed: " + rows);
        if (union) assertEquals(2, withPrefix(rows, "g"), path + ": the clean batch-mate still lands: " + rows);
        else assertEquals(List.of(), rows, path);

        Path home = Path.of(cfg.dirs().quarantine(), "rejects_all_or_nothing");
        assertTrue(Files.exists(home.resolve("bad.csv")), path + ": quarantined under rejects_all_or_nothing");
        assertTrue(Files.exists(home.resolve("bad_errors.csv")), path + ": the sidecar is kept beside it");
        assertTrue(Files.readString(home.resolve("bad_errors.csv")).contains("short,9.0"), "the raw line is kept");
        assertFalse(Files.exists(Path.of(cfg.dirs().poll(), "bad.csv")), "the file left the inbox");
        assertFalse(Files.exists(Path.of(cfg.dirs().backup(), "bad.csv")), "never committed");

        String status = Files.readString(Path.of(cfg.dirs().statusFilePath()));
        assertTrue(status.contains("QUARANTINED_MISMATCH"), status);
        assertTrue(status.contains("rejects_all_or_nothing: 1 record(s) rejected"), status);
    }

    // ── eject is unchanged ─────────────────────────────────────────────────────────

    @ParameterizedTest
    @ValueSource(strings = {"java", "duckdb", "duckdb-union", "duckdb-chunked"})
    void ejectIsUnchangedTheGoodRecordsLandAndTheRejectGoesToTheSidecar(String path, @TempDir Path dir)
            throws Exception {
        for (String mode : new String[]{null, "eject"}) {
            Path sub = Files.createDirectories(dir.resolve(mode == null ? "absent" : mode));
            PipelineConfig cfg = pipeline(sub, path, mode);
            assertFalse(cfg.rejectsAllOrNothing());
            drop(cfg, "bad.csv", BAD);

            CollectorProcessor.run(cfg);

            assertEquals(2, withPrefix(landed(cfg), "b"), path + "/" + mode + ": " + landed(cfg));
            // ⚠ The chunked path names its sidecar per CHUNK (bad_chunk_NNNNN_errors.csv) — pre-existing.
            try (Stream<Path> errs = Files.list(Path.of(cfg.dirs().errors()))) {
                assertTrue(errs.anyMatch(p -> p.getFileName().toString().startsWith("bad")
                        && p.getFileName().toString().endsWith("_errors.csv")), path + "/" + mode);
            }
            assertTrue(Files.exists(Path.of(cfg.dirs().backup(), "bad.csv")), "committed");
        }
    }

    // ── crash between knowing the reject count and publishing ──────────────────────

    /**
     * The quarantine is the last step of the decision. Blocking its reason directory (a regular FILE where the
     * directory must go) fails it — a stand-in for a crash between the reject count and the publish. Nothing of
     * the file may be visible, and it must stay in the inbox for the retry.
     */
    @ParameterizedTest
    @ValueSource(strings = {"java", "duckdb", "duckdb-union", "duckdb-chunked"})
    void aFailureBetweenTheRejectCountAndThePublishLeavesNothingVisible(String path, @TempDir Path dir)
            throws Exception {
        PipelineConfig cfg = pipeline(dir, path, "all_or_nothing");
        drop(cfg, "bad.csv", BAD);
        Files.createDirectories(Path.of(cfg.dirs().quarantine()));
        Files.writeString(Path.of(cfg.dirs().quarantine(), "rejects_all_or_nothing"), "blocks the reason dir");

        CollectorProcessor.run(cfg);

        assertEquals(List.of(), landed(cfg), path + ": no output is visible");
        assertTrue(Files.exists(Path.of(cfg.dirs().poll(), "bad.csv")), path + ": the file stays in the inbox");
        assertFalse(Files.exists(Path.of(cfg.dirs().backup(), "bad.csv")), path + ": never committed");
    }

    // ── X4 replay does not apply to a whole-file quarantine ────────────────────────

    @Test
    void replayOfAnAllOrNothingQuarantinedFileIsRefusedTheWholeFileIsTheRecovery(@TempDir Path dir) throws Exception {
        PipelineConfig cfg = pipeline(dir, "duckdb", "all_or_nothing");
        drop(cfg, "bad.csv", BAD);
        CollectorProcessor.run(cfg);

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> RecordReplay.replay(cfg, "bad.csv", null));
        assertTrue(e.getMessage().contains("all_or_nothing"), e.getMessage());
        assertEquals(List.of(), landed(cfg), "the refusal landed nothing");
        assertTrue(Files.exists(Path.of(cfg.dirs().quarantine(), "rejects_all_or_nothing", "bad_errors.csv")),
                "the sidecar is untouched");
    }

    // ── config ──────────────────────────────────────────────────────────────────────

    @Test
    void anUnknownRejectModeIsRefusedAtLoadNamingTheKey(@TempDir Path dir) throws Exception {
        Path toon = PipelineConfigBatchTestRef.writePipeline(dir, "  reject_mode: sometimes\n");
        Exception e = assertThrows(Exception.class, () -> PipelineConfig.load(toon.toString()));
        String all = e + " " + (e.getCause() == null ? "" : e.getCause().toString());
        assertTrue(all.contains("processing.reject_mode"), all);
    }
}
