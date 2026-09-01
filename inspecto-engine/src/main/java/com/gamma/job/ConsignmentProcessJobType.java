package com.gamma.job;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamma.api.PublicApi;
import com.gamma.consignment.ConsignmentOutput;
import com.gamma.consignment.ConsignmentOutputStores;
import com.gamma.consignment.ConsignmentProcessor;
import com.gamma.consignment.DerivedTable;
import com.gamma.consignment.DerivedTableEmitter;
import com.gamma.consignment.DerivedTableWriter;
import com.gamma.consignment.GuardedDerivedTableEmitter;
import com.gamma.consignment.ConsignmentReader;
import com.gamma.consignment.DbConsignmentOutputStore;
import com.gamma.consignment.GuardedSummaryEmitter;
import com.gamma.consignment.ProcessorContext;
import com.gamma.consignment.ProcessorResult;
import com.gamma.consignment.SandboxConsignmentReader;
import com.gamma.consignment.SummaryEmitter;
import com.gamma.consignment.SummaryRow;
import com.gamma.consignment.SummaryWriter;
import com.gamma.signal.SignalEmitter;
import com.gamma.util.RunLog;

import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.function.Function;

/**
 * <b>§14.2 — the framework half.</b> The built-in {@code consignment.process} Job Type that resolves which
 * Consignment a run is about, narrows everything to it, and calls a third-party {@link ConsignmentProcessor}.
 *
 * <p><b>No new registry and no new {@code ServiceLoader} contract for Jobs.</b> Registration reuses the existing
 * {@link JobTypeProvider} seam (a class-based provider, as {@code SqlTemplateJobType} already is), and
 * {@code JobTypeRegistry}'s duplicate-id guard means a Job Pack can never displace this built-in.
 *
 * <p><b>How the author avoids knowing about Signals.</b> The Consignment id is declared as a
 * {@link ParameterDecl} whose {@code deduce} expression is {@code $signal.batchId} — resolved by the existing
 * {@code ParameterResolver} against the firing Signal's payload, which {@code JobService.mirrorPipelineCommit}
 * populates for every {@code pipeline.commit}. So a processor triggered by a commit receives the right
 * Consignment without declaring anything about signals, and the same Job triggered manually works by binding
 * {@code consignment_id} in config. A required parameter that resolves to nothing fails the run before any
 * author code executes.
 */
@PublicApi(since = "4.0.0")
public final class ConsignmentProcessJobType implements JobTypeProvider {

    /** The Job Type id — the {@code type:} string in a {@code *_job.toon}. */
    public static final String TYPE_ID = "consignment.process";

    static final String P_CONSIGNMENT = "consignment_id";
    static final String P_PROCESSOR = "processor";
    static final String P_CHAIN_CONFIG = "chain_config";

    private static final ObjectMapper JSON = new ObjectMapper();

    private final Function<String, ConsignmentProcessor> lookup;
    private final String dataDir;

    /** Production: processors are discovered by {@link ServiceLoader}; summaries land under {@code dataDir}. */
    public ConsignmentProcessJobType(String dataDir) {
        this(ConsignmentProcessJobType::fromServiceLoader, dataDir);
    }

    /**
     * No data root ⇒ §7.3 summary persistence is off, and the §7.2 guardrail still runs. Kept so an embedder
     * that only wants the guarded-emit behaviour need not invent a directory.
     */
    public ConsignmentProcessJobType() {
        this(ConsignmentProcessJobType::fromServiceLoader, null);
    }

    /** Test/embedder seam: resolve a processor id without going through {@code META-INF/services}. */
    ConsignmentProcessJobType(Function<String, ConsignmentProcessor> lookup) {
        this(lookup, null);
    }

    ConsignmentProcessJobType(Function<String, ConsignmentProcessor> lookup, String dataDir) {
        this.lookup = lookup;
        this.dataDir = dataDir;
    }

    /** The §7.3 summary tree: its own root, so {@code compact} can give it its own {@code min_age_days}. */
    static String summariesRoot(String dataDir) {
        return java.nio.file.Path.of(dataDir, "_summaries").toString();
    }

