package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.gamma.sql.SqlSandbox;
import com.gamma.sql.SqlSandboxPolicy;

import java.io.IOException;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * The READ-ONLY structure of author SQL — DuckDB's own {@code json_serialize_sql} parse tree, for the
 * structured predicate view on a Filter Step (AUTHORING-REDESIGN-1 (c); design
 * {@code docs/archived-documents/plans-archive/authoring-ast-table-design.md}).
 *
 * <p>Four operator decisions (2026-09-23) shape this class, and each is load-bearing:
 * <ul>
 *   <li><b>Read-only, for good (Q2).</b> There is no {@code json_deserialize_sql} here and there never
 *       will be: the round trip drops comments and re-spells the whole statement (design T1), and
 *       an author's text must survive. ⛔ Do not add an AST→SQL method "for symmetry".</li>
 *   <li><b>No lexical guard (Q3).</b> Nothing binds and nothing executes — {@code json_serialize_sql}
 *       parses only, so even {@code read_csv('<a real file>')} is never opened — and the function itself
 *       refuses every non-SELECT (T5). {@code SqlGuard} would false-reject legal predicates naming
 *       {@code set}/{@code replace}. ⛔ This is NOT a precedent for the sibling
 *       {@code /components/transform/describe}: that route BINDS, and keeps its guard.</li>
 *   <li><b>A sealed {@link SqlSandbox} (Q4)</b> — the connection the Step 0 pins in
 *       {@code SqlSandboxTest} were measured on, so production runs what the pins prove. Accepted cost:
 *       a temp DB file per call.</li>
 *   <li><b>Verbatim (design §4).</b> The tree is passed through as DuckDB emits it — never reshaped into
 *       a house model (that is a second parser by another name) and never slimmed with
 *       {@code skip_null}/{@code skip_empty} (T3). Only the keys the SPA reads are a contract (Q5,
 *       {@code SqlAstContractTest}).</li>
 * </ul>
 *
 * <p>A parse failure is DATA, not an exception (T6): it comes back as {@link Result#ok()} {@code false}
 * with DuckDB's message and position.
 */
final class SqlAst {

    /** What the caller hands in: a whole SELECT, or a bare row predicate ({@code transform.filter.where}). */
    enum Fragment { STATEMENT, PREDICATE }

    /**
     * T7: a bare predicate is not a statement, so it is parsed inside this wrapper — the same relation
     * name the engine's filter reads — and its {@code where_clause} read back out.
     */
    static final String PREDICATE_PREFIX = "SELECT 1 FROM input WHERE ";

    /** The wrapper with a placeholder predicate: every legal predicate's tree must match it off the WHERE. */
    private static final String SKELETON = PREDICATE_PREFIX + "TRUE";

    /** Returned as the subtype when a "predicate" carries clauses of its own (ORDER BY, UNION, …). */
    static final String NOT_A_PREDICATE = "NOT_A_PREDICATE";

    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * {@code ok} with the tree, or not-{@code ok} with DuckDB's message, the position (into the text the
     * CALLER sent, never into the hidden wrapper), and DuckDB's subtype.
     */
    record Result(boolean ok, JsonNode ast, String message, Integer position, String subtype) {
        static Result error(String message, Integer position, String subtype) {
            return new Result(false, null, message, position, subtype);
        }
    }

    private SqlAst() {}

    static Result parse(String sql, Fragment fragment) throws SQLException, IOException {
        try (SqlSandbox sb = SqlSandbox.open(SqlSandboxPolicy.defaultPolicy())) {
            sb.seal();
            if (fragment == Fragment.STATEMENT) {
                JsonNode tree = serialize(sb, sql);
                return isError(tree) ? error(tree, 0) : new Result(true, tree, null, null, null);
            }
            JsonNode tree = serialize(sb, PREDICATE_PREFIX + sql);
            if (isError(tree)) return error(tree, PREDICATE_PREFIX.length());
            JsonNode statements = tree.path("statements");
            JsonNode node = statements.path(0).path("node");
            JsonNode where = node.path("where_clause");
            if (statements.size() != 1 || !where.isObject()
                    || !withoutWhere(node).equals(withoutWhere(serialize(sb, SKELETON).path("statements").path(0).path("node")))) {
                return Result.error("this is not a single row condition — it carries clauses of its own "
                        + "(such as ORDER BY, GROUP BY, LIMIT or UNION)", null, NOT_A_PREDICATE);
            }
            return new Result(true, where, null, null, null);
        }
    }

    /**
     * T2: the bind parameter needs an explicit {@code ::VARCHAR} — a bare {@code ?} fails at prepare, and
     * the "fix" that makes it pass is string concatenation of author SQL. Never concatenate.
     */
    private static JsonNode serialize(SqlSandbox sb, String sql) throws SQLException, IOException {
        try (PreparedStatement ps = sb.preparedStatement("SELECT json_serialize_sql(?::VARCHAR)")) {
            ps.setString(1, sql);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return JSON.readTree(rs.getString(1));
            }
        }
    }

    private static boolean isError(JsonNode tree) {
        return tree.path("error").asBoolean(false);
    }

    private static Result error(JsonNode tree, int wrapperLength) {
        Integer position = null;
        JsonNode p = tree.get("position");
        if (p != null && !p.isNull()) {
            try {
                position = Math.max(0, Integer.parseInt(p.asText()) - wrapperLength);
            } catch (NumberFormatException ignored) {
                // no usable position — the message still stands on its own
            }
        }
        String subtype = tree.hasNonNull("error_subtype") ? tree.get("error_subtype").asText()
                : tree.path("error_type").asText(null);
        return Result.error(tree.path("error_message").asText("the SQL could not be read"), position, subtype);
    }

    /** The SELECT node with its WHERE removed and every source offset stripped — the shape to compare. */
    private static JsonNode withoutWhere(JsonNode node) {
        JsonNode copy = node.deepCopy();
        if (copy instanceof ObjectNode o) o.remove("where_clause");
        stripLocations(copy);
        return copy;
    }

    private static void stripLocations(JsonNode n) {
        if (n instanceof ObjectNode o) o.remove("query_location");
        for (JsonNode child : n) stripLocations(child);   // an object iterates its values, an array its items
    }
}
