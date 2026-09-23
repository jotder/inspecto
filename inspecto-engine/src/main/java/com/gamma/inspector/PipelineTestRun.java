package com.gamma.inspector;

import com.gamma.etl.Consignment;
import com.gamma.etl.ConsignmentPlanner;
import com.gamma.etl.DataTransformer;
import com.gamma.etl.IngestProgress;
import com.gamma.etl.LineageRow;
import com.gamma.etl.PartitionOutput;
import com.gamma.etl.PipelineConfig;
import com.gamma.etl.SchemaSelector;
import com.gamma.etl.StepProgress;
import com.gamma.sql.SqlViews;
import com.gamma.util.DuckDbUtil;
import com.gamma.util.JdbcRows;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Stream;

/**
 * A <b>bounded test run over real inbox files</b> — the Build→Test→Run journey's Step 5a. Parses the
 * user's actual files through the <em>real</em> ingest path so the result reflects production
 * behaviour, while producing <b>zero production side effects</b>.
 *
 * <p>This is the real-file counterpart to {@link com.gamma.pipeline.exec.PipelineDryRun}, which is
 * synthetic-rows-only and skips parsing entirely.
 *
 * <h2>Why this is safe — two independent containments, both by construction</h2>
 *
 * <p><b>1. Call-graph containment.</b> {@link ConsignmentIngestor#process} is, in order:
 * {@code strategy.ingest(...)}, then {@code commit(...)}, then {@code writeAudit(...)}, then
 * {@code recordProvenance(...)}. This class calls <b>only the first</b>. That matters because the
 * destinations which are <em>not</em> derived from the config — and therefore cannot be redirected by
 * one — all live in the three statements that are skipped:
 * the acquisition ledger ({@code -Dacquire.ledger.backend}), the consignment output registry
 * ({@code -Dconsignment.outputs.backend}), file stages ({@code -Dfile.stages.backend}), the
 * {@code pipeline.batch.*} Signal (ambient {@code EventLog.current()}, keyed by space MDC) and the
 * provenance matrix (a process-wide registry). Redirecting paths alone would have missed every one.
 *
 * <p><b>2. Filesystem containment.</b> The picked files are <b>copied</b> into
 * {@code scratchRoot/poll} and the run executes against {@link PipelineConfig#forScratchRun}, whose
 * every destination is re-rooted under {@code scratchRoot}. ⚠ The copy is <b>not</b> an optimisation
 * to remove: {@code CsvIngestStrategy} quarantines an unreadable / field-mismatched / empty member via
 * {@code QuarantineManager.quarantine}, which does a {@code Files.move} of the <em>source</em> file.
 * Run against the real inbox and testing a malformed file would delete it from the user's inbox.
 *
 * <p>Neither containment relies on the other — a mistake in one is still caught by the other. Building
 * the batches here also bypasses {@link CollectorProcessor}, so the dedup/marker layer never runs and a
 * test run cannot mark a file as already-processed.
 *
 * <p><b>Scratch lifecycle is the caller's.</b> {@code scratchRoot} is deliberately not deleted here —
 * the parsed output under it is what a caller reads back to build a preview. Call
 * {@link #deleteScratch(Path)} in a {@code finally}.
 */
public final class PipelineTestRun {

    private static final Logger log = LoggerFactory.getLogger(PipelineTestRun.class);

    private PipelineTestRun() {}

    /** Per-input-file outcome. {@code status} is a {@link com.gamma.etl.MemberStatus} constant name. */
    public record FileResult(String filename, String status, long parsedRows, long errorRows, String error) {}

    /**
     * What a test run observed. {@code status} aggregates the batches: {@code FAILED} if any batch
     * failed, else {@code SUCCESS} if any produced rows, else {@code EMPTY}.
     *
     * @param outputs   partition files written <b>under the scratch root</b> — valid only until
     *                  {@link #deleteScratch(Path)} runs
     * @param castFailures values a declared coercion silently nulled while keeping the row;
     *                     <b>{@code -1} means NOT MEASURED</b>, never "clean" (see {@code IngestOutcome})
     */
    /**
     * @param schemaByOutput output file → the SEGMENT that wrote it, for a segment-routed frontend
     *        ({@code IngestOutcome.schemaByOutput}, which the union-mode ingester fills as it writes each
     *        segment). Empty for a single-schema Pipeline — "all one schema", never "unknown".
     *        ⚠ This is what makes {@code WB-08} a seed change rather than a plumbing exercise: the
     *        attribution already existed at write time; nothing was carrying it out.
     * @param rawRows the PARSER's rows, captured just before the mapping ran, grouped by segment — up to
     *        {@link #SEED_ROWS} per segment; key {@code null} for a single-schema Pipeline. This is what the
     *        graph preview seeds its parse node with (operator decision 2026-09-23,
     *        {@code TESTRUN-SEED-IS-MAPPED-OUTPUT-1}). 🔴 It used to seed with the rows the ingest WROTE,
     *        which are already mapped, so the walk re-applied every mapping to canonical columns: a mapping
     *        over a raw column the mapping does not keep refused 422 ({@code AMOUNT_MINOR},
     *        {@code EVENT_TIME}), and a {@code keep} of a TIMESTAMP silently re-parsed its written value
     *        into NULL. A segment that routed nothing is ABSENT, never present-and-empty.
     */
    public record Result(String status, int batches, List<FileResult> files, long totalInputRows,
                         long rowsWritten, long castFailures, List<PartitionOutput> outputs, String error,
                         Map<String, String> schemaByOutput, Map<String, List<Map<String, Object>>> rawRows) {

        /** Single-schema form — no per-segment attribution and no raw sample to carry. */
        public Result(String status, int batches, List<FileResult> files, long totalInputRows,
                      long rowsWritten, long castFailures, List<PartitionOutput> outputs, String error) {
            this(status, batches, files, totalInputRows, rowsWritten, castFailures, outputs, error,
                    Map.of(), Map.of());
        }
    }

