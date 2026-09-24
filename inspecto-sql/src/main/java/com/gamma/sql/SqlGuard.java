package com.gamma.sql;

import com.gamma.config.spec.Finding;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * A lexical/structural allow-list for agent-generated SQL — the first of the SQL sandbox's two layers
 * (M6 / v3.6.0; closes architecture gap G4). It runs <em>before</em> the SQL ever reaches DuckDB,
 * because a query that merely {@code EXPLAIN}s can still evaluate smuggled functions <em>during
 * planning</em> — so {@code EXPLAIN}/{@code LIMIT 0} is not, by itself, a security boundary. This guard
 * is: a candidate must be a <b>single, read-only {@code SELECT}/{@code WITH} statement</b> that touches
 * none of DuckDB's file/extension/system surface.
 *
 * <h3>What it rejects</h3>
 * <ul>
 *   <li><b>Multi-statement</b> — any {@code ;} beyond a single optional trailing one.</li>
 *   <li><b>Not a read-only query</b> — anything not beginning with {@code SELECT} or {@code WITH …
 *       SELECT}; any DDL/DML keyword ({@code CREATE}/{@code INSERT}/{@code UPDATE}/{@code DELETE}/
 *       {@code DROP}/{@code ALTER}/{@code MERGE}/{@code TRUNCATE}/{@code ATTACH}/{@code COPY}/…).</li>
 *   <li><b>File/extension/system functions</b> — {@code read_*}/{@code write_*}/{@code *_scan}/
 *       {@code copy}/{@code getenv}/{@code glob}/{@code sniff_csv}/{@code system}/{@code shell}/
 *       {@code query}/{@code query_table}/{@code read_blob}/{@code read_text}/{@code parquet_metadata}/…
 *       — every DuckDB table function except the path-free generators in {@link #SAFE_TABLE_FUNCTIONS},
 *       bare or double-quoted (see {@link #BLOCKED_FUNCTION}); the {@code install}/
 *       {@code load}/{@code pragma}/{@code set} configuration verbs.</li>
 *   <li><b>Comment-smuggling</b> — line ({@code --}) and block ({@code /* *}{@code /}) comments are
 *       stripped (outside string literals) <em>before</em> the token scan, with block comments removed
 *       to empty so {@code read/*x*}{@code /_csv(…)} cannot hide a blocked token; an unterminated block
 *       comment is itself rejected.</li>
 *   <li><b>Anything DuckDB's own parse tree shows</b> — text the lexical checks pass is then parsed by
 *       DuckDB ({@code json_serialize_sql}) and every relation in the tree is judged wherever it sits
 *       (see {@link #parseTreeViolations}); the lexical checks stay as the first layer.</li>
 * </ul>
 *
 * <p>Returns {@code ERROR}-severity {@link Finding}s (reusing the config-spec model, as
 * {@code ConfigSafetyValidator} does); an empty list means the SQL passed the allow-list. Never throws.
 * Errs toward rejection: a false reject only forces a repair round, a false accept is a security hole.
 *
 * @since 3.6.0
 */
public final class SqlGuard {

    private SqlGuard() {}

    /**
     * Every function call in the text: a name — bare or double-quoted, since DuckDB resolves
     * {@code "read_csv"('x')} exactly like {@code read_csv('x')} — followed by {@code (}. Each name is
     * then tested against {@link #BLOCKED_FUNCTION}.
     */
    private static final Pattern FUNCTION_CALL = Pattern.compile("\"?\\b([A-Za-z_]\\w*)\"?\\s*\\(");

    /**
     * Function names rejected outright (matched against the WHOLE lower-cased name). 🔴 Fail-closed
     * ({@code SQLGUARD-PARQUET-METADATA-1}): every DuckDB <b>table function</b> is refused unless it is in
     * {@link #SAFE_TABLE_FUNCTIONS} — the file readers ({@code read_*}, {@code *_scan}, {@code glob},
     * {@code sniff_csv}), the file INSPECTORS that are not readers ({@code parquet_metadata}/{@code _schema}/
     * {@code _file_metadata}/{@code _kv_metadata}/{@code _full_metadata}/{@code _bloom_probe}, which leak a
     * file's schema, row counts and min/max statistics), the path-exposing catalog functions
     * ({@code duckdb_*}, {@code pragma_*}, {@code which_secret}), the ones that write or mutate
     * ({@code enable_logging}/{@code enable_profiling} take a storage path; {@code force_checkpoint};
     * {@code truncate_duckdb_logs}), SQL-executing ones ({@code query}, {@code query_table},
     * {@code json_execute_serialized_sql}, {@code summary}/{@code histogram}, which run {@code query_table}
     * over a caller-named relation), and the autoloadable extensions' surfaces (ducklake, postgres, sqlite,
     * mysql, odbc, iceberg, delta, azure, aws, excel, spatial, vortex, lance, motherduck, ui, tpch/tpcds).
     * {@code SqlGuardFileFunctionContractTest} derives the table-function list from {@code duckdb_functions()}
     * and fails the build when a DuckDB upgrade adds one that this pattern does not refuse.
     */
    private static final Pattern BLOCKED_FUNCTION = Pattern.compile(
            "read_\\w+|write_\\w+|\\w*_scan|\\w*_scan_\\w+|copy|getenv|glob|sniff_csv|system|shell|exec|eval"
                    + "|query|query_table|summary|histogram\\w*|json_execute_serialized_sql"
                    + "|parquet_\\w+|\\w*duckdb_\\w+|pragma_\\w+|which_secret|test_\\w+_types"
                    + "|\\w*checkpoint|\\w+_logging|\\w+_profiling"
                    + "|\\w+_attach|\\w+_query|\\w+_execute|\\w+_clear_cache|clear_\\w+_cache|pg_clear_cache"
                    + "|load_aws_credentials|(ducklake|postgres|sqlite|mysql|odbc|iceberg|delta|azure|aws|vortex"
                    + "|lance|md|motherduck|ui)_\\w+|st_read\\w*|start_ui|stop_ui_server|dbgen|dsdgen");

    /**
     * The DuckDB table functions a read-only query may call: pure generators over their arguments, with no
     * file, catalog or setting behind them. Package-private for the contract test, which fails when an entry
     * here stops being a table function (a stale exemption) or when {@link #BLOCKED_FUNCTION} refuses one.
     */
    static final java.util.Set<String> SAFE_TABLE_FUNCTIONS = java.util.Set.of(
            "range", "generate_series", "unnest", "repeat", "repeat_row", "json_each", "json_tree",
            "pg_timezone_names", "icu_calendar_names");

    /** Keyword verbs that have no place in a read-only SELECT (matched as whole words, anywhere). */
    private static final Pattern BLOCKED_KEYWORDS = Pattern.compile(
            "\\b(attach|detach|install|load|pragma|set|reset|export|import|copy|call|checkpoint"
                    + "|create|insert|update|delete|drop|alter|merge|truncate|replace|grant|revoke"
                    + "|vacuum|analyze|prepare|execute)\\b",
            Pattern.CASE_INSENSITIVE);

    /**
     * A relation reference — the token after {@code FROM} or {@code JOIN}. DuckDB's REPLACEMENT SCAN turns a
     * string literal, or an identifier that looks like a path, into an implicit file read:
     * {@code SELECT * FROM 'C:/any/file.csv'} reads the file with no {@code read_csv(} token anywhere in the
     * text, so {@link #FUNCTION_CALL} never sees it. 🔴 Found live 2026-09-17: {@code POST /db/query}
     * returned the rows of {@code spaces/demo/audit/jobs_runs.csv} — a file outside every store — to a
     * caller with no capability. The relation position is the only place a path can do harm, so it is
     * the only place this looks.
     */
    private static final Pattern RELATION_REF = Pattern.compile(
            "\\b(from|join)\\s+('[^']*'|\"[^\"]*\"|[^\\s(),;]+)", Pattern.CASE_INSENSITIVE);

    /** An identifier that is really a path or URL: separators, a scheme, a glob, or a data-file suffix. */
    private static final Pattern PATH_LIKE = Pattern.compile(
            "[\\\\/]|://|[*?\\[]|\\.(csv|tsv|txt|parquet|pq|json\\w*|ndjson|xlsx?|gz|zst|bz2|db|duckdb|arrow|ipc|feather|sqlite)$",
            Pattern.CASE_INSENSITIVE);

    /** A cleaned candidate must start here: optional leading {@code (}, then SELECT or WITH. */
    private static final Pattern STARTS_READONLY = Pattern.compile(
            "^\\(*\\s*(select|with)\\b", Pattern.CASE_INSENSITIVE);

    /**
     * Check {@code sql} against the allow-list. Returns every violation as an {@code ERROR}
     * {@link Finding}; an empty list means the SQL is a single read-only query touching no blocked
     * surface. Never throws.
     */
    public static List<Finding> check(String sql) {
        return check(sql, null);
    }

    /**
     * Like {@link #check(String)}, but a relation reference that matches {@code trustedRelation}
     * exactly (case-sensitive, quotes stripped) is exempt from the {@link #PATH_LIKE} rejection even
     * though its name contains path-like characters (e.g. a store id such as
     * {@code "mule_transfers/database"}). Pass the caller's already path-jailed store/table name — the
     * one it is about to {@code CREATE VIEW <trustedRelation> AS …} before running this SQL — never a
     * name taken from the SQL text itself or from unvalidated user input; anything else still runs the
     * literal-file / arbitrary-path check as before. A string literal in the relation position is
     * always rejected regardless — {@code trustedRelation} only widens what a quoted identifier may
     * name, never a file/URL literal.
     */
    public static List<Finding> check(String sql, String trustedRelation) {
        List<Finding> out = new ArrayList<>();
        if (sql == null || sql.isBlank()) {
            out.add(Finding.error("sql", "the SQL query is empty"));
            return out;
        }

        Stripped s = strip(sql);
        if (s.unterminatedComment) {
            out.add(Finding.error("sql", "the SQL contains an unterminated block comment (/* with no */)"));
            return out;
        }
        String cleaned = s.text.trim();
        if (cleaned.isEmpty()) {
            out.add(Finding.error("sql", "the SQL is empty once comments are removed"));
            return out;
        }

        // One statement only: strip a single trailing ';', then any remaining ';' is a second statement.
        String noTrailing = cleaned.endsWith(";")
                ? cleaned.substring(0, cleaned.length() - 1).trim()
                : cleaned;
        if (noTrailing.contains(";")) {
            out.add(Finding.error("sql",
                    "only a single statement is allowed; remove the extra ';'-separated statement(s)"));
        }

        if (!STARTS_READONLY.matcher(noTrailing).find()) {
            out.add(Finding.error("sql",
                    "only a single read-only query is allowed — it must begin with SELECT or WITH"));
        }

        var fm = FUNCTION_CALL.matcher(noTrailing);
        while (fm.find()) {
            String name = fm.group(1).toLowerCase(java.util.Locale.ROOT);
            if (BLOCKED_FUNCTION.matcher(name).matches()) {
                out.add(Finding.error("sql", "the function '" + name
                        + "(...)' is not allowed (file/extension/system access is forbidden in a KPI query)"));
                break;
            }
        }

        var km = BLOCKED_KEYWORDS.matcher(noTrailing);
        if (km.find()) {
            out.add(Finding.error("sql", "the keyword '" + km.group(1).toUpperCase()
                    + "' is not allowed — only a read-only SELECT over the catalog views is permitted"));
        }

        var rm = RELATION_REF.matcher(noTrailing);
        while (rm.find()) {
            String ref = rm.group(2);
            boolean literal = ref.startsWith("'");
            String bare = (literal || ref.startsWith("\"")) ? ref.substring(1, ref.length() - 1) : ref;
            boolean trusted = !literal && trustedRelation != null && trustedRelation.equals(bare);
            if (!trusted && (literal || PATH_LIKE.matcher(bare).find())) {
                out.add(Finding.error("sql", "'" + bare + "' after " + rm.group(1).toUpperCase()
                        + " names a file or URL, not a table — DuckDB would read it directly (replacement scan); "
                        + "only catalog tables and views may be queried"));
                break;
            }
        }

        // Layer two, only for text the lexical layer passed: DuckDB's own parse tree of the ORIGINAL text.
        if (out.isEmpty()) {
            for (String v : parseTreeViolations(sql, trustedRelation)) out.add(Finding.error("sql", v));
        }
        return out;

    }

    /** True iff {@code sql} passes the allow-list with no findings. */
    public static boolean isReadOnly(String sql) {
        return check(sql).isEmpty();
    }

    // ── layer two: DuckDB's own parse tree (SQLGUARD-COMMA-RELATION-1) ─────────────────

    /**
     * The relation kinds a read-only query may use. Anything else — {@code SHOW_REF} ({@code DESCRIBE}/
     * {@code SUMMARIZE}/{@code SHOW}, which name a relation or a file by string) or a kind a later DuckDB
     * adds — is refused.
     */
    private static final java.util.Set<String> SAFE_RELATION_KINDS = java.util.Set.of(
            "BASE_TABLE", "TABLE_FUNCTION", "JOIN", "SUBQUERY", "EMPTY", "EXPRESSION_LIST", "PIVOT");

    /** The query-node kinds a single SELECT statement serializes to. */
    private static final java.util.Set<String> SAFE_QUERY_NODES = java.util.Set.of(
            "SELECT_NODE", "SET_OPERATION_NODE", "RECURSIVE_CTE_NODE", "CTE_NODE");

    /**
     * A {@code BASE_TABLE} name part that is not a plain identifier: a path separator, a drive or scheme
     * colon, a file-suffix dot, or a glob. In the parse tree a string literal and a quoted identifier in
     * the relation position are the same node, and DuckDB turns either into a file read when no catalog
     * relation has that name.
     */
    private static final Pattern TREE_PATH_LIKE = Pattern.compile("[\\\\/.:*?\\[]");

    /** DuckDB's built-in catalog views ({@code duckdb_databases}, {@code duckdb_settings}, …), queryable without parens. */
    private static final Pattern CATALOG_VIEW = Pattern.compile("duckdb_\\w+|pragma_\\w+");

    private static final com.fasterxml.jackson.databind.ObjectMapper JSON =
            new com.fasterxml.jackson.databind.ObjectMapper();

    private static final Object PARSER_LOCK = new Object();
    /** In-memory, extension-free, sealed; used only for {@code json_serialize_sql}. Guarded by {@link #PARSER_LOCK}. */
    private static java.sql.Connection parser;

    /**
     * 🔴 {@code SQLGUARD-COMMA-RELATION-1}: the lexical scan judged only the token after {@code FROM}/
     * {@code JOIN}, so {@code SELECT b.secret FROM (SELECT 1) a, '<file>' b} read the file. Rather than
     * teach a regex where a FROM list starts and ends, ask DuckDB: {@code json_serialize_sql} returns the
     * parser's own tree, and every relation in it — FROM-list items, joins, subqueries, CTE bodies, set-op
     * branches, LATERAL, scalar subqueries — is judged wherever it sits. Refused: any statement DuckDB will
     * not serialize (it serializes SELECT only) or more than one; any relation kind outside
     * {@link #SAFE_RELATION_KINDS}; a {@code BASE_TABLE} whose catalog/schema/name is path-like (unless it is
     * exactly {@code trustedRelation}) or is a {@link #CATALOG_VIEW}; a {@code TABLE_FUNCTION} not in {@link #SAFE_TABLE_FUNCTIONS}; any
     * function call {@link #BLOCKED_FUNCTION} names. A parser failure of any kind refuses (fail closed).
     * Costs ~0.2 ms a call on a warm connection (measured 2026-09-24, DuckDB 1.5.2.1).
     */
    static List<String> parseTreeViolations(String sql, String trustedRelation) {
        String json;
        synchronized (PARSER_LOCK) {
            try {
                if (parser == null) parser = openParser();
                try (var ps = parser.prepareStatement("SELECT json_serialize_sql(?::VARCHAR)")) {
                    ps.setString(1, sql);
                    try (var rs = ps.executeQuery()) {
                        rs.next();
                        json = rs.getString(1);
                    }
                }
            } catch (Exception | LinkageError e) {
                closeParser();
                return List.of("the SQL could not be checked by the DuckDB parser (" + e.getMessage()
                        + ") — refused");
            }
        }
        List<String> out = new ArrayList<>();
        try {
            var root = JSON.readTree(json);
            if (root.path("error").asBoolean(true)) {
                out.add("DuckDB will not accept this as a single read-only SELECT: "
                        + root.path("error_message").asText("unparseable"));
                return out;
            }
            var statements = root.path("statements");
            if (statements.size() != 1) {
                out.add("only a single statement is allowed; DuckDB parsed " + statements.size());
                return out;
            }
            String kind = statements.get(0).path("node").path("type").asText("");
            if (!SAFE_QUERY_NODES.contains(kind)) {
                out.add("a '" + kind + "' statement is not a read-only SELECT");
                return out;
            }
            walk(statements.get(0), trustedRelation, out);
        } catch (Exception e) {
            out.add("the DuckDB parse tree could not be read (" + e.getMessage() + ") — refused");
        }
        return out;
    }

    private static void walk(com.fasterxml.jackson.databind.JsonNode n, String trustedRelation, List<String> out) {
        if (!out.isEmpty()) return;
        if (n.isArray()) {
            for (var c : n) walk(c, trustedRelation, out);
            return;
        }
        if (!n.isObject()) return;
        // A relation (TableRef) is the only node carrying both an alias and a sample clause.
        if (n.has("alias") && n.has("sample")) {
            String kind = n.path("type").asText("");
            if (!SAFE_RELATION_KINDS.contains(kind)) {
                out.add("a '" + kind + "' relation is not allowed — only catalog tables, views and subqueries");
                return;
            }
            if (kind.equals("BASE_TABLE")) {
                String catalog = n.path("catalog_name").asText(""), schema = n.path("schema_name").asText("");
                String table = n.path("table_name").asText("");
                boolean trusted = trustedRelation != null && catalog.isEmpty() && schema.isEmpty()
                        && trustedRelation.equals(table);
                for (String part : List.of(catalog, schema, table))
                    if (!trusted && TREE_PATH_LIKE.matcher(part).find()) {
                        out.add("'" + part + "' names a file or URL, not a table — DuckDB would read it "
                                + "directly (replacement scan); only catalog tables and views may be queried");
                        return;
                    }
                // The catalog VIEWS behind the table functions: FROM duckdb_databases (no parens) lists
                // every attached database's file path, and FUNCTION_CALL only sees a name followed by '('.
                if (CATALOG_VIEW.matcher(table.toLowerCase(java.util.Locale.ROOT)).matches()) {
                    out.add("the catalog view '" + table + "' is not allowed (it exposes paths and settings)");
                    return;
                }
            } else if (kind.equals("TABLE_FUNCTION")) {
                String fn = n.path("function").path("function_name").asText("").toLowerCase(java.util.Locale.ROOT);
                if (!SAFE_TABLE_FUNCTIONS.contains(fn)) {
                    out.add("the table function '" + fn + "(...)' is not allowed (file/extension/system access "
                            + "is forbidden in a KPI query)");
                    return;
                }
            }
        }
        if ("FUNCTION".equals(n.path("class").asText(""))) {
            String fn = n.path("function_name").asText("").toLowerCase(java.util.Locale.ROOT);
            if (BLOCKED_FUNCTION.matcher(fn).matches()) {
                out.add("the function '" + fn + "(...)' is not allowed (file/extension/system access is "
                        + "forbidden in a KPI query)");
                return;
            }
        }
        for (var c : n) walk(c, trustedRelation, out);
    }

    private static java.sql.Connection openParser() throws Exception {
        com.gamma.util.DuckDbUtil.loadDriver();
        var c = java.sql.DriverManager.getConnection("jdbc:duckdb:");
        try {
            SqlSandbox.disableExtensionAutoload(c);
            SqlSandbox.sealAllowing(c, List.of());
            return c;
        } catch (Exception e) {
            c.close();
            throw e;
        }
    }

    private static void closeParser() {
        if (parser == null) return;
        try { parser.close(); } catch (Exception ignored) { /* already broken */ }
        parser = null;
    }

    // ── comment stripping (string-literal aware) ───────────────────────────────────────

    private record Stripped(String text, boolean unterminatedComment) {}

    /**
     * Remove line and block comments, honouring single-quoted string literals (so a literal containing
     * {@code --} or {@code /*} is not mistaken for a comment). Block comments are removed to empty so a
     * comment cannot be used to split a blocked token; line comments become a newline.
     */
    private static Stripped strip(String sql) {
        StringBuilder b = new StringBuilder(sql.length());
        int i = 0, n = sql.length();
        boolean inStr = false;
        while (i < n) {
            char c = sql.charAt(i);
            if (inStr) {
                b.append(c);
                if (c == '\'') {
                    if (i + 1 < n && sql.charAt(i + 1) == '\'') { // escaped '' inside a literal
                        b.append('\'');
                        i += 2;
                        continue;
                    }
                    inStr = false;
                }
                i++;
                continue;
            }
            if (c == '\'') {
                inStr = true;
                b.append(c);
                i++;
            } else if (c == '-' && i + 1 < n && sql.charAt(i + 1) == '-') {
                i += 2;
                while (i < n && sql.charAt(i) != '\n') i++;
                b.append('\n');
            } else if (c == '/' && i + 1 < n && sql.charAt(i + 1) == '*') {
                int end = sql.indexOf("*/", i + 2);
                if (end < 0) return new Stripped(b.toString(), true);
                i = end + 2; // drop the comment entirely (no separator) to defeat token-splitting
            } else {
                b.append(c);
                i++;
            }
        }
        return new Stripped(b.toString(), false);
    }
}
