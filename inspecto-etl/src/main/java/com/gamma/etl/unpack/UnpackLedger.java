package com.gamma.etl.unpack;

import com.gamma.util.CsvLedger;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The run-level <b>unpack ledger</b> (unpack-stage plan §2.2, unblocked by the operator's §6 Q1
 * sign-off 2026-08-26): one row per ARCHIVE per RUN, the fourth CSV ledger beside status / batches /
 * lineage.
 *
 * <h3>Why the Archive's row is a RUN fact and not a Consignment member</h3>
 * An archive can outlive one Consignment — {@code archive.zip} with 500 entries against
 * {@code batch.max_files: 100} plans FIVE batches, so the roll-up cannot be a {@code MemberEntry} in
 * "the" manifest, because there are five. The expansion happens once per Run, before Consignments are
 * planned, so the Run is the honest home. Entries stay ordinary Files with per-Consignment status
 * exactly as before.
 *
 * <h3>⛔ One declaration — do not grow a sixth mirror</h3>
 * The batches ledger's column list is restated in FIVE places (writer header, row record, codec,
 * {@code OperationalTables.BATCHES}, and a test's literal) and none is generated from another, so a
 * column added to one and missed in another drifts silently — and because reads are by header NAME,
 * a stale mirror <em>hides</em> the column rather than erroring. This ledger declares its columns
 * ONCE in {@link #COLUMNS}; the header is joined from it and the codec is index-aligned to it by
 * construction. The plan calls this out by name: "this new ledger must not repeat that pattern".
 *
 * <h3>Lifecycle</h3>
 * Accumulated in memory across a run and flushed once at its end ({@link #flush}). ⚠ NOT written at
 * the {@code UnpackOrigins.consume()} release points, though those are where an archive's last entry
 * lands: a batch that fails at COMMIT runs neither the finalize nor the quarantine path, so the
 * release never fires — and those are exactly the archives an operator most needs a row for. A crash
 * loses the row, which is the same posture the rest of the unpack registry already takes.
 *
 * <p>Keyed by the archive's normalized ABSOLUTE path, never its filename: {@code in/east/data.zip}
 * and {@code in/west/data.zip} are two archives, and keying on the basename would silently sum them
 * into one row.
 */
public final class UnpackLedger {

    /**
     * The ledger's columns, declared ONCE. {@link #HEADER} is joined from this and {@link #line} is
     * index-aligned to it — see the class comment on why this is not restated anywhere else.
     */
    public static final List<String> COLUMNS = List.of(
            "run_id", "archive_relpath", "format", "entries_found", "entries_ingested",
            "entries_failed", "entries_skipped", "bytes_in", "bytes_out", "status", "error",
            "consignment_ids");

    /** The header line — derived from {@link #COLUMNS}, never typed out a second time. */
    public static final String HEADER = String.join(",", COLUMNS);

    /** run id → archive absolute path → that archive's tally. */
    private static final Map<String, Map<Path, Row>> RUNS = new ConcurrentHashMap<>();

    private UnpackLedger() {}

    /** One archive's accumulating tally. Mutated under the owning run's monitor. */
    private static final class Row {
        private final Path archive;
        private String format = "";
        private int found;
        private int ingested;
        private int failed;
        private int skipped;
        private long bytesIn;
        private long bytesOut;
        private boolean expansionFailed;
        private String error = "";
        private final Set<String> consignmentIds = new LinkedHashSet<>();

        Row(Path archive) { this.archive = archive; }
    }

    /**
     * Record an archive's expansion outcome — everything knowable at expand time, before any
     * Consignment is planned.
     *
     * @param found  entries seen in the walk, readable or not; {@code 0} distinguishes an EMPTY
     *               archive from an all-encrypted UNREADABLE one (see {@link UnpackStatus#verdict})
     * @param error  the expansion failure message, or blank when it succeeded
     */
    public static void expanded(String runId, File archive, String format, int found, int skipped,
                                long bytesIn, long bytesOut, boolean expansionFailed, String error) {
        Row r = row(runId, archive);
        synchronized (r) {
            r.format = format;
            r.found = found;
            r.skipped = skipped;
            r.bytesIn = bytesIn;
            r.bytesOut = bytesOut;
            r.expansionFailed = expansionFailed;
            r.error = error == null ? "" : error;
        }
    }

    /**
     * Record one entry's outcome against its archive, plus the Consignment it landed in.
     *
     * <p>⚠ Must be called with the archive path captured at INGEST time ({@code MemberAudit.originPath}),
     * never resolved later: commit's {@code UnpackStage.cleanup} consumes the origin mapping, so a
     * late lookup reads blank for every expanded file — the same trap {@code MemberAudit.origin}
     * already documents.
     */
    public static void entryOutcome(String runId, File archive, String consignmentId, boolean success) {
        Row r = row(runId, archive);
        synchronized (r) {
            if (success) r.ingested++; else r.failed++;
            if (consignmentId != null && !consignmentId.isBlank()) r.consignmentIds.add(consignmentId);
        }
    }

    /** Whether this run has any archive rows to write (cheap enough to call unconditionally). */
    public static boolean isEmpty(String runId) {
        Map<Path, Row> run = RUNS.get(runId);
        return run == null || run.isEmpty();
    }

    /**
     * Write and DISCARD this run's rows. Idempotent: a second call writes nothing, so a caller that
     * flushes on both the normal and the error path cannot double-report.
     *
     * @param pollRoot the poll root the archive paths are relativized against for display; an
     *                 archive outside it falls back to its filename
     */
    public static void flush(String runId, String ledgerPath, Path pollRoot) {
        Map<Path, Row> run = RUNS.remove(runId);
        if (run == null || run.isEmpty() || ledgerPath == null || ledgerPath.isBlank()) return;
        List<String> lines = new ArrayList<>(run.size());
        for (Row r : run.values()) {
            synchronized (r) { lines.add(line(runId, r, pollRoot)); }
        }
        new CsvLedger<String>(ledgerPath, HEADER, s -> s).appendAll(lines);
    }

    /** Drop a run's rows without writing them — for tests and for a run that never opened a ledger. */
    public static void discard(String runId) {
        RUNS.remove(runId);
    }

    /** One CSV line, index-aligned to {@link #COLUMNS} in the order declared there. */
    private static String line(String runId, Row r, Path pollRoot) {
        UnpackStatus status = UnpackStatus.verdict(
                r.found, r.ingested, r.failed, r.skipped, r.expansionFailed);
        String rel = r.archive.getFileName().toString();
        try {
            if (pollRoot != null && r.archive.startsWith(pollRoot))
                rel = pollRoot.relativize(r.archive).toString().replace('\\', '/');
        } catch (IllegalArgumentException ignored) { /* different root — keep the filename */ }
        return String.join(",",
                CsvLedger.q(runId),
                "\"" + CsvLedger.q(rel) + "\"",
                CsvLedger.q(r.format),
                String.valueOf(r.found),
                String.valueOf(r.ingested),
                String.valueOf(r.failed),
                String.valueOf(r.skipped),
                String.valueOf(r.bytesIn),
                String.valueOf(r.bytesOut),
                status.name(),
                "\"" + CsvLedger.q(r.error) + "\"",
                "\"" + CsvLedger.q(String.join(" ", r.consignmentIds)) + "\"");
    }

    private static Row row(String runId, File archive) {
        Path key = archive.toPath().toAbsolutePath().normalize();
        return RUNS.computeIfAbsent(runId, k -> new ConcurrentHashMap<>())
                .computeIfAbsent(key, Row::new);
    }
}
