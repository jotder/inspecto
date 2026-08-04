package com.gamma.consignment;

import com.gamma.api.PublicApi;
import com.gamma.etl.PartitionOutput;
import com.gamma.etl.PartitionWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * <b>§7.3 — the durable summary tier.</b> Writes the rows a {@link GuardedSummaryEmitter} validated as
 * <b>one Parquet file per (Consignment × record-day)</b>, partitioned by {@code record_day}, under a
 * {@code _summaries/&lt;target&gt;/} tree of its own.
 *
 * <p>The separate tree is what gives §7.3's "its own compaction horizon" somewhere to point: a {@code compact}
 * job can target the summary root with a different {@code min_age_days} than the detail data. One file per
 * Consignment (never a shared file rewritten) is what keeps §5.1's append-only invariant intact, and it is why
 * this reuses {@link PartitionWriter} rather than issuing {@code COPY … PARTITION_BY} directly — DuckDB names
 * partition files {@code data_0.parquet}, which would collide between Consignments; {@code PartitionWriter}
 * stages and then reveals each file under a per-Consignment name.
 *
 * <p><b>Composability survives to read time in a sidecar</b>, not in a column name or a database. See
 * {@link #writeMeasureSidecar}: §7.2's rules are worthless if a reader can sum an average, and the one artifact
 * guaranteed to travel with a copied directory is a file beside it.
 *
 * <p>⚠ <b>Unpartitioned fallback (operator call, 2026-08-04, against advice).</b> When a target's rows carry no
 * {@code record_day}, its summary is written as a single flat file instead of being refused. §7.3 explicitly
 * <em>supersedes</em> the flat layout — "flat summary directories mean 'give me day D' globs everything" — so
 * this reintroduces the read pattern the section exists to fix, silently, for any target whose author forgot the
 * key. It is deliberate and recorded; prefer emitting {@code record_day} in every {@link SummaryRow}.
 */
@PublicApi(since = "5.0.0")
public final class SummaryWriter {

    private static final Logger log = LoggerFactory.getLogger(SummaryWriter.class);

    /** The reserved key that drives §7.3's partitioning. */
    public static final String RECORD_DAY = "record_day";

    /** Sidecar file naming the composability of every measure ever written for a target. */
    static final String MEASURES_SIDECAR = "_measures.csv";

    /** Suffix on the registry's {@code table_name} for summary files — see {@link #register}. */
    static final String SUMMARY_SUFFIX = "__summary";

    /**
     * Identifiers that may become a SQL column or a <b>directory name</b>. Deliberately strict: a target arrives
     * from third-party processor code, and it is used to build a path, so anything outside this set is refused
     * rather than escaped. This is the path-jail rule applied at the seam where untrusted names enter.
     */
    private static final Pattern SAFE_NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_]{0,127}");

    private SummaryWriter() {}

    /**
     * Write every validated row, grouped by target, and return the registry rows describing the files produced.
     *
     * <p>Does <b>not</b> register them — the caller does, so this stays a pure writer and the ordering rule
     * ("register only after the data is revealed") lives in one place.
     *
     * @param conn          a DuckDB connection used only for scratch tables; nothing in it is retained
     * @param summariesRoot the summary tree root, e.g. {@code <dataDir>/_summaries}
     * @param consignmentId the unit of work, which names each file and owns each registry row
     * @param rows          rows already validated by {@link GuardedSummaryEmitter}
     */
    public static List<ConsignmentOutput> write(Connection conn, String summariesRoot,
                                               String consignmentId, List<SummaryRow> rows) throws Exception {
        if (rows == null || rows.isEmpty()) return List.of();
        requireSafe(consignmentId, "consignment id");

        Map<String, List<SummaryRow>> byTarget = new LinkedHashMap<>();
        for (SummaryRow r : rows) byTarget.computeIfAbsent(r.target(), t -> new ArrayList<>()).add(r);

        List<ConsignmentOutput> out = new ArrayList<>();
        String writtenAt = Instant.now().toString();
        for (Map.Entry<String, List<SummaryRow>> e : byTarget.entrySet())
            out.addAll(writeTarget(conn, summariesRoot, consignmentId, e.getKey(), e.getValue(), writtenAt));
        return out;
    }

    // ── one target ───────────────────────────────────────────────────────────────

    private static List<ConsignmentOutput> writeTarget(Connection conn, String summariesRoot,
                                                       String consignmentId, String target,
                                                       List<SummaryRow> rows, String writtenAt) throws Exception {
        requireSafe(target, "summary target");

        // Column sets are the UNION across the target's rows, in first-seen order: two processors (or two
        // Consignments) may summarise the same target at slightly different grains, and a missing cell is a
        // legitimate NULL rather than a reason to refuse.
        Set<String> keyCols = new LinkedHashSet<>();
        Set<String> measureCols = new LinkedHashSet<>();
        Map<String, Measure.Composability> composability = new LinkedHashMap<>();
        for (SummaryRow r : rows) {
            for (String k : r.keys().keySet()) requireSafe(k, "summary key");
            keyCols.addAll(r.keys().keySet());
            for (Measure m : r.measures()) {
                requireSafe(m.name(), "measure name");
                measureCols.add(m.name());
                composability.putIfAbsent(m.name(), m.composability());
            }
        }
        for (String m : measureCols)
            if (keyCols.contains(m))
                throw new IllegalArgumentException("summary target '" + target + "': '" + m
                        + "' is both a grain key and a measure — one name must mean one thing");

        // §7.3 partitions by record_day. Partition only when EVERY row carries one: a target split across both
        // layouts would be readable by neither a day-pruned nor a flat query.
        boolean partitioned = keyCols.contains(RECORD_DAY)
                && rows.stream().allMatch(r -> notBlank(r.keys().get(RECORD_DAY)));
        if (keyCols.contains(RECORD_DAY) && !partitioned)
            log.warn("summary target '{}': some rows carry no {} — writing the whole target unpartitioned, so "
                            + "day-pruned reads over it degrade to a full scan (§7.3)", target, RECORD_DAY);

        String scratch = "__summary_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        Path targetDir = Path.of(summariesRoot, target);
        Files.createDirectories(targetDir);
        try {
            materialise(conn, scratch, keyCols, measureCols, rows, partitioned);
            String baseName = consignmentId + "_summary";

            List<PartitionOutput> written = partitioned
                    ? PartitionWriter.write(conn, scratch, targetDir.toString(), "PARQUET", null,
                            baseName, List.of(RECORD_DAY), List.of())
                    : List.of(writeFlat(conn, scratch, targetDir, baseName + "_out.parquet"));

            writeMeasureSidecar(targetDir, composability);

            Map<String, Long> counts = ConsignmentOutputs.countByPartition(conn, scratch,
                    partitioned ? List.of(RECORD_DAY) : List.of());
            return register(consignmentId, target, written, counts, writtenAt);
        } finally {
            try (Statement st = conn.createStatement()) {
                st.execute("DROP TABLE IF EXISTS " + scratch);
            } catch (Exception ignored) { /* scratch table in a caller-owned connection */ }
        }
    }

    /** Build the scratch relation: grain keys as VARCHAR, measures as DOUBLE (§7.2 stores components, not text). */
    private static void materialise(Connection conn, String scratch, Set<String> keyCols, Set<String> measureCols,
                                    List<SummaryRow> rows, boolean partitioned) throws Exception {
        List<String> cols = new ArrayList<>();
        StringBuilder ddl = new StringBuilder("CREATE TABLE ").append(scratch).append(" (");
        for (String k : keyCols) {
            if (!cols.isEmpty()) ddl.append(", ");
            ddl.append('"').append(k).append("\" VARCHAR");
            cols.add(k);
        }
        for (String m : measureCols) {
            if (!cols.isEmpty()) ddl.append(", ");
            ddl.append('"').append(m).append("\" DOUBLE");
            cols.add(m);
        }
        ddl.append(')');
        try (Statement st = conn.createStatement()) {
            st.execute(ddl.toString());
        }

        StringBuilder ins = new StringBuilder("INSERT INTO ").append(scratch).append(" VALUES (");
        for (int i = 0; i < cols.size(); i++) ins.append(i == 0 ? "?" : ", ?");
        ins.append(')');
        try (PreparedStatement ps = conn.prepareStatement(ins.toString())) {
            for (SummaryRow r : rows) {
                Map<String, Measure> measures = r.byName();
                int i = 1;
                for (String k : keyCols) {
                    String v = r.keys().get(k);
                    // An unpartitioned target may legitimately have no record_day; a partitioned one cannot,
                    // because a NULL partition value would produce a Hive dir DuckDB cannot round-trip.
                    if (RECORD_DAY.equals(k) && partitioned && !notBlank(v))
                        throw new IllegalStateException("record_day missing on a partitioned summary row");
                    ps.setString(i++, v);
                }
                for (String m : measureCols) {
                    Measure mm = measures.get(m);
                    if (mm == null) ps.setNull(i++, java.sql.Types.DOUBLE);
                    else ps.setDouble(i++, mm.value());
                }
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    /**
     * The unpartitioned write. {@link PartitionWriter} cannot do this — it always emits
     * {@code PARTITION_BY (…)} and derives each partition from the staged file's parent directory — so this
     * mirrors its stage-then-atomically-reveal discipline for the single-file case.
     */
    private static PartitionOutput writeFlat(Connection conn, String scratch, Path dir, String fileName)
            throws Exception {
        Path staged = dir.resolve(fileName + "." + UUID.randomUUID().toString().substring(0, 8) + ".tmp");
        try (Statement st = conn.createStatement()) {
            st.execute("COPY (SELECT * FROM " + scratch + ") TO '"
                    + staged.toAbsolutePath().toString().replace('\\', '/').replace("'", "''")
                    + "' (FORMAT PARQUET)");
        }
        Path finalPath = dir.resolve(fileName);
        Files.move(staged, finalPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        return new PartitionOutput("", finalPath.toString(), Files.size(finalPath));
    }

    /**
     * Write (merging with what is already there) the sidecar naming each measure's composability.
     *
     * <p><b>Merged rather than overwritten</b>, because two Consignments may summarise the same target with
     * different measure sets and the sidecar describes the target, not one run. This read-modify-write is not a
     * §5.2 violation: §5.2 governs the append-only <em>data</em> path, and this is derived metadata that can be
     * rebuilt from the Parquet schemas at any time.
     */
    static void writeMeasureSidecar(Path targetDir, Map<String, Measure.Composability> composability)
            throws IOException {
        Path sidecar = targetDir.resolve(MEASURES_SIDECAR);
        Map<String, String> merged = new LinkedHashMap<>();
        if (Files.exists(sidecar)) {
            List<String> lines = Files.readAllLines(sidecar, StandardCharsets.UTF_8);
            for (int i = 1; i < lines.size(); i++) {          // skip header
                String[] c = lines.get(i).split(",", -1);
                if (c.length >= 2 && !c[0].isBlank()) merged.put(c[0], c[1]);
            }
        }
        composability.forEach((name, comp) -> merged.put(name, comp == null ? "" : comp.name()));

        StringBuilder sb = new StringBuilder("measure,composability\n");
        merged.forEach((name, comp) -> sb.append(name).append(',').append(comp).append('\n'));
        Path staged = targetDir.resolve(MEASURES_SIDECAR + ".tmp");
        Files.writeString(staged, sb.toString(), StandardCharsets.UTF_8);
        Files.move(staged, sidecar, StandardCopyOption.REPLACE_EXISTING);
    }

    /**
     * Registry rows for the written files.
     *
     * <p><b>{@code table_name} carries a {@code __summary} suffix, and that is load-bearing.</b>
     * {@link GuardedSummaryEmitter#reconcile} sums the registry's detail {@code row_count} <em>by table name</em>;
     * registering a summary under the target's own name would inflate that total and silently break §7.2's
     * reconciliation — the very check these rows exist to support.
     */
    private static List<ConsignmentOutput> register(String consignmentId, String target,
                                                    List<PartitionOutput> written, Map<String, Long> counts,
                                                    String writtenAt) {
        List<ConsignmentOutput> out = new ArrayList<>(written.size());
        for (PartitionOutput p : written) {
            String partition = p.partition() == null ? "" : p.partition();
            out.add(new ConsignmentOutput(consignmentId, null, target + SUMMARY_SUFFIX, partition,
                    recordDayOf(partition), p.outputFile(), counts.getOrDefault(partition, 0L),
                    p.bytes(), writtenAt, 0, ConsignmentOutput.State.LIVE));
        }
        return out;
    }

    /** {@code record_day=2026-07-01} → {@code 2026-07-01}; anything else (including flat) has no record day. */
    private static String recordDayOf(String partition) {
        String prefix = RECORD_DAY + "=";
        return partition.startsWith(prefix) ? partition.substring(prefix.length()) : null;
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    private static void requireSafe(String name, String what) {
        if (name == null || !SAFE_NAME.matcher(name).matches())
            throw new IllegalArgumentException("unsafe " + what + " '" + name
                    + "': must match " + SAFE_NAME.pattern()
                    + " — it becomes a SQL identifier and a directory name");
    }
}
