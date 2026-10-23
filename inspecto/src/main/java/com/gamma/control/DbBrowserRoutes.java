package com.gamma.control;

import com.gamma.spi.auth.ApiException;
import com.gamma.spi.auth.ErrorCodes;
import com.gamma.spi.http.ApiContext;
import com.gamma.spi.http.RouteModule;
import com.gamma.config.spec.Finding;
import com.gamma.pipeline.ComponentRegistry;
import com.gamma.pipeline.ComponentStore;
import com.gamma.query.QueryExecutor;
import com.gamma.query.ResultSetDescriptor;
import com.gamma.sql.SqlGuard;
import com.gamma.sql.SqlViews;
import com.gamma.util.BrowsableStore;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;
import com.gamma.access.WriteGates;
import static com.gamma.util.Values.intOr;
import static com.gamma.util.SqlIdent.q;

/**
 * Raw table browser — a per-space "database client" over the space's data
 * ({@code GET /db/catalog}, {@code GET /db/table}, {@code POST /db/query}; design
 * {@code docs/superpower/db-browser-design.md}). Phase 1 browses the <b>business-data</b> stores: the
 * Hive-partitioned Parquet/CSV directories under the space's {@code dataDir}. Each store is read through
 * the same trusted-relation-registered-as-a-view + {@link SqlGuard}-checked-user-SQL split the authored
 * query path uses ({@link QueryRoutes}), executed in an ephemeral DuckDB sandbox ({@link QueryExecutor}),
 * so there is no lock contention with the live engine and no new query engine.
 *
 * <p>Fail-closed: write root unset → 503; unknown / non-catalogued store → 404; a store path that escapes
 * the data root → 403; SQL that fails the {@link SqlGuard} allow-list → 422.
 *
 * <p>Operational (control-plane DB) tables are browsed through each live store's own connection via the
 * {@link BrowsableStore} seam (single-writer lock ⇒ no fresh connection; reads run {@code synchronized}
 * on the store). They appear only when a capability runs on a {@code db}/{@code postgres} backend — the
 * operational catalog is empty on the default in-memory/file backends.
 */
final class DbBrowserRoutes implements RouteModule {

    private static final int DEFAULT_LIMIT = 200;
    private static final int MAX_LIMIT = 5_000;

    /** The one business-data group id (Parquet/CSV stores under {@code dataDir}). */
    private static final String STORES_GROUP = "stores";

    @Override
    public void register(ApiContext api) {
        api.get("/db/catalog", (e, m) -> catalog(api));
        api.get("/db/table", (e, m) -> table(api, e));
        api.post("/db/query", (e, m) -> query(api, e, api.body(e)));
    }

    // ── GET /db/catalog ──────────────────────────────────────────────────────────

    private Object catalog(ApiContext api) {
        Path writeRoot = WriteGates.requireWriteRoot(api, "table browser");
        Map<String, String> datasetByRef = datasetByRef(writeRoot);

        List<Map<String, Object>> tables = new ArrayList<>();
        Path dataRoot = api.dataRoot();
        if (dataRoot != null && Files.isDirectory(dataRoot)) {
            try (Stream<Path> dirs = Files.list(dataRoot)) {
                for (Path dir : dirs.filter(Files::isDirectory).sorted().toList()) {
                    String name = dir.getFileName().toString();
                    if (name.startsWith(".")) continue;
                    String format = detectFormat(pipelineDatabaseDir(api, name).orElse(dir));
                    if (format == null) continue;   // no parquet/csv data → not a browsable store
                    Map<String, Object> t = new LinkedHashMap<>();
                    t.put("name", name);
                    t.put("format", format);
                    t.put("dataset", datasetByRef.get(name));   // owning dataset id, or null
                    tables.add(t);
                }
            } catch (IOException e) {
                throw new ApiException(503, ErrorCodes.CAPABILITY_UNAVAILABLE, "could not list data directory: " + e.getMessage());
            }
        }

        Map<String, Object> stores = new LinkedHashMap<>();
        stores.put("id", STORES_GROUP);
        stores.put("label", "Data Stores");
        stores.put("kind", "parquet");
        stores.put("tables", tables);

        List<Map<String, Object>> groups = new ArrayList<>();
        groups.add(stores);
        groups.addAll(operationalGroups(api));
        return Map.of("groups", groups);
    }

