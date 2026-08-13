package com.gamma.inspector;

import com.gamma.etl.Batch;
import com.gamma.etl.ConsignmentPlanner;
import com.gamma.etl.IngestProgress;
import com.gamma.etl.LineageRow;
import com.gamma.etl.PartitionOutput;
import com.gamma.etl.PipelineConfig;
import com.gamma.etl.SchemaSelector;
import com.gamma.etl.StepProgress;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
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
 * <p><b>1. Call-graph containment.</b> {@link BatchProcessor#process} is, in order:
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
 * to remove: {@code CsvBatchStrategy} quarantines an unreadable / field-mismatched / empty member via
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

    /** Per-input-file outcome. {@code status} is {@code SUCCESS} or a {@code QUARANTINED_*} reason. */
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
    public record Result(String status, int batches, List<FileResult> files, long totalInputRows,
                         long rowsWritten, long castFailures, List<PartitionOutput> outputs, String error) {}

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

        List<Batch> batches = ConsignmentPlanner.plan(
                staged, resolver,
                scratch.processing().batchMaxFiles(), scratch.processing().batchMaxBytes(),
                scratch.identity().runTimestamp(),
                ConsignmentPlanner.Order.valueOf(
                        scratch.processing().batchOrder().toUpperCase(java.util.Locale.ROOT)));

        List<FileResult> files = new ArrayList<>();
        List<PartitionOutput> outputs = new ArrayList<>();
        long inputRows = 0, written = 0, casts = -1;
        boolean anyFailed = false, anyRows = false;
        String error = "";

        for (Batch batch : batches) {
            BatchIngestStrategy strategy = (scratch.schemas().ingesterClass() == null)
                    ? new CsvBatchStrategy()
                    : new StreamingPluginBatchStrategy();

            IngestOutcome outcome;
            try {
                outcome = strategy.ingest(batch, scratch);
            } finally {
                // Mirrors BatchProcessor.process — a progress snapshot must never outlive the batch.
                IngestProgress.clear(scratch.identity().pipelineName());
                StepProgress.clear(scratch.identity().pipelineName());
            }
            // ⚠ DELIBERATELY NOT CALLED: commit(...) / writeAudit(...) / recordProvenance(...).
            // Those three are the entire production side-effect surface — see the class javadoc.
            // If you add a fourth side-effecting call to BatchProcessor.process, it must not be
            // mirrored here, and this comment is where you will find out why.

            for (MemberAudit m : outcome.memberAudits())
                files.add(new FileResult(m.filename(), m.status(), m.parsedRows(), m.errorRows(), m.error()));
            outputs.addAll(outcome.outputs());
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
                List.copyOf(outputs), error);
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
