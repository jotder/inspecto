package com.gamma.inspector;

import com.gamma.etl.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.*;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end coverage for the large-file handling added in 3.10.0:
 * <ul>
 *   <li><b>streaming single-pass</b> (no {@code raw_f0}/{@code raw_input} copy) for a single-member
 *       native batch — already exercised by the existing suite, asserted here for output parity;</li>
 *   <li><b>auto-chunking</b> ({@code processing.chunking}) of an oversized file into bounded chunks
 *       whose aggregated output conserves every row and whose partitions match the un-chunked run,
 *       with the <em>original</em> file remaining the audit/marker/backup unit.</li>
 * </ul>
 */
class ChunkedStreamingTest {

    private Consignment.Member member(PipelineConfig cfg, File f, int id) {
        SchemaSelector.Selection sel = new SchemaSelector.Selection(cfg.schemas().single(), null);
        return new Consignment.Member(f, id, f.length(), sel);
    }

    private void process(PipelineConfig cfg, Consignment batch) {
        ConsignmentIngestor.process(batch, cfg, new ConsignmentAuditWriter(
                cfg.dirs().statusFilePath(), cfg.dirs().batchesFilePath(), cfg.dirs().lineageFilePath()));
    }

    /** Sum data rows across all CSV output files (each file has a header row to discount). */
    private long outputDataRows(PipelineConfig cfg) throws Exception {
        try (Stream<Path> w = Files.walk(Path.of(cfg.dirs().database()))) {
            return w.filter(p -> p.getFileName().toString().endsWith("_out.csv"))
                    .mapToLong(p -> {
                        try { return Math.max(0, Files.readAllLines(p).size() - 1); }
                        catch (Exception e) { throw new RuntimeException(e); }
                    }).sum();
        }
    }

    private List<Path> outFiles(PipelineConfig cfg) throws Exception {
        try (Stream<Path> w = Files.walk(Path.of(cfg.dirs().database()))) {
            return w.filter(p -> p.getFileName().toString().endsWith("_out.csv")).sorted().toList();
        }
    }

    private static final String DATA = """
            ID,AMT,EVENT_DATE
            a1,1.0,2020-04-03
            a2,2.0,2020-04-03
            a3,3.0,2020-01-01
            a4,4.0,2020-01-01
            a5,5.0,2020-04-03
            a6,6.0,2020-01-01
            """;

    @Test
    void chunkedRunConservesRowsAndPartitionsAndKeepsOriginalAsMember(@TempDir Path dir) throws Exception {
        // Tiny threshold so the 6-row file splits into several chunks.
        String chunking = """
              chunking:
                max_file_bytes: 30
                target_chunk_bytes: 30
            """;
        Path toon = com.gamma.etl.PipelineConfigBatchTest.writePipeline(dir, chunking);
        PipelineConfig cfg = PipelineConfig.load(toon.toString());
        assertTrue(cfg.chunking().appliesTo(1_000), "chunking should be enabled");

        Path inbox = Path.of(cfg.dirs().poll());
        Files.createDirectories(inbox);
        Path solo = inbox.resolve("solo.csv");
        Files.writeString(solo, DATA);
        assertTrue(solo.toFile().length() > 30, "file must exceed the chunk threshold");

        Consignment batch = new Consignment(cfg.identity().runTimestamp() + "_mini_0001", "mini", null,
                List.of(member(cfg, solo.toFile(), 0)));
        process(cfg, batch);

        // Every one of the 6 data rows survives, summed across all chunk output files.
        assertEquals(6, outputDataRows(cfg));

        // Output spans both date partitions and is named by the chunk stem (solo_cNNNNN_out.csv).
        List<Path> outs = outFiles(cfg);
        assertTrue(outs.size() >= 2, "expected at least one file per partition");
        assertTrue(outs.stream().allMatch(p -> p.getFileName().toString().startsWith("solo_c")),
                "chunked outputs carry the _cNNNNN stem: " + outs);
        assertTrue(outs.stream().anyMatch(p -> p.toString().replace('\\','/').contains("/day=03/")));
        assertTrue(outs.stream().anyMatch(p -> p.toString().replace('\\','/').contains("/day=01/")));

        // The ORIGINAL file is the audit/marker/backup unit — not the transient chunks.
        assertFalse(Files.exists(solo), "original moved to backup");
        assertTrue(Files.exists(Path.of(cfg.dirs().backup(), "solo.csv")));
        assertTrue(Files.exists(Path.of(cfg.dirs().markers(), "solo.csv.processed")));
        String batches = Files.readString(Path.of(cfg.dirs().batchesFilePath()));
        assertTrue(batches.contains(",SUCCESS,"));
        // Lineage attributes rows to the original file, never a chunk name.
        String lineage = Files.readString(Path.of(cfg.dirs().lineageFilePath()));
        assertTrue(lineage.contains("solo.csv"));
        assertFalse(lineage.contains("_chunk_"), "lineage must not leak chunk file names");

        // No chunk scratch left behind under the temp dir.
        try (Stream<Path> t = Files.walk(Path.of(cfg.dirs().temp()))) {
            assertFalse(t.anyMatch(p -> p.getFileName().toString().contains("_chunk_")),
                    "chunk files must be deleted after processing");
        }
    }