    /** The live DB-backed operational stores as catalog groups (empty on the default in-memory/file backends). */
    private static List<Map<String, Object>> operationalGroups(ApiContext api) {
        List<Map<String, Object>> groups = new ArrayList<>();
        for (BrowsableStore b : HostContext.of(api).service().browsableStores()) {
            List<Map<String, Object>> tables = b.browseTables().stream()
                    .<Map<String, Object>>map(t -> Map.of("name", t)).toList();
            Map<String, Object> g = new LinkedHashMap<>();
            g.put("id", "ops:" + b.browseId());
            g.put("label", "Operational · " + b.browseLabel());
            g.put("kind", "operational");
            g.put("engine", b.browseEngine());
            g.put("live", true);
            g.put("tables", tables);
            groups.add(g);
        }
        return groups;
    }

    /** Map each dataset component's {@code physicalRef} → its id, so the catalog can name a store's owner. */
    private static Map<String, String> datasetByRef(Path writeRoot) {
        Map<String, String> byRef = new LinkedHashMap<>();
        ComponentStore store = new ComponentStore(writeRoot.resolve("registry"));
        for (ComponentRegistry.Component c : store.list("dataset")) {
            Object ref = c.content().get("physicalRef");
            if (ref != null) byRef.putIfAbsent(String.valueOf(ref), c.name());
        }
        return byRef;
    }

    // ── GET /db/table?group=&name=&limit=&offset=&sort=field:dir ───────────────────

    private Object table(ApiContext api, HttpExchange ex) throws IOException {
        WriteGates.requireWriteRoot(api, "table browser");
        String group = orDefault(ApiContext.query(ex, "group"), STORES_GROUP);
        String name = ApiContext.query(ex, "name");
        if (name == null || name.isBlank()) throw new ApiException(400, ErrorCodes.MALFORMED_REQUEST, "missing 'name'");
        int limit = clampLimit(ApiContext.parseIntOr(ApiContext.query(ex, "limit"), DEFAULT_LIMIT));
        int offset = Math.max(0, ApiContext.parseIntOr(ApiContext.query(ex, "offset"), 0));
        List<QueryExecutor.Sort> sort = parseSort(ApiContext.query(ex, "sort"));

        if (group.startsWith("ops:")) {
            requireOperationalRead(ex);
            requireUnmaskedOperational(ex, group, false);
            return browseOperational(api, group, name, null, limit, offset);
        }
        if (!STORES_GROUP.equals(group))
            throw new ApiException(404, ErrorCodes.NOT_FOUND, "unknown group '" + group + "'");
        return browseStore(api, ex, name, null, limit, offset, sort);
    }

    // ── POST /db/query {group,table,sql,limit,offset} ──────────────────────────────

    private Object query(ApiContext api, HttpExchange ex, Map<String, Object> body) throws IOException {
        WriteGates.requireWriteRoot(api, "table browser");
        String group = orDefault(ApiContext.str(body, "group"), STORES_GROUP);
        String tableName = ApiContext.str(body, "table");
        String sql = ApiContext.str(body, "sql");
        if (sql == null) throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "missing 'sql'");
        if (group.startsWith("ops:")) {
            requireOperationalRead(ex);
            requireUnmaskedOperational(ex, group, true);
        }

        // A store id may itself contain path-like characters (e.g. "mule_transfers/database") without
        // being a smuggled file reference — SqlGuard's PATH_LIKE check can't tell those apart from the
        // SQL text alone. Grant that one exemption only once `tableName` is proven to be a real store
        // under this space's data root (the same jail browseStore enforces below); a name that fails
        // this check is not trusted, so the ordinary PATH_LIKE rejection still applies to it.
        String trustedRelation = STORES_GROUP.equals(group) && tableName != null && isJailedStore(api, tableName)
                ? tableName : null;
        List<Finding> findings = SqlGuard.check(sql, trustedRelation);
        if (!findings.isEmpty())
            return ApiContext.respondJson(ex, 422, Map.of(
                    "error", "SQL failed the read-only safety check", "findings", findings));