    /**
     * The ordered chain a {@code processor} parameter names: one id, or several separated by commas.
     *
     * <p><b>Order is authored, never inferred.</b> Two steps may both read the base and neither
     * declares a dependency on the other, so the only honest ordering is the one written down.
     *
     * <p>⚠ A duplicate id is <b>kept</b>, not de-duplicated: running the same processor twice in a
     * chain is legal — it sees a different Consignment state each time — and silently dropping the
     * second would be a surprise.
     */
    static List<String> chainOf(String param) {
        if (param == null || param.isBlank()) return List.of();
        List<String> out = new ArrayList<>();
        for (String part : param.split(",")) {
            String id = part.trim();
            if (!id.isEmpty()) out.add(id);
        }
        return out;
    }

    /**
     * The ordered per-step config a {@code chain_config} parameter carries: a JSON array of
     * {@code {"config": {...}}} objects, positionally aligned with {@link #chainOf}'s chain — index 0
     * configures the first named step, and so on. Blank/absent yields {@code List.of()}, which callers
     * read as "every step gets no config" rather than an error; a non-blank value that is not valid JSON,
     * or whose {@code config} entry is not an object, throws.
     */
    static List<Map<String, String>> chainConfigsOf(String json) {
        if (json == null || json.isBlank()) return List.of();
        List<Map<String, Object>> raw;
        try {
            raw = JSON.readValue(json, new TypeReference<List<Map<String, Object>>>() {});
        } catch (Exception e) {
            throw new IllegalArgumentException(
                    "chain_config is not a JSON array of {\"config\": {...}} objects: " + e.getMessage(), e);
        }
        List<Map<String, String>> out = new ArrayList<>(raw.size());
        for (Map<String, Object> entry : raw) {
            Object cfg = entry.get("config");
            Map<String, String> m = new LinkedHashMap<>();
            if (cfg != null) {
                if (!(cfg instanceof Map<?, ?>))
                    throw new IllegalArgumentException(
                            "chain_config entry's \"config\" must be an object, got: " + cfg);
                // 🔴 A config value is carried as TEXT (ProcessorContext.config() is Map<String,String>),
                // so a nested list/object has no faithful representation here. String.valueOf on a parsed
                // JSON container yields a Java toString — {"columns":["a","b"]} would reach the processor
                // as the literal "[a, b]" — i.e. the run SUCCEEDS on quietly wrong input. Refusing is the
                // only honest option: silent corruption of a step's parameters is worse than a failed run
                // that names the key (CHAIN-CONFIG-1).
                ((Map<?, ?>) cfg).forEach((k, v) -> {
                    if (v instanceof Map<?, ?> || v instanceof Iterable<?> || (v != null && v.getClass().isArray()))
                        throw new IllegalArgumentException("chain_config: value for '" + k + "' must be text, a "
                                + "number or true/false — every config value is stored as text, so a nested list "
                                + "or object would reach the processor mangled (got: " + v + ")");
                    // ⚠ And null must be refused HERE, by name: Map.copyOf below rejects null values with a
                    // bare NullPointerException that names neither the key nor the reason.
                    if (v == null)
                        throw new IllegalArgumentException("chain_config: value for '" + k + "' is null — omit "
                                + "the key instead; a config value cannot be null");
                    m.put(String.valueOf(k), String.valueOf(v));
                });
            }
            out.add(Map.copyOf(m));
        }
        return out;
    }

    @Override
    public JobTypeDescriptor descriptor() {
        return new JobTypeDescriptor(TYPE_ID, "Consignment Processor",
                "Runs a ConsignmentProcessor over one committed Consignment, with a read-only view of the "
                        + "files it wrote and §7.2-guarded summary emission.",
                List.of(
                        new ParameterDecl(P_CONSIGNMENT, ParamType.STRING, true, "$signal.batchId", null,
                                "The Consignment to process. Deduced from the firing pipeline.commit Signal; "
                                        + "bind it explicitly for a manual run."),
                        ParameterDecl.required(P_PROCESSOR, ParamType.STRING,
                                "The id() of the ConsignmentProcessor to run, or an ordered "
                                        + "comma-separated chain of them (mask,rollup,report). Each step "
                                        + "sees the Consignment as the previous one left it, including "
                                        + "the tables it registered."),
                        ParameterDecl.of(P_CHAIN_CONFIG, ParamType.JSON)
                                .tier(ParameterDecl.Tier.ADVANCED)
                                .description("Per-step configuration for a " + P_PROCESSOR + " chain, "
                                        + "reachable as ProcessorContext.config(): a JSON array of "
                                        + "{\"config\": {...}} objects, one per chain step in the same "
                                        + "order (a two-step chain needs a two-element array). Absent or "
                                        + "empty gives every step no config; declared, its length must "
                                        + "match the chain's.")
                                .build()),
                List.of(), List.of());
    }

