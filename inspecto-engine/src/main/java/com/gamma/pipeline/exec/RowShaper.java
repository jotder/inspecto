package com.gamma.pipeline.exec;

import com.gamma.api.PublicApi;
import com.gamma.etl.DataTransformer;
import com.gamma.etl.PipelineConfig;
import com.gamma.pipeline.BuiltinNodeType;
import com.gamma.pipeline.PipelineNode;
import com.gamma.pipeline.PipelineRel;
import com.gamma.query.MeasureCompiler;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * <b>T10 — row-shaping SQL assembly.</b> Executes one flow {@code transform.*} node as SQL over a DuckDB
 * input relation, producing one or more <b>named output relations</b> (the multi-named-relation node-output
 * contract T9 made enforceable). Each output relation is materialised as a DuckDB table named
 * {@code <outPrefix>__<relkey>} and returned as a {@link Relation} ({@code rel} = the {@link PipelineRel}
 * the edge carries, {@code table} = the DuckDB table).
 *
 * <p>This is the thing the legacy {@link com.gamma.etl.DataTransformer} could not do: it emitted exactly
 * one {@code SELECT … FROM <one source>} into one table (column-scalar only). Here each operator can add
 * a {@code WHERE} (filter / validate), a {@code CASE}/per-branch predicate (route), {@code QUALIFY}
 * (dedup), {@code UNNEST} (split) or a multi-input join/union (merge), and split a batch across several
 * named relations. Per-column expressions still reuse {@link com.gamma.etl.TransformCompiler}'s trust model
 * (author-owned scalar SQL emitted verbatim).
 *
 * <h3>Authored config contracts (node {@code config})</h3>
 * <ul>
 *   <li>{@code transform.filter} — {@code where}: bool SQL → {@code data} (kept) + {@code dropped}.</li>
 *   <li>{@code transform.validate} — {@code rule}: bool SQL → {@code data} (valid) + {@code invalid}.</li>
 *   <li>{@code transform.route} — {@code mode}: {@code case}|{@code clone} (default {@code case});
 *       {@code branches}: [{@code {key, where}}]; optional {@code default} key → one {@code route:<key>}
 *       relation per branch.</li>
 *   <li>{@code transform.dedup[.*]} — {@code keys}: [col]; optional {@code order_by} (SQL) →
 *       {@code data} (first per key) + {@code duplicate}.</li>
 *   <li>{@code transform.split} — {@code column}: list/array col; optional {@code as} → {@code data}.</li>
 *   <li>{@code transform.join} — {@code reference}: Reference Dataset name; {@code on}: key column(s)
 *       (scalar or list — the two lowering paths disagree, both are accepted) → {@code data} (LEFT JOIN:
 *       every input row survives, unmatched keys carry NULLs). Needs a {@link ReferenceResolver} —
 *       the 4-arg {@link #shape} refuses it (see {@link ReferenceResolver#NONE}).</li>
 *   <li>{@code transform.summarize} — {@code group_by}: [col] (optional); {@code measures}: shorthand
 *       ({@code count} | {@code agg(field)}, compiled by {@link MeasureCompiler}) → {@code data}
 *       (the rollup).</li>
 *   <li>{@code transform.map}/{@code select}/{@code derive} — {@code columns}: map/select take
 *       [{@code {name, expr}}] / [name]; derive adds [{@code {name, expr}}] to the input columns →
 *       {@code data}.</li>
 *   <li>{@code transform.merge} — {@code type}: {@code union}|{@code inner}|{@code left} (default
 *       {@code union}); {@code on}: [col] for joins → {@code data} (see {@link #merge}).</li>
 * </ul>
 *
 * <p>NULL-safe partitioning: a predicate that evaluates to {@code NULL} sends the row to the negative
 * side ({@code dropped}/{@code invalid}) — i.e. {@code data} keeps {@code COALESCE(pred, FALSE)} only.
 */
@PublicApi(since = "4.3.0")
public final class RowShaper {

    private RowShaper() {}

    /**
     * <b>Every node-config key this class reads on a {@code transform.map} node</b> — the executable
     * vocabulary, as opposed to what the map dialog can type. {@code PipelineEditable} splits it into
     * authored ({@code columns}, {@code rules} — lowered to {@code processing.map}) and derived
     * ({@code schema}, {@code csv} — never lowered), and {@code MapNodeKeyContractTest} pins this
     * constant against both that split and the {@code node.cfg("…")} reads in the map-path methods
     * below. ⚠ A key that becomes executable here without joining the lowering allow-list is silently
     * dropped on save — the failure this constant exists to make impossible.
     */
    public static final Set<String> MAP_NODE_CONFIG_KEYS = Set.of("columns", "rules", "schema", "csv");

    /** A produced relation: the {@link PipelineRel} an outbound edge carries + the DuckDB table holding it. */
    public record Relation(String rel, String table) {}

    /**
     * <b>The reference seam.</b> Resolves a Reference Dataset name to a DuckDB relation (table/view name)
     * readable on the given connection — the one thing {@code transform.join} needs that the rest of the
     * shaper does not: reach outside the batch. Same functional-interface seam shape as
     * {@link PipelineExecutor.SinkWriter}/{@code ProvenanceCollector}; the caller that owns reference
     * context (dry-run sampling a reference, a future production route) supplies one — everything else
     * gets {@link #NONE}, which refuses rather than resolving wrongly.
     */
    @FunctionalInterface
    public interface ReferenceResolver {
        String resolve(Connection conn, String reference) throws SQLException;

        /** The no-context default: any resolution attempt refuses with the reference it could not reach. */
        ReferenceResolver NONE = (conn, reference) -> {
            throw new IllegalStateException("no ReferenceResolver supplied — cannot resolve reference '"
                    + reference + "' for transform.join in this execution context");
        };
    }

    /**
     * Shape a single-input {@code transform.*} node over {@code input}, creating its output tables under
     * {@code outPrefix}. {@code transform.merge} is multi-input — call {@link #merge} instead. This overload
     * carries no reference context, so a {@code transform.join} node refuses
     * ({@link ReferenceResolver#NONE}).
     */
    public static List<Relation> shape(Connection conn, PipelineNode node, String input, String outPrefix)
            throws SQLException {
        return shape(conn, node, input, outPrefix, ReferenceResolver.NONE);
    }

    /** As {@link #shape(Connection, PipelineNode, String, String)}, with reference context for {@code transform.join}. */
    public static List<Relation> shape(Connection conn, PipelineNode node, String input, String outPrefix,
                                       ReferenceResolver references) throws SQLException {
        String type = node.type();
        if (BuiltinNodeType.TRANSFORM_JOIN.type().equals(type))     return join(conn, node, input, outPrefix, references);
        if (BuiltinNodeType.TRANSFORM_FILTER.type().equals(type))   return filter(conn, node, input, outPrefix);
        if (BuiltinNodeType.TRANSFORM_VALIDATE.type().equals(type)) return validate(conn, node, input, outPrefix);
        if (BuiltinNodeType.TRANSFORM_ROUTE.type().equals(type))    return route(conn, node, input, outPrefix);
        if (type.startsWith("transform.dedup"))                      return dedup(conn, node, input, outPrefix);
        if (BuiltinNodeType.TRANSFORM_SPLIT.type().equals(type))    return split(conn, node, input, outPrefix);
        if (BuiltinNodeType.TRANSFORM_SUMMARIZE.type().equals(type)) return summarize(conn, node, input, outPrefix);
        if (BuiltinNodeType.TRANSFORM_MAP.type().equals(type)
                || BuiltinNodeType.TRANSFORM_SELECT.type().equals(type)
                || BuiltinNodeType.TRANSFORM_DERIVE.type().equals(type)) return project(conn, node, input, outPrefix);
        throw new IllegalArgumentException("RowShaper cannot shape node type '" + type + "' (id=" + node.id() + ")");
    }

    // ── filter / validate (predicate split) ────────────────────────────────────

    private static List<Relation> filter(Connection conn, PipelineNode node, String input, String p) throws SQLException {
        return predicateSplit(conn, input, p, str(node, "where"), PipelineRel.DATA, PipelineRel.DROPPED);
    }

    private static List<Relation> validate(Connection conn, PipelineNode node, String input, String p) throws SQLException {
        return predicateSplit(conn, input, p, str(node, "rule"), PipelineRel.DATA, PipelineRel.INVALID);
    }

    /** Split {@code input} on a boolean {@code pred}: keep-side gets {@code COALESCE(pred,FALSE)}, the rest go negative. */
    private static List<Relation> predicateSplit(Connection conn, String input, String prefix,
                                                 String pred, String keepRel, String dropRel) throws SQLException {
        requireExpr(pred, "predicate");
        String keep = table(prefix, keepRel);
        String drop = table(prefix, dropRel);
        exec(conn, "CREATE TABLE " + q(keep) + " AS SELECT * FROM " + q(input) + " WHERE COALESCE((" + pred + "), FALSE)");
        exec(conn, "CREATE TABLE " + q(drop) + " AS SELECT * FROM " + q(input) + " WHERE NOT COALESCE((" + pred + "), FALSE)");
        return List.of(new Relation(keepRel, keep), new Relation(dropRel, drop));
    }

    // ── route (content-based branching) ─────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private static List<Relation> route(Connection conn, PipelineNode node, String input, String prefix) throws SQLException {
        Object raw = node.cfg("branches");
        if (!(raw instanceof List<?> branchList) || branchList.isEmpty())
            throw new IllegalArgumentException("transform.route node '" + node.id() + "' needs a non-empty 'branches' list");
        boolean clone = "clone".equalsIgnoreCase(str(node, "mode"));   // default = case (exclusive)
        String defaultKey = strOrNull(node, "default");

        List<Map<String, Object>> branches = new ArrayList<>();
        for (Object b : branchList) branches.add((Map<String, Object>) b);

        List<Relation> out = new ArrayList<>();
        if (clone) {
            // independent: a row may leave on several branches
            for (Map<String, Object> b : branches) {
                String key = reqStr(b, "key", node.id());
                String where = reqStr(b, "where", node.id());
                String tbl = table(prefix, PipelineRel.route(key));
                exec(conn, "CREATE TABLE " + q(tbl) + " AS SELECT * FROM " + q(input)
                        + " WHERE COALESCE((" + where + "), FALSE)");
                out.add(new Relation(PipelineRel.route(key), tbl));
            }
            return out;
        }
        // case (exclusive, first-match-wins): label each row, then split by label
        StringBuilder cse = new StringBuilder("CASE");
        for (Map<String, Object> b : branches) {
            String key = reqStr(b, "key", node.id());
            String where = reqStr(b, "where", node.id());
            cse.append(" WHEN COALESCE((").append(where).append("), FALSE) THEN ").append(sqlStr(key));
        }
        cse.append(" ELSE ").append(defaultKey == null ? "NULL" : sqlStr(defaultKey)).append(" END");
        String labelled = table(prefix, "labelled");
        exec(conn, "CREATE TABLE " + q(labelled) + " AS SELECT *, (" + cse + ") AS __route FROM " + q(input));

        List<String> emitted = new ArrayList<>();
        for (Map<String, Object> b : branches) emitted.add(reqStr(b, "key", node.id()));
        if (defaultKey != null && !emitted.contains(defaultKey)) emitted.add(defaultKey);
        for (String key : emitted) {
            String tbl = table(prefix, PipelineRel.route(key));
            exec(conn, "CREATE TABLE " + q(tbl) + " AS SELECT * EXCLUDE(__route) FROM " + q(labelled)
                    + " WHERE __route = " + sqlStr(key));
            out.add(new Relation(PipelineRel.route(key), tbl));
        }
        return out;
    }

    // ── dedup (QUALIFY) ──────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private static List<Relation> dedup(Connection conn, PipelineNode node, String input, String prefix) throws SQLException {
        Object keysRaw = node.cfg("keys");
        if (!(keysRaw instanceof List<?> keyList) || keyList.isEmpty())
            throw new IllegalArgumentException("transform.dedup node '" + node.id() + "' needs a non-empty 'keys' list");
        String partition = String.join(", ", ((List<Object>) keysRaw).stream().map(k -> q(k.toString())).toList());
        String order = strOrNull(node, "order_by");
        String window = "ROW_NUMBER() OVER (PARTITION BY " + partition
                + (order == null ? "" : " ORDER BY " + order) + ")";
        String data = table(prefix, PipelineRel.DATA);
        String dup  = table(prefix, PipelineRel.DUPLICATE);
        // QUALIFY needs the window in the predicate; compute rn once in a subquery so both sides agree.
        String ranked = "(SELECT *, " + window + " AS __rn FROM " + q(input) + ")";
        exec(conn, "CREATE TABLE " + q(data) + " AS SELECT * EXCLUDE(__rn) FROM " + ranked + " WHERE __rn = 1");
        exec(conn, "CREATE TABLE " + q(dup)  + " AS SELECT * EXCLUDE(__rn) FROM " + ranked + " WHERE __rn > 1");
        return List.of(new Relation(PipelineRel.DATA, data), new Relation(PipelineRel.DUPLICATE, dup));
    }

    // ── summarize (group-by rollup) ───────────────────────────────────────────────

    /**
     * {@code transform.summarize} — {@code group_by}: [col] (optional); {@code measures}: shorthand
     * strings in {@code MaterializeTask.compileSpec}'s documented grammar ({@code count} |
     * {@code agg(field)}) → one aggregated {@code data} relation. The shorthand is split exactly as
     * MaterializeTask splits it and everything downstream is {@link MeasureCompiler} — validated
     * identifiers, the shared aggregation set, the stable {@code agg_field} result-column ids — so
     * a summarize node, a materialize job and a BI query all speak one measure grammar.
     *
     * <p>The compiled SELECT carries MeasureCompiler's mandatory LIMIT, passed as
     * {@link Integer#MAX_VALUE} — a batch's group count is bounded by its row count, so the cap
     * never binds; it is the compiler's shape, not a sampling decision.
     */
    private static List<Relation> summarize(Connection conn, PipelineNode node, String input, String prefix)
            throws SQLException {
        Object measuresRaw = node.cfg("measures");
        if (!(measuresRaw instanceof List<?> measureList) || measureList.isEmpty())
            throw new IllegalArgumentException("transform.summarize node '" + node.id()
                    + "' needs a non-empty 'measures' list");
        List<Map<String, Object>> measures = new ArrayList<>();
        for (Object o : measureList) {
            String m = o.toString().trim();
            if ("count".equals(m)) { measures.add(Map.of("agg", "count")); continue; }
            int p = m.indexOf('(');
            if (p < 0 || !m.endsWith(")"))
                throw new IllegalArgumentException("node '" + node.id()
                        + "': measure must be count or agg(field), got '" + m + "'");
            measures.add(Map.of("agg", m.substring(0, p), "field", m.substring(p + 1, m.length() - 1)));
        }
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("dataset", input);
        body.put("measures", measures);
        if (node.cfg("group_by") instanceof List<?> groupBy && !groupBy.isEmpty()) body.put("groupBy", groupBy);
        body.put("limit", Integer.MAX_VALUE);
        String select = MeasureCompiler.compile(MeasureCompiler.parse(body, Integer.MAX_VALUE, Integer.MAX_VALUE));
        String data = table(prefix, PipelineRel.DATA);
        exec(conn, "CREATE TABLE " + q(data) + " AS " + select);
        return List.of(new Relation(PipelineRel.DATA, data));
    }

    // ── join (reference LEFT JOIN through the resolver seam) ─────────────────────

    /**
     * {@code transform.join} — {@code reference}: the Reference Dataset name, resolved to a relation via
     * the {@link ReferenceResolver} seam; {@code on}: the key column(s). {@code on} arrives as a scalar
     * string from {@code PipelineLift}/the flat file but as a list from {@code RecipeCompiler} — both
     * spellings are accepted.
     *
     * <p>Always a <b>LEFT JOIN</b>: the node emits only {@code data} (no reject relation), so an inner
     * join would silently drop unmatched rows — the exact silent-discard shape the multiplicity work
     * removes. Unmatched keys carry NULL reference columns instead; {@code USING} folds the key columns
     * once. A non-key column name shared by both sides fails the {@code CREATE TABLE} loudly rather than
     * writing an ambiguous result.
     */
    private static List<Relation> join(Connection conn, PipelineNode node, String input, String prefix,
                                       ReferenceResolver references) throws SQLException {
        String reference = strOrNull(node, "reference");
        if (reference == null)
            throw new IllegalArgumentException("transform.join node '" + node.id() + "' needs a 'reference'");
        List<String> on = new ArrayList<>();
        if (node.cfg("on") instanceof List<?> list) {
            for (Object o : list) if (o != null && !o.toString().isBlank()) on.add(o.toString());
        } else {
            String scalar = strOrNull(node, "on");
            if (scalar != null) on.add(scalar);
        }
        if (on.isEmpty())
            throw new IllegalArgumentException("transform.join node '" + node.id() + "' needs 'on' key column(s)");
        String ref = references.resolve(conn, reference);
        String using = "USING (" + String.join(", ", on.stream().map(RowShaper::q).toList()) + ")";
        String data = table(prefix, PipelineRel.DATA);
        exec(conn, "CREATE TABLE " + q(data) + " AS SELECT * FROM " + q(input)
                + " LEFT JOIN " + q(ref) + " " + using);
        return List.of(new Relation(PipelineRel.DATA, data));
    }

    // ── split (UNNEST) ────────────────────────────────────────────────────────────

    private static List<Relation> split(Connection conn, PipelineNode node, String input, String prefix) throws SQLException {
        String col = str(node, "column");
        requireExpr(col, "column");
        String as = strOrNull(node, "as");
        if (as == null) as = col;
        String data = table(prefix, PipelineRel.DATA);
        exec(conn, "CREATE TABLE " + q(data) + " AS SELECT * EXCLUDE(" + q(col) + "), UNNEST(" + q(col)
                + ") AS " + q(as) + " FROM " + q(input));
        return List.of(new Relation(PipelineRel.DATA, data));
    }

    // ── projection (map / select / derive) ─────────────────────────────────────────

    private static List<Relation> project(Connection conn, PipelineNode node, String input, String prefix) throws SQLException {
        String data = table(prefix, PipelineRel.DATA);
        exec(conn, "CREATE TABLE " + q(data) + " AS " + projectionSelect(node, input));
        return List.of(new Relation(PipelineRel.DATA, data));
    }

    /** The {@code SELECT … FROM <input>} for a projection node over the table {@code input} (reused by {@link #fuse}). */
    private static String projectionSelect(PipelineNode node, String input) {
        return projectionSelectFrom(node, q(input), input);
    }

    /**
     * As {@link #projectionSelect}, but over a pre-rendered FROM target (a quoted table, or a
     * {@code (subquery) AS _t}). {@code sourceTable} is that target's bare identifier — derived mapping
     * expressions qualify their column references with it, so it must match what {@code fromTarget} reads.
     */
    @SuppressWarnings("unchecked")
    private static String projectionSelectFrom(PipelineNode node, String fromTarget, String sourceTable) {
        String type = node.type();
        List<?> cols = columnsOf(node, sourceTable);
        if (cols == null || cols.isEmpty())
            throw new IllegalArgumentException(type + " node '" + node.id() + "' needs a non-empty 'columns' list");
        StringBuilder sel = new StringBuilder("SELECT ");
        if (BuiltinNodeType.TRANSFORM_SELECT.type().equals(type)) {           // narrow to named columns
            List<String> names = new ArrayList<>();
            for (Object c : cols) names.add(q(c.toString()));
            sel.append(String.join(", ", names));
        } else {
            boolean derive = BuiltinNodeType.TRANSFORM_DERIVE.type().equals(type);
            if (derive) sel.append("*, ");                                    // derive keeps input columns
            List<String> exprs = new ArrayList<>();
            for (Object c : cols) {
                Map<String, Object> m = (Map<String, Object>) c;
                String name = reqStr(m, "name", node.id());
                String expr = reqStr(m, "expr", node.id());
                exprs.add("(" + expr + ") AS " + q(name));
            }
            sel.append(String.join(", ", exprs));
        }
        return sel.append(" FROM ").append(fromTarget).toString();
    }

    /**
     * A projection node's {@code columns}, or — for a {@code transform.map} lifted from a legacy config,
     * which carries the schema itself rather than authored columns ({@code PipelineLift} keeps legacy
     * sub-records verbatim) — the schema's mapping rules compiled to {@code [{name, expr}]} by
     * {@link DataTransformer#dataColumns}, the same authority the legacy engine's own SELECT uses.
     *
     * <p>Returns {@code null} when neither is available, leaving the caller to raise its own error.
     */
    private static List<?> columnsOf(PipelineNode node, String sourceTable) {
        if (node.cfg("columns") instanceof List<?> authored && !authored.isEmpty()) return authored;
        if (!BuiltinNodeType.TRANSFORM_MAP.type().equals(node.type())) return null;
        Map<String, Object> schema = mappingSchemaOf(node);
        if (schema == null) return null;
        return DataTransformer.dataColumns(schema, csvSettingsOf(node), sourceTable);
    }

    /**
     * The {@code csv} settings to compile this node's rules with — DATE/TIMESTAMP sources parse with the
     * pipeline's configured format lists, and nothing else off {@code csv} is read.
     *
     * <p>Three shapes reach this, because a graph arrives by two routes. Lifted in-process, it carries the
     * real record ({@code PipelineLift} puts it on the parser node, {@code PipelineDryRun} moves it within
     * the map node's reach). Decoded from JSON — a dry-run candidate body, or the same graph round-tripped
     * through {@code GET /pipelines/{name}/graph/raw} — {@code PipelineCodec} keeps config verbatim, so the
     * identical block is a plain map and the formats are rebuilt from it. Absent altogether, the lists are
     * empty, which compiles a typed column to a plain {@code TRY_CAST} rather than failing the whole run:
     * a {@code mapping} component declares no field types, so its rules never consult a format list.
     */
    private static PipelineConfig.CsvSettings csvSettingsOf(PipelineNode node) {
        Object csv = node.cfg("csv");
        if (csv instanceof PipelineConfig.CsvSettings settings) return settings;
        if (csv instanceof Map<?, ?> m)
            return PipelineConfig.CsvSettings.ofFormats(
                    formatList(m.get("dateFormats")), formatList(m.get("tsFormats")));
        return PipelineConfig.CsvSettings.ofFormats(List.of(), List.of());
    }

    /** One decoded format list — empty for anything that is not a list, so a malformed block degrades. */
    private static List<String> formatList(Object value) {
        if (!(value instanceof List<?> list)) return List.of();
        List<String> out = new ArrayList<>();
        for (Object o : list) if (o != null) out.add(o.toString());
        return out;
    }

    /**
     * The {@code {raw.fields, mapping.rules}} map to compile a map node's projection from: a legacy
     * {@code schema} carried verbatim by a lifted config, or — for a node whose rules come from a
     * {@code mapping} component, whose content is {@code {name, rules}} — those rules with no declared field
     * types, which is honest: a mapping component carries none, so every {@code DIRECT} rule is a plain
     * reference. A node with both prefers the schema, the richer of the two.
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> mappingSchemaOf(PipelineNode node) {
        if (node.cfg("schema") instanceof Map<?, ?> s
                && s.get("mapping") instanceof Map<?, ?> m
                && m.get("rules") instanceof List<?> schemaRules && !schemaRules.isEmpty())
            return (Map<String, Object>) s;
        if (node.cfg("rules") instanceof List<?> rules && !rules.isEmpty())
            return Map.of("raw", Map.of("fields", List.of()), "mapping", Map.of("rules", rules));
        return null;
    }

    /**
     * <b>T32 follow-up — compile a SIMPLE node to a single {@code SELECT} over {@code innerSql}</b> (a subquery),
     * used to capture a {@code sink.view}'s {@code derived_sql} along a linear path. Handles
     * {@code filter}/{@code map}/{@code select}/{@code derive} (each a single-relation, single-SELECT op);
     * returns {@link Optional#empty()} for anything else (route/split/dedup/merge/validate produce multiple
     * relations or non-SELECT shapes) or a malformed node — so the caller leaves {@code derived_sql} null rather
     * than emit wrong SQL. Mirrors the SQL {@link #shape} would run for these node types.
     */
    public static Optional<String> toSelect(PipelineNode node, String innerSql) {
        String type = node.type();
        String from = "(" + innerSql + ") AS _t";
        if (BuiltinNodeType.TRANSFORM_FILTER.type().equals(type)) {
            Object w = node.cfg("where");
            if (w == null || w.toString().isBlank()) return Optional.empty();
            return Optional.of("SELECT * FROM " + from + " WHERE COALESCE((" + w + "), FALSE)");
        }
        if (BuiltinNodeType.TRANSFORM_MAP.type().equals(type)
                || BuiltinNodeType.TRANSFORM_SELECT.type().equals(type)
                || BuiltinNodeType.TRANSFORM_DERIVE.type().equals(type)) {
            try {
                return Optional.of(projectionSelectFrom(node, from, "_t"));
            } catch (RuntimeException e) {
                return Optional.empty();
            }
        }
        return Optional.empty();
    }

    /**
     * <b>Chain-fusion (T10).</b> Fuse a linear run of projection ({@code map}/{@code select}/{@code derive})
     * and {@code filter} nodes into a <b>single</b> {@code SELECT … WHERE …} pass over {@code input},
     * avoiding an intermediate table per node. Safe for the common shape (a projection plus filters whose
     * predicates and expressions reference the chain's <em>input</em> columns); the executor falls back to
     * per-node {@link #shape} for anything that interdepends. Emits one {@code data} relation.
     */
    public static Relation fuse(Connection conn, List<PipelineNode> chain, String input, String outPrefix)
            throws SQLException {
        if (chain.isEmpty()) throw new IllegalArgumentException("fuse needs at least one node");
        String projection = "SELECT * FROM " + q(input);
        List<String> wheres = new ArrayList<>();
        for (PipelineNode n : chain) {
            if (BuiltinNodeType.TRANSFORM_FILTER.type().equals(n.type())) {
                String w = str(n, "where");
                requireExpr(w, "predicate");
                wheres.add("COALESCE((" + w + "), FALSE)");
            } else {
                projection = projectionSelect(n, input);   // last projection wins (input-referencing)
            }
        }
        String data = table(outPrefix, PipelineRel.DATA);
        String sql = "CREATE TABLE " + q(data) + " AS " + projection;
        if (!wheres.isEmpty()) sql += (projection.toUpperCase().contains(" WHERE ") ? " AND " : " WHERE ")
                + String.join(" AND ", wheres);
        exec(conn, sql);
        return new Relation(PipelineRel.DATA, data);
    }

    // ── merge (multi-input join / union) ───────────────────────────────────────────

    /**
     * Merge several input relations into one {@code data} relation. {@code type=union} → {@code UNION ALL BY
     * NAME} (column-name aligned); {@code type=inner|left} → an {@code N}-way join on the {@code on} columns
     * (a node with fan-in {@code data} edges).
     */
    @SuppressWarnings("unchecked")
    public static List<Relation> merge(Connection conn, PipelineNode node, List<String> inputs, String outPrefix)
            throws SQLException {
        if (inputs.size() < 2) throw new IllegalArgumentException("transform.merge node '" + node.id()
                + "' needs >= 2 inputs, got " + inputs.size());
        String type = strOrNull(node, "type");
        String data = table(outPrefix, PipelineRel.DATA);
        String sql;
        if (type == null || "union".equalsIgnoreCase(type)) {
            List<String> parts = new ArrayList<>();
            for (String in : inputs) parts.add("SELECT * FROM " + q(in));
            sql = String.join(" UNION ALL BY NAME ", parts);
        } else {
            Object onRaw = node.cfg("on");
            if (!(onRaw instanceof List<?> onList) || onList.isEmpty())
                throw new IllegalArgumentException("transform.merge join on node '" + node.id() + "' needs an 'on' column list");
            String using = "USING (" + String.join(", ", ((List<Object>) onRaw).stream().map(o -> q(o.toString())).toList()) + ")";
            String join = "inner".equalsIgnoreCase(type) ? " JOIN " : " LEFT JOIN ";
            StringBuilder from = new StringBuilder(q(inputs.get(0)));
            for (int i = 1; i < inputs.size(); i++) from.append(join).append(q(inputs.get(i))).append(' ').append(using);
            sql = "SELECT * FROM " + from;
        }
        exec(conn, "CREATE TABLE " + q(data) + " AS " + sql);
        return List.of(new Relation(PipelineRel.DATA, data));
    }

    // ── helpers ──────────────────────────────────────────────────────────────────

    private static void exec(Connection conn, String sql) throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.execute(sql);
        }
    }

    /** A safe, unique DuckDB table identifier for {@code <prefix>__<relkey>}. */
    private static String table(String prefix, String rel) {
        return sanitize(prefix) + "__" + sanitize(rel);
    }

    private static String sanitize(String s) {
        return s.replaceAll("[^A-Za-z0-9]+", "_").replaceAll("^_+|_+$", "");
    }

    /** Double-quote an identifier (escaping embedded quotes). */
    private static String q(String ident) {
        return SqlIdent.q(ident);
    }

    /** A single-quoted SQL string literal. */
    private static String sqlStr(String s) {
        return SqlIdent.sqlStr(s);
    }

    private static String str(PipelineNode n, String key) {
        Object v = n.cfg(key);
        return v == null ? null : v.toString();
    }

    private static String strOrNull(PipelineNode n, String key) {
        String v = str(n, key);
        return (v == null || v.isBlank()) ? null : v;
    }

    private static void requireExpr(String v, String what) {
        if (v == null || v.isBlank()) throw new IllegalArgumentException("missing " + what);
    }

    private static String reqStr(Map<String, Object> m, String key, String nodeId) {
        Object v = m.get(key);
        if (v == null || v.toString().isBlank())
            throw new IllegalArgumentException("node '" + nodeId + "': missing '" + key + "'");
        return v.toString();
    }
}
