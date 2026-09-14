package com.gamma.etl;

import com.gamma.util.DuckDbUtil;
import com.gamma.util.Topology;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
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
 * <p>Activation requires {@code output.ducklake.enabled: true} in the pipeline
 * config.  The method is a no-op otherwise.
 *
 * <p>Extracted from {@link com.gamma.inspector.CollectorProcessor#registerInDuckLake}.
 */
public final class DuckLakeRegistrar {

    private static final Logger log = LoggerFactory.getLogger(DuckLakeRegistrar.class);

    private DuckLakeRegistrar() {}

    /**
     * Register {@code outputPaths} in the DuckLake catalog referenced by the
     * pipeline config's {@code output.ducklake} section.
     *
     * @param outputPaths absolute paths of Parquet files to register
     * @param tableName   target DuckLake table (overrides the toon's {@code table} key)
     * @param cfg         pipeline configuration
     */
    @SuppressWarnings("unchecked")
    public static void register(List<String> outputPaths, String tableName, PipelineConfig cfg) {
        if (outputPaths.isEmpty()) return;
        Map<String, Object> dl = cfg.output().duckLake();
        if (dl == null) return;
        if (!Boolean.parseBoolean(String.valueOf(dl.getOrDefault("enabled", false)))) return;

        String catalogUrl = (String) dl.get("catalog_url");
        String dataPath   = (String) dl.get("data_path");
        String schema     = (String) dl.getOrDefault("schema", "main");
        String table      = (tableName != null) ? tableName : (String) dl.get("table");

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
     * The DuckLake catalog backends that are a SHARED SERVER rather than a local file.
     *
     * <p>DuckLake reads whatever follows {@code ducklake:} as a backend spec, and anything without a
     * recognised backend prefix is a <b>file path</b>. That is the whole hazard below.
     */
    private static final List<String> SERVER_BACKENDS = List.of("postgres:", "mysql:");

    /**
     * ⛔ In a {@code partitioned} topology, refuse a catalog that would be a LOCAL FILE (D4, phase C).
     *
     * <p><b>Why this exists when {@link #onRegistrationFailure} already makes failure fatal.</b> D10
     * covers the catalog that cannot be reached. It does not cover the catalog that is reached
     * <b>successfully and privately</b> — and that is the likelier mistake, because it never raises
     * anything. Measured 2026-09-14 against duckdb_jdbc 1.5.2.1: a {@code catalog_url} with no recognised
     * backend prefix does not fail, it <b>silently creates a local DuckDB file catalog</b> named after the
     * whole string. On N pods that is N private catalogs, each pod seeing only its own Parquet, every batch
     * green — precisely the split-brain D10 exists to prevent, arriving as success rather than as failure.
     * So the shape of the URL has to be refused up front; there is no later moment at which it looks wrong.
     *
     * <p>⚠ <b>Single-node behaviour is deliberately unchanged.</b> A file catalog is the correct and
     * documented choice when one process owns the lakehouse, which is every Personal and single-node
     * Standard install. This refuses it only where "one process" is false.
     *
     * <p>⚠ <b>{@code postgresql://…} is refused too, and that is not a mis-diagnosis.</b> It reads like a
     * shared catalog and is what {@code okf/backend/integrations.md} shipped as THE example, but it carries
     * no recognised prefix, so DuckLake treats it as a path as well — measured failing in the same probe.
     * The message therefore names the spelling that was measured WORKING rather than only refusing.
     */
    static void requireSharedCatalog(String catalogUrl) {
        if (!Topology.partitioned()) return;
        String url = catalogUrl == null ? "" : catalogUrl.trim();
        String lower = url.toLowerCase();
        if (SERVER_BACKENDS.stream().anyMatch(lower::startsWith)) return;

        throw new IllegalStateException("output.ducklake.catalog_url=" + (url.isEmpty() ? "(unset)" : url)
                + " is not a shared catalog, and -D" + Topology.PROPERTY + "=partitioned forbids it. DuckLake"
                + " reads anything without a backend prefix as a FILE PATH, so this would not fail — it would"
                + " quietly give this pod its own private catalog, and every pod would see only the Parquet"
                + " it wrote itself while every batch reported success. Use a server-backed catalog, e.g."
                + " catalog_url: \"postgres:dbname=lake host=db port=5432 user=U password=P\" (measured"
                + " working 2026-09-14). ⛔ A postgresql:// or postgres:// URL is NOT that spelling and is"
                + " read as a path too. Or run this node with -D" + Topology.PROPERTY + "=single if it"
                + " genuinely owns its lakehouse alone.");
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
