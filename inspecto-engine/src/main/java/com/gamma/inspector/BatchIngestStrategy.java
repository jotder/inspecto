package com.gamma.inspector;

import com.gamma.consignment.ConsignmentOutputs;
import com.gamma.consignment.EventTimeBounds;
import com.gamma.etl.Batch;
import com.gamma.etl.CsvIngester;
import com.gamma.query.DecisionRuleApplier;
import com.gamma.etl.LineageCollector;
import com.gamma.etl.LineageRow;
import com.gamma.etl.PartitionDef;
import com.gamma.etl.PartitionOutput;
import com.gamma.etl.PartitionWriter;
import com.gamma.etl.PipelineConfig;
import com.gamma.util.DuckDbUtil;

import java.io.File;
import java.io.IOException;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;

/**
 * The ingest+transform+write half of processing one {@link Batch} — the part that
 * differs between the built-in CSV path and the plugin-ingester path. Each strategy
 * owns its own DuckDB connection lifecycle and produces an {@link IngestOutcome}
 * (survivors, outputs, lineage, per-member audit, status); the shared, path-agnostic
 * tail — commit (register → manifest → backup → markers) and audit — stays in
 * {@link BatchProcessor}, which selects the strategy and drives that tail.
 *
 * <p>This replaces the former {@code processCsv}/{@code processPlugin} god-methods:
 * {@link BatchProcessor#process} now dispatches polymorphically on
 * {@link PipelineConfig.Schemas#ingesterClass()} instead of branching inline.
 *
 * <p>Implementations are stateless and cheap to instantiate per batch.
 */
interface BatchIngestStrategy {

    /**
     * Ingest, transform, and write {@code batch}. Never throws: ingest failures are
     * captured into the returned outcome as {@code status = "FAILED"} so the batch
     * still flows through commit-skip + audit exactly as before.
     */
    IngestOutcome ingest(Batch batch, PipelineConfig cfg);

    // ── shared helpers ──────────────────────────────────────────────────────────

    /** Best-effort {@code DROP TABLE IF EXISTS}, swallowing any error. */
    static void dropTable(Connection conn, String table) {
        try (Statement st = conn.createStatement()) {
            st.execute("DROP TABLE IF EXISTS \"" + table + "\"");
        } catch (Exception ignored) { }
    }

    /** Best-effort {@code DROP VIEW IF EXISTS}, swallowing any error. */
    static void dropView(Connection conn, String view) {
        try (Statement st = conn.createStatement()) {
            st.execute("DROP VIEW IF EXISTS \"" + view + "\"");
        } catch (Exception ignored) { }
    }

    /** A non-null message for an exception, falling back to its simple class name. */
    static String msg(Exception e) {
        return e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
    }

    // ── shared ingest-tail helpers (used by both strategies) ─────────────────────

    /**
     * Partition columns from a schema. E1: a schema declaring NO partition source yields an EMPTY
     * list — the write lands as one flat file per batch instead of the {@code year=1900/month=01/
     * day=01} sentinel bucket the old {@code (year,month,day)} default degenerated to. Read-back is
     * layout-agnostic (depth-agnostic globs; the Selector subtracts by catalog status), so existing
     * sentinel directories stay readable beside new flat files — old data is deliberately left in
     * place (D6).
     */
    static List<String> partitionColumns(Map<String, Object> schema) {
        List<PartitionDef> defs = PartitionDef.fromSchema(schema);
        return defs.isEmpty() ? List.of() : PartitionDef.columnNames(defs);
    }

    /**
     * The result of one partitioned write: output files, their lineage matrix, and each file's event-time
     * bounds.
     *
     * @param bounds §3.1 event-time range per output file. Covers only the files written from the measured
     *               relation — decision-rule routed files write different rows and carry no entry — and is
     *               empty whenever the relation has no {@code __event_time} column at all.
     */
    record Written(List<PartitionOutput> outputs, List<LineageRow> lineage,
                   Map<String, EventTimeBounds> bounds) {}