        int limit = clampLimit(intOr(body.get("limit"), DEFAULT_LIMIT));
        int offset = Math.max(0, intOr(body.get("offset"), 0));
        if (group.startsWith("ops:"))
            return browseOperational(api, group, tableName, sql, limit, offset);
        if (!STORES_GROUP.equals(group))
            throw new ApiException(404, ErrorCodes.NOT_FOUND, "unknown group '" + group + "'");
        if (tableName == null) throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "missing 'table' (the store the SQL reads from)");
        return browseStore(api, ex, tableName, sql, limit, offset, List.of());
    }

    /**
     * DB-QUERY-UNGATED-1, operator decision 2026-10-03 (option A): reading OPERATIONAL-store rows (the
     * {@code ops:*} groups — the control-plane DB) takes {@code canAuthorWorkbench}, the capability the
     * row-reading agent tools carry ({@code ToolCapabilities.ROW_READERS}, pinned by
     * {@code ToolCapabilitiesCoverageTest}). Business-store reads stay open: Viewer dashboards read Dataset
     * rows through these routes. Checked in the handler, before any SQL check or read, so the route-gating
     * scanners (which see only {@code withCapability} literals) do not count it — the manifest records it as
     * {@code group-gated}, and {@code ControlApiDbBrowserTest} pins it over real HTTP.
     */
    private static void requireOperationalRead(HttpExchange ex) {
        ApiContext.requireCapability(ex, "canAuthorWorkbench");
    }

    /** The operational stores whose rows carry no Incident / Alert content (the agent scan tools' allow-list). */
    static final Set<String> KEYLESS_OPERATIONAL = Set.of("acquire", "consignment-outputs", "file-stages", "jobs",
            "provenance", "status");

    /**
     * OBJECT-READ-MASKING-RESIDUALS-1: the object / link / note / tag / event / receipt stores hold raw classified keys,
     * and their rows cannot be masked column-by-column ({@code BiResultMasking} covers Dataset-backed stores only). A
     * caller without {@code canRevealLinkEntities} may page a keyless store's tables, but never runs SQL on {@code ops:*}:
     * the stores share one connection, so a query named under {@code ops:status} could read {@code objects}. Fails closed.
     */
    private static void requireUnmaskedOperational(HttpExchange ex, String group, boolean sql) {
        boolean reveals = ApiContext.subject(ex)
                .map(s -> s.capabilities().contains(AuditReadMasking.UNMASK_CAPABILITY)).orElse(true);
        if (reveals || (!sql && KEYLESS_OPERATIONAL.contains(group.substring("ops:".length())))) return;
        throw new ApiException(403, ErrorCodes.PERMISSION_DENIED, sql
                ? "SQL over operational stores needs " + AuditReadMasking.UNMASK_CAPABILITY
                : "operational store '" + group + "' holds classified keys; reading it needs " + AuditReadMasking.UNMASK_CAPABILITY);
    }

    // ── shared Parquet/CSV store read ──────────────────────────────────────────────

    /**
     * Read one business-data store as a relation. Builds the trusted {@code read_parquet}/{@code read_csv}
     * relation ({@link SqlViews}), registers it as a view named after the store, and runs either a
     * generated {@code SELECT *} (browse) or the caller's already-{@code SqlGuard}-checked SQL (ad-hoc)
     * over it in the sandbox.
     */
    /** Same jail {@link #browseStore} enforces, checked early so a path-like-but-legitimate store id
     *  (e.g. {@code "mule_transfers/database"}) can be trusted by {@link SqlGuard} before it runs. */
    /** A store at or under a {@code .restricted} segment, or whose tree contains one ({@code DataRef}'s rule). */
    private static boolean restricted(Path root, Path storeDir) {
        try {
            com.gamma.config.safety.DataRef.requireNotRestricted(root, storeDir, "store", root.relativize(storeDir).toString());
            return false;
        } catch (IllegalArgumentException e) {
            return true;
        }
    }

    private static boolean isJailedStore(ApiContext api, String storeName) {
        Path dataRoot = api.dataRoot();
        if (dataRoot == null) return false;
        Path root = dataRoot.normalize();
        Path storeDir = root.resolve(storeName).normalize();
        if (!storeDir.startsWith(root)) return false;
        Path browseDir = pipelineDatabaseDir(api, storeName).orElse(storeDir);
        if (restricted(root, browseDir)) return false;
        return Files.isDirectory(browseDir);
    }

    private Object browseStore(ApiContext api, HttpExchange ex, String storeName, String userSql,
                               int limit, int offset, List<QueryExecutor.Sort> sort) {
        Path dataRoot = api.dataRoot();
        if (dataRoot == null) throw new ApiException(404, ErrorCodes.NOT_FOUND, "no data directory for this space");
        Path root = dataRoot.normalize();
        Path storeDir = root.resolve(storeName).normalize();
        if (!storeDir.startsWith(root)) throw new ApiException(403, ErrorCodes.PATH_JAIL_VIOLATION, "store path escapes the data root");
        Path browseDir = pipelineDatabaseDir(api, storeName).orElse(storeDir);
        // processing.refusal: the restricted quarantine is never browsable, by id or by any ancestor's glob.
        if (restricted(root, browseDir)) throw new ApiException(403, ErrorCodes.PATH_JAIL_VIOLATION, "store path addresses the restricted quarantine");
        if (!Files.isDirectory(browseDir)) throw new ApiException(404, ErrorCodes.NOT_FOUND, "no store '" + storeName + "'");

        String format = detectFormat(browseDir);
        if (format == null) throw new ApiException(404, ErrorCodes.NOT_FOUND, "store '" + storeName + "' has no parquet/csv data");

        // QueryExecutor registers this as `CREATE VIEW <store> AS <relationSql>`, so it must be a full
        // SELECT — the same wrap DatasetRelation / SourceStoreReader apply around the reader. The source
        // is the Consignment catalog's PINNED file list (addressing §7-A), not a live glob: a browse over a
        // pipeline sink during a full recompute otherwise re-expands the glob at scan time and shows a
        // revision the catalog has already superseded (2026-09-06; the same fix DatasetRelation carries).
        String source = com.gamma.consignment.ConsignmentSelector.sourceLiteral(
                browseDir.toString().replace('\\', '/'), SqlViews.ext(format));
        String relationSql = "SELECT * FROM " + SqlViews.readerOverLiteral(format, source, true);
        String sql = userSql != null ? userSql : "SELECT * FROM " + q(storeName);

        // BI-QUERY-MASKING-1: a store's classified entity keys are masked unless the caller may reveal them; a caller who may
        // not cannot sort on one, nor compare one in ad-hoc SQL.
        BiResultMasking masking = storeMasking(api, ex, storeName);
        masking.vetSort(sort);
        if (userSql != null) masking.vetAdHocSql(userSql);

        QueryExecutor.Request req = new QueryExecutor.Request(storeName, relationSql, sql,
                limit, offset, List.of(), sort);
        try {
            QueryExecutor.Result raw = QueryExecutor.run(req);
            QueryExecutor.Result shown = masking.apply(raw,
                    userSql == null ? raw.columns().stream().map(ResultSetDescriptor.Column::name).toList() : null, userSql);
            Map<String, Object> data = response(shown);
            Map<String, Object> block = masking.describe();
            if (block != null) data.put("masking", block);
            return data;
        } catch (IllegalArgumentException bad) {          // unsafe projection/sort identifier
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, bad.getMessage());
        } catch (SQLException e) {
            // a run-time error can quote a stored key (error(msisdn), a failed cast): withheld while masking is in force
            throw masking.scrub(new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "query failed: " + ServerFaults.sqlMessage(e.getMessage(), api)));
        } catch (IOException e) {
            throw ServerFaults.curated(503, ErrorCodes.CAPABILITY_UNAVAILABLE, "query sandbox unavailable", "sandbox could not be opened", e);
        }
    }

    // ── operational (control-plane DB) table read via the live-connection BrowsableStore seam ──

    /**
     * Read one operational store's table (or run its {@code SqlGuard}-checked ad-hoc SQL) through the
     * store's live connection — {@link BrowsableStore} runs it {@code synchronized} on the store, so it
     * never races the store's own writes and never opens a second (lock-conflicting) connection.
     */
    private Object browseOperational(ApiContext api, String group, String table, String userSql,
                                     int limit, int offset) {
        String id = group.substring("ops:".length());
        BrowsableStore store = null;
        for (BrowsableStore b : HostContext.of(api).service().browsableStores())
            if (b.browseId().equals(id)) { store = b; break; }
        if (store == null)
            throw new ApiException(404, ErrorCodes.NOT_FOUND, "no live operational store '" + id + "' (its backend is not DB-backed)");
        if (userSql == null && (table == null || table.isBlank()))
            throw new ApiException(400, ErrorCodes.MALFORMED_REQUEST, "missing 'name'");
        long t0 = System.nanoTime();
        try {
            BrowsableStore.Page page = userSql != null
                    ? store.browseQuery(userSql, limit, offset)
                    : store.browseTable(table, limit, offset);
            return responseFromPage(page, (System.nanoTime() - t0) / 1_000_000);
        } catch (IllegalArgumentException bad) {          // unknown table for this store
            throw new ApiException(404, ErrorCodes.NOT_FOUND, bad.getMessage());
        } catch (SQLException e) {
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "query failed: " + ServerFaults.sqlMessage(e.getMessage(), api));
        }
    }

    private static Object responseFromPage(BrowsableStore.Page p, long elapsedMs) {
        List<Map<String, Object>> columns = new ArrayList<>();
        for (BrowsableStore.Column c : p.columns()) {
            Map<String, Object> col = new LinkedHashMap<>();
            col.put("name", c.name());
            col.put("type", c.type());
            col.put("role", null);
            col.put("cardinality", null);
            columns.add(col);
        }
        Map<String, Object> statistics = new LinkedHashMap<>();
        statistics.put("rowCount", p.rows().size());
        statistics.put("elapsedMs", elapsedMs);
        statistics.put("truncated", p.truncated());
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("columns", columns);
        data.put("rows", p.rows());
        data.put("statistics", statistics);
        return data;
    }

    // ── helpers ────────────────────────────────────────────────────────────────────

    /**
     * A pipeline's canonical transformed output dir ({@code dirs.database}), when the store name is a
     * registered pipeline and that dir exists. A pipeline's data tree colocates heterogeneous working
     * stores (raw {@code backup/} copies, {@code errors/}, {@code quarantine/}, downstream job output),
     * so globbing the whole tree would mix schemas — and format auto-detection could lock onto the
     * pre-mapping backup CSVs, hiding mapped columns (the demo {@code orders} store's {@code GROSS}).
     */
    private static Optional<Path> pipelineDatabaseDir(ApiContext api, String storeName) {
        return HostContext.of(api).service().configFor(storeName)
                .map(cfg -> Path.of(cfg.dirs().database()).toAbsolutePath().normalize())
                .filter(Files::isDirectory);
    }

    /** The at-rest format of a store dir: {@code "PARQUET"} / {@code "CSV"}, or {@code null} if neither. */
    private static String detectFormat(Path storeDir) {
        try (Stream<Path> s = Files.walk(storeDir, 6)) {
            return s.filter(Files::isRegularFile)
                    .map(p -> {
                        String n = p.getFileName().toString().toLowerCase(Locale.ROOT);
                        return n.endsWith(".parquet") ? "PARQUET" : n.endsWith(".csv") ? "CSV" : null;
                    })
                    .filter(f -> f != null)
                    .findFirst()
                    .orElse(null);
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * The masking for the rows of a business-data STORE (not a Dataset): the classification of every Dataset that
     * reads the store through its {@code physicalRef}, merged, plus the pipeline schema and sibling lineage the helper
     * already resolves from {@code physicalRef} (the same resolver every other BI route uses).
     */
    private static BiResultMasking storeMasking(ApiContext api, HttpExchange ex, String storeName) {
        Path writeRoot = api.writeRoot();
        ComponentStore store = new ComponentStore(writeRoot.resolve("registry"));
        List<Object> columns = new ArrayList<>();
        for (ComponentRegistry.Component c : store.list("dataset"))
            if (storeName.equals(String.valueOf(c.content().get("physicalRef"))) && c.content().get("columns") instanceof List<?> cols)
                columns.addAll(cols);
        Map<String, Object> pseudo = new LinkedHashMap<>();
        pseudo.put("physicalRef", storeName);
        pseudo.put("columns", columns);
        return BiResultMasking.of(writeRoot, store, new com.gamma.pipeline.ViewStore(writeRoot.resolve("views")),
                "store:" + storeName, pseudo, BiResultMasking.mayReveal(ex));
    }

    private static Map<String, Object> response(QueryExecutor.Result r) {
        List<Map<String, Object>> columns = new ArrayList<>();
        for (ResultSetDescriptor.Column c : r.columns()) {
            Map<String, Object> col = new LinkedHashMap<>();
            col.put("name", c.name());
            col.put("type", c.type());
            col.put("role", c.role());
            col.put("cardinality", c.cardinality());
            columns.add(col);
        }
        Map<String, Object> statistics = new LinkedHashMap<>();
        statistics.put("rowCount", r.rowCount());
        statistics.put("elapsedMs", r.elapsedMs());
        statistics.put("truncated", r.truncated());

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("columns", columns);
        data.put("rows", r.rows());
        data.put("statistics", statistics);
        return data;
    }

    private static List<QueryExecutor.Sort> parseSort(String s) {
        if (s == null || s.isBlank()) return List.of();
        int c = s.lastIndexOf(':');
        String field = c > 0 ? s.substring(0, c) : s;
        boolean desc = c > 0 && "desc".equalsIgnoreCase(s.substring(c + 1));
        return List.of(new QueryExecutor.Sort(field.trim(), desc));
    }

    private static int clampLimit(int l) {
        return Math.max(1, Math.min(MAX_LIMIT, l));
    }

    private static String orDefault(String v, String def) {
        return (v == null || v.isBlank()) ? def : v;
    }
}
