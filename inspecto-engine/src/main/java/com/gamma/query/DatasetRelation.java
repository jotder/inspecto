package com.gamma.query;

import com.gamma.config.safety.DataRef;
import com.gamma.config.spec.Finding;
import com.gamma.pipeline.ViewDefinition;
import com.gamma.pipeline.ViewStore;
import com.gamma.sql.SqlGuard;
import com.gamma.sql.SqlViews;
import com.gamma.util.Values;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Resolves a {@code dataset} component's config to a <b>relation SQL</b> string usable as
 * {@code CREATE VIEW <name> AS <relationSql>} (W4; design §6 context 7). This is the query-time
 * <em>read</em> path — it does not materialize anything (Matrices materialization stays a separate
 * backend-backlog concern). Three forms, exactly one per dataset:
 * <ul>
 *   <li>{@code {view: "<store>"}} — the pipeline-produced {@link ViewDefinition}'s
 *       {@link ViewDefinition#derivedSql() derived SQL} (which already embeds its physical paths).</li>
 *   <li>{@code {physicalRef: "<store>"}} — a {@code read_parquet('<dataDir>/<store>/**')} glob
 *       over the space's at-rest data (the same physical layout {@code ViewQuery} reads). Always
 *       Parquet: no dataset author (fixtures, Studio, or the job registrars) writes a {@code format}
 *       key, so a once-documented option was removed from the doc rather than built.</li>
 *   <li>{@code {sql: "<SELECT …>", sourceName: "<store>"}} — a <b>virtual</b> Dataset authored as SQL
 *       (VIRTUAL-DATASET-SQL-1). The SQL is the author's text, so it must pass {@link SqlGuard} first; its
 *       {@code FROM <store>} is then bound to that store's {@code physicalRef}-style read as a CTE:
 *       {@code WITH "<store>" AS (<store read>) SELECT * FROM (<sql>) AS __virtual}. The Studio editor
 *       persists the SQL it ran (hand-edited or builder-generated), so the preview, the saved Dataset and
 *       every server reader evaluate the same text.</li>
 * </ul>
 * The returned SQL is <b>trusted</b> (server-built) and is the only place file-reading functions appear —
 * a query's own text (and a virtual Dataset's {@code sql}) is {@code SqlGuard}-checked and can never smuggle one.
 *
 * <p><b>Calculated columns (DAT-5).</b> A dataset may declare {@code calculated: [{name, expr}]} —
 * row-level derived columns computed at query time. Each {@code expr} is a caller-authored SQL
 * <em>fragment</em> and must pass {@link ExpressionGuard} (fragment-level safety; design:
 * {@code docs/superpower/calculated-columns-design.md}); each {@code name} must be a plain identifier.
 * The base relation is then wrapped {@code SELECT *, (expr) AS "name", … FROM (<base>)} — so every
 * consumer (BI query, reports, alerts, materialization) sees calculated columns as real columns.
 * Fail-closed: one bad column makes the whole dataset unusable (422), never silently degraded.
 */
public final class DatasetRelation {

    private DatasetRelation() {}

    private static final Pattern SAFE_IDENT = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");
    private static final Pattern TRAILING_SEMICOLON = Pattern.compile(";(\\s*--[^\\n]*)*\\s*$");

    /**
     * @param datasetConfig the dataset component's parsed config
     * @param dataRoot      the space's data directory (for {@code physicalRef}); may be {@code null} for view-backed datasets
     * @param views         the space's view store (for {@code view}-backed datasets)
     * @throws IllegalArgumentException on an unusable dataset config (→ 422 at the route)
     */
    public static String relationSql(Map<String, Object> datasetConfig, Path dataRoot, ViewStore views) {
        return withCalculated(baseRelationSql(datasetConfig, dataRoot, views), datasetConfig);
    }