    @Override
    public Job create(JobConfig config) {
        return new ProcessJob(config.name(), lookup, dataDir);
    }

    /** The first registered processor whose {@link ConsignmentProcessor#id()} matches, or {@code null}. */
    private static ConsignmentProcessor fromServiceLoader(String id) {
        if (id == null || id.isBlank()) return null;
        for (ConsignmentProcessor p : ServiceLoader.load(ConsignmentProcessor.class))
            if (id.equals(p.id())) return p;
        return null;
    }

    // ── the run ──────────────────────────────────────────────────────────────────

    private static final class ProcessJob implements Job {

        private final String name;
        private final Function<String, ConsignmentProcessor> lookup;
        private final String dataDir;

        ProcessJob(String name, Function<String, ConsignmentProcessor> lookup, String dataDir) {
            this.name = name;
            this.lookup = lookup;
            this.dataDir = dataDir;
        }

        @Override public String name() { return name; }

        @Override public String type() { return TYPE_ID; }

        /** This type is parameter-driven, so the legacy no-arg entry point cannot do the work. */
        @Override
        public JobResult run() {
            return JobResult.failed(TYPE_ID + " requires a JobContext (its parameters carry the Consignment id)", 0L);
        }

        @Override
        public JobResult run(JobContext ctx) throws Exception {
            long t0 = System.nanoTime();
            String consignmentId = ctx.params().get(P_CONSIGNMENT);
            if (consignmentId == null || consignmentId.isBlank())
                return JobResult.failed("no " + P_CONSIGNMENT + ": nothing was bound and $signal.batchId did "
                        + "not resolve — a manual run must bind it in config", ms(t0));

            List<String> chain = ConsignmentProcessJobType.chainOf(ctx.params().get(P_PROCESSOR));
            if (chain.isEmpty())
                return JobResult.failed("no " + P_PROCESSOR + ": name the processor to run, or an ordered "
                        + "comma-separated chain of them", ms(t0));

            List<Map<String, String>> chainConfigs;
            try {
                chainConfigs = ConsignmentProcessJobType.chainConfigsOf(ctx.params().get(P_CHAIN_CONFIG));
            } catch (IllegalArgumentException e) {
                return JobResult.failed(e.getMessage(), ms(t0));
            }
            if (!chainConfigs.isEmpty() && chainConfigs.size() != chain.size())
                return JobResult.failed(P_CHAIN_CONFIG + " has " + chainConfigs.size() + " entr"
                        + (chainConfigs.size() == 1 ? "y" : "ies") + " but the chain names " + chain.size()
                        + " step(s) — one config entry per chain step, in order", ms(t0));

            // Resolve EVERY step before running ANY of them. A chain that fails half-way on a typo has
            // already written and registered the earlier steps' tables, and those are not rolled back
            // (the data path is append-only), so an unresolvable id must stop the run before it starts.
            List<ConsignmentProcessor> processors = new ArrayList<>(chain.size());
            for (int i = 0; i < chain.size(); i++) {
                ConsignmentProcessor p = lookup.apply(chain.get(i));
                if (p == null)
                    return JobResult.failed("no ConsignmentProcessor registered with id '" + chain.get(i)
                            + "' — declare it in META-INF/services/" + ConsignmentProcessor.class.getName()
                            + (chain.size() > 1 ? " (step " + (i + 1) + " of " + chain.size()
                                    + "; nothing has run)" : ""), ms(t0));
                processors.add(p);
            }

            // The registry is default-off; an absent store means no readable relations, NOT that the
            // Consignment wrote nothing. The manifest remains authoritative for existence (§11.3).
            DbConsignmentOutputStore store = ConsignmentOutputStores.shared();
            if (store == null)
                ctx.log().warn("consignment output registry is disabled — the processor gets no readable "
                        + "relations", "consignment_id", consignmentId);

            ProcessorResult last = null;
            for (int i = 0; i < processors.size(); i++) {
                String processorId = chain.get(i);

                // 🔴 Re-read the registry PER STEP. This is what makes a chain a chain: step N sees the
                // Consignment as step N-1 left it, including the tables that step registered. Reading it
                // once outside the loop would give every step the pre-chain view and quietly make the
                // ordering meaningless.
                List<ConsignmentOutput> outputs = (store == null) ? List.of() : store.outputs(consignmentId);

                GuardedSummaryEmitter summaries = new GuardedSummaryEmitter();
                // Author SQL may name a REGISTERED path directly (a cross-Consignment read); the
                // registry itself is the authority, and with no store nothing is readable.
                GuardedDerivedTableEmitter tables = new GuardedDerivedTableEmitter(
                        store == null ? GuardedDerivedTableEmitter.ReadablePaths.NONE : store::isReadable);
                // ...and a fresh reader per step, because its lazy views are built from that outputs list.
                Map<String, String> stepConfig = chainConfigs.isEmpty() ? Map.of() : chainConfigs.get(i);
                try (ConsignmentReader reader = SandboxConsignmentReader.over(outputs)) {
                    ProcessorResult result = processors.get(i).process(
                            new AdaptedContext(consignmentId, outputs, reader, summaries, tables, stepConfig, ctx));

                    // §7.2's free reconciliation — reported, never thrown: summarising a filtered subset is legal.
                    summaries.reconcile(outputs).ifPresent(diff ->
                            ctx.log().warn("summary count does not reconcile against detail rows",
                                    "consignment_id", consignmentId, "detail", diff));

                    if (result == null)
                        return JobResult.failed("processor '" + processorId + "' returned no result", ms(t0));

                    persistSummaries(ctx, consignmentId, summaries.emitted(), processorId);
                    // ⚠ Inside the reader's try-with-resources on purpose: the author's SQL names the
                    // Consignment's lazy views, which live on that sandbox and vanish when it closes.
                    persistDerivedTables(ctx, consignmentId, reader, tables.emitted(), processorId);
                    last = result;
                }
                if (chain.size() > 1)
                    ctx.log().info("chain step complete", "consignment_id", consignmentId,
                            "step", (i + 1) + " of " + chain.size(),
                            "processor", processorId, "status", last.status());
            }
            return chain.size() == 1
                    ? new JobResult(last.status(), last.message(), ms(t0))
                    : new JobResult(last.status(), chain.size() + " step chain complete ("
                            + String.join(" then ", chain) + "); last: " + last.message(), ms(t0));
        }