    /**
     * The shared tail of every ingest path: apply the pipeline's Decision Rules to {@code table}
     * ({@link DecisionRuleApplier} — exact no-op when none are authored), then write it
     * Hive-partitioned under {@code dbDir} and collect the input→output lineage matrix over the same
     * partitions. Routed rows contribute their own outputs + lineage.
     *
     * <p><b>Multi-destination fan-out ({@code sinks:}).</b> When the pipeline declares more than one
     * {@link PipelineConfig#sinks() destination}, the main partitioned write is repeated to each — under
     * that destination's own {@code database} root (the {@code dbDir} suffix beyond {@code dirs.database},
     * e.g. the {@code table} subdir, is preserved) and its own {@code format}/{@code compression}. A single
     * destination (the {@code output:} shorthand) is byte-for-byte the legacy single write. The Decision
     * Rules run <em>once</em> (they have side effects — routed writes + quarantine — that must not repeat),
     * so decision-rule routing combined with multiple destinations is refused here; a versioned reference
     * store combined with multiple destinations is refused earlier, at {@link PipelineConfig#prepare()}.
     * The batch's source finalisation (backup/markers/ledger) runs once over the union of every
     * destination's outputs — those side effects are per-source-file, not per-destination.
     */
    static Written writeAndTrace(Connection conn, String table, List<String> partCols,
                                 PipelineConfig cfg, String dbDir, String baseName,
                                 String batchId, Map<Integer, String> srcIdToFile) throws Exception {
        List<PipelineConfig.Sink> sinks = cfg.sinks();
        boolean fanOut = sinks.size() > 1;

        DecisionRuleApplier.Result applied = DecisionRuleApplier.apply(
                conn, table, cfg, dbDir, baseName, partCols, batchId, srcIdToFile);
        if (fanOut && !applied.outputs().isEmpty())
            throw new IllegalStateException("decision-rule routing writes to a single destination; combining "
                    + "it with a multi-destination sinks: pipeline is not yet supported");

        // Reference Phase-2 P1/P2: a `produces: reference` pipeline with `load: upsert|scd2` writes an
        // append-only versioned store — each batch stamps system columns (__key_hash/__row_hash/
        // __valid_from/__op/__batch_id), folds out within-batch key duplicates, skips rows identical to
        // the store's current version, and reveals under a batch-unique file stem so prior versions
        // survive (latest-version-wins / as-of are derived at read time by the enrichment views).
        // `load: replace` (the default) is untouched — plain overwrite. (Refused with sinks:>1 at prepare().)
        String writeTable = table;
        String writeBase  = baseName;

        // ⚠ Record-grain dedup does NOT run here. It used to (a ROW_NUMBER QUALIFY between transform and
        // the write), and it was the one cross-record operation in the ingest path. Moved out 2026-08-11
        // (operator decision): dedup is a TRANSFORM concern, so in ELT terms it belongs in the T, not the
        // EL. Stage-1 stays the M..N multiplexer — per-record work and routing — which is what keeps each
        // batch embarrassingly parallel and crash-isolated.
        //
        // It is not silently dropped: PipelineConfig.prepare() now REFUSES to arm a pipeline carrying
        // processing.dedup, exactly as it already did for route/summarize/join. The three cross-record
        // kinds are finally uniform — parsed, lifted and round-tripped, executed by Stage-2 only.

        if (cfg.producesReference() && cfg.reference().load().versionedStore()) {
            String versioned = "__ref_versioned";
            stampReferenceVersions(conn, writeTable, versioned, cfg.reference().key(), batchId,
                    existingStoreReader(dbDir, cfg.output().format()));
            writeTable = versioned;
            writeBase = baseName + "__v_" + batchId;   // batch-unique ⇒ append, never overwrite
        }

        // Fan the main partitioned write out to every destination (one for the single-output shorthand,
        // byte-for-byte). `rel` is dbDir's suffix beyond dirs.database (e.g. the table subdir), re-rooted
        // under each destination's own database.
        java.nio.file.Path rel = fanOut
                ? Paths.get(cfg.dirs().database()).relativize(Paths.get(dbDir)) : null;
        List<PartitionOutput> outputs = new java.util.ArrayList<>(applied.outputs());
        List<LineageRow> lineage = new java.util.ArrayList<>(applied.lineage());

        // §3.1: one GROUP BY over the relation being written, before the fan-out — the same event-time range
        // applies to every destination, since each writes the identical rows. Empty for a relation with no
        // __event_time (nothing downstream requires bounds).
        Map<String, EventTimeBounds> byPartition = ConsignmentOutputs.boundsByPartition(conn, writeTable, partCols);
        Map<String, EventTimeBounds> bounds = new java.util.HashMap<>();

        for (PipelineConfig.Sink dest : sinks) {
            String destDir = fanOut ? Paths.get(dest.database()).resolve(rel).toString() : dbDir;
            // B4: dest.filenameColumn() (null = byte-identical) translates __src_id into a source-file
            // column via the same map the lineage ledger uses.
            List<PartitionOutput> outs = PartitionWriter.write(conn, writeTable, destDir,
                    dest.format(), dest.compression(), writeBase, partCols,
                    dest.filenameColumn(), srcIdToFile);
            outputs.addAll(outs);
            lineage.addAll(LineageCollector.collect(conn, writeTable, batchId, srcIdToFile, outs, partCols));
            // Re-key onto files, and only these files: applied.outputs() came from a different relation.
            for (PartitionOutput o : outs) {
                EventTimeBounds b = byPartition.get(o.partition());
                if (b != null) bounds.put(o.outputFile(), b);
            }
        }
        return new Written(outputs, lineage, bounds);
    }