    private static String baseRelationSql(Map<String, Object> datasetConfig, Path dataRoot, ViewStore views) {
        String view = Values.trimToNull(datasetConfig == null ? null : datasetConfig.get("view"));
        String userSql = Values.trimToNull(datasetConfig == null ? null : datasetConfig.get("sql"));
        String physicalRef = Values.trimToNull(datasetConfig == null ? null : datasetConfig.get("physicalRef"));
        if (userSql != null) {
            // Two declared relations is ambiguous: refuse rather than let key order pick one silently.
            if (view != null || physicalRef != null)
                throw new IllegalArgumentException(
                        "dataset declares 'sql' beside a 'view' or 'physicalRef'; declare exactly one relation");
            return virtualRelationSql(userSql, Values.trimToNull(datasetConfig.get("sourceName")), dataRoot);
        }
        if (view != null) {
            Optional<ViewDefinition> def = views == null ? Optional.empty() : views.get(view);
            // Rendered, not raw (addressing §7-A): a runner-written definition templates its source read, so
            // the catalog subtracts superseded files here — the same filtering the physicalRef branch below
            // applies. A pre-template / hand-authored definition's plain SQL passes through untouched.
            String sql = def.map(ViewReaderSql::rendered).orElse(null);
            if (def.isEmpty())
                throw new IllegalArgumentException("dataset references unknown view '" + view + "'");
            if (sql == null || sql.isBlank())
                throw new IllegalArgumentException("view '" + view + "' has no derived SQL to query");
            return sql;
        }
        if (physicalRef != null) return storeRelationSql(physicalRef, dataRoot);
        throw new IllegalArgumentException("dataset must declare a 'view', a 'physicalRef', or (virtual) its 'sql'");
    }

    /**
     * A virtual Dataset's relation: the author's SQL over its one source store. The store name the SQL reads
     * {@code FROM} is bound as a CTE over the same read a {@code physicalRef} gets, so the SQL can name
     * nothing but that store (any other relation simply does not exist in the scope → a DuckDB error → 422).
     * The SQL is wrapped on its own lines, so a trailing {@code -- comment} cannot swallow the closing paren.
     */
    private static String virtualRelationSql(String userSql, String sourceName, Path dataRoot) {
        if (sourceName == null)
            throw new IllegalArgumentException(
                    "a virtual dataset's 'sql' needs a 'sourceName' — the store its FROM reads");
        String storeRead = storeRelationSql(sourceName, dataRoot);   // path-jails the name first
        // The store name is proven to be a jailed store ref above, so SqlGuard may let it through as a
        // relation even when it carries a '/' (the same exemption POST /db/query grants a real store).
        List<Finding> findings = SqlGuard.check(userSql, sourceName);
        if (!findings.isEmpty())
            throw new IllegalArgumentException("dataset 'sql' failed the SQL safety check: "
                    + String.join("; ", findings.stream().map(Finding::message).toList()));
        // SqlGuard admits ONE trailing ';' (optionally followed by line comments) — inside a subquery it is a
        // syntax error, so it is dropped here; everything else is embedded exactly as authored.
        String body = TRAILING_SEMICOLON.matcher(userSql.strip()).replaceFirst("");
        return "WITH \"" + sourceName.replace("\"", "\"\"") + "\" AS (" + storeRead + ")\n"
                + "SELECT * FROM (\n" + body + "\n) AS __virtual";
    }

    /** The {@code physicalRef} read of one store ref (local, path-jailed; or a granted {@code shared/} snapshot). */
    private static String storeRelationSql(String ref, Path dataRoot) {
        // A shared/<owner>/<item> ref routes to the owner's Exchange snapshot (grant-checked, fail-closed)
        // instead of this space's data root — everything downstream reads it as an ordinary Parquet glob.
        // Only the shape is answerable for a shared ref; the local branch also gets containment, so the
        // two readers of a physicalRef (here and ExpectationEvaluator) now apply one rule, not two.
        Path base = ref.startsWith(SHARED_PREFIX)
                ? resolveShared(DataRef.requireShape(ref, REF_LABEL))
                : DataRef.requireUnder(dataRoot, ref, REF_LABEL);
        String root = base.normalize().toString().replace('\\', '/');
        // Store-layout contract: a pipeline-shaped store (one with a database/ subtree) reads its
        // mapped output only — quarantine/backup/nested trees stay out of the dataset. An explicit
        // deeper ref (orders/database, orders/rollup) resolves as written.
        if (!ref.startsWith(SHARED_PREFIX)) root = SqlViews.storeReadRoot(root);
        // Addressing §7-A: the Consignment catalog subtracts files it has marked unreadable, and yields the
        // plain quoted glob when it has nothing to say. This is the only reader that sees a pipeline sink's
        // output, so it is the one that has to be filtered before a full recompute may leave an old
        // revision on disk (step 6). No connection is threaded here on purpose — relationSql has call
        // sites in three modules — so the selector walks the tree, as the storeReadRoot check above does.
        //
        // The read OPTIONS come from SqlViews, never from here: a hand-built read_parquet(<literal>) omitted
        // union_by_name, so a store that gained a column mid-life read fine as a view and failed as a
        // Dataset. hive_partitioning stays off — turning it on would surface partition segments as new
        // columns on every existing Dataset, which is a separate decision, not a bug fix.
        String source = com.gamma.consignment.ConsignmentSelector.sourceLiteral(root, "parquet");
        return "SELECT * FROM " + SqlViews.readerOverLiteral("PARQUET", source, false);
    }