        /**
         * §7.3: write the validated summary rows, then register the files.
         *
         * <p><b>Ordering matches §11.3's rule</b> — the registry row is written only after the Parquet file is
         * revealed, so a crash between them loses an index entry and never a claim that data exists.
         *
         * <p><b>A summary-write failure fails the Run</b>, unlike the best-effort registry write: the processor's
         * numbers are its output, so losing them silently would make a green Run a lie. It is deliberately the
         * opposite trade-off from {@code record()}, where the data had already landed.
         *
         * @param processorId the {@code producer} for these registry rows — the processor that emitted them, not
         *                    this Job Type: two processors can summarise the same target, and
         *                    {@code producerHighWater} groups by producer. Event-time bounds come from what the
         *                    rows themselves {@linkplain SummaryRow#bounds() declare} — nothing here derives
         *                    them; see {@link SummaryWriter#write}.
         */
        /**
         * Materialise the derived tables a processor asked for, then register them — the same ordering rule
         * {@link #persistSummaries} follows, because a registry row pointing at a file that does not exist
         * yet is exactly the window a reader can fall into.
         *
         * <p>⚠ Registering them onto THIS Consignment is what makes the chain work: the next step's
         * {@code outputs()} sees them, and a reprocess supersedes them with the base (supersede is keyed on
         * the Consignment, so no lineage edge is needed).
         */
        private void persistDerivedTables(JobContext ctx, String consignmentId, ConsignmentReader reader,
                                          List<DerivedTable> tables, String processorId) throws Exception {
            if (tables.isEmpty()) return;
            if (dataDir == null) {
                ctx.log().warn("derived-table persistence is off (no data root) — " + tables.size()
                        + " validated request(s) were guarded but not materialised",
                        "consignment_id", consignmentId);
                return;
            }
            if (ctx.dryRun()) {
                ctx.log().info("dry run — " + tables.size() + " derived table(s) validated, nothing written",
                        "consignment_id", consignmentId);
                return;
            }
            List<ConsignmentOutput> written = DerivedTableWriter.write(
                    reader, derivedRoot(dataDir), consignmentId, tables, processorId);
            ConsignmentOutputStores.record(written);
            for (ConsignmentOutput o : written)
                ctx.log().info("derived table written", "consignment_id", consignmentId,
                        "table", o.tableName(), "partition", o.partitionKey(), "rows", String.valueOf(o.rows()));
        }