    // ⚠ applyRecordDedup lived here and was deleted 2026-08-11 with the move of record dedup to Stage-2.
    // Its replacement already exists and is better: RowShaper.dedup (pipeline/exec) computes the same
    // ROW_NUMBER window but emits the losers as a first-class `duplicate` relation instead of counting
    // and discarding them — so the rows this lane could only report as a number are inspectable there.
    // A consequence worth knowing: EventType.DEDUP_RECORDS_DROPPED now has NO emitter. The constant is
    // deliberately kept — the taxonomy is public and the Stage-2 executor is the right place to emit it.

    /** The reference system columns a versioned store carries (§2.1) — never part of the payload hash. */
    List<String> REF_SYSTEM_COLUMNS =
            List.of("__key_hash", "__row_hash", "__valid_from", "__op", "__batch_id");

    /**
     * Reference Phase-2 P1/P2 (design (c) — append-only, latest-version-wins): materialise {@code dst}
     * from {@code src} with the reference system columns appended and within-batch key duplicates
     * folded out. Each surviving row carries {@code __key_hash} (canonical hash of the declared
     * {@code reference.key} columns), {@code __row_hash} (canonical hash of the whole payload),
     * {@code __valid_from} (load instant), {@code __op} ({@code 'upsert'} on the ingest path —
     * {@code 'delete'} tombstones are honoured by the read-side views but are not produced here) and
     * {@code __batch_id}. The lineage tag {@code __src_id} is kept so {@link PartitionWriter}'s default
     * exclude and {@link LineageCollector} keep working unchanged — but it is excluded from
     * {@code __row_hash}, since it is per-batch bookkeeping and would make every re-delivery look changed.
     *
     * <p>Within-batch dedup keeps one row per {@code __key_hash} ({@code QUALIFY row_number() = 1}); a
     * batch that delivers the same key twice writes a single version. The winner is arbitrary
     * (no {@code order_by} column yet — the plan's optional latest-by-column is a later refinement).
     *
     * <p><b>P2 unchanged-row skip:</b> when {@code existingStoreReader} is non-null (the store already
     * has files), a row whose {@code (__key_hash, __row_hash)} equals its key's <em>current</em> version
     * in that store writes no new version — a re-delivered identical dimension row does not grow the
     * history. A changed payload, a new key, and a key whose current version is a tombstone all still
     * append.
     */
    static void stampReferenceVersions(Connection conn, String src, String dst,
                                       List<String> keyCols, String batchId,
                                       String existingStoreReader) throws SQLException {
        if (keyCols == null || keyCols.isEmpty())
            throw new IllegalStateException(
                    "reference load 'upsert'/'scd2' requires a non-empty reference.key (config validation "
                    + "should have rejected this pipeline before execution)");
        String keyHash = md5Of(keyCols);
        String rowHash = md5Of(payloadColumns(conn, src));
        String staged = "SELECT *, " + keyHash + " AS __key_hash, " + rowHash + " AS __row_hash, "
                + "now()::TIMESTAMP AS __valid_from, "
                + "'upsert' AS __op, "
                + "'" + batchId.replace("'", "''") + "' AS __batch_id "
                + "FROM \"" + src + "\" "
                + "QUALIFY row_number() OVER (PARTITION BY " + keyHash + ") = 1";
        String sql = "CREATE TABLE \"" + dst + "\" AS SELECT * FROM (" + staged + ") AS _staged";
        if (existingStoreReader != null)
            // Both are fixed-length md5 hex and never null, so the concatenation is unambiguous —
            // and a scalar IN-list is portable where a row-constructor IN is not.
            sql += " WHERE __key_hash || __row_hash NOT IN ("
                    + "SELECT __key_hash || __row_hash FROM (SELECT * FROM " + existingStoreReader
                    + " QUALIFY row_number() OVER (PARTITION BY __key_hash ORDER BY __valid_from DESC) = 1"
                    + ") WHERE __op != 'delete')";
        try (Statement st = conn.createStatement()) {
            st.execute("DROP TABLE IF EXISTS \"" + dst + "\"");
            st.execute(sql);
        }
    }

