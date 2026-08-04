package com.gamma.consignment;

import com.gamma.api.PublicApi;
import com.gamma.config.spec.Finding;
import com.gamma.sql.SqlGuard;
import com.gamma.sql.SqlSandbox;
import com.gamma.sql.SqlSandboxPolicy;
import com.gamma.sql.SqlViews;
import com.gamma.util.JdbcRows;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;

/**
 * The {@link ConsignmentReader} implementation: a hardened {@code SqlSandbox} carrying one lazy view per target
 * the Consignment wrote, with every query passed through {@code SqlGuard} first.
 *
 * <p>Relations are built from the §11.3 registry rather than by globbing a directory, which is what makes the
 * read <b>Consignment-scoped</b>: a partition directory holds files from every Consignment that ever wrote that
 * day, so a glob would silently widen the read to other units of work. The registry knows the exact paths.
 */
@PublicApi(since = "5.0.0")
public final class SandboxConsignmentReader implements ConsignmentReader {

    private static final Logger log = LoggerFactory.getLogger(SandboxConsignmentReader.class);

    private final SqlSandbox sandbox;
    private final List<String> relations;

    private SandboxConsignmentReader(SqlSandbox sandbox, List<String> relations) {
        this.sandbox = sandbox;
        this.relations = List.copyOf(relations);
    }

    /**
     * Open a reader over {@code outputs} — normally {@code DbConsignmentOutputStore.outputs(consignmentId)}.
     *
     * <p>Only {@link ConsignmentOutput.State#LIVE} rows become readable: a {@code SUPERSEDED} or
     * {@code COMPACTED_AWAY} file may no longer exist, and §6.3 exists precisely because something else now
     * holds those rows. A {@code LIVE} row whose file is missing is skipped with a warning rather than left to
     * fail every query over its relation — one stale row must not make the whole target unreadable.
     */
    public static ConsignmentReader over(List<ConsignmentOutput> outputs) throws Exception {
        Map<String, List<ConsignmentOutput>> byTable = new LinkedHashMap<>();
        if (outputs != null)
            for (ConsignmentOutput o : outputs) {
                if (o.state() != ConsignmentOutput.State.LIVE) continue;
                if (o.path() == null || o.path().isBlank()) continue;
                if (!Files.isRegularFile(Path.of(o.path()))) {
                    log.warn("consignment-outputs row points at a missing file, skipped: {}", o.path());
                    continue;
                }
                byTable.computeIfAbsent(relationName(o), k -> new ArrayList<>()).add(o);
            }

        SqlSandbox sandbox = SqlSandbox.open(SqlSandboxPolicy.defaultPolicy());
        try {
            // Trusted registration phase: file access is still on, and stays on — see ConsignmentReader's note
            // on why this sandbox is deliberately never sealed.
            try (Statement st = sandbox.statement()) {
                for (Map.Entry<String, List<ConsignmentOutput>> e : byTable.entrySet())
                    st.execute("CREATE VIEW " + quote(e.getKey()) + " AS " + readerSql(e.getValue()));
            }
            return new SandboxConsignmentReader(sandbox, List.copyOf(byTable.keySet()));
        } catch (Exception e) {
            sandbox.close();
            throw e;
        }
    }

    @Override
    public List<Map<String, Object>> query(String sql) throws Exception {
        List<Finding> findings = SqlGuard.check(sql);
        if (!findings.isEmpty()) {
            StringJoiner why = new StringJoiner("; ");
            for (Finding f : findings) why.add(f.message());
            // Refused before DuckDB is touched: a query that merely plans can still evaluate smuggled
            // functions, so the lexical layer has to come first (SqlGuard's own rationale).
            throw new IllegalArgumentException(
                    "a Consignment read must be a single read-only query — " + why);
        }
        try (Statement st = sandbox.statement();
             ResultSet rs = st.executeQuery(stripTrailingSemicolon(sql.trim()))) {
            return JdbcRows.toMaps(rs);
        }
    }

    @Override
    public List<String> relations() {
        return relations;
    }

    @Override
    public void close() {
        sandbox.close();
    }

    // ── helpers ──────────────────────────────────────────────────────────────────

    /** The relation name for an output: its target, falling back to {@code consignment} when unnamed. */
    private static String relationName(ConsignmentOutput o) {
        return (o.tableName() == null || o.tableName().isBlank()) ? "consignment" : o.tableName();
    }

    /**
     * One relation over several files. {@code UNION ALL BY NAME} rather than positional {@code UNION ALL}: a
     * target's files may gain columns over time (the same reason {@link SqlViews#reader} passes
     * {@code union_by_name}), and aligning by position would mis-stack them.
     */
    private static String readerSql(List<ConsignmentOutput> outs) {
        StringJoiner sj = new StringJoiner(" UNION ALL BY NAME ");
        for (ConsignmentOutput o : outs)
            sj.add("SELECT * FROM " + SqlViews.reader(format(o.path()), o.path(), true));
        return sj.toString();
    }

    /** Format from the revealed file's extension — the registry stores the path, not the format. */
    private static String format(String path) {
        return path.toLowerCase(java.util.Locale.ROOT).endsWith(".parquet") ? "PARQUET" : "CSV";
    }

    private static String quote(String identifier) {
        return "\"" + identifier.replace("\"", "\"\"") + "\"";
    }

    private static String stripTrailingSemicolon(String sql) {
        return sql.endsWith(";") ? sql.substring(0, sql.length() - 1).trim() : sql;
    }
}