    // ── CHUNKED-UNREADABLE-FAILS-BATCH-1: classify a failure per chunk ─────────────────────────────

    private PipelineConfig chunkedPipeline(Path dir, String chunkBytes) throws Exception {
        String chunking = "  chunking:\n    max_file_bytes: 30\n    target_chunk_bytes: " + chunkBytes + "\n";
        Path toon = com.gamma.etl.PipelineConfigBatchTest.writePipeline(dir, chunking);
        PipelineConfig cfg = PipelineConfig.load(toon.toString());
        Files.createDirectories(Path.of(cfg.dirs().poll()));
        return cfg;
    }

    private static boolean quarantined(PipelineConfig cfg, String name) throws Exception {
        Path q = Path.of(cfg.dirs().quarantine());
        if (!Files.exists(q)) return false;
        try (Stream<Path> w = Files.walk(q)) {
            return w.anyMatch(p -> p.getFileName().toString().equals(name));
        }
    }

    /**
     * A {@code .gz} that is TRUNCATED mid-stream: the first chunks decompress and are WRITTEN, then a
     * later chunk's read hits the torn end. The file is unreadable, so it must be quarantined
     * {@code QUARANTINED_UNREADABLE} — not FAILED and retried every cycle — and the Consignment must
     * not stay half-written: the chunks written before the torn one are rolled back.
     */
    @Test
    void truncatedGzipLaterChunkIsQuarantinedUnreadableAndEarlierChunksAreRolledBack(@TempDir Path dir)
            throws Exception {
        PipelineConfig cfg = chunkedPipeline(dir, "200000");
        Path gz = Path.of(cfg.dirs().poll()).resolve("big.csv.gz");
        // > the chunker's 1 MiB read buffer decompressed, so chunk 0 is written before the torn tail is read.
        StringBuilder csv = new StringBuilder("ID,AMT,EVENT_DATE\n");
        for (int i = 0; i < 120_000; i++)
            csv.append("r").append(i).append(',').append(i % 97).append(".0,2020-0").append(1 + i % 4).append("-03\n");
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        try (java.util.zip.GZIPOutputStream z = new java.util.zip.GZIPOutputStream(bytes)) {
            z.write(csv.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        byte[] whole = bytes.toByteArray();
        Files.write(gz, java.util.Arrays.copyOf(whole, whole.length * 3 / 4));   // tear off the last quarter

        process(cfg, new Consignment(cfg.identity().runTimestamp() + "_mini_0001", "mini", null,
                List.of(member(cfg, gz.toFile(), 0))));

        assertFalse(Files.exists(gz), "an unreadable file must leave the inbox");
        assertTrue(quarantined(cfg, "big.csv.gz"), "and land in quarantine");
        String status = Files.readString(Path.of(cfg.dirs().statusFilePath()));
        assertTrue(status.contains("QUARANTINED_UNREADABLE"), status);
        assertTrue(status.contains("chunk"), "the reason names the torn chunk: " + status);
        String batches = Files.readString(Path.of(cfg.dirs().batchesFilePath()));
        assertFalse(batches.contains(",FAILED,"), "an unreadable file is a quarantine, not a FAILED batch: " + batches);
        // Atomicity: nothing the earlier chunks wrote survives — no half-written Consignment in the Dataset.
        Path db = Path.of(cfg.dirs().database());
        if (Files.exists(db))
            try (Stream<Path> w = Files.walk(db)) {
                List<Path> left = w.filter(Files::isRegularFile)
                        .filter(p -> p.getFileName().toString().startsWith("big_c")).toList();
                assertTrue(left.isEmpty(), "earlier chunks' outputs must be rolled back: " + left);
            }
        assertFalse(Files.exists(ConsignmentIngestStrategy.branchCommitLogPath(cfg,
                        cfg.identity().runTimestamp() + "_mini_0001")),
                "no branch-commit log may claim the rolled-back chunks as committed");
    }

    /** Unreadable from the first byte (not gzip at all): quarantined the same way, nothing written. */
    @Test
    void corruptGzipChunkedFileIsQuarantinedUnreadable(@TempDir Path dir) throws Exception {
        PipelineConfig cfg = chunkedPipeline(dir, "30");
        Path gz = Path.of(cfg.dirs().poll()).resolve("corrupt.csv.gz");
        Files.write(gz, "this is not gzip-compressed data, but long enough to be chunked".getBytes());

        process(cfg, new Consignment(cfg.identity().runTimestamp() + "_mini_0001", "mini", null,
                List.of(member(cfg, gz.toFile(), 0))));

        assertTrue(quarantined(cfg, "corrupt.csv.gz"), "an unreadable chunked file must be quarantined");
        assertTrue(Files.readString(Path.of(cfg.dirs().statusFilePath())).contains("QUARANTINED_UNREADABLE"));
    }

    /**
     * PARKED-BRANCH-LEAK-ON-FAILED-BATCH-1: a branch parked earlier in the batch (recorded before the torn
     * chunk) must not outlive a non-SUCCESS outcome - the registry is drained on every real outcome.
     */
    @Test
    void parkedBranchEntryIsDrainedWhenTheBatchDoesNotSucceed(@TempDir Path dir) throws Exception {
        PipelineConfig cfg = chunkedPipeline(dir, "30");
        Path gz = Path.of(cfg.dirs().poll()).resolve("corrupt.csv.gz");
        Files.write(gz, "this is not gzip-compressed data, but long enough to be chunked".getBytes());
        String batchId = cfg.identity().runTimestamp() + "_mini_0001";
        ParkedBranches.record(batchId, "sink_parked", dir.resolve("parked.parquet"));

        process(cfg, new Consignment(batchId, "mini", null, List.of(member(cfg, gz.toFile(), 0))));

        assertTrue(Files.readString(Path.of(cfg.dirs().statusFilePath())).contains("QUARANTINED_UNREADABLE"));
        assertTrue(ParkedBranches.drain(batchId).isEmpty(),
                "a non-SUCCESS batch must drain its parked-branch entries, not leak them");
    }

    /**
     * The other half of the split: a READABLE chunked file whose TRANSFORM fails (a {@code partitionKey}
     * naming an absent column) fails the BATCH, named, and stays in the inbox — never quarantined.
     */
    @Test
    void chunkedTransformFailureFailsTheBatchAndLeavesTheFileInTheInbox(@TempDir Path dir) throws Exception {
        chunkedPipeline(dir, "30");   // writes the pipeline + schema; the schema is then broken and reloaded
        Files.writeString(dir.resolve("mini_schema.toon"), com.gamma.etl.PipelineConfigBatchTest.miniSchema()
                .replace("partitionKey: EVENT_DATE", "partitionKey: NO_SUCH_COLUMN"));
        PipelineConfig cfg = PipelineConfig.load(dir.resolve("mini_pipeline.toon").toString());
        Path solo = Path.of(cfg.dirs().poll()).resolve("solo.csv");
        Files.writeString(solo, DATA);

        process(cfg, new Consignment(cfg.identity().runTimestamp() + "_mini_0001", "mini", null,
                List.of(member(cfg, solo.toFile(), 0))));

        assertTrue(Files.exists(solo), "a readable file must stay in the inbox when its TRANSFORM fails");
        assertFalse(quarantined(cfg, "solo.csv"), "nothing may be quarantined");
        String batches = Files.readString(Path.of(cfg.dirs().batchesFilePath()));
        assertTrue(batches.contains(",FAILED,"), "the batch must be FAILED: " + batches);
        assertTrue(batches.contains("transform failed for solo.csv"), "named: " + batches);
    }

    @Test
    void chunkedAndUnchunkedProduceSameRowTotal(@TempDir Path dir) throws Exception {
        // Un-chunked baseline (chunking disabled).
        Path baseDir = dir.resolve("base");
        Files.createDirectories(baseDir);
        Path baseToon = com.gamma.etl.PipelineConfigBatchTest.writePipeline(baseDir, "");
        PipelineConfig base = PipelineConfig.load(baseToon.toString());
        Files.createDirectories(Path.of(base.dirs().poll()));
        Path baseFile = Path.of(base.dirs().poll()).resolve("solo.csv");
        Files.writeString(baseFile, DATA);
        process(base, new Consignment(base.identity().runTimestamp() + "_mini_0001", "mini", null,
                List.of(member(base, baseFile.toFile(), 0))));
        long baseRows = outputDataRows(base);

        // Chunked run over identical data.
        Path chDir = dir.resolve("chunked");
        Files.createDirectories(chDir);
        String chunking = """
              chunking:
                max_file_bytes: 25
            """;
        Path chToon = com.gamma.etl.PipelineConfigBatchTest.writePipeline(chDir, chunking);
        PipelineConfig ch = PipelineConfig.load(chToon.toString());
        Files.createDirectories(Path.of(ch.dirs().poll()));
        Path chFile = Path.of(ch.dirs().poll()).resolve("solo.csv");
        Files.writeString(chFile, DATA);
        process(ch, new Consignment(ch.identity().runTimestamp() + "_mini_0001", "mini", null,
                List.of(member(ch, chFile.toFile(), 0))));
        long chRows = outputDataRows(ch);

        assertEquals(6, baseRows);
        assertEquals(baseRows, chRows, "chunked output must conserve exactly the un-chunked row total");
    }
}