    /** {@code md5(concat_ws(chr(31), COALESCE(CAST(c AS VARCHAR), ''), …))} over {@code cols}, in order. */
    private static String md5Of(List<String> cols) {
        StringBuilder hash = new StringBuilder("md5(concat_ws(chr(31)");
        for (String c : cols)
            hash.append(", COALESCE(CAST(\"").append(c.replace("\"", "\"\"")).append("\" AS VARCHAR), '')");
        return hash.append("))").toString();
    }

    /**
     * The columns of {@code src} that make up the reference payload — everything except the lineage tag
     * {@code __src_id} and any already-present system column. Read from the table metadata (not a
     * {@code COLUMNS(*)} star expression) so the hash expression is explicit and order-stable.
     */
    private static List<String> payloadColumns(Connection conn, String src) throws SQLException {
        List<String> cols = new java.util.ArrayList<>();
        try (Statement st = conn.createStatement();
             java.sql.ResultSet rs = st.executeQuery("SELECT * FROM \"" + src + "\" LIMIT 0")) {
            java.sql.ResultSetMetaData md = rs.getMetaData();
            for (int i = 1; i <= md.getColumnCount(); i++) {
                String c = md.getColumnName(i);
                if (!"__src_id".equals(c) && !REF_SYSTEM_COLUMNS.contains(c)) cols.add(c);
            }
        }
        if (cols.isEmpty())
            throw new IllegalStateException("reference batch '" + src + "' has no payload columns to hash");
        return cols;
    }

    /**
     * The table-function expression reading a versioned reference store's already-written files, or
     * {@code null} when the store is still empty (first batch — nothing to compare against, and a glob
     * matching no file is an error in DuckDB).
     */
    private static String existingStoreReader(String dbDir, String format) {
        String fmt = (format == null || format.isBlank()) ? "CSV" : format.toUpperCase(java.util.Locale.ROOT);
        String ext = com.gamma.sql.SqlViews.ext(fmt);
        java.nio.file.Path root = Paths.get(dbDir);
        if (!java.nio.file.Files.isDirectory(root)) return null;
        try (java.util.stream.Stream<java.nio.file.Path> walk = java.nio.file.Files.walk(root)) {
            if (walk.noneMatch(p -> java.nio.file.Files.isRegularFile(p)
                    && p.getFileName().toString().endsWith("." + ext))) return null;
        } catch (IOException e) {
            return null;   // unreadable tree ⇒ skip the optimisation, never fail the write
        }
        return com.gamma.sql.SqlViews.reader(fmt, dbDir.replace("\\", "/") + "/**/*." + ext, true);
    }

