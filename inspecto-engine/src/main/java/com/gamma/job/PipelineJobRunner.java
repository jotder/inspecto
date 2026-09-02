package com.gamma.job;

import com.gamma.api.PublicApi;
import com.gamma.enrich.ReferenceReader;
import com.gamma.etl.ConsignmentEvent;
import com.gamma.etl.PartitionOutput;
import com.gamma.event.Event;
import com.gamma.event.EventLevel;
import com.gamma.event.EventLog;
import com.gamma.event.EventType;
import com.gamma.pipeline.ComponentRegistry;
import com.gamma.pipeline.PipelineEdge;
import com.gamma.pipeline.PipelineGraph;
import com.gamma.pipeline.PipelineNode;
import com.gamma.pipeline.PipelineRel;
import com.gamma.pipeline.PipelineStore;
import com.gamma.pipeline.PipelineStores;
import com.gamma.pipeline.ViewDefinition;
import com.gamma.pipeline.ViewStore;
import com.gamma.pipeline.exec.BranchCommitCoordinator;
import com.gamma.pipeline.exec.BranchCommitLog;
import com.gamma.pipeline.exec.ConservationCheck;
import com.gamma.pipeline.exec.DbProvenanceStore;
import com.gamma.pipeline.exec.PartitionSinkWriter;
import com.gamma.pipeline.exec.PipelineExecutor;
import com.gamma.pipeline.exec.PipelineWatermarkStore;
import com.gamma.pipeline.exec.ProvenanceRow;
import com.gamma.pipeline.exec.RowShaper;
import com.gamma.pipeline.exec.SourceStoreReader;
import com.gamma.etl.ConsignmentEventBus;
import com.gamma.query.ViewReaderSql;
import com.gamma.sql.SqlViews;
import com.gamma.util.DuckDbUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * <b>T32 Phase A — run an authored {@code *_flow.toon} flow for real, as a {@link JobType#PIPELINE} job.</b>
 *
 * <p>An authored flow is <em>job-style</em> (§3.8, T23): it reads a {@code source_store} (data already
 * at rest), runs its {@code transform.*} nodes, and writes its sink {@code store}(s) — it is not a
 * re-acquisition (ingest is pipeline-exclusive). So rather than compile it back to a {@code PipelineConfig}
 * (which only round-trips lifted graphs, not UI-authored ones), this runner drives the production
 * {@link PipelineExecutor} directly, hosted on the existing {@link com.gamma.job.JobService} scheduler — which
 * gives scheduling, audit, the deletion fence (T25) and {@code DbJobRunStore} reporting (T27) for free.
 *
 * <p>The run mirrors {@link com.gamma.enrich.EnrichmentEngine}'s read-view → transform → partitioned-write
 * → publish-event shape, on a throwaway DuckDB:
 * <ol>
 *   <li>seed each {@code source_store} as a view ({@link SourceStoreReader});</li>
 *   <li>execute the {@code transform → sink} subgraph ({@link PipelineExecutor#execute}) with a
 *       {@link PartitionSinkWriter} and a {@link BranchCommitCoordinator} (idempotent multi-branch commit,
 *       T11) — a flow job has no acquisition to finalise, so the source-finalisation step is a no-op;</li>
 *   <li>publish a chain {@link ConsignmentEvent} so downstream {@code on_pipeline} jobs fire.</li>
 * </ol>
 *
 * <h3>Config ({@code *_job.toon})</h3>
 * <pre>
 * job:
 *   name: nightly_rollup
 *   type: pipeline             # this runner
 *   flow: events_rollup        # authored flow id (PipelineStore.get); `pipeline:` is the canonical key
 *   pipeline_config: config/x_pipeline.toon   # OR (A5-at-rest): the flat file whose Stage-2 chain this
 *                              #   run executes over its landed store (PipelineLift.stageTwo, lifted at
 *                              #   run time); mutually exclusive with pipeline:/flow:
 *   cron: "0 2 * * *"          # OR on_pipeline: events_etl  (event)  OR manual (trigger API)
 *   data_dir: database         # optional — overrides the injected data root; must not point inside
 *                              #   the space data root (sinks are top-level stores — see below)
 *   batch_id: ...              # optional — fixed batch id (idempotent re-run); default per-run timestamp
 * </pre>
 *
 * <p><b>Scope (T32 Phase C):</b> multiple {@code source_store}s (each seeded as its own view; a
 * {@code transform.merge} joins/unions them); persistent/materialized sinks plus {@code sink.view} logical
 * stores (registered as a {@link com.gamma.pipeline.ViewDefinition}); full-recompute by default, or opt-in
 * incremental re-run via the {@code incremental_column} job param (single-source — reads only rows past the
 * stored watermark and appends). See {@code docs/flow-live-execution-plan.md}.
 */
@PublicApi(since = "4.0.0")
public final class PipelineJobRunner implements Job {

    private static final Logger log = LoggerFactory.getLogger(PipelineJobRunner.class);
    private static final String SEED_VIEW_PREFIX = "pipeline_src";

    private final JobConfig cfg;
    private final ConsignmentEventBus bus;
    private final PipelineStore pipelineStore;
    private final String dataDir;
    private final String auditDir;
    private final DbProvenanceStore provenance;   // T21 — nullable; default-off unless -Dprovenance.backend set
    private final Supplier<ComponentRegistry> registry;   // nullable — no registry means no `use:` resolution
    /** Loaded-pipeline context a {@code transform.join}'s by-name reference resolves against; nullable. */
    private final Supplier<List<com.gamma.etl.PipelineConfig>> pipelines;
    /** Whether an enabled {@code retire_superseded} maintenance job exists, or {@code null} if unknown —
     *  see the 8-arg constructor. */
    private final BooleanSupplier retireSupersededConfigured;

    /** As {@link #PipelineJobRunner(JobConfig, ConsignmentEventBus, PipelineStore, String, String, DbProvenanceStore)} with no provenance store. */
    public PipelineJobRunner(JobConfig cfg, ConsignmentEventBus bus, PipelineStore pipelineStore,
                         String dataDir, String auditDir) {
        this(cfg, bus, pipelineStore, dataDir, auditDir, null);
    }

    /**
     * @param cfg        the job config ({@code flow} param = authored flow id)
     * @param bus        the batch-event bus for chain events
     * @param pipelineStore  the authored-flow store ({@code <write-root>/flows}) to load the flow from
     * @param dataDir    the data root under which each store is a sub-directory (per-job {@code data_dir} overrides)
     * @param auditDir   the directory for the branch-commit log
     * @param provenance the data-plane provenance store (T21), or {@code null} to not record per-edge counts
     */
    public PipelineJobRunner(JobConfig cfg, ConsignmentEventBus bus, PipelineStore pipelineStore,
                         String dataDir, String auditDir, DbProvenanceStore provenance) {
        this(cfg, bus, pipelineStore, dataDir, auditDir, provenance, null);
    }

    /**
     * As above, plus the component registry this run resolves its {@code use:} bindings against.
     *
     * @param registry supplies the registry to resolve against, or {@code null} to resolve nothing. Supplied
     *                 rather than held so each run scans live (a component edited between two runs takes
     *                 effect on the second) — the same per-call scan the dry-run route does.
     */
    public PipelineJobRunner(JobConfig cfg, ConsignmentEventBus bus, PipelineStore pipelineStore,
                         String dataDir, String auditDir, DbProvenanceStore provenance,
                         Supplier<ComponentRegistry> registry) {
        this(cfg, bus, pipelineStore, dataDir, auditDir, provenance, registry, null);
    }

    /**
     * As above, plus the loaded-pipeline context a {@code transform.join} node's <b>by-name</b> reference
     * ({@code reference/<pipeline>}) resolves against — A5-at-rest slice 5. Supplied rather than held for
     * the same reason as {@code registry}: each run reads live. {@code null} leaves by-name joins refusing
     * with the wiring named ({@link ReferenceReader#sqlFor}); a {@code path:} reference needs no context.
     */
    public PipelineJobRunner(JobConfig cfg, ConsignmentEventBus bus, PipelineStore pipelineStore,
                         String dataDir, String auditDir, DbProvenanceStore provenance,
                         Supplier<ComponentRegistry> registry,
                         Supplier<List<com.gamma.etl.PipelineConfig>> pipelines) {
        this(cfg, bus, pipelineStore, dataDir, auditDir, provenance, registry, pipelines, null);
    }

    /**
     * As above, plus whether an enabled {@code retire_superseded} maintenance job is configured
     * ({@code null} if the caller cannot answer — e.g. a runner built outside a {@link JobService} host —
     * which is read as "don't know" and suppresses the warning below rather than guessing).
     *
     * <p>Queried, not held: the scheduler's job set can change between runs (a job added, disabled, or
     * removed), and this asks fresh each full recompute rather than caching an answer from construction
     * time.
     *
     * @param retireSupersededConfigured answers "is at least one enabled {@code retire_superseded} job
     *                                    configured", checked only after a full recompute actually
     *                                    supersedes something (operations-reference.md, addressing §6:
     *                                    without one, every full recompute leaves a permanent extra copy
     *                                    on disk, and today nothing says so)
     */
    public PipelineJobRunner(JobConfig cfg, ConsignmentEventBus bus, PipelineStore pipelineStore,
                         String dataDir, String auditDir, DbProvenanceStore provenance,
                         Supplier<ComponentRegistry> registry,
                         Supplier<List<com.gamma.etl.PipelineConfig>> pipelines,
                         BooleanSupplier retireSupersededConfigured) {
        this.cfg = cfg;
        this.bus = bus;
        this.pipelineStore = pipelineStore;
        this.dataDir = dataDir;
        this.auditDir = auditDir;
        this.provenance = provenance;
        this.registry = registry;
        this.pipelines = pipelines;
        this.retireSupersededConfigured = retireSupersededConfigured;
    }

    @Override public String name() { return cfg.name(); }
    @Override public String type() { return "pipeline"; }

    @Override
    public JobResult run() throws Exception {
        return execute(null);
    }

    /**
     * The entry point {@link JobService} invokes — identical work, plus one Run Artifact per store this run
     * produced (§5-B). Until 2026-08-10 this runner recorded <b>none</b>, which is why
     * {@code $upstream(<pipelineJob>).artifact(<store>)} resolved to nothing at all: the artifact is the
     * handle, and its {@code ref} — the sink's declared {@code store} — is the one identifier that is also the
     * Consignment registry's {@code table_name}, so the event-time attrs can key off it.
     */
    @Override
    public JobResult run(JobContext ctx) throws Exception {
        return execute(ctx);
    }

    /** @param artifacts where to record this run's produced stores, or {@code null} to record none. */
    /**
     * X2 cross-lane provenance: tell the framework which Consignments a source view reads. {@code read} is
     * the selector's kept list — {@code null} when the read was unfiltered (no output registry), in which
     * case the file set is unknown and NOTHING is reported: an unknown trail must not be recorded as an
     * empty one. Shared with {@link SqlTemplateJob}, the other at-rest store reader.
     */
    static void reportSources(JobContext ctx, String store, List<String> read) {
        if (ctx == null || read == null || read.isEmpty()) return;
        com.gamma.consignment.DbConsignmentOutputStore registry = com.gamma.consignment.ConsignmentOutputStores.shared();
        if (registry == null) return;
        ctx.readConsignments(registry.sourcesForPaths(store, read));
    }

    private JobResult execute(JobContext ctx) throws Exception {
        ArtifactRecorder artifacts = ctx == null ? null : ctx.artifacts();
        // A5-at-rest slice 2: `pipeline_config:` names the flat *_pipeline.toon file (a path, like the
        // enrich job's `config:`); the Stage-2 remainder is lifted at RUN time (PipelineLift.stageTwo),
        // so the flat file stays the single truth — no derived graph is persisted to the flow store.
        // Mutually exclusive with `pipeline:`/`flow:` — carrying both leaves the graph source undefined.
        String flatPath = cfg.opt("pipeline_config", null);
        // Tier 3 dual-read (vocabulary plan §4): `pipeline:` is canonical; `flow:` is the pre-rename key,
        // read only, kept for existing *_job.toon files that were never resaved.
        String pipelineIdOpt = cfg.opt("pipeline", null);
        final String pipelineId;
        PipelineGraph g;
        if (flatPath != null) {
            if (pipelineIdOpt != null || cfg.opt("flow", null) != null)
                throw new IllegalArgumentException("pipeline job '" + cfg.name()
                        + "' carries both pipeline_config: and pipeline:/flow: — pick one graph source");   // vocab-allow: names the two config KEYS, `pipeline:` and the legacy `flow:`
            g = com.gamma.pipeline.PipelineLift.stageTwo(com.gamma.etl.PipelineConfig.load(flatPath));
            pipelineId = g.name();
        } else {
            pipelineId = pipelineIdOpt != null ? pipelineIdOpt : cfg.require("flow");
            g = pipelineStore.get(pipelineId).orElseThrow(() -> new IllegalArgumentException(
                    "pipeline job '" + cfg.name() + "' references unknown pipeline '" + pipelineId + "'"));
        }
        // Resolve `use:` bindings before anything reads the graph. PipelineStore.get returns the graph as
        // authored — local config only — so a node that references a component (a mapping's rules, a
        // grammar) would otherwise run with those keys simply absent, silently producing nothing rather
        // than failing. The dry-run route has always resolved, which is why a binding could preview
        // correctly and then no-op in the real run. An unresolvable reference is left as-is here (the save
        // route already refuses one with UNKNOWN_USE_REF).
        if (registry != null) g = registry.get().effectiveGraph(g);
        String dir = cfg.opt("data_dir", dataDir);
        requireTopLevelSinks(g, dir);
        String batchId = cfg.opt("batch_id", cfg.name().toLowerCase().replace(' ', '_')
                + "-" + System.currentTimeMillis());
        List<Seed> seeds = seedsOf(g);
        String incCol = cfg.opt("incremental_column", "").trim();   // T32 Phase C — opt-in incremental re-run
        boolean incremental = !incCol.isBlank();
        // T32 follow-up — incremental is per-source: each source_store carries its own watermark (keyed by
        // store) and is filtered + advanced independently below, so multi-source incremental works. It requires
        // the incremental_column to exist in every source_store (e.g. a union of like-shaped stores, or sources
        // that all carry the same event-time column).
        PipelineWatermarkStore watermarks = incremental ? new PipelineWatermarkStore(Path.of(auditDir)) : null;

        long t0 = System.nanoTime();
        File db = DuckDbUtil.tempDbFile("flowjob_");
        try (Connection conn = DuckDbUtil.openConnection(db)) {
            // Flow-jobs have no per-pipeline processing.duckdb config; honour the global -D caps so this
            // scratch connection isn't uncapped (defaults ≈ 80% RAM) while the batch path is capped.
            DuckDbUtil.applyGlobalDuckDbSettings(conn);
            Map<String, String> seedViews = new LinkedHashMap<>();
            for (Seed seed : seeds) {                              // one view per source_store (multi-source, Phase C)
                String view = SEED_VIEW_PREFIX + "_" + safe(seed.node());
                String predicate = incremental
                        ? watermarks.get(pipelineId, seed.store())
                            .map(wm -> "\"" + incCol + "\" > '" + wm.replace("'", "''") + "'").orElse(null)
                        : null;
                List<String> read = SourceStoreReader.registerView(conn, view, dir, seed.store(), seed.format(), predicate);
                reportSources(ctx, seed.store(), read);
                seedViews.put(seed.node(), view);
            }

            // Every run gets a batch-unique base name (addressing step 6). Incremental runs always did — each
            // increment is its own file — and a full recompute now does too, so it writes a new revision beside
            // the old one instead of rewriting bytes a catalog row points at.
            //
            // This keeps the property the old stable name existed for. That name was chosen so a same-batch_id
            // replay stayed idempotent; deriving the name FROM the batch id keeps exactly that (a replay writes
            // its own path again) while giving each genuine recompute a path of its own. It also avoids the
            // `_g<N>_` spelling the plan proposed, which DuckDbRecordSink already uses for a memory-bounded
            // flush chunk — a different concept that would have become indistinguishable on disk.
            String sinkBase = cfg.name().toLowerCase().replace(' ', '_') + "_" + safe(batchId);
            // The pipeline id is the producer stamped on this run's registry rows (§3.6): the id rather than the
            // display name, because a watermark folds over producer identity and a renamed pipeline must stay
            // the same producer.
            PartitionSinkWriter writer = new PartitionSinkWriter(conn, dir, sinkBase, batchId, pipelineId);
            BranchCommitCoordinator coordinator = new BranchCommitCoordinator(new BranchCommitLog(
                    Path.of(auditDir).resolve(safe(pipelineId) + "_branch_commit_" + safe(batchId) + ".csv").toString()));

            // T20/T21 — collect per-(node, relationship) record counts during the walk (counts must be taken
            // while the scratch relations are live) and persist them as this run's data-plane provenance.
            String runTs = Instant.now().toString();
            List<ProvenanceRow> provRows = new ArrayList<>();
            PipelineExecutor.ProvenanceCollector collector = provenance == null
                    ? PipelineExecutor.ProvenanceCollector.NONE
                    : (nodeId, rel, rowCount) -> provRows.add(new ProvenanceRow(pipelineId, batchId, nodeId, rel, rowCount, runTs));

            // D-9: the at-rest run is the one path with real run context — pipeline id (the ledger's
            // stable key, rename-proof like the watermark's producer), this run's batch id, and the
            // space's dedup ledger — so a windowed transform.dedup can claim keys durably here.
            PipelineExecutor.execute(conn, g, seedViews, batchId, coordinator, writer, () -> {}, collector,
                    references(), null, RowShaper.ExecutionContext.forRun(pipelineId, batchId));

            if (provenance != null) {
                provenance.record(provRows);
                reportConservation(g, pipelineId, batchId, provRows);   // T22 — §11.4 invariant → event/alert
            }

            if (incremental) advanceWatermarks(conn, watermarks, pipelineId, seeds, seedViews, incCol);
            else supersedeEarlierRevisions(g, batchId);

            long ms = (System.nanoTime() - t0) / 1_000_000L;
            List<String> parts = writer.outputs().stream().map(PartitionOutput::partition).distinct().toList();
            List<String> srcStores = seeds.stream().map(Seed::store).toList();
            registerViews(g, pipelineId, srcStores, dir);              // T32 Phase C — sink.view → durable definition
            recordStoreArtifacts(artifacts, g, writer.rowsByStore());
            bus.publish(new ConsignmentEvent(cfg.name(), batchId, "SUCCESS", parts, writer.totalRows(), ms, 0));
            log.info("[PIPELINEJOB] {} ran pipeline '{}' (source_store(s) {}): {} file(s), {} row(s) → {}",
                    cfg.name(), pipelineId, srcStores, writer.outputs().size(), writer.totalRows(),
                    PipelineStores.produced(g));
            return JobResult.ok(writer.outputs().size() + " file(s), " + writer.totalRows()
                    + " row(s) → store(s) " + PipelineStores.produced(g), ms);
        } finally {
            DuckDbUtil.deleteTempDb(db);
        }
    }

    // ── helpers ──────────────────────────────────────────────────────────────────

    /** View-name prefix for a resolved Reference Dataset (distinct from the {@code source_store} seeds). */
    private static final String REF_VIEW_PREFIX = "pipeline_ref";

    /**
     * <b>A5-at-rest slice 5 — the production {@link RowShaper.ReferenceResolver}.</b> Resolves a
     * {@code transform.join} node's {@code reference} ({@code reference/<pipeline>} or a path) through
     * {@link ReferenceReader} — the same resolution the Stage-2 {@code EnrichmentEngine} uses, so a
     * versioned reference store's current/as-of view is derived identically on both routes — and registers
     * it as a view on this run's scratch connection, because {@link RowShaper} joins against a named
     * relation, not an expression.
     *
     * <p>The view is created once per distinct reference and reused: two joins against the same dimension
     * read one view rather than two, and re-resolving mid-run could otherwise see a reference store that
     * changed between them.
     */
    private RowShaper.ReferenceResolver references() {
        return (conn, reference) -> {
            String view = REF_VIEW_PREFIX + "_" + safe(reference);
            String sql = ReferenceReader.sqlFor(ReferenceReader.parse(reference),
                    pipelines == null ? null : pipelines.get());
            try (Statement st = conn.createStatement()) {
                st.execute("CREATE OR REPLACE VIEW \"" + view + "\" AS SELECT * FROM " + sql);
            }
            log.info("[PIPELINEJOB] {} resolved reference '{}' for join", cfg.name(), reference);
            return view;
        };
    }

    /**
     * Record one dataset Run Artifact per store this run wrote bytes to, named and {@code ref}'d by the store
     * (§5-B) so {@code $upstream(<job>).artifact(<store>).event_time_min|event_time_max} resolves against the
     * Consignment registry, which keys on that same string.
     *
     * <p>Only stores that actually received rows are recorded — {@code sink.view} nodes rest no bytes, so the
     * registry holds nothing to range over and an artifact for one would name a stream that does not exist.
     * The {@link ResultSetMeta} is {@code null}: this runner writes files through {@code PartitionWriter} and
     * never holds a result set to describe, and inventing a shape here would be a claim about columns nothing
     * checked. The watermark is the record time, as every other recorder passes — it is when the artifact was
     * written, not a statement about event time, which is precisely what the registry answers instead.
     */
    private static void recordStoreArtifacts(ArtifactRecorder artifacts, PipelineGraph g,
                                             Map<String, Long> rowsByStore) {
        if (artifacts == null) return;
        for (String store : PipelineStores.produced(g)) {
            Long rows = rowsByStore.get(store);
            if (rows == null) continue;
            artifacts.dataset(store, store, null, rows, Instant.now());
        }
    }

    /**
     * After a successful <b>full recompute</b>, mark every earlier revision of the stores it produced
     * {@code SUPERSEDED} (addressing step 6), so {@code ConsignmentSelector} stops naming their files. Nothing
     * is deleted here — the bytes stay until a retirement pass removes them, so a read already in flight
     * finishes on the revision it started with.
     *
     * <p><b>Full recomputes only, and that asymmetry is the whole point.</b> An incremental run <em>appends</em>
     * a slice; superseding the earlier revisions there would discard every increment before it and leave the
     * table holding only the newest slice. The caller's {@code if (incremental)} branch is what keeps these two
     * apart, so the two must never be merged into one unconditional call.
     *
     * <p>A no-op when the registry is absent — the pre-step-6 behaviour, where a recompute overwrote in place,
     * is what a deployment without a catalog still gets, and it is self-consistent: nothing recorded the old
     * revision, so nothing needs to un-record it.
     *
     * <p>⚠ <b>The bytes this leaves behind are only ever reclaimed by a {@code retire_superseded}
     * maintenance job, and none is configured by default.</b> Without one, every full recompute is a
     * permanent extra copy — so once this method actually supersedes something, it asks
     * {@link #retireSupersededConfigured} and warns if the answer is "no" or "unknown says no was checked".
     */
    private void supersedeEarlierRevisions(PipelineGraph g, String batchId) {
        var registry = com.gamma.consignment.ConsignmentOutputStores.shared();
        if (registry == null) return;
        List<String> supersededStores = new ArrayList<>();
        for (String store : PipelineStores.produced(g)) {
            int superseded = registry.supersedeOtherRevisions(store, batchId);
            if (superseded > 0) {
                supersededStores.add(store);
                log.info("[PIPELINEJOB] full recompute of '{}' superseded {} file(s) from earlier revision(s) — "
                        + "readers stop naming them now; the bytes go with the next retirement pass",
                        store, superseded);
            }
        }
        if (!supersededStores.isEmpty() && retireSupersededConfigured != null
                && !retireSupersededConfigured.getAsBoolean())
            log.warn("[PIPELINEJOB] no enabled 'retire_superseded' maintenance job is configured — the "
                    + "file(s) just superseded for {} will never be reclaimed; every future full recompute "
                    + "of these stores adds another permanent copy on disk", supersededStores);
    }

    /**
     * The write-side store-layout contract (BACKLOG §1, decided 2026-07-18): a persistent store is a
     * <b>top-level directory under the space data root</b>. A sink resolving deeper — a {@code data_dir}
     * pointed inside another store's tree (the UAT double-count shape), or a slashed {@code store}
     * name — would be swept by recursive dataset reads over the enclosing store, so the run fails
     * closed before any bytes are written. A root fully outside the space data root (an external
     * {@code data_dir} export) stays allowed. Job configs bypass {@code ConfigSafetyValidator}, so
     * this is enforced here at run time.
     */
    private void requireTopLevelSinks(PipelineGraph g, String dir) {
        Path root = Path.of(dataDir).toAbsolutePath().normalize();
        for (PipelineStores.Produced p : PipelineStores.producedStores(g)) {
            if (!p.restsOnDisk()) continue;
            Path sink = Path.of(dir, p.store()).toAbsolutePath().normalize();
            if (!sink.startsWith(root) || root.equals(sink.getParent())) continue;
            throw new IllegalArgumentException("pipeline job '" + cfg.name() + "' sink store '" + p.store()
                    + "' resolves to '" + sink + "', nested inside the space data root — persistent"
                    + " stores are top-level directories under the data root (a nested store"
                    + " double-counts in recursive dataset reads); drop the data_dir override or point"
                    + " it fully outside the data root");
        }
    }

    /**
     * T22 — evaluate the §11.4 conservation invariant over this run's per-edge counts and emit a
     * {@link EventType#PIPELINE_CONSERVATION_IMBALANCE} event for each non-amplifying node where records were
     * lost or unexpectedly amplified. The {@link com.gamma.ops.EventObjectBridge} promotes it to a managed ALERT.
     * Never throws — observability must not break the run that just succeeded.
     *
     * <p>Package-visible (not private) so a test can drive the emit bridge with crafted imbalanced counts:
     * a healthy real run conserves by construction (every conserving node records both its kept and its
     * diverted relations), so a positive {@code PIPELINE_CONSERVATION_IMBALANCE} is only reachable from an
     * injected count mismatch, not a clean run.
     */
    static void reportConservation(PipelineGraph g, String pipelineId, String batchId, List<ProvenanceRow> rows) {
        try {
            Map<String, Long> counts = new LinkedHashMap<>();
            for (ProvenanceRow r : rows) counts.put(r.nodeId() + "|" + r.rel(), r.rowCount());
            for (ConservationCheck.Imbalance im : ConservationCheck.imbalances(g, counts)) {
                EventLog.current().emit(Event.builder(EventType.PIPELINE_CONSERVATION_IMBALANCE)
                        .level("LOSS".equals(im.kind()) ? EventLevel.ERROR : EventLevel.WARN)
                        .source(PipelineJobRunner.class.getName())
                        .pipeline(pipelineId)
                        .correlationId(batchId)
                        .message("pipeline '" + pipelineId + "' node '" + im.node() + "': "
                                + im.recordsIn() + " in, " + im.recordsOut() + " out (" + im.kind() + ")")
                        .attr("node", im.node())
                        .attr("recordsIn", im.recordsIn())
                        .attr("recordsOut", im.recordsOut())
                        .attr("kind", im.kind())
                        .build());
                log.warn("[PIPELINEJOB] conservation imbalance in pipeline '{}' at node '{}': {} in, {} out ({})",
                        pipelineId, im.node(), im.recordsIn(), im.recordsOut(), im.kind());
            }
        } catch (RuntimeException e) {
            log.warn("[PIPELINEJOB] conservation check failed for pipeline '{}': {}", pipelineId, e.getMessage());
        }
    }

    /** A seed: one {@code source_store} node, its store, and its at-rest format. */
    private record Seed(String node, String store, String format) {}

    /** Every {@code source_store} node in the flow (≥1); a {@code transform.merge} downstream joins/unions them. */
    private static List<Seed> seedsOf(PipelineGraph g) {
        List<Seed> seeds = g.nodes().stream()
                .filter(n -> {
                    Object s = n.cfg(PipelineStores.CONFIG_SOURCE_STORE);
                    return s != null && !s.toString().isBlank();
                })
                .map(n -> {
                    Object fmt = n.cfg("format");
                    return new Seed(n.id(), n.cfg(PipelineStores.CONFIG_SOURCE_STORE).toString(),
                            fmt == null || fmt.toString().isBlank() ? "PARQUET" : fmt.toString().toUpperCase());
                })
                .toList();
        if (seeds.isEmpty())
            throw new IllegalArgumentException("pipeline '" + g.name() + "' declares no '"
                    + PipelineStores.CONFIG_SOURCE_STORE + "' — a pipeline job reads data at rest (§3.8)");
        return seeds;
    }

    /**
     * T32 Phase C — register a durable {@link ViewDefinition} for each logical {@code sink.view} the flow
     * produces (those that {@link PipelineStores.Produced#restsOnDisk() rest nothing}). Non-fatal: the data sinks
     * have already committed, so a registration failure is logged, not raised. Views land under
     * {@code <write-root>/views/} (sibling of the authored-flow store) for a KPI/report/alert API to bind to.
     */
    private void registerViews(PipelineGraph g, String pipelineId, List<String> srcStores, String dir) {
        // an A5-at-rest run (pipeline_config:) has no authored-flow store to anchor the views sibling on —
        // and its lifted graph only ever carries sink.persistent, so there is nothing to register anyway
        if (pipelineStore == null) return;
        ViewStore views = new ViewStore(pipelineStore.root().resolveSibling("views"));
        String now = Instant.now().toString();
        for (PipelineStores.Produced p : PipelineStores.producedStores(g)) {
            if (p.restsOnDisk()) continue;     // persistent/materialized already wrote bytes
            DerivedView derived = deriveViewSql(g, p.node(), dir).orElse(null);   // single SELECT when expressible
            String derivedSql = derived == null ? null : derived.sql();
            try {
                views.write(new ViewDefinition(p.store(), pipelineId, srcStores, derivedSql,
                        derived == null ? null : derived.readerRoot(),
                        derived == null ? null : derived.readerFormat(), now));
                log.info("[PIPELINEJOB] registered logical view '{}' (pipeline '{}', source_store(s) {}){}",
                        p.store(), pipelineId, srcStores, derivedSql == null ? "" : " with derived_sql");
            } catch (Exception e) {
                log.warn("[PIPELINEJOB] could not register view '{}': {}", p.store(), e.getMessage());
            }
        }
    }

    /** A derived view's SQL plus the ingredients its templated reader is re-rendered from at read time. */
    private record DerivedView(String sql, String readerRoot, String readerFormat) {}

    /**
     * T32 follow-up — best-effort {@code derived_sql} for a {@code sink.view}: if the view is fed by a
     * <b>single</b> source_store through a <b>linear</b> path of simple nodes
     * ({@code filter}/{@code map}/{@code select}/{@code derive}), fold that path into one SELECT over the source
     * read so a consumer can query the view directly. Returns empty for a branched / merged / multi-source /
     * complex path — the view then stays a re-run-the-flow definition ({@code derived_sql} null).
     *
     * <p><b>The source read is a template, not a glob</b> (addressing §7-A). This SQL is persisted and executed
     * later, so a baked-in glob would keep reading a revision the catalog has since marked superseded — the
     * defect this shape closes. {@link ViewReaderSql#READER_TOKEN} stands in for the read and is rendered
     * through the Consignment Selector at every execution, with the root/format recorded alongside.
     */
    private static Optional<DerivedView> deriveViewSql(PipelineGraph g, String viewNodeId, String dir) {
        Map<String, PipelineNode> byId = g.byId();
        List<PipelineNode> chain = new ArrayList<>();       // transforms between source and view, view-first
        String cur = viewNodeId;
        Set<String> seen = new HashSet<>();
        String sourceStore = null;
        String sourceFmt = "PARQUET";
        while (true) {
            List<PipelineEdge> inbound = g.edgesTo(cur).stream().filter(e -> PipelineRel.DATA.equals(e.rel())).toList();
            if (inbound.size() != 1) return Optional.empty();    // not a single linear data input (branch/merge/none)
            String prev = inbound.get(0).from();
            if (!seen.add(prev)) return Optional.empty();         // cycle guard
            PipelineNode pn = byId.get(prev);
            if (pn == null) return Optional.empty();
            Object ss = pn.cfg(PipelineStores.CONFIG_SOURCE_STORE);
            if (ss != null && !ss.toString().isBlank()) {         // reached the source — stop
                sourceStore = ss.toString();
                Object fmt = pn.cfg("format");
                if (fmt != null && !fmt.toString().isBlank()) sourceFmt = fmt.toString().toUpperCase();
                break;
            }
            chain.add(pn);                                        // an intermediate transform to fold
            cur = prev;
        }
        String readerRoot = SqlViews.storeReadRoot(dir.replace("\\", "/") + "/" + sourceStore);
        String sql = "SELECT * FROM " + ViewReaderSql.READER_TOKEN;
        for (int i = chain.size() - 1; i >= 0; i--) {            // fold in source→view order
            Optional<String> step = RowShaper.toSelect(chain.get(i), sql);
            if (step.isEmpty()) return Optional.empty();          // a non-simple node on the path
            sql = step.get();
        }
        return Optional.of(new DerivedView(sql, readerRoot, sourceFmt));
    }

    /**
     * T32 Phase C — advance each source_store's high-watermark to the {@code max(incremental_column)} over the
     * rows just processed (the filtered seed view). {@code null} max = no new rows ⇒ keep the prior watermark.
     * Called only after the branch commit (crash-before-advance re-reads the increment, which the sink write
     * makes idempotent).
     */
    private static void advanceWatermarks(Connection conn, PipelineWatermarkStore store, String pipelineId,
                                          List<Seed> seeds, Map<String, String> seedViews, String incCol)
            throws Exception {
        for (Seed seed : seeds) {
            String newMax = queryMaxAsText(conn, seedViews.get(seed.node()), incCol);
            if (newMax != null) store.put(pipelineId, seed.store(), newMax);
        }
    }

    /**
     * {@code max(col)::VARCHAR} over {@code view}; {@code null} when the view is empty (no new rows this run).
     *
     * <p><b>Parquet string-statistics guard (task #11):</b> DuckDB answers {@code max()} on a Parquet
     * <em>VARCHAR</em> column from the column's min/max statistics, which the Parquet writer <em>truncates</em>
     * (e.g. {@code '2020-01-02'} → {@code '2020-01-'}). A truncated watermark is a prefix (smaller), so the next
     * run's {@code col > 'wm'} predicate would re-admit already-seen rows. We defeat the stat pushdown for a
     * string column with a computed expression ({@code col || ''}) so the max is the true scanned value; numeric
     * and temporal columns have exact stats, so they keep native {@code max()} (correct ordering — a lexical max
     * over an integer column would be wrong).
     */
    private static String queryMaxAsText(Connection conn, String view, String col) throws Exception {
        String q = "\"" + col + "\"";
        String expr = isVarcharColumn(conn, view, col) ? "max(" + q + " || '')" : "max(" + q + ")";
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT " + expr + "::VARCHAR FROM \"" + view + "\"")) {
            return rs.next() ? rs.getString(1) : null;
        }
    }

    /** Whether {@code col} reads back as a DuckDB {@code VARCHAR} in {@code view} (empty view ⇒ false). */
    private static boolean isVarcharColumn(Connection conn, String view, String col) throws Exception {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT any_value(typeof(\"" + col + "\")) FROM \"" + view + "\"")) {
            return rs.next() && "VARCHAR".equalsIgnoreCase(rs.getString(1));
        }
    }

    /** Sanitise a string for use as a filename segment (the branch-commit log lives in the audit dir). */
    private static String safe(String s) {
        return s.replaceAll("[^A-Za-z0-9._-]", "_");
    }
}
