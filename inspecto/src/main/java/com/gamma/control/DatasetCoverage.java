package com.gamma.control;

import com.gamma.access.ComponentAccess;
import com.gamma.access.WriteGates;
import com.gamma.entitystore.EntityTypes;
import com.gamma.entitystore.LinkAnalysisSettings;
import com.gamma.pipeline.ComponentRegistry;
import com.gamma.pipeline.ComponentStore;
import com.gamma.pipeline.ViewStore;
import com.gamma.query.DatasetRelation;
import com.gamma.spi.auth.ApiException;
import com.gamma.spi.auth.ErrorCodes;
import com.gamma.spi.http.ApiContext;
import com.gamma.util.DuckDbUtil;
import com.gamma.util.SqlIdent;
import com.sun.net.httpserver.HttpExchange;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code GET /datasets/{id}/coverage} (LA-REFERENCE-DETAILS-MORE-1, LA UC-04 H9) - how many of a Reference's entities
 * appear in the Datasets that hold its Entity Type: "312 accounts described; 295 appear in transfers; 17 do not".
 *
 * <p>A Reference is an Add data Dataset of {@code kind: reference} with an {@code identifyingColumn} and a
 * {@code primaryEntityType}. {@code described} = the distinct keys of its identifying column; for each other Dataset the
 * caller may view (not a Reference) with a column whose {@code classification} resolves to that Entity Type
 * ({@link OnboardMatches#typeOf}), {@code appear} = how many of those keys are found in ANY such column, {@code absent}
 * the rest. Keys compare as trimmed text, as Add data's shared-column {@code matches} do.
 *
 * <p><b>Counts only</b>: no key or value leaves, and never a count over a column masked for the caller
 * ({@link BiResultMasking#hidesFromCaller}, as Add data's {@code matches}): a caller who may create a Dataset of chosen
 * values classified as the Entity Type would otherwise learn from {@code appear} which masked keys the Reference holds. So
 * a Reference whose identifying column is masked for the caller answers no counts (with the {@code reason}), and a
 * Dataset's masked columns are never compared (a Dataset left with none is not listed). <b>Bounded</b>: each Dataset is read over at
 * most {@link #MAX_SCAN_ROWS} rows and at most {@link #MAX_DATASETS} Datasets are compared; {@code truncated} says a cap
 * bit. A Dataset that cannot be read is left out, never an error.
 *
 * <p>Gates, fail closed: write root unset 503 · an id the store refuses 400 · unknown or not viewable 404 (the
 * Dataset's view permission, as {@code GET /datasets/{id}/arrivals}; no capability). A Dataset that is not a Reference,
 * or a Space without a data directory, answers 200 with no counts and the {@code reason}.
 */
final class DatasetCoverage {

    /** At most this many Datasets are compared with the Reference. */
    static final int MAX_DATASETS = 20;
    /** Each Dataset is read over at most this many rows. */
    static final long MAX_SCAN_ROWS = 1_000_000;

    private DatasetCoverage() {}

    static Object of(ApiContext api, HttpExchange ex, String id) {
        Path writeRoot = WriteGates.requireWriteRoot(api, "dataset coverage");                       // 503
        ComponentStore store = new ComponentStore(writeRoot.resolve("registry"));
        Map<String, Object> ref;
        try {
            ref = store.get("dataset", id).map(ComponentRegistry.Component::content)
                    .orElseThrow(() -> new ApiException(404, ErrorCodes.NOT_FOUND, "no dataset '" + id + "'"));
        } catch (IllegalArgumentException bad) {
            throw new ApiException(400, ErrorCodes.MALFORMED_REQUEST, bad.getMessage());                // 400
        }
        if (!ComponentAccess.canView(ex, ref))
            throw new ApiException(404, ErrorCodes.NOT_FOUND, "no dataset '" + id + "'");                // 404

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("dataset", id);
        String keyCol = text(ref.get("identifyingColumn"));
        String entityType = text(ref.get("primaryEntityType"));
        out.put("entityType", entityType);
        out.put("identifyingColumn", keyCol);
        out.put("described", null);
        out.put("datasets", List.of());
        out.put("truncated", false);
        if (!"reference".equals(ref.get("kind")) || keyCol == null || entityType == null) {
            out.put("reason", "'" + id + "' is not a Reference (details about entities with an identifying column)");
            return out;
        }
        List<EntityTypes.EntityType> types = LinkAnalysisSettings.forRoot(writeRoot).effectiveEntityTypes();
        for (EntityTypes.EntityType t : types) if (t.id().equals(entityType)) out.put("entityTypeLabel", t.label());
        Path dataRoot = api.dataRoot() == null ? null : api.dataRoot().toAbsolutePath().normalize();
        if (dataRoot == null) {
            out.put("reason", "this Space has no data directory");
            return out;
        }

        ViewStore views = new ViewStore(writeRoot.resolve("views"));
        boolean reveal = BiResultMasking.mayReveal(ex);
        try {
            if (BiResultMasking.of(writeRoot, store, views, id, ref, reveal).hidesFromCaller(keyCol)) {
                out.put("reason", "'" + keyCol + "' is masked for you, so how many of its entities appear elsewhere is not counted");
                return out;
            }
        } catch (RuntimeException unreadable) {
            out.put("reason", "'" + id + "' could not be read");
            return out;
        }
        // the other viewable Datasets holding the Entity Type, by name; a Reference describes, it does not "appear"
        List<String[]> holders = new ArrayList<>();             // {name, relation, column...}
        boolean truncated = false;
        List<ComponentRegistry.Component> all;
        try {
            all = new ArrayList<>(store.list("dataset"));
        } catch (Exception unreadable) {
            all = List.of();
        }
        all.sort(Comparator.comparing(ComponentRegistry.Component::name));
        for (ComponentRegistry.Component d : all) {
            Map<String, Object> c = d.content();
            if (d.name().equals(id) || "reference".equals(c.get("kind")) || !ComponentAccess.canView(ex, c)) continue;
            List<String> cols = typedColumns(c, entityType, types);
            if (cols.isEmpty()) continue;
            if (holders.size() >= MAX_DATASETS) {
                truncated = true;
                break;
            }
            String relation;
            try {
                BiResultMasking masking = BiResultMasking.of(writeRoot, store, views, d.name(), c, reveal);
                cols.removeIf(masking::hidesFromCaller);   // a count over a masked column would tell which keys exist
                if (cols.isEmpty()) continue;
                relation = DatasetRelation.relationSql(c, dataRoot, views);
            } catch (RuntimeException unreadable) {
                continue;
            }
            List<String> row = new ArrayList<>(List.of(d.name(), relation));
            row.addAll(cols);
            holders.add(row.toArray(String[]::new));
        }

        String refRelation;
        try {
            refRelation = DatasetRelation.relationSql(ref, dataRoot, views);
        } catch (RuntimeException unreadable) {
            out.put("reason", "'" + id + "' could not be read");
            return out;
        }
        try {
            DuckDbUtil.loadDriver();
        } catch (ClassNotFoundException e) {
            out.put("reason", "'" + id + "' could not be read");
            return out;
        }
        try (Connection conn = DuckDbUtil.openInMemory(null, List.of(dataRoot), null)) {
            long[] described = describe(conn, refRelation, keyCol, MAX_SCAN_ROWS);
            truncated |= described[1] == 1;
            out.put("described", described[0]);
            List<Map<String, Object>> rows = new ArrayList<>();
            for (String[] h : holders) {
                List<String> cols = List.of(h).subList(2, h.length);
                long[] appear = appear(conn, h[1], cols, MAX_SCAN_ROWS);
                if (appear == null) continue;
                truncated |= appear[1] == 1;
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("dataset", h[0]);
                m.put("columns", cols);
                m.put("appear", appear[0]);
                m.put("absent", described[0] - appear[0]);
                rows.add(m);
            }
            out.put("datasets", rows);
        } catch (SQLException unreadable) {
            out.put("described", null);
            out.put("reason", "'" + id + "' could not be read");
        }
        out.put("truncated", truncated);
        return out;
    }

    /** The columns of a Dataset entry whose classification resolves to {@code entityType}. */
    static List<String> typedColumns(Map<String, Object> dataset, String entityType, List<EntityTypes.EntityType> types) {
        List<String> out = new ArrayList<>();
        if (dataset.get("columns") instanceof List<?> cols)
            for (Object o : cols)
                if (o instanceof Map<?, ?> m && m.get("name") instanceof String n
                        && entityType.equals(OnboardMatches.typeOf(m.get("classification") instanceof String s ? s : null, types)))
                    out.add(n);
        return out;
    }

    /**
     * Load the distinct keys of {@code keyCol} (over at most {@code cap} rows) into the temp table {@code cov_ref}:
     * {count, 1 when the cap bit else 0}. A cut read counts one row past the cap.
     */
    static long[] describe(Connection conn, String relation, String keyCol, long cap) throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.execute("CREATE OR REPLACE TEMP TABLE cov_scan AS SELECT " + SqlIdent.q(keyCol) + " AS k FROM (" + relation
                    + ") AS cov_rel LIMIT " + (cap + 1));
            st.execute("CREATE OR REPLACE TEMP TABLE cov_ref AS SELECT DISTINCT TRIM(CAST(k AS VARCHAR)) AS k FROM cov_scan"
                    + " WHERE k IS NOT NULL AND TRIM(CAST(k AS VARCHAR)) <> ''");
            long scanned = count(st, "SELECT count(*) FROM cov_scan");
            long keys = count(st, "SELECT count(*) FROM cov_ref");
            st.execute("DROP TABLE cov_scan");
            return new long[]{keys, scanned > cap ? 1 : 0};
        }
    }

    /**
     * How many {@code cov_ref} keys appear in any of {@code cols} of the relation (over at most {@code cap} rows):
     * {count, 1 when the cap bit else 0}, or null when it cannot be read.
     */
    static long[] appear(Connection conn, String relation, List<String> cols, long cap) {
        try (Statement st = conn.createStatement()) {
            StringBuilder sel = new StringBuilder();
            for (String c : cols) sel.append(sel.isEmpty() ? "" : ", ").append(SqlIdent.q(c));
            st.execute("CREATE OR REPLACE TEMP TABLE cov_ev AS SELECT " + sel + " FROM (" + relation + ") AS cov_rel LIMIT "
                    + (cap + 1));
            StringBuilder values = new StringBuilder();
            for (String c : cols)
                values.append(values.isEmpty() ? "" : " UNION ").append("SELECT TRIM(CAST(").append(SqlIdent.q(c))
                        .append(" AS VARCHAR)) FROM cov_ev");
            long scanned = count(st, "SELECT count(*) FROM cov_ev");
            long found = count(st, "SELECT count(*) FROM cov_ref WHERE k IN (" + values + ")");
            st.execute("DROP TABLE cov_ev");
            return new long[]{found, scanned > cap ? 1 : 0};
        } catch (SQLException unreadable) {
            return null;
        }
    }

    private static long count(Statement st, String sql) throws SQLException {
        try (ResultSet rs = st.executeQuery(sql)) {
            return rs.next() ? rs.getLong(1) : 0;
        }
    }

    private static String text(Object o) {
        return o instanceof String s && !s.isBlank() ? s.trim() : null;
    }
}