    /**
     * Consolidated-output base name: a single surviving member keeps its file stem (legacy
     * {@code <basename>_out.<ext>} naming); a multi-member batch is named by its batch id.
     */
    static String consolidatedBaseName(List<Batch.Member> survivors, Batch batch) {
        return survivors.size() == 1
                ? CsvIngester.stripExtensions(survivors.get(0).file().getName())
                : batch.batchId();
    }

    /** Output database dir for a batch: {@code dirs.database}, or a {@code table}-named subdir when set. */
    static String databaseDir(Batch batch, PipelineConfig cfg) {
        return (batch.table() != null && !batch.table().isBlank())
                ? Paths.get(cfg.dirs().database(), batch.table()).toString()
                : cfg.dirs().database();
    }

    /** A lazy {@code SELECT * … UNION ALL …} over the given relations (tables or views). */
    static String unionAll(List<String> relations) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < relations.size(); i++) {
            if (i > 0) sb.append(" UNION ALL ");
            sb.append("SELECT * FROM \"").append(relations.get(i)).append('"');
        }
        return sb.toString();
    }

    /**
     * The scratch directory for the per-batch temp DB <em>and</em> DuckDB's spill: explicit
     * {@code processing.duckdb.temp_directory}, else {@code dirs.temp} (on the data volume), else
     * {@code null} ⇒ fall back to the JVM temp dir. Routing scratch here is what keeps a huge
     * file's multi-hundred-GB temp data off a small system {@code /tmp}.
     */
    static String scratchDir(PipelineConfig cfg) {
        String explicit = cfg.duckdb().tempDirectory();
        if (explicit != null && !explicit.isBlank()) return explicit;
        String temp = cfg.dirs().temp();
        return (temp != null && !temp.isBlank()) ? temp : null;
    }

    /**
     * Create the per-batch temp DuckDB database in the resolved {@link #scratchDir scratch dir}
     * (data volume), falling back to {@code java.io.tmpdir} only when none is configured.
     */
    static File openTempDb(PipelineConfig cfg, String prefix) throws IOException {
        String dir = scratchDir(cfg);
        return dir == null ? DuckDbUtil.tempDbFile(prefix)
                           : DuckDbUtil.tempDbFile(prefix, Paths.get(dir));
    }

    /**
     * Apply the per-connection thread cap and any optional DuckDB resource controls
     * (memory limit, spill {@code temp_directory} = the scratch dir, spill size cap) to a freshly
     * opened worker connection.
     *
     * <p>The thread cap is resolved through {@link DuckDbUtil#effectiveWorkerThreads} so that the
     * default ({@code duckdb_threads = 0}) auto-divides the host's cores among the concurrent
     * batches ({@code processing.threads}) instead of letting every batch connection grab all cores
     * — the latter oversubscribes the CPU when more than one batch runs at a time.
     */
    static void configure(Connection conn, PipelineConfig cfg) throws SQLException {
        int effectiveThreads = DuckDbUtil.effectiveWorkerThreads(
                cfg.processing().duckdbThreads(),
                cfg.processing().threads(),
                Runtime.getRuntime().availableProcessors());
        DuckDbUtil.applyWorkerThreads(conn, effectiveThreads);
        // Per-config value wins; else the global -Dprocessing.duckdb.* fallback, so one operator knob
        // caps this path uniformly with the (config-less) flow-job and enrichment scratch connections.
        DuckDbUtil.applyDuckDbSettings(conn,
                DuckDbUtil.globalOr(cfg.duckdb().memoryLimit(), DuckDbUtil.PROP_MEMORY_LIMIT),
                scratchDir(cfg),
                DuckDbUtil.globalOr(cfg.duckdb().maxTempDirectorySize(), DuckDbUtil.PROP_MAX_TEMP_DIRECTORY_SIZE));
    }
}
