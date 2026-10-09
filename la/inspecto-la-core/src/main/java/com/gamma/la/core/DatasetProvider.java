package com.gamma.la.core;

import com.gamma.sql.SqlSandboxPolicy;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * The Dataset PORT of Link Analysis (LA separation D-1 step 5b/6): exactly what Link Analysis reads from the platform's
 * Dataset registry and query engine — and nothing else. Link Analysis ({@code inspecto-la-core}, {@code inspecto-la-api})
 * names no engine class; the bridge ({@code inspecto-geo-link}'s {@code EngineDatasetProvider}) implements this port from
 * {@code DatasetRead} / {@code QueryExecutor} / {@code ConditionSql} and registers it through
 * {@code META-INF/services/com.gamma.la.core.DatasetProvider}. Find the active one through {@link DatasetProviders}.
 *
 * <p><b>Unbound ⇒ absent.</b> With no provider on the classpath a route that needs a Dataset answers a clean
 * {@code 503} (see {@link DatasetProviders#require()}), never a stack trace.
 *
 * <p>The records mirror the engine's {@code QueryExecutor.Request} / {@code Result} and {@code ResultSetDescriptor.Column}
 * field-for-field; they are LA-owned so the engine's types never leak into Link Analysis.
 */
public interface DatasetProvider {

    /** One described result column: name, coarse type ({@code number|string|date|boolean}), role, and (dimensions) cardinality. */
    record Column(String name, String type, String role, Integer cardinality) {}

    /** One ORDER BY term. */
    record Sort(String field, boolean descending) {}

    /**
     * @param datasetName the logical name {@code sql} references (registered as a view); {@code null} if none
     * @param relationSql trusted relation SQL for the dataset; {@code null} if none
     * @param sql         the resolved, SqlGuard-checked query text
     * @param limit       max rows to return (a further row is read to detect truncation)
     * @param offset      rows to skip
     * @param projection  output columns, or empty for all
     * @param sort        ORDER BY terms, or empty
     * @param binds       positional bound parameters, in order
     */
    record Request(String datasetName, String relationSql, String sql,
                   int limit, int offset, List<String> projection, List<Sort> sort,
                   List<String> binds) {

        /** No bound parameters. */
        public Request(String datasetName, String relationSql, String sql,
                       int limit, int offset, List<String> projection, List<Sort> sort) {
            this(datasetName, relationSql, sql, limit, offset, projection, sort, List.of());
        }

        public Request {
            binds = binds == null ? List.of() : List.copyOf(binds);
        }
    }

    /** The typed, bounded result. */
    record Result(List<Column> columns, List<Map<String, Object>> rows, int rowCount, boolean truncated, long elapsedMs) {}

    /** One registered Dataset: its name and its configuration. */
    record Entry(String name, Map<String, Object> content) {}

    /** Builds the statement once the relation's REAL column names are known (see {@link #runPlanned}). */
    @FunctionalInterface
    interface Planner {
        Request plan(List<String> columns);
    }

    /** The Dataset's configuration, or empty when none is registered under that id. */
    Optional<Map<String, Object>> dataset(Path writeRoot, String datasetId);

    /** Every registered Dataset. */
    List<Entry> datasets(Path writeRoot);

    /** The trusted relation SQL of a Dataset's configuration. */
    String relationSql(Map<String, Object> dataset, Path dataRoot, Path writeRoot);

    /**
     * The directories the Dataset's relation reads ({@link #relationSql}): its data root plus any shared-store root. Link
     * Analysis seals the index build's DuckDB connection to these and the index store, so a relation can read nowhere else.
     */
    List<Path> readRoots(Map<String, Object> dataset, Path dataRoot);

    /**
     * The {@link InputFingerprint} of the INPUT FILES the Dataset's relation reads now (after superseded-file
     * subtraction), or {@code null} when this provider cannot say ('unknown' - the default, so an existing implementer or
     * test double keeps compiling and Link Analysis claims no currency it cannot know). An implementation returns
     * {@code InputFingerprint.noFiles(sql)} for a relation with nothing to list and {@code InputFingerprint.tooMany(..)}
     * above {@link InputFingerprint#MAX_FILES}; it throws {@link IllegalArgumentException} for an unusable Dataset.
     * Cost is one directory listing (design 5.3a).
     */
    default InputFingerprint inputFingerprint(Map<String, Object> dataset, Path dataRoot, Path writeRoot) {
        return null;
    }

    /**
     * {@link #inputFingerprint(Map, Path, Path)} under a time {@code budget}: a listing that runs out of it answers
     * {@code InputFingerprint.unknown("timeout")}, one that starts with the budget spent {@code unknown("budget")}. The default
     * ignores the budget (a provider that cannot list files has nothing to bound).
     */
    default InputFingerprint inputFingerprint(Map<String, Object> dataset, Path dataRoot, Path writeRoot, FingerprintBudget budget) {
        return inputFingerprint(dataset, dataRoot, writeRoot);
    }

    /**
     * The Dataset's relation SQL over ONLY {@code relativePaths} (input-file paths as {@link #inputFingerprint} reports them),
     * for an index APPEND of just the files added since the last build (D-3 step 8); {@code null} - the default - when the
     * relation is not row-wise over its files (a virtual or view-backed Dataset) or the provider cannot say, in which case an
     * index can only be rebuilt in full. Throws {@link IllegalArgumentException} for a path that is not an input file.
     */
    default String relationSqlOverFiles(Map<String, Object> dataset, Path dataRoot, Path writeRoot, List<String> relativePaths) {
        return null;
    }

    /** Reserved {@link #schemaClassification} key: the Dataset's column lineage cannot be traced (fail closed). */
    String UNKNOWN_LINEAGE = "*";

    /**
     * What the pipeline schemas behind the Dataset's stores classify, through their mappings (ASSURE-CLASSIFICATION-
     * PROPAGATION-1): lower-cased column to its strictest class among those {@code masked} accepts, plus
     * {@link #UNKNOWN_LINEAGE} when the lineage of a masked raw class cannot be traced. The platform's ONE lineage
     * resolver, the one {@code publish.postgres} and Risk Score evidence masking use. The default (a provider with no
     * pipeline schemas) reports nothing.
     */
    default Map<String, String> schemaClassification(Path writeRoot, Map<String, Object> dataset, Predicate<String> masked) {
        return Map.of();
    }

    /** Run a statement under the platform's default sandbox policy. */
    Result run(Request req) throws SQLException, IOException;

    /** Run a statement under {@code policy}. */
    Result run(Request req, SqlSandboxPolicy policy) throws SQLException, IOException;

    /** Run a statement under {@code policy} with the session time zone {@code timeZone}. */
    Result run(Request req, SqlSandboxPolicy policy, ZoneId timeZone) throws SQLException, IOException;

    /**
     * Probe the relation's columns and run the statement {@code planner} builds from them, in ONE sandbox session. The
     * planned request's own {@code datasetName}/{@code relationSql} are ignored in favour of the arguments. An unchecked
     * refusal the planner throws propagates unchanged and no statement runs.
     */
    Result runPlanned(String datasetName, String relationSql, SqlSandboxPolicy policy, Planner planner)
            throws SQLException, IOException;

    /**
     * Render a condition-tree {@code filter} as a SQL predicate with every operand value a bound parameter, never a
     * literal: {@code sql} holds one
     * {@code ?} per entry of {@code binds}, in text order, so the caller appends them to {@code Request.binds} at the
     * position the predicate occupies in its statement. No filter value reaches the statement text.
     *
     * @throws IllegalArgumentException when the root is not a group
     */
    BoundFilter predicateBound(Object filter);

    /** A rendered predicate plus its positional bind values (all strings; the SQL casts each). */
    record BoundFilter(String sql, List<String> binds) {
        public BoundFilter {
            binds = binds == null ? List.of() : List.copyOf(binds);
        }

        /** No constraint, no binds. */
        public static final BoundFilter TRUE = new BoundFilter("TRUE", List.of());

        /** {@code (this) AND (other)}, binds in text order. */
        public BoundFilter and(BoundFilter other) {
            List<String> all = new java.util.ArrayList<>(binds);
            all.addAll(other.binds);
            return new BoundFilter("(" + sql + ") AND (" + other.sql + ")", all);
        }
    }
}