        /** The derived-table tree root — the sibling of the summary root. */
        private static String derivedRoot(String dataDir) {
            return java.nio.file.Paths.get(dataDir, "_derived").toString();
        }

        private void persistSummaries(JobContext ctx, String consignmentId, List<SummaryRow> rows,
                                      String processorId) throws Exception {
            if (rows.isEmpty()) return;
            if (dataDir == null) {
                ctx.log().warn("§7.3 summary persistence is off (no data root) — " + rows.size()
                        + " validated summary row(s) were guarded but not stored", "consignment_id", consignmentId);
                return;
            }
            if (ctx.dryRun()) {
                ctx.log().info("dry run — " + rows.size() + " summary row(s) validated, nothing written",
                        "consignment_id", consignmentId);
                return;
            }
            try (java.sql.Connection scratch = com.gamma.util.JdbcDrivers.connect("jdbc:duckdb:")) {
                List<ConsignmentOutput> written =
                        SummaryWriter.write(scratch, summariesRoot(dataDir), consignmentId, rows, processorId);
                ConsignmentOutputStores.record(written);
                // S3a: the summaries are visible once recorded — one dataset.write per distinct store
                // (additive, never throws).
                java.util.Map<String, Long> rowsByStore = new java.util.LinkedHashMap<>();
                for (ConsignmentOutput o : written)
                    rowsByStore.merge(o.tableName(), o.rows(), Long::sum);
                rowsByStore.forEach((store, n) ->
                        com.gamma.signal.DatasetWriteSignal.emit(store, n, processorId));
                ctx.log().info("wrote " + written.size() + " summary file(s) from " + rows.size() + " row(s)",
                        "consignment_id", consignmentId);
            }
        }

        private static long ms(long t0) {
            return (System.nanoTime() - t0) / 1_000_000L;
        }
    }

    /**
     * The {@link ProcessorContext} the adapter hands the author: Consignment-scoped, with the Job surface
     * delegated member-by-member rather than exposed wholesale.
     */
    private record AdaptedContext(String consignmentId, List<ConsignmentOutput> outputs,
                                  ConsignmentReader read, SummaryEmitter summaries,
                                  DerivedTableEmitter tables, Map<String, String> config,
                                  JobContext job) implements ProcessorContext {

        @Override public RunLog log() { return job.log(); }

        @Override public boolean dryRun() { return job.dryRun(); }

        @Override public Map<String, String> config() { return config; }

        /** Stamps {@code consignment_id} into every payload so an author never re-states it. */
        @Override
        public SignalEmitter signals() {
            return (type, severity, payload) -> {
                Map<String, Object> stamped = new LinkedHashMap<>();
                if (payload != null) stamped.putAll(payload);
                stamped.putIfAbsent("consignment_id", consignmentId);
                job.signals().emit(type, severity, stamped);
            };
        }
    }
}