    /**
     * How many raw rows per segment are captured for the graph preview. Bounded because
     * {@link com.gamma.pipeline.exec.PipelineDryRun} works in memory — a picked file can be arbitrarily large.
     */
    public static final int SEED_ROWS = 1000;

    /** The ingest lane's internal lineage tag — bookkeeping on the raw relation, never a parsed column. */
    private static final String SRC_ID = "__src_id";

    /**
     * Parse {@code pickedFiles} through the real ingest path into {@code scratchRoot}.
     *
     * @param cfg         the pipeline's config — used for parsing rules only; every path is re-rooted
     * @param pickedFiles the user's real inbox files (they are copied, never read destructively)
     * @param scratchRoot an empty directory owned by the caller (see {@link #deleteScratch})
     * @throws IllegalArgumentException if no files were picked
     */
    public static Result run(PipelineConfig cfg, List<Path> pickedFiles, Path scratchRoot)
            throws IOException {
        if (pickedFiles == null || pickedFiles.isEmpty())
            throw new IllegalArgumentException("a test run needs at least one file");

        PipelineConfig scratch = cfg.forScratchRun(scratchRoot);
        List<File> staged = stage(pickedFiles, Path.of(scratch.dirs().poll()));
        Files.createDirectories(Path.of(scratch.dirs().database()));

        ConsignmentPlanner.SchemaResolver resolver = (scratch.schemas().selector() != null)
                ? scratch.schemas().selector()::select
                : f -> new SchemaSelector.Selection(scratch.schemas().single(), null);

        List<Consignment> batches = ConsignmentPlanner.plan(
                staged, resolver,
                scratch.processing().batchMaxFiles(), scratch.processing().batchMaxBytes(),
                scratch.identity().runTimestamp(),
                ConsignmentPlanner.Order.valueOf(
                        scratch.processing().batchOrder().toUpperCase(java.util.Locale.ROOT)));

        List<FileResult> files = new ArrayList<>();
        List<PartitionOutput> outputs = new ArrayList<>();
        Map<String, String> schemaByOutput = new LinkedHashMap<>();
        long inputRows = 0, written = 0, casts = -1;
        boolean anyFailed = false, anyRows = false;
        String error = "";
        Map<String, List<Map<String, Object>>> rawRows = new LinkedHashMap<>();
        DataTransformer.RawInputObserver capture = (conn, schema, source) -> {
            String segment = segmentOf(scratch, schema);
            List<Map<String, Object>> rows = rawRows.computeIfAbsent(segment, k -> new ArrayList<>());
            int room = SEED_ROWS - rows.size();
            if (room <= 0) return;
            try (Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery("SELECT * FROM \"" + source + "\" LIMIT " + room)) {
                for (Map<String, Object> row : JdbcRows.toMaps(rs)) {
                    row.remove(SRC_ID);
                    rows.add(row);
                }
            }
            if (rows.isEmpty()) rawRows.remove(segment);
        };

        for (Consignment batch : batches) {
            ConsignmentIngestStrategy strategy = (scratch.schemas().ingesterClass() == null)
                    ? new CsvIngestStrategy()
                    : new StreamingPluginIngestStrategy();

            IngestOutcome outcome;
            try {
                outcome = ScopedValue.where(DataTransformer.RAW_INPUT, capture)
                        .call(() -> strategy.ingest(batch, scratch));
            } finally {
                // Mirrors ConsignmentIngestor.process — a progress snapshot must never outlive the batch.
                IngestProgress.clear(scratch.identity().pipelineName());
                StepProgress.clear(scratch.identity().pipelineName());
            }
            // ⚠ DELIBERATELY NOT CALLED: commit(...) / writeAudit(...) / recordProvenance(...).
            // Those three are the entire production side-effect surface — see the class javadoc.
            // If you add a fourth side-effecting call to ConsignmentIngestor.process, it must not be
            // mirrored here, and this comment is where you will find out why.

            for (MemberAudit m : outcome.memberAudits())
                files.add(new FileResult(m.filename(), m.status().name(), m.parsedRows(), m.errorRows(), m.error()));
            outputs.addAll(outcome.outputs());
            schemaByOutput.putAll(outcome.schemaByOutput());
            inputRows += outcome.totalInputRows();
            written += outcome.lineage().stream().mapToLong(LineageRow::rowCount).sum();
            if (outcome.castFailures() >= 0) casts = (casts < 0 ? 0 : casts) + outcome.castFailures();
            if ("FAILED".equals(outcome.status())) {
                anyFailed = true;
                if (error.isEmpty()) error = outcome.error();
            } else if ("SUCCESS".equals(outcome.status())) {
                anyRows = true;
            }
        }

        String status = anyFailed ? "FAILED" : (anyRows ? "SUCCESS" : "EMPTY");
        log.info("Test run of pipeline {} over {} file(s): {} — {} row(s) in, {} written",
                cfg.identity().pipelineName(), pickedFiles.size(), status, inputRows, written);
        return new Result(status, batches.size(), List.copyOf(files), inputRows, written, casts,
                List.copyOf(outputs), error, Map.copyOf(schemaByOutput), Collections.unmodifiableMap(rawRows));
    }

