package com.gamma.etl;

import com.gamma.util.DuckDbUtil;
import com.gamma.util.LakehouseCatalog;
import com.gamma.util.Topology;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.DriverManager;
import java.nio.file.Path;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Inserts newly written Parquet files into a DuckLake catalog.
 *
 * <p>The DuckLake step is optional and, <b>in a {@code single} topology</b>, non-fatal: any connectivity
 * or SQL failure is caught and logged without aborting ETL success for the file.
 *
 * <p>⛔ <b>In a {@code partitioned} topology it is FATAL (decision D10, scale-out phase A).</b> A pod that
 * cannot reach the shared catalog would write Parquet files no other pod can see, so the batch must fail
 * rather than succeed invisibly. See {@link #onRegistrationFailure}.
 *
 * <p>Activation requires {@code ducklake.enabled: true} on the destination — a {@code sinks[]} entry's own
 * block, else {@code output.ducklake} (see {@link #register}). On a {@code single}
 * topology the method is a no-op otherwise — the lakehouse is an optional sidecar there.
 *
 * <p>⛔ <b>In a {@code partitioned} topology it is MANDATORY, not optional</b> (operator 2026-09-14,
 * scale-out §5.4). Unregistered Parquet is Parquet no other node can see, which is the same invisible
 * output D10 refuses when registration fails — so "not configured" can no longer be a quiet no-op either.
 * See {@link #requireRegistrationConfigured}, whose javadoc also records the half of that invariant this
 * does NOT cover.
 *
 * <p>Extracted from {@link com.gamma.inspector.CollectorProcessor#registerInDuckLake}.
 */
public final class DuckLakeRegistrar {

    private static final Logger log = LoggerFactory.getLogger(DuckLakeRegistrar.class);

    private DuckLakeRegistrar() {}

    /**
     * Register {@code outputPaths} in the DuckLake catalog of the destination that wrote each one — every
     * {@code cfg.sinks()} entry's <b>effective</b> {@code ducklake} block (the entry's own, else
     * {@code output.ducklake}, else none; resolved by {@code PipelineConfig.resolveSinks}).
     *
     * <p>🔴 <b>Per sink since 2026-09-23</b> ({@code SINK-DUCKLAKE-IGNORED-1}, operator decision). This read
     * only {@code cfg.output().duckLake()} and pooled every file into it, so a sink's own lake was silently
     * dropped and a two-sink pipeline registered both sinks' files into one lake. The single-destination
     * shorthand is unchanged: its one sink IS {@code output.ducklake}, and it receives every file.
     *
     * <p>⛔ The partitioned "registration is mandatory" check runs for EVERY sink that wrote, before ANY
     * attach, so one sink is never registered while another's Parquet stays invisible.
     *
     * @param outputPaths absolute paths of Parquet files to register
     * @param tableName   target DuckLake table (overrides each lake block's {@code table} key)
     * @param cfg         pipeline configuration
     */
    public static void register(List<String> outputPaths, String tableName, PipelineConfig cfg) {
        if (outputPaths.isEmpty()) return;
        List<Registration> plan = plan(outputPaths, cfg);
        // G9 backstop for a lane the pipeline registry does not guard (the job lane, the CLI entry points):
        // DuckLake registration is Professional+, so a Personal build fails the batch rather than skip it.
        for (Registration r : plan)
            if (r.duckLake() != null && Boolean.parseBoolean(String.valueOf(r.duckLake().get("enabled"))))
                EditionFeatures.require(EditionFeatures.SINK_DUCKLAKE);
        // ⛔ Checked BEFORE the early returns in registerOne, not after: those returns ARE the hole. On one
        // node they are the optional-sidecar contract; when partitioned they are how a pipeline writes
        // Parquet that is registered nowhere and no other node can see.
        boolean multi = cfg.sinks().size() > 1;
        for (Registration r : plan)
            requireRegistrationConfigured(r.duckLake(), multi ? r.sink().database() : null);
        for (Registration r : plan) registerOne(r.files(), tableName, r.duckLake());
    }

    /** One destination's share of a batch: the sink, its effective lake ({@code null} if none), its files. */
    record Registration(PipelineConfig.Sink sink, Map<String, Object> duckLake, List<String> files) {}

    /**
     * Which files each destination's lake receives. One sink (the shorthand, or a one-entry
     * {@code sinks:}) takes every file, exactly as before. Several sinks are told apart by their
     * {@code database} root — the directory every write site re-roots that sink's output under
     * ({@code IngestSinkWriter.write}, {@code ConsignmentIngestStrategy.flatWriteAndTrace}); the DEEPEST
     * matching root wins, so nested databases do not claim each other's files. Sinks that wrote nothing
     * are omitted. ⛔ A file under no sink's root is refused: registering it into an arbitrary lake is the
     * silent mis-registration this exists to end.
     */
    static List<Registration> plan(List<String> outputPaths, PipelineConfig cfg) {
        List<PipelineConfig.Sink> sinks = cfg.sinks();
        if (sinks.size() == 1)
            return List.of(new Registration(sinks.get(0), sinks.get(0).duckLake(), List.copyOf(outputPaths)));

        List<Path> roots = sinks.stream().map(s -> Path.of(s.database()).toAbsolutePath().normalize()).toList();
        Map<Integer, List<String>> byIndex = new LinkedHashMap<>();
        for (String p : outputPaths) {
            Path f = Path.of(p).toAbsolutePath().normalize();
            int best = -1;
            for (int i = 0; i < roots.size(); i++)
                if (f.startsWith(roots.get(i))
                        && (best < 0 || roots.get(i).getNameCount() > roots.get(best).getNameCount())) best = i;
            if (best < 0)
                throw new IllegalStateException("DuckLake: written file '" + p + "' lies under none of this"
                        + " pipeline's sinks[] databases " + sinks.stream().map(PipelineConfig.Sink::database)
                        .toList() + ", so which destination's ducklake it belongs to is unknown");
            byIndex.computeIfAbsent(best, k -> new ArrayList<>()).add(p);
        }
        List<Registration> plan = new ArrayList<>();
        for (int i = 0; i < sinks.size(); i++) {
            List<String> files = byIndex.get(i);
            if (files != null) plan.add(new Registration(sinks.get(i), sinks.get(i).duckLake(), List.copyOf(files)));
        }
        return plan;
    }

    private static void registerOne(List<String> outputPaths, String tableName, Map<String, Object> dl) {
        if (dl == null) return;
        if (!Boolean.parseBoolean(String.valueOf(dl.getOrDefault("enabled", false)))) return;

        String catalogUrl = (String) dl.get("catalog_url");
        String dataPath   = (String) dl.get("data_path");
        String schema     = (String) dl.getOrDefault("schema", "main");
        String table      = (tableName != null) ? tableName : (String) dl.get("table");

        registerInto(outputPaths, table, catalogUrl, dataPath, schema);
    }

    /**
     * Register {@code outputPaths} into an explicitly named catalog — the same work as
     * {@link #register}, without needing a {@link PipelineConfig}.
     *
     * <p><b>Why this overload exists.</b> The at-rest pipeline-job lane
     * ({@code com.gamma.job.PipelineJobRunner}) has <b>no {@code PipelineConfig} in scope at all</b>: it
     * loads a {@code PipelineGraph} from the store, and {@code sink.ducklake} is only a UI alias for
     * {@code sink.persistent} with no execution-time meaning. So that lane could not call the config-shaped
     * method above however much it wanted to, which is a large part of why it registered nothing
     * ({@code DUCKLAKE-GRAPH-LANE-1}). Its catalog comes from {@link com.gamma.util.LakehouseCatalog}
     * instead — the deployment-level property the READ side already reads, so both ends of the lakehouse
     * name it the same way.
     *
     * @param outputPaths absolute paths of Parquet files to register
     * @param table       target DuckLake table — the sink's store name on the pipeline-job lane
     * @param catalogUrl  the DuckLake backend spec, e.g. {@code postgres:dbname=lake host=db}
     * @param dataPath    the catalog's data path
     * @param schema      target schema; {@code main} when null
     */
    public static void registerInto(List<String> outputPaths, String table,
                                    String catalogUrl, String dataPath, String schemaOrNull) {
        if (outputPaths.isEmpty()) return;
        String schema = (schemaOrNull == null || schemaOrNull.isBlank()) ? "main" : schemaOrNull;

        requireSharedCatalog(catalogUrl);

        log.info("DuckLake: registering {} file(s) into {}.{} ...",
                outputPaths.size(), schema, table);
        try {
            java.io.File lakeDb = DuckDbUtil.tempDbFile("duckdb_lake_");
            try (Connection conn = DriverManager.getConnection(DuckDbUtil.jdbcUrl(lakeDb));
                 Statement  stmt = conn.createStatement()) {

                // AIRGAP-EXTENSIONS-1 (2026-09-11): this was an unconditional `INSTALL ducklake FROM
                // core`, i.e. a network fetch on every DuckLake registration -- so an "air-gapped"
                // install had egress the moment a pipeline enabled DuckLake, and the failure arrived
                // only as the non-fatal warning below. The shared loader tries the cached LOAD, then
                // the file staged by package.ps1 under -Dduckdb.extension.dir, and reaches INSTALL
                // only on a deployment that actually has a network.
                DuckDbExtension.ensureLoaded(conn, "ducklake", "output.ducklake.enabled");
                // AIRGAP-PGSCANNER-LOAD-1 (2026-09-14): the ATTACH below AUTOLOADS the catalog backend's
                // scanner, and autoload reads DuckDB's own extension_directory -- never
                // -Dduckdb.extension.dir. So staging postgres_scanner (shipped hours earlier) did nothing
                // on an air-gapped host: the staged file was skipped and the attach reached for a network
                // INSTALL, which D10 makes FATAL in a partitioned topology. Loading it BY NAME first takes
                // the same cached -> staged-file -> network ladder `ducklake` above already takes.
                // ⛔ There is still no LOAD inside the ATTACH to grep for; this line is the only evidence.
                String backend = LakehouseCatalog.backendExtension(catalogUrl);
                if (backend != null) {
                    DuckDbExtension.ensureLoaded(conn, backend, "the DuckLake catalog backend in catalog_url");
                }
                stmt.execute(String.format(
                        "ATTACH 'ducklake:%s' AS lake (DATA_PATH '%s'%s)",
                        catalogUrl, dataPath.replace("\\", "/"), attachOptions()));
                stmt.execute("CREATE SCHEMA IF NOT EXISTS lake.\"" + schema + '"');

                String firstPath = outputPaths.get(0).replace("\\", "/");
                stmt.execute(String.format(
                        "CREATE TABLE IF NOT EXISTS lake.\"%s\".\"%s\" AS" +
                                " SELECT * FROM read_parquet('%s') LIMIT 0",
                        schema, table, firstPath));

                String pathList = outputPaths.stream()
                        .map(p -> '\'' + p.replace("\\", "/") + '\'')
                        .collect(Collectors.joining(", ", "[", "]"));
                stmt.execute(String.format(
                        "INSERT INTO lake.\"%s\".\"%s\" SELECT * FROM read_parquet(%s)",
                        schema, table, pathList));

                log.info("DuckLake: OK — {} file(s) registered in {}.{}",
                        outputPaths.size(), schema, table);
            } finally {
                DuckDbUtil.deleteTempDb(lakeDb);
            }
        } catch (Exception e) {
            onRegistrationFailure(e, catalogUrl);
        }
    }

    /**
     * ⛔ In a {@code partitioned} topology, refuse a catalog that would be a LOCAL FILE (D4, phase C).
     *
     * <p>The rule itself lives in {@link LakehouseCatalog#requireShared} because the READ side applies the
     * same one to {@code -Dinspecto.ducklake.catalog}. ⛔ Two copies is how the write and read sides come
     * to disagree about what "shared" means, and the disagreement would surface as one of them quietly
     * using a private catalog — the very defect the rule exists to catch.
     */
    static void requireSharedCatalog(String catalogUrl) {
        LakehouseCatalog.requireShared(catalogUrl, "output.ducklake.catalog_url");
    }

    /**
     * ⛔ In a {@code partitioned} topology, registration is MANDATORY — not the opt-in sidecar it is on one
     * node (operator, 2026-09-14; scale-out §5.4 bullet 4).
     *
     * <p><b>Why.</b> {@link #register} is a no-op unless {@code output.ducklake.enabled} is true. On one
     * node that is exactly right: the lakehouse is optional. Across pods it means a pipeline can write
     * Parquet that is <b>registered nowhere</b>, so no other pod can see it — the same outcome D10 already
     * calls fatal when registration FAILS, reached instead by never attempting it. ⛔ D10 closed the
     * failure path and left the not-configured path open; this closes it.
     *
     * <p>⚠ <b>This is a deliberate behaviour change with a real blast radius</b>: a deployment running
     * {@code partitioned} without a DuckLake block now fails its batches where before they succeeded. That
     * is the point — those batches were producing invisible output — but it is why the message says which
     * flag to unset to get the old behaviour back.
     *
     * <p>🔴 <b>It is only HALF the invariant — but NOT the half first recorded here.</b> The single call
     * site is {@code ConsignmentIngestor.finalizeSource}, and this javadoc first said the uncovered half
     * was "the GRAPH lane". ⛔ <b>That was wrong, and is corrected here rather than quietly deleted.</b>
     * The branch-aware graph lane <i>does</i> register: {@code ConsignmentIngestStrategy.writeAndTrace}
     * forks to {@code flatWriteAndTrace} or {@code graphWriteAndTrace} and <b>both return the same
     * {@code Written}</b>, whose outputs flow through {@code IngestOutcome} into that one shared tail.
     * Verified by reading the fork, not the plan — the scale-out plan asserts the same falsehood, which is
     * where it came from.
     *
     * <p>The genuinely unregistered path is the <b>at-rest pipeline-job lane</b>,
     * {@code com.gamma.job.PipelineJobRunner}: it drives {@code PipelineExecutor.execute} directly with a
     * no-op finalizer ({@code () -> {}}), bypassing {@code ConsignmentIngestor} entirely, and contains
     * <b>zero</b> DuckLake references. So a partitioned deployment now refuses an unconfigured ingest
     * pipeline while pipeline JOBS keep writing Parquet no other node can see. ⛔ Do not read
     * "registration is mandatory when partitioned" as a system invariant; it is an invariant of the ingest
     * path. Tracked as {@code DUCKLAKE-GRAPH-LANE-1} — ⚠ an id that is itself a misnomer, kept because a
     * shipped commit already cites it.
     */
    static void requireRegistrationConfigured(Map<String, Object> duckLakeCfg) {
        requireRegistrationConfigured(duckLakeCfg, null);
    }

    /** As above, naming the {@code sinks[]} destination ({@code sinkDatabase}) on a multi-sink pipeline. */
    static void requireRegistrationConfigured(Map<String, Object> duckLakeCfg, String sinkDatabase) {
        if (!Topology.partitioned()) return;
        boolean enabled = duckLakeCfg != null
                && Boolean.parseBoolean(String.valueOf(duckLakeCfg.getOrDefault("enabled", false)));
        if (enabled) return;

        throw new IllegalStateException("-D" + Topology.PROPERTY + "=partitioned requires"
                + " output.ducklake.enabled: true, and this pipeline has "
                + (duckLakeCfg == null ? "no output.ducklake block" : "it disabled or unset")
                + (sinkDatabase == null ? "" : " for the sinks[] destination '" + sinkDatabase + "'")
                + ". Several processes share this state, so Parquet that is registered in no catalog is"
                + " Parquet no other node can see — the same invisible output D10 already refuses when"
                + " registration FAILS, reached by never attempting it. Configure the shared catalog, or"
                + " run this node with -D" + Topology.PROPERTY + "=single if it genuinely owns its"
                + " lakehouse alone.");
    }

    /**
     * Extra {@code ATTACH} options, per topology.
     *
     * <p>🔴 <b>{@code DATA_INLINING_ROW_LIMIT 0} when partitioned.</b> DuckLake inlines small writes INTO
     * THE CATALOG DATABASE instead of writing Parquet to the data path. Measured 2026-09-14 with a control
     * pair: without this option a small table produced <b>0</b> Parquet files and its rows appeared in the
     * catalog's {@code ducklake_inlined_data_*} table; with it, the same write produced <b>1</b> Parquet
     * file in the data path. Inlining makes the catalog database a DATA path, which breaks the object-store
     * model D4 signed and leaves any external Parquet reader (D13) incomplete.
     *
     * <p>⚠ Left ON for {@code single}, where it is a genuine optimisation and nothing reads the Parquet
     * behind the catalog's back.
     *
     * <p>Package-private for the same reason as {@link #onRegistrationFailure}: this is the DECISION, and
     * the reactor cannot drive a real ATTACH (the extension load reaches a network {@code INSTALL} on a
     * machine with no cached copy). ⛔ Without a direct test the partitioned branch has NO coverage, which
     * is the state the scale-out plan explicitly warns about for this exact option.
     */
    static String attachOptions() {
        return Topology.partitioned() ? ", DATA_INLINING_ROW_LIMIT 0" : "";
    }

    /**
     * What a registration failure means, per topology (decision <b>D10</b>).
     *
     * <p>{@code single}: unchanged — the DuckLake step is an optional sidecar and the batch still succeeds.
     * {@code partitioned}: fatal, because the catalog is how other pods SEE what this pod wrote; silently
     * skipping it leaves Parquet files that no other pod can read, which is worse than a failed batch.
     *
     * <p>⚠ <b>Deliberately not routed through {@code StoreHealth}</b>, which is this repo's single
     * enforcement point for the same invariant over operational STORES. Two reasons: {@code StoreHealth}
     * records "what an OPENER resolved to" once at boot, whereas registration is a per-batch commit that
     * would overwrite that entry on every batch and leave {@code /health/details} reporting the last
     * batch's outcome as a store's resolved state; and it is keyed by space id, which
     * {@link PipelineConfig} does not carry. The topology check itself is {@link Topology#partitioned()}
     * either way, so there is still one definition of "am I partitioned", just two consequences.
     *
     * <p>Package-private so the branch is directly testable: the reactor cannot drive a REAL DuckLake
     * failure cheaply (the extension load reaches a network {@code INSTALL} on a machine with no cached
     * copy — see this class's test for the measurement), and ⛔ mocking a real driver's real failure is
     * explicitly refused there. This method is the decision, not the driver, so it is tested directly.
     */
    static void onRegistrationFailure(Exception cause, String catalogUrl) {
        if (Topology.partitioned())
            throw new IllegalStateException("DuckLake registration failed and -" + "D" + Topology.PROPERTY
                    + "=partitioned forbids continuing: " + cause.getMessage() + " (catalog: " + catalogUrl
                    + "). A pod that cannot reach the shared catalog writes Parquet files no other pod can "
                    + "see, so the batch fails rather than succeeding invisibly. Fix the catalog, or run "
                    + "this node with -D" + Topology.PROPERTY + "=single if it genuinely owns its lakehouse "
                    + "alone.", cause);
        log.warn("DuckLake registration failed (non-fatal): {}", cause.getMessage());
    }
}