    private static final String SHARED_PREFIX = "shared/";
    private static final String REF_LABEL = "dataset physicalRef";

    /** Resolve a {@code shared/<owner>/<item>} ref to its snapshot dir via the installed {@link SharedRefResolver}. */
    private static Path resolveShared(String ref) {
        String[] parts = ref.substring(SHARED_PREFIX.length()).split("/", -1);
        if (parts.length != 2 || parts[0].isEmpty() || parts[1].isEmpty())
            throw new IllegalArgumentException("malformed shared ref '" + ref + "' (expected shared/<owner>/<item>)");
        return SharedRefResolver.global().resolveSnapshot(parts[0], parts[1])
                .orElseThrow(() -> new IllegalArgumentException(
                        "shared dataset '" + ref + "' is not available (no active grant, or no snapshot published yet)"));
    }

    /**
     * The dataset's declared <b>temporal column</b> — the {@code columns[{name,type,role}]} entry carrying
     * {@code role: temporal} (consignment addressing §3.1, step 2). Until now that declaration was written by
     * Studio and read by nobody; this is the one place the backend resolves it, so event-time bounds are
     * captured from a column the dataset itself names rather than a guessed one.
     *
     * <p><b>Absent degrades, ambiguous rejects.</b> No {@code columns} block, or none carrying the role,
     * returns empty — a dataset without a temporal column must still be readable (decision D3: addressing
     * degrades, it does not break). Two columns claiming the role throw: there is no honest way to pick one,
     * and silently taking the first would bind the catalog's bounds to declaration order.
     *
     * <p>The name is identifier-checked here because the caller's next move is to embed it in
     * {@code min()/max()} SQL — the same fail-closed rule the calculated columns follow.
     *
     * @throws IllegalArgumentException if two columns declare {@code role: temporal}, or the one that does
     *                                  has no plain-identifier name
     */
    public static Optional<String> temporalColumn(Map<String, Object> datasetConfig) {
        Object cols = datasetConfig == null ? null : datasetConfig.get("columns");
        if (!(cols instanceof java.util.List<?> list)) return Optional.empty();
        String found = null;
        for (Object o : list) {
            // an entry that is not an object declares no role at all — it cannot be the temporal one
            if (!(o instanceof Map<?, ?> c) || !"temporal".equalsIgnoreCase(Values.trimToNull(cast(c).get("role")))) continue;
            String name = Values.trimToNull(cast(c).get("name"));
            if (name == null || !SAFE_IDENT.matcher(name).matches())
                throw new IllegalArgumentException(
                        "temporal column needs a plain-identifier 'name', got '" + name + "'");
            if (found != null)
                throw new IllegalArgumentException("dataset declares two temporal columns ('" + found
                        + "' and '" + name + "'); exactly one column may carry role: temporal");
            found = name;
        }
        return Optional.ofNullable(found);
    }

    /** Wrap {@code base} with the dataset's calculated columns (DAT-5), or return it untouched when none. */
    private static String withCalculated(String base, Map<String, Object> datasetConfig) {
        Object calc = datasetConfig == null ? null : datasetConfig.get("calculated");
        if (!(calc instanceof java.util.List<?> list) || list.isEmpty()) return base;
        StringBuilder cols = new StringBuilder();
        for (Object o : list) {
            if (!(o instanceof Map<?, ?> c))
                throw new IllegalArgumentException("calculated entries must be {name, expr} objects");
            String name = Values.trimToNull(cast(c).get("name"));
            String expr = Values.trimToNull(cast(c).get("expr"));
            if (name == null || !SAFE_IDENT.matcher(name).matches())
                throw new IllegalArgumentException("calculated column needs a plain-identifier 'name', got '" + name + "'");
            if (expr == null)
                throw new IllegalArgumentException("calculated column '" + name + "' needs an 'expr'");
            cols.append(", (").append(ExpressionGuard.check(expr)).append(") AS \"").append(name).append('"');
        }
        return "SELECT *" + cols + " FROM (" + base + ") AS __base";
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> cast(Map<?, ?> m) {
        return (Map<String, Object>) m;
    }
}