    /**
     * The segment whose schema a transform is applying, or {@code null} for a single-schema Pipeline. The
     * union-mode ingester passes the segment's own schema map from the config, so it is found by identity
     * first and by value as a fallback. ⛔ Never guessed from table names.
     */
    private static String segmentOf(PipelineConfig cfg, Map<String, Object> schema) {
        Map<String, Map<String, Object>> segments = cfg.schemas().segments();
        if (segments == null) return null;
        for (Map.Entry<String, Map<String, Object>> e : segments.entrySet())
            if (e.getValue() == schema) return e.getKey();
        for (Map.Entry<String, Map<String, Object>> e : segments.entrySet())
            if (e.getValue().equals(schema)) return e.getKey();
        return null;
    }

    /**
     * Read up to {@code limit} parsed rows back out of a run's scratch outputs, in the
     * {@code List<Map<String,Object>>} shape {@link com.gamma.pipeline.exec.PipelineDryRun#run} seeds
     * from — the bridge that lets the graph preview run over <b>real</b> data instead of synthetic
     * sample rows (Step 5a-ii).
     *
     * <p>Reads through {@link SqlViews#reader} so the format's option list is the same one every other
     * reader in the codebase uses, rather than a hand-built {@code read_*(}. ⚠ {@code hive_partitioning}
     * stays <b>off</b>, matching {@code DatasetRelation}: enabling it would surface partition columns
     * that are not part of the parsed row, which would misrepresent what the pipeline actually produced.
     *
     * <p>Returns an empty list when the run wrote nothing ({@code EMPTY}/{@code FAILED}) — the caller
     * decides whether that is a warning, since {@link com.gamma.pipeline.exec.PipelineDryRun} refuses an
     * empty sample.
     *
     * @param result      a result from {@link #run}, whose scratch root must still exist
     * @param outputFormat {@code cfg.output().format()} — the format the run wrote
     */
    public static List<Map<String, Object>> sampleRows(Result result, String outputFormat, int limit)
            throws SQLException, IOException {
        if (result.outputs().isEmpty() || limit <= 0) return List.of();
        List<String> paths = result.outputs().stream().map(PartitionOutput::outputFile).toList();
        File db = DuckDbUtil.tempDbFile("testrun_sample_");
        try (Connection conn = DuckDbUtil.openConnection(db);
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT * FROM " + SqlViews.reader(outputFormat, paths, false) + " LIMIT " + limit)) {
            return JdbcRows.toMaps(rs);
        } finally {
            DuckDbUtil.deleteTempDb(db);
        }
    }

    /**
     * Copy the picked files into the scratch poll dir. A copy, not a move or a hardlink — see the
     * class javadoc. A name already staged gets a numeric suffix so two same-named files from
     * different directories cannot collide into one.
     */
    private static List<File> stage(List<Path> picked, Path pollDir) throws IOException {
        Files.createDirectories(pollDir);
        List<File> staged = new ArrayList<>();
        for (Path src : picked) {
            if (!Files.isRegularFile(src))
                throw new IOException("not a readable file: " + src);
            String name = src.getFileName().toString();
            Path dst = pollDir.resolve(name);
            for (int n = 2; Files.exists(dst); n++) dst = pollDir.resolve(n + "_" + name);
            Files.copy(src, dst, StandardCopyOption.COPY_ATTRIBUTES);
            staged.add(dst.toFile());
        }
        return staged;
    }

    /** Best-effort recursive delete of a scratch root. Never throws — cleanup must not mask a result. */
    public static void deleteScratch(Path scratchRoot) {
        if (scratchRoot == null || !Files.exists(scratchRoot)) return;
        try (Stream<Path> walk = Files.walk(scratchRoot)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        } catch (IOException | UncheckedIOException e) {
            log.warn("Could not fully delete test-run scratch {}: {}", scratchRoot, e.toString());
        }
    }
}
