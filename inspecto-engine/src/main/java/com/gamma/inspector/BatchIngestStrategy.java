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
import com.gamma.pipeline.NodeCategory;
import com.gamma.pipeline.PipelineNodeTypes;
import com.gamma.util.DuckDbUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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

    Logger log = LoggerFactory.getLogger(BatchIngestStrategy.class);

    /**
     * Ingest, transform, and write {@code batch}. Never throws: ingest failures are
     * captured into the returned outcome as {@code status = "FAILED"} so the batch
     * still flows through commit-skip + audit exactly as before.
     */
    IngestOutcome ingest(Batch batch, PipelineConfig cfg);

    // ── shared helpers ──────────────────────────────────────────────────────────

    /** Best-effort {@code DROP TABLE IF EXISTS} — logs a warning on failure, never throws. */
    static void dropTable(Connection conn, String table) {
        try (Statement st = conn.createStatement()) {
            st.execute("DROP TABLE IF EXISTS \"" + table + "\"");
        } catch (Exception e) {
            log.warn("best-effort drop table {} failed: {}", table, msg(e));
        }
    }

    /** Best-effort {@code DROP VIEW IF EXISTS} — logs a warning on failure, never throws. */
    static void dropView(Connection conn, String view) {
        try (Statement st = conn.createStatement()) {
            st.execute("DROP VIEW IF EXISTS \"" + view + "\"");
        } catch (Exception e) {
            log.warn("best-effort drop view {} failed: {}", view, msg(e));
        }
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
                                 String batchId, Map<Integer, String> srcIdToFile,
                                 String writeScope) throws Exception {
        // ── the lane fork ─────────────────────────────────────────────────────
        // This is the one choke point every ingest lane already funnels through, holding the live
        // connection and the materialised table. Everything downstream of the returned Written
        // (commit / finalizeSource / writeAudit / events / provenance) is the SAME code either way —
        // that is the whole parity argument, so do not "improve" this into an earlier divert.
        //
        // An authored route: diverts when its lifted graph engages (arming plan S2). A NON-route
        // pipeline diverts when the two lanes are provably the same write (Phase 6 precondition —
        // see graphLaneCarries). Everything else stays flat.
        //
        // ⚠ `writeScope` is a CALLER's fact, not a config property: two of this method's four callers
        // write a batch in SEVERAL calls (one per chunk, one per segment) and those writes reuse the
        // same sink node ids, so without a discriminator the second and later calls would look
        // "already committed" in the batch's BranchCommitLog and their rows would vanish. Each such
        // caller passes its own scope; the whole-batch callers pass "" and record exactly the keys
        // they always did (which is what keeps the drain, reading bare sink ids, untouched).
        //
        // The Decision Rules run ONCE, above the fork: they have side effects (routed writes +
        // quarantine) that must not repeat, both lanes ran them as their first act anyway, and their
        // RESULT is part of the admission — a rule that actually routed rows keeps a non-route
        // pipeline flat, because the graph lane does not implement rule-routed outputs (it refuses
        // them). Whether rules exist is a property of the space registry, not of the config, so this
        // is the only place the answer is knowable.
        DecisionRuleApplier.Result applied = DecisionRuleApplier.apply(
                conn, table, cfg, dbDir, baseName, partCols, batchId, srcIdToFile);

        com.gamma.pipeline.PipelineGraph lifted = null;
        if (cfg.routeConfig() != null) {
            com.gamma.pipeline.PipelineGraph routed = com.gamma.pipeline.PipelineLift.lift(cfg);
            if (com.gamma.pipeline.exec.BatchGraphRunner.engages(routed)) lifted = routed;
        } else if (applied.outputs().isEmpty() && graphLaneCarries(cfg)) {
            lifted = com.gamma.pipeline.PipelineLift.lift(cfg);
        }
        return lifted != null
                ? graphWriteAndTrace(conn, table, partCols, cfg, dbDir, baseName, batchId, srcIdToFile,
                        lifted, applied, writeScope)
                : flatWriteAndTrace(conn, table, partCols, cfg, dbDir, baseName, batchId, srcIdToFile,
                        applied);
    }

    /**
     * The legacy write: {@code DecisionRuleApplier} → optional reference versioning → the partitioned
     * write, fanned out to every {@code sinks[]} destination. Split out of {@link #writeAndTrace} when
     * the lane fork gained a second admission (Phase 6 precondition, 2026-08-29) so both lanes are
     * callable side by side — which is what {@code FlatVsGraphLaneParityTest} needs to diff them.
     */
    static Written flatWriteAndTrace(Connection conn, String table, List<String> partCols,
                                     PipelineConfig cfg, String dbDir, String baseName,
                                     String batchId, Map<Integer, String> srcIdToFile,
                                     DecisionRuleApplier.Result applied) throws Exception {
        List<PipelineConfig.Sink> sinks = cfg.sinks();
        boolean fanOut = sinks.size() > 1;

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

    /**
     * The branch-aware write (arming plan S2, Option B): drive the {@code route -> sinks} subgraph of
     * {@code lifted} over the already-materialised {@code table} through {@link BatchGraphRunner},
     * committing each branch through the durable {@link com.gamma.pipeline.exec.BranchCommitLog} and
     * writing it to its paired {@code sinks[]} destination via {@link IngestSinkWriter}.
     *
     * <p><b>Finalisation stays with {@code BatchProcessor.commit}</b> — the runner's
     * once-after-all-branches hook is a no-op here, deliberately: this method returns the flat
     * {@code Written} shape into {@link IngestOutcome}, and the caller's commit/audit tail then runs
     * the REAL {@code finalizeSource} (manifest, backup, markers LAST, ledger, watermark) plus
     * {@code writeAudit} (ledgers, BatchEvent, signals) — the same code, not a mirror. The runner's
     * own finalizer cannot be that body: it fires inside the strategy, before the batch outcome
     * exists, and the plan's Option-B constraint is a shared seam, never a second caller.
     *
     * <p>Refusals mirror the flat path's: decision-rule routing combined with route branches would
     * run rule side effects against rows a branch may then re-route — refused by name, exactly as
     * the flat path refuses rule-routing + fan-out.
     */
    private static Written graphWriteAndTrace(Connection conn, String table, List<String> partCols,
                                              PipelineConfig cfg, String dbDir, String baseName,
                                              String batchId, Map<Integer, String> srcIdToFile,
                                              com.gamma.pipeline.PipelineGraph lifted,
                                              DecisionRuleApplier.Result applied,
                                              String writeScope) throws Exception {
        if (!applied.outputs().isEmpty())
            throw new IllegalStateException("decision-rule routing writes to a single destination; combining "
                    + "it with a route: pipeline's branches is not supported");
        if (cfg.producesReference() && cfg.reference().load().versionedStore())
            throw new IllegalStateException("a versioned reference store cannot be written per route branch — "
                    + "one version history is ill-defined across branches (same rule as sinks:>1 at prepare())");

        // Seed the node whose data relation IS the materialised table — the node that FEEDS the write.
        // With a route: that is the route node's upstream (the map/transform node for this batch's
        // schema); without one it is the sink's own upstream. Seeding there means the executor never
        // re-runs parse/map: it walks the write tail only, which is what keeps this a write-lane
        // divert rather than a second execution engine (Phase 6 precondition, 2026-08-29).
        String seedNodeId = seedFeedingTheWrite(lifted);

        IngestSinkWriter writer = new IngestSinkWriter(
                conn, cfg, partCols, dbDir, baseName, batchId, srcIdToFile);
        java.nio.file.Path commitLog =
                com.gamma.pipeline.exec.BranchCommitLog.pathFor(cfg.dirs().temp(), batchId);
        java.nio.file.Files.createDirectories(commitLog.getParent());
        com.gamma.pipeline.exec.BatchGraphRunner.run(
                new com.gamma.pipeline.exec.BatchGraphRunner.Input(
                        conn, lifted, seedNodeId, table, batchId, dbDir, baseName, commitLog, writeScope),
                writer,
                () -> { /* finalisation is BatchProcessor.commit's, once the outcome returns */ },
                // Park hook (S4b): a disabled route-branch sink's rows are materialised to a durable
                // Parquet under the park home (a sibling of backup, never under a database dir a
                // store glob could sweep) and recorded for BatchProcessor's parked-finalisation.
                (node, inputTable) -> {
                    java.nio.file.Path parkDir = java.nio.file.Paths.get(cfg.dirs().backup(), "parked");
                    java.nio.file.Files.createDirectories(parkDir);
                    java.nio.file.Path parkTable = parkDir.resolve(batchId + "__" + node.id() + ".parquet");
                    try (java.sql.Statement st = conn.createStatement()) {
                        st.execute("COPY " + inputTable + " TO '"
                                + parkTable.toString().replace('\\', '/').replace("'", "''")
                                + "' (FORMAT parquet)");
                    }
                    ParkedBranches.record(batchId, node.id(), parkTable);
                });
        return new Written(writer.outputs(), writer.lineage(), writer.bounds());
    }

    /**
     * <b>Phase 6 precondition, narrow slice (2026-08-29):</b> whether a NON-route pipeline's write may
     * run through the graph lane. Deleting the legacy lane eventually needs the graph lane to carry
     * every pipeline; this admits the shape where the two lanes are provably the same write, and leaves
     * every other shape flat.
     *
     * <p>Admitted only when ALL hold:
     * <ul>
     *   <li><b>no versioned reference store</b> — {@code stampReferenceVersions} is flat-lane-only, and
     *       the graph lane refuses it by name rather than silently skipping the stamp;</li>
     *   <li><b>every sink is fed straight off the map node</b> — the flat lane materialised the table
     *       through map (plus the {@code csv_settings.where} filter, which the lift places UPSTREAM of
     *       map and the seed therefore skips). Any node BETWEEN map and sink would be executed by the
     *       walk — new behaviour, not parity. Those kinds ({@code dedup}/{@code join}/{@code summarize})
     *       are already refused at {@code prepare()} for this lane, so this is a structural belt to that
     *       braces, and it fails to the flat lane rather than throwing.</li>
     * </ul>
     *
     * <p><b>Multi-destination fan-out is carried (slice B).</b> The lift already emits one
     * {@code sink.persistent} node per {@code sinks[]} destination, each fed by its own {@code data}
     * edge off map, and {@link com.gamma.pipeline.exec.PipelineExecutor} already writes every data-fed
     * sink independently — so the fan-out needs no new machinery here, and it GAINS per-destination
     * crash resumption (one {@code BRANCH} row each) that the flat lane's single loop does not have.
     * ⚠ {@code dataFedSinkCount} still counts those N sinks as ONE branch; that is an ENGAGEMENT
     * question for the route lane ("is there a second branch to divert for?") and must not be confused
     * with this admission, which is about whether the write is reproducible in the graph lane.
     *
     * <p>Decision-rule outputs are NOT part of the admission: {@code DecisionRuleApplier} runs inside
     * {@code graphWriteAndTrace} exactly as it does on the flat path, and refuses the combination there.
     */
    static boolean graphLaneCarries(PipelineConfig cfg) {
        if (cfg.producesReference() && cfg.reference().load().versionedStore()) return false;
        com.gamma.pipeline.PipelineGraph lifted = com.gamma.pipeline.PipelineLift.lift(cfg);
        List<com.gamma.pipeline.PipelineNode> sinks = lifted.nodes().stream()
                .filter(n -> PipelineNodeTypes.isCategory(n.type(), NodeCategory.SINK)).toList();
        if (sinks.size() != cfg.sinks().size() || sinks.isEmpty()) return false;
        String seed = seedFeedingTheWrite(lifted);
        if (!"transform.map".equals(lifted.byId().get(seed).type())) return false;
        // EVERY sink must hang directly off the seed — one straggler behind another node would be
        // executed by the walk, which is new behaviour rather than the same write.
        return sinks.stream().allMatch(sink -> lifted.edgesTo(sink.id()).stream()
                .anyMatch(e -> com.gamma.pipeline.PipelineRel.DATA.equals(e.rel()) && seed.equals(e.from())));
    }

    /**
     * The node whose {@code data} relation is the already-materialised batch table: the one that feeds
     * the write tail. For a {@code route:} pipeline it is the route node's upstream; for a non-route
     * pipeline it is the single data-fed sink's upstream. Both are "the last node the FLAT lane already
     * executed", which is the invariant that makes seeding there safe — the walk then performs only the
     * write, never a second parse/map.
     */
    static String seedFeedingTheWrite(com.gamma.pipeline.PipelineGraph lifted) {
        String downstream = lifted.nodes().stream()
                .filter(n -> "transform.route".equals(n.type()))
                .map(com.gamma.pipeline.PipelineNode::id).findFirst()
                .orElseGet(() -> lifted.nodes().stream()
                        .filter(n -> PipelineNodeTypes.isCategory(n.type(), NodeCategory.SINK))
                        .map(com.gamma.pipeline.PipelineNode::id)
                        .filter(id -> lifted.edgesTo(id).stream()
                                .anyMatch(e -> com.gamma.pipeline.PipelineRel.DATA.equals(e.rel())))
                        .findFirst()
                        .orElseThrow(() -> new IllegalStateException(
                                "engaged graph has neither a transform.route node nor a data-fed sink — "
                                        + "engagement and lift disagree")));
        return lifted.edgesTo(downstream).stream()
                .filter(e -> com.gamma.pipeline.PipelineRel.DATA.equals(e.rel()))
                .map(com.gamma.pipeline.PipelineEdge::from).findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "node '" + downstream + "' has no inbound data edge to seed"));
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
        // Per-config value wins; else the server configuration's installed memory_limit, else the
        // -Dprocessing.duckdb.* bootstrap default, so one operator knob caps this path uniformly with
        // the (config-less) flow-job and enrichment scratch connections.
        DuckDbUtil.applyDuckDbSettings(conn,
                DuckDbUtil.memoryLimit(cfg.duckdb().memoryLimit()),
                scratchDir(cfg),
                DuckDbUtil.globalOr(cfg.duckdb().maxTempDirectorySize(), DuckDbUtil.PROP_MAX_TEMP_DIRECTORY_SIZE));
    }
}
