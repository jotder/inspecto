package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.gamma.config.spec.ConfigSpecs;
import com.gamma.entitystore.EntityTypes;
import com.gamma.entitystore.LinkAnalysisSettings;
import com.gamma.entitystore.MaskTokens;
import com.gamma.mask.EvidenceMasker;
import com.gamma.query.SharedRefResolver;
import com.gamma.pipeline.ComponentStore;
import com.gamma.pipeline.ViewStore;
import com.gamma.query.MeasureCompiler;
import com.gamma.query.QueryExecutor;
import com.gamma.query.ResultSetDescriptor;
import com.gamma.spi.auth.ApiException;
import com.gamma.spi.auth.ErrorCodes;
import com.gamma.spi.http.ApiContext;
import com.gamma.sql.SqlGuard;
import com.gamma.util.ColumnClassification;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * Masking of classified entity keys in BI RESULT ROWS (BI-QUERY-MASKING-1, operator decision 2026-10-12; design
 * {@code docs/archived-documents/plans-archive/bi-query-masking-design.md}). The ONE helper every Dataset-reading BI route calls
 * ({@code /bi/query}, {@code /queries/{id}/run}, {@code /datasets/{id}/rows}, the public share query).
 *
 * <p><b>Which columns</b> - the Space's {@code maskingMode} ({@link LinkAnalysisSettings}), the same setting Link Analysis
 * reads. {@code none}: nothing. {@code typed} (default): a column whose classification a MASKED in-force Entity Type
 * claims (own {@code columns[].classification}, the pipeline-schema lineage, sibling Datasets over the same store).
 * {@code all}: every column classified MSISDN / IMSI / ACCOUNT / PII. A calculated column whose expression names a masked
 * column is masked too. <b>Fail closed:</b> when the lineage cannot be traced (a view or virtual Dataset over a classified
 * store, an unreadable pipeline) every column the Dataset does not declare as unclassified is masked.
 *
 * <p><b>Cell</b> - {@code masked:<16 hex>} under the Space's ONE identity key ({@link MaskTokens#spaceKey}), the alias
 * Link Analysis exploration and Entity Lists use, so one id has one alias. Applied AFTER execution: grouping and
 * {@code countDistinct} ran over the raw values.
 *
 * <p><b>Caller</b> - raw only for a holder of {@link #REVEAL}; a request with no Subject (Basic, no auth module) is
 * unrestricted like every capability gate, EXCEPT the public share route ({@link #forPublicShare}), which never reveals.
 *
 * <p><b>Inference channels refused (422) for a caller who cannot reveal:</b> a filter, a {@code when} condition or an
 * ordering on a masked column (a prefix filter bisects raw values; an order leaks rank), and any measure over one that
 * reads its values — {@code min / max / sum / avg}, the median, percentiles and spread ({@link #revealsValues}). {@code
 * count} / {@code countDistinct} stay allowed.
 */
final class BiResultMasking {

    /** The one grant meaning "may see raw entity keys" (Link Analysis reveal, Audit read masking, Risk Scores). */
    static final String REVEAL = AuditReadMasking.UNMASK_CAPABILITY;

    private static final Pattern WORD = Pattern.compile("[A-Za-z0-9_]+");

    /** The alias {@code DatasetRelation.withCalculated} gives the relation a calculated column is computed over. */
    private static final Set<String> CALC_BASE = Set.of("__base");

    /** A DuckDB error type ({@code Conversion Error:}); only {@link #COMPILE_TIME} ones are raised before any row is read. */
    private static final Pattern ERROR_TYPE = Pattern.compile("([A-Za-z]+) Error:");
    private static final Set<String> COMPILE_TIME = Set.of("Parser", "Binder", "Catalog");

    private final Path writeRoot;
    private final String mode;
    private final boolean reveal;
    private final Set<String> masked;       // lower-cased result/dataset column names
    private final boolean unknown;          // lineage untraceable: fail closed
    private final Set<String> knownClean;   // declared columns known NOT to be masked
    private final String basis;
    private final Map<String, String> tokens = new HashMap<>();   // per-request: a repeated key hashes once
    private byte[] key;
    private List<String> lastMasked = List.of();   // the result columns the last apply() masked

    private BiResultMasking(Path writeRoot, String mode, boolean reveal, Set<String> masked, boolean unknown,
                            Set<String> knownClean, String basis) {
        this.writeRoot = writeRoot;
        this.mode = mode;
        this.reveal = reveal;
        this.masked = masked;
        this.unknown = unknown;
        this.knownClean = knownClean;
        this.basis = basis;
    }

    /** The {@code shared/<owner>/<item>} refs a Dataset reads directly. */
    private static List<String> sharedRefs(Map<String, Object> dataset) {
        List<String> refs = new ArrayList<>();
        for (String k : List.of("physicalRef", "sourceName"))
            if (dataset.get(k) != null && String.valueOf(dataset.get(k)).trim().startsWith("shared/"))
                refs.add(String.valueOf(dataset.get(k)).trim());
        return refs;
    }

    /** True when the caller may see raw keys: holds {@link #REVEAL}, or no Subject is attached (Basic edition). */
    static boolean mayReveal(HttpExchange ex) {
        return ApiContext.subject(ex).map(s -> s.capabilities().contains(REVEAL)).orElse(true);
    }

    /** The masking for a read over {@code datasetId}; {@code reveal} is {@link #mayReveal}. */
    static BiResultMasking of(Path writeRoot, ComponentStore store, ViewStore views, String datasetId,
                              Map<String, Object> dataset, boolean reveal) {
        return of(writeRoot, store, views, datasetId, dataset, reveal,
                LinkAnalysisSettings.forRoot(writeRoot).effectiveMaskingMode());
    }

    /**
     * The public share query: never reveals, and reads the mode the Space STATES, else the shipped {@code typed} - never the
     * sign-in default ({@link com.gamma.entitystore.MaskingDefault}), whose {@code none} rests on "whoever uses this install
     * may reveal anyway", which an anonymous link holder cannot (BI-QUERY-MASKING-1 review R5, session decision 2026-10-23).
     * A Space that states {@code none} keeps raw shares (D-M2).
     */
    static BiResultMasking forPublicShare(Path writeRoot, ComponentStore store, ViewStore views, String datasetId,
                                          Map<String, Object> dataset) {
        String stated = LinkAnalysisSettings.forRoot(writeRoot).maskingMode();
        return of(writeRoot, store, views, datasetId, dataset, false,
                stated != null ? stated : ConfigSpecs.LINK_ANALYSIS_MASKING_MODES.get(0));
    }

    private static BiResultMasking of(Path writeRoot, ComponentStore store, ViewStore views, String datasetId,
                                      Map<String, Object> dataset, boolean reveal, String mode) {
        if ("none".equals(mode))
            return new BiResultMasking(writeRoot, mode, reveal, Set.of(), false, Set.of(), "masking is off for this Space (maskingMode none)");
        List<EntityTypes.EntityType> types = LinkAnalysisSettings.forRoot(writeRoot).effectiveEntityTypes();
        Predicate<String> claim = "all".equals(mode)
                ? cls -> cls != null && ColumnClassification.SENSITIVE.contains(cls.trim().toUpperCase(Locale.ROOT))
                : cls -> cls != null && typeOf(types, cls).map(EntityTypes.EntityType::masked).orElse(false);

        Set<String> masked = new LinkedHashSet<>();
        boolean unknown = false;
        Set<String> declared = new LinkedHashSet<>();
        if (dataset.get("columns") instanceof List<?> cols)
            for (Object o : cols)
                if (o instanceof Map<?, ?> c && c.get("name") != null) {
                    String name = String.valueOf(c.get("name")).trim().toLowerCase(Locale.ROOT);
                    declared.add(name);
                    if (c.get("classification") != null && claim.test(String.valueOf(c.get("classification")))) masked.add(name);
                }
        // The lineage (sibling Datasets + pipeline schema through the mapping): SENSITIVE classes, then judged by the claim.
        Map<String, String> lineage = EvidenceMasker.lineageClassification(datasetId, dataset, store, views);
        for (Map.Entry<String, String> e : lineage.entrySet()) {
            if (EvidenceMasker.UNKNOWN_LINEAGE.equals(e.getKey())) unknown = true;
            else if (claim.test(e.getValue())) masked.add(e.getKey());
        }
        // The claim-based schema pass, which also sees classes outside SENSITIVE that a masked type claims (IMEI, WALLET).
        for (Map.Entry<String, String> e : EvidenceMasker.schemaClassification(
                writeRoot, EvidenceMasker.datasetStores(dataset), claim).entrySet()) {
            if (EvidenceMasker.UNKNOWN_LINEAGE.equals(e.getKey())) unknown = true;
            else masked.add(e.getKey());
        }
        // BI-QUERY-MASKING-1 (D-M6): a Dataset over another Space's shared snapshot carries the OWNER's classification, judged
        // here under THIS Space's entity types. A ref whose classification is not known (live grant, older snapshot) fails closed.
        for (String ref : sharedRefs(dataset)) {
            String[] parts = ref.split("/");
            Optional<Map<String, String>> carried = parts.length == 3
                    ? SharedRefResolver.global().classification(parts[1], parts[2]) : Optional.empty();
            if (carried.isEmpty()) { unknown = true; continue; }
            for (Map.Entry<String, String> e : carried.get().entrySet()) {
                if ("UNKNOWN".equals(e.getValue())) unknown = true;
                else if (!"NONE".equals(e.getValue()) && claim.test(e.getValue())) masked.add(e.getKey());
            }
            if (notBlank(dataset.get("view")) || notBlank(dataset.get("sql"))) unknown = true;   // a rename cannot be traced
        }
        // A view or virtual Dataset over a store with classified columns: a rename cannot be traced (EvidenceMasker's rule).
        if (!lineage.isEmpty() && (notBlank(dataset.get("view")) || notBlank(dataset.get("sql")))) unknown = true;

        // A calculated column that names a masked column carries it (run twice so a chain of two is followed), and so does one
        // that names the row it is computed over (review R4: concat(__base) is the whole row, raw key included).
        if (dataset.get("calculated") instanceof List<?> calc)
            for (int pass = 0; pass < 2; pass++)
                for (Object o : calc)
                    if (o instanceof Map<?, ?> c && c.get("name") != null
                            && (mentions(String.valueOf(c.get("expr")), masked) || mentions(String.valueOf(c.get("expr")), CALC_BASE)))
                        masked.add(String.valueOf(c.get("name")).trim().toLowerCase(Locale.ROOT));
        declared.removeAll(masked);
        String basis = unknown
                ? "the lineage of '" + datasetId + "' cannot be traced, so every column it does not declare is masked"
                : masked.isEmpty() ? "no column of '" + datasetId + "' is classified as a masked key (maskingMode " + mode + ")"
                : "column(s) " + masked + " of '" + datasetId + "' are classified as masked keys (maskingMode " + mode + ")";
        return new BiResultMasking(writeRoot, mode, reveal, masked, unknown, declared, basis);
    }

    /** Some column is masked for the Dataset (whoever asks); the {@code masking} block is only added then. */
    boolean applies() {
        return !"none".equals(mode) && (!masked.isEmpty() || unknown);
    }

    /** Masking is in force for THIS caller. */
    boolean engaged() {
        return applies() && !reveal;
    }

    /** A Dataset column (any type) the caller may not see raw: masked, or not provably clean while the lineage is unknown. */
    private boolean isMasked(String column, boolean viaText) {
        String c = column == null ? "" : column.trim().toLowerCase(Locale.ROOT);
        return masked.contains(c) || ((unknown || viaText) && !knownClean.contains(c));
    }

    // ── refusals (inference channels) ───────────────────────────────────────────────

    /**
     * 422 when the spec filters, conditions or orders on a masked column, or returns a raw key through
     * {@code min / max / sum / avg} or a statistic. A no-op unless {@link #engaged}. {@code checkFilters=false} is for the public share
     * route, whose flat {@code filters} are the SHARED DASHBOARD'S stored ones, not the recipient's.
     * {@code boundText} is a bound saved query's own text (a Widget over a Query; never the {@code WITH} prefix, which does
     * not parse): its output names are the author's, so when it reads a key ({@link #readsKey}) every column not declared
     * clean is treated as possibly masked.
     */
    void vet(MeasureCompiler.Spec spec, boolean checkFilters, String boundText) {
        if (!engaged()) return;
        boolean viaText = boundText != null && readsKey(boundText);
        if (checkFilters)
            for (MeasureCompiler.Filter f : spec.filters()) refuseIfMasked(f.field(), "filtering", viaText);
        for (String field : whenFields(spec.when())) refuseIfMasked(field, "filtering", viaText);
        for (MeasureCompiler.Sort s : spec.orderBy()) refuseIfMasked(s.field(), "ordering", viaText);
        for (MeasureCompiler.Measure m : spec.measures())
            if (m.field() != null && revealsValues(m.agg()))
                refuseIfMasked(m.field(), m.agg() + " over", viaText);
        for (MeasureCompiler.Ratio r : spec.ratios())
            for (MeasureCompiler.Measure m : List.of(r.num(), r.den()))
                if (m.field() != null && revealsValues(m.agg()))
                    refuseIfMasked(m.field(), "a ratio of " + m.agg() + " over", viaText);
        if (spec.window() != null) refuseIfMasked(spec.window().field(), "windowing", viaText);
    }

    /**
     * The spec with the chart's COSMETIC category ordering ({@link MeasureCompiler.Sort#cosmetic}) over a masked grouped
     * dimension replaced by a descending order on the first Measure, for a caller who cannot reveal: a refusal would blank every
     * chart over a masked key. A spec with no Measure is left for {@link #vet} to refuse. Any other ordering on a masked column is untouched, so {@link #vet} still refuses it.
     */
    MeasureCompiler.Spec stripCosmeticOrder(MeasureCompiler.Spec spec, String boundText) {
        if (!engaged() || spec.measures().isEmpty()) return spec;   // no measure: the compiler's key-order fallback would leak, so vet refuses
        boolean viaText = boundText != null && readsKey(boundText);
        List<MeasureCompiler.Sort> kept = new ArrayList<>();
        for (MeasureCompiler.Sort s : spec.orderBy())
            if (!(s.cosmetic() && isMasked(s.field(), viaText))) kept.add(s);
        if (kept.size() == spec.orderBy().size()) return spec;
        // The compiler orders an unordered GROUP BY by its keys (a stable LIMIT), which would be the raw rank again: order by
        // the first Measure (descending, no raw value involved) so the cut stays deterministic and leaks nothing.
        if (kept.isEmpty()) kept.add(new MeasureCompiler.Sort(spec.measures().get(0).id(), true));
        return spec.withOrderBy(kept);
    }

    /** {@code POST /queries/{id}/run}: a caller-supplied {@code sort} on a masked column is refused. */
    void vetSort(List<QueryExecutor.Sort> sort) {
        if (!engaged()) return;
        for (QueryExecutor.Sort s : sort) refuseIfMasked(s.field(), "ordering", false);
    }

    private void refuseIfMasked(String column, String what, boolean viaText) {
        if (isMasked(column, viaText))
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, what + " column '" + column
                    + "' is refused: it is a masked key (or its lineage is untraceable), and " + what
                    + " it would leak its raw values; the caller needs " + REVEAL);
    }

    private static List<String> whenFields(Object node) {
        List<String> out = new ArrayList<>();
        collectFields(node, out);
        return out;
    }

    private static void collectFields(Object node, List<String> out) {
        if (node instanceof Map<?, ?> m) {
            for (Map.Entry<?, ?> e : m.entrySet()) {
                String k = String.valueOf(e.getKey());
                if (("field".equals(k) || "valueField".equals(k)) && e.getValue() != null) out.add(String.valueOf(e.getValue()));
                else collectFields(e.getValue(), out);
            }
        } else if (node instanceof List<?> l) {
            for (Object o : l) collectFields(o, out);
        }
    }

    // ── the result ──────────────────────────────────────────────────────────────────

    /**
     * {@code r} with the masked cells replaced by their alias and their column type reported as {@code string}; {@code r}
     * itself when nothing is masked for this caller. {@code dimensions} (a structured spec's {@code groupBy}) names the
     * output columns that are Dataset columns; {@code null} for free SQL, whose output names are the author's, so there a
     * string column is masked too when {@code sqlText} names a masked column (or the lineage is unknown) and the column is
     * not a declared clean one ({@code SELECT upper(msisdn) AS u}). ⚠ A NUMERIC derivation of a numeric key is not seen.
     */
    QueryExecutor.Result apply(QueryExecutor.Result r, List<String> dimensions, String sqlText) {
        if (!engaged()) return r;
        JsonNode tree = dimensions == null ? selectNode(sqlText) : null;
        // COLUMNS('msi.*') names the key by pattern, so the text need not spell it
        boolean viaText = dimensions == null && (unknown || mentions(sqlText, masked) || opaqueRefs(sqlText));
        Set<String> dims = new HashSet<>();
        if (dimensions != null) for (String d : dimensions) dims.add(d.trim().toLowerCase(Locale.ROOT));
        List<ResultSetDescriptor.Column> columns = new ArrayList<>();
        Set<String> hit = new LinkedHashSet<>();
        // Free SQL: which output POSITIONS derive from a masked column (a numeric CAST of a numeric key included), by the
        // DuckDB parse tree; null when the positions cannot be trusted (parse failure, subquery / CTE, a star or any other
        // item that can expand to several columns).
        Set<Integer> derived = viaText ? derivedPositions(tree) : null;
        int pos = -1;
        for (ResultSetDescriptor.Column c : r.columns()) {
            pos++;
            String n = c.name().trim().toLowerCase(Locale.ROOT);
            boolean mask = masked.contains(n)
                    || (dimensions != null && unknown && dims.contains(n) && !knownClean.contains(n))
                    || (viaText && "string".equals(c.type()) && !knownClean.contains(n))
                    || (viaText && derived != null && derived.contains(pos))
                    // positions untrusted: every column is masked, a clean name included (a key can be aliased to one)
                    || (viaText && derived == null);
            if (mask) hit.add(c.name());
            columns.add(mask ? new ResultSetDescriptor.Column(c.name(), "string", c.role(), c.cardinality()) : c);
        }
        lastMasked = List.copyOf(hit);
        if (hit.isEmpty()) return r;
        List<Map<String, Object>> rows = new ArrayList<>(r.rows().size());
        for (Map<String, Object> row : r.rows()) {
            Map<String, Object> out = new LinkedHashMap<>(row);
            for (String col : hit) {
                Object v = out.get(col);
                if (v != null) out.put(col, alias(String.valueOf(v)));
            }
            rows.add(out);
        }
        return new QueryExecutor.Result(columns, rows, r.rowCount(), r.truncated(), r.elapsedMs());
    }

    /** What the response says about masking; {@code null} when no column of the Dataset is masked (the body stays byte-identical). */
    Map<String, Object> describe() {
        if (!applies()) return null;
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("mode", mode);
        m.put("masked", engaged());
        m.put("columns", engaged() ? lastMasked : List.of());
        m.put("basis", basis);
        return m;
    }

    /**
     * The 0-based output positions of {@code sql}'s select list that derive from a masked column (or, with an untraceable
     * lineage, from any column not declared clean) through anything but a {@code count}; {@code null} when the positions
     * cannot be trusted: the text does not parse as one SELECT, holds a subquery or CTE (more than one SELECT node), or
     * an item can expand to several columns ({@link #multiColumn}), which shifts every later position
     * (BI-MASKING-SQL-SHAPE-LEAKS-1). Closes the numeric derivation {@code CAST(msisdn AS BIGINT) AS m}, which no string
     * rule sees; an item that names an alias of a key ({@code msisdn AS k, k = '..' AS hit}) derives from it too.
     */
    private Set<Integer> derivedPositions(JsonNode node) {
        if (node == null) return null;
        JsonNode list = node.path("select_list");
        Set<String> aliases = keyAliases(list);
        Set<Integer> out = new LinkedHashSet<>();
        for (int i = 0; i < list.size(); i++) {
            JsonNode expr = list.get(i);
            if (multiColumn(expr)) return null;
            String fn = expr.path("function_name").asText("").toLowerCase(Locale.ROOT);
            boolean count = "FUNCTION".equals(expr.path("class").asText()) && (fn.equals("count") || fn.equals("count_star"))
                    && (expr.path("filter").isNull() || expr.path("filter").isMissingNode())
                    && barePlainArguments(expr);   // count(msisdn) is a number; count(CASE WHEN msisdn = ..) is a test of a value
            if (!count && refsKey(expr, aliases)) out.add(i);
        }
        return out;
    }

    /** The one plain SELECT in {@code sql}, or {@code null} (parse failure, not a SELECT, a subquery or CTE). */
    private static JsonNode selectNode(String sql) {
        JsonNode node = sql == null ? null : SqlGuard.parseSelectNode(sql);
        return node != null && "SELECT_NODE".equals(node.path("type").asText()) && countSelectNodes(node) == 1 ? node : null;
    }

    /** A select item that can yield more than one column: any star ({@code *}, {@code COLUMNS(..)}, {@code t.*}), or {@code unnest} / {@code unpack}. */
    private static boolean multiColumn(JsonNode n) {
        if (n.isObject() && ("STAR".equals(n.path("class").asText()) || "STAR".equals(n.path("type").asText()))) return true;
        String fn = n.isObject() ? n.path("function_name").asText("").toLowerCase(Locale.ROOT) : "";
        if (fn.equals("unnest") || fn.equals("unlist") || fn.equals("unpack")) return true;
        for (JsonNode c : n) if (multiColumn(c)) return true;
        return false;
    }

    /** A star that picks or rewrites columns, so it may cover a key whatever its text says: {@code COLUMNS(..)}, EXCLUDE, REPLACE, RENAME. */
    private static boolean patternStar(JsonNode n) {
        if (!n.isObject() || !"STAR".equals(n.path("type").asText())) return false;
        return n.path("columns").asBoolean(false) || !n.path("expr").isMissingNode() && !n.path("expr").isNull()
                || n.path("exclude_list").size() > 0 || n.path("qualified_exclude_list").size() > 0
                || n.path("replace_list").size() > 0 || n.path("rename_list").size() > 0;
    }

    private static boolean hasOpaqueRef(JsonNode n) {
        // any star: a self-join's second copy of the key comes back as msisdn_1, which no name rule sees
        if (n.isObject() && ("STAR".equals(n.path("type").asText()) || "POSITIONAL_REFERENCE".equals(n.path("type").asText()))) return true;
        for (JsonNode c : n) if (hasOpaqueRef(c)) return true;
        return false;
    }

    /**
     * The WHOLE statement (a UNION, subquery or CTE included) holds a star or a {@code #n} reference, either of which may
     * read a key the text never spells.
     */
    private static boolean opaqueRefs(String sql) {
        JsonNode node = sql == null ? null : SqlGuard.parseSelectNode(sql);
        return node != null && hasOpaqueRef(node);
    }

    /** The lower-cased aliases of select items that read a key, followed through lateral aliases ({@code msisdn AS k, k AS j}). */
    private Set<String> keyAliases(JsonNode list) {
        Set<String> out = new HashSet<>();
        for (boolean grew = true; grew; ) {
            grew = false;
            for (JsonNode item : list) {
                String alias = item.path("alias").asText("").trim().toLowerCase(Locale.ROOT);
                if (!alias.isEmpty() && !out.contains(alias) && refsKey(item, out)) grew = out.add(alias);
            }
        }
        return out;
    }

    /** {@code n} reads a key: a masked column, an alias of one, or a {@link #patternStar} (fail closed). */
    private boolean refsKey(JsonNode n, Set<String> aliases) {
        if (patternStar(n) || n.isObject() && "POSITIONAL_REFERENCE".equals(n.path("type").asText())) return true;   // #1 may be the key
        if (n.isObject() && "COLUMN_REF".equals(n.path("type").asText()) && n.path("column_names").size() == 1
                && aliases.contains(n.path("column_names").get(0).asText().trim().toLowerCase(Locale.ROOT))) return true;
        if (n.isObject() && "COLUMN_REF".equals(n.path("type").asText()) && n.path("column_names").isArray()) {
            JsonNode names = n.path("column_names");
            String col = names.get(names.size() - 1).asText().trim().toLowerCase(Locale.ROOT);
            if (masked.contains(col) || (unknown && !knownClean.contains(col))) return true;
        }
        for (JsonNode c : n) if (refsKey(c, aliases)) return true;
        return false;
    }

    /** An ORDER BY term that names a select item by position or by {@code ALL}. */
    private static boolean positional(JsonNode expr) {
        while ("COLLATE".equals(expr.path("type").asText())) expr = expr.path("child");   // ORDER BY 1 COLLATE nocase
        return "VALUE_CONSTANT".equals(expr.path("type").asText())
                || "STAR".equals(expr.path("type").asText()) && expr.path("columns").asBoolean(false)
                && (expr.path("expr").isMissingNode() || expr.path("expr").isNull())
                && expr.path("exclude_list").size() == 0 && expr.path("qualified_exclude_list").size() == 0
                && expr.path("replace_list").size() == 0 && expr.path("rename_list").size() == 0;   // not COLUMNS(* REPLACE ..)
    }

    /**
     * Every argument of the aggregate is a bare column reference (or a star): nothing is computed from a key's value.
     * DuckDB 1.5 serialises the operands as {@code children: [expr]}, 2.0 as {@code arguments: [{name, expression}]};
     * both lists are read (so neither can hide the other); an aggregate carrying neither is an AST shape this code does
     * not know, so it is NOT plain (fail closed).
     */
    private static boolean barePlainArguments(JsonNode fn) {
        List<JsonNode> operands = new ArrayList<>();
        boolean known = fn.path("children").isArray() || fn.path("arguments").isArray();
        if (!known) return false;
        fn.path("children").forEach(operands::add);
        fn.path("arguments").forEach(a -> operands.add(a.path("expression")));
        for (JsonNode a : operands)
            if (!"COLUMN_REF".equals(a.path("type").asText()) && !"STAR".equals(a.path("type").asText())) return false;
        JsonNode order = fn.path("order_bys").path("orders");
        return order.isMissingNode() || order.size() == 0;
    }

    /**
     * Ad-hoc SQL typed by the caller ({@code POST /db/query}): a masked column may appear only in the select list (its
     * output is aliased); in a WHERE, HAVING, GROUP BY, ORDER BY, JOIN, subquery or CTE it would test or bisect raw values,
     * so it is refused 422 - and so is an alias of it, a {@code COLUMNS(..)} pattern, and {@code ORDER BY 1} / {@code ORDER
     * BY ALL} while a key is selected (BI-MASKING-SQL-SHAPE-LEAKS-1). Text that does not parse as one plain SELECT is
     * refused when it names a masked column.
     */
    void vetAdHocSql(String sql) {
        if (!engaged()) return;
        JsonNode node = selectNode(sql);
        boolean leak = false;
        if (node != null) {
            JsonNode list = node.path("select_list");
            Set<String> aliases = keyAliases(list);
            boolean keySelected = false;
            for (JsonNode item : list) keySelected |= refsKey(item, aliases) || multiColumn(item);   // a plain * selects the key too
            for (Map.Entry<String, JsonNode> e : node.properties()) {
                if ("select_list".equals(e.getKey())) continue;
                if ("modifiers".equals(e.getKey())) {
                    for (JsonNode mod : e.getValue())
                        if ("ORDER_MODIFIER".equals(mod.path("type").asText())) {
                            for (JsonNode o : mod.path("orders"))
                                // any other star in a term (ORDER BY *, (* LIKE 'msi%')) may sort by the key: refused
                                leak |= positional(o.path("expression")) ? keySelected : refsKey(o, aliases) || hasOpaqueRef(o);
                        } else leak |= refsKey(mod, aliases);
                } else leak |= refsKey(e.getValue(), aliases);
            }
        } else leak = unknown || mentions(sql, masked) || opaqueRefs(sql);
        if (leak)
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "the SQL compares, joins, groups or orders by a masked "
                    + "key (or its lineage is untraceable); that would leak its raw values, so a caller needs " + REVEAL
                    + " - a masked column may only be selected");
    }

    private static int countSelectNodes(JsonNode n) {
        int k = n.isObject() && "SELECT_NODE".equals(n.path("type").asText()) ? 1 : 0;
        for (JsonNode c : n) k += countSelectNodes(c);
        return k;
    }

    /** {@code GET /kpis/{id}/value}: a Measure that reads a masked column's values ({@link #revealsValues}) is refused. */
    void vetMeasure(MeasureCompiler.Measure m) {
        if (!engaged() || m.field() == null) return;
        if (revealsValues(m.agg())) refuseIfMasked(m.field(), m.agg() + " over", false);
    }

    /**
     * Every aggregate but {@code count} / {@code countDistinct} reads the column's VALUES: {@code min / max / sum / avg}
     * return a raw key per group, a median or percentile is one (or an interpolation between two), and a spread over a
     * two-row group gives away their difference. Fail closed: an aggregate added to the grammar later is refused too.
     */
    static boolean revealsValues(String agg) {
        return !"count".equals(agg) && !"countDistinct".equals(agg);
    }

    /** Columns a caller-supplied value is compared with (a rule template hole's {@code field}): a masked one is an oracle. */
    void vetFields(List<String> fields, String what) {
        if (!engaged()) return;
        for (String f : fields) if (f != null) refuseIfMasked(f, what, false);
    }

    /**
     * The {@code $parameter} oracle: a caller-supplied parameter value is substituted into a saved query's text, so
     * {@code WHERE msisdn LIKE $prefix} bisects a masked key exactly as a filter would. Refused when the caller supplies any
     * value and the query text reads a key ({@link #readsKey}: by name, or through an opaque reference such as {@code #1};
     * review R3) or the lineage is untraceable. Declared defaults stay the author's.
     */
    void vetParameterValues(String queryText, java.util.Collection<String> suppliedNames) {
        if (!engaged() || suppliedNames.isEmpty()) return;
        if (unknown || readsKey(queryText))
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "parameter value(s) " + suppliedNames
                    + " are refused: the query reads a masked key (or its lineage is untraceable), and a caller-supplied value "
                    + "could be compared with its raw values; the caller needs " + REVEAL);
    }

    /** {@code sql} reads a key by a route the result masking recognises: it names a masked column, or holds an opaque reference. */
    private boolean readsKey(String sql) {
        return mentions(sql, masked) || opaqueRefs(sql);
    }

    /**
     * {@code POST /bi/query} for a Widget bound to a saved Query: the output columns are the spec's, computed over the query's
     * result, so the query's own select-list positions say nothing about them. When {@code queryText} (the saved query's
     * rendered text) reads a key or the lineage is untraceable, every output column is masked; otherwise a column is masked
     * by its name (review R1: the masking used to read the {@code WITH} prefix, which does not parse, so an opaque
     * reference in the query went unseen).
     */
    QueryExecutor.Result applyBound(QueryExecutor.Result r, String queryText) {
        if (!engaged()) return r;
        boolean all = unknown || readsKey(queryText);
        List<ResultSetDescriptor.Column> columns = new ArrayList<>();
        Set<String> hit = new LinkedHashSet<>();
        for (ResultSetDescriptor.Column c : r.columns()) {
            boolean mask = all || masked.contains(c.name().trim().toLowerCase(Locale.ROOT));
            if (mask) hit.add(c.name());
            columns.add(mask ? new ResultSetDescriptor.Column(c.name(), "string", c.role(), c.cardinality()) : c);
        }
        lastMasked = List.copyOf(hit);
        if (hit.isEmpty()) return r;
        List<Map<String, Object>> rows = new ArrayList<>(r.rows().size());
        for (Map<String, Object> row : r.rows()) {
            Map<String, Object> out = new LinkedHashMap<>(row);
            for (String col : hit) if (out.get(col) != null) out.put(col, alias(String.valueOf(out.get(col))));
            rows.add(out);
        }
        return new QueryExecutor.Result(columns, rows, r.rowCount(), r.truncated(), r.elapsedMs());
    }

    /**
     * {@code fault} (a 422 built from a failed run's DuckDB text) as this caller may see it. DuckDB QUOTES the offending value
     * in a run-time error ({@code error(msisdn)} answers {@code Invalid Input Error: 4417..}, a failed cast names the string),
     * so while masking is in force the text survives only when every error type it names is raised before any row is read
     * (Parser / Binder / Catalog); any other answers a fixed sentence (review R2). A revealer, an unclassified Dataset and
     * {@code maskingMode none} keep the full text; a non-422 (the no-data 409) is untouched.
     */
    ApiException scrub(ApiException fault) {
        if (!engaged() || fault.status != 422 || compileTimeOnly(fault.getMessage())) return fault;
        return new ApiException(422, fault.errorCode, "the query failed while reading the data; the database's error text is "
                + "withheld because it can quote a stored value of a masked key (a caller with " + REVEAL + " sees it)");
    }

    private static boolean compileTimeOnly(String message) {
        var m = ERROR_TYPE.matcher(message == null ? "" : message);
        boolean any = false;
        while (m.find()) {
            if (!COMPILE_TIME.contains(m.group(1))) return false;
            any = true;
        }
        return any;
    }

    private String alias(String raw) {
        return tokens.computeIfAbsent(raw, v -> MaskTokens.token(key(), v));
    }

    private byte[] key() {
        if (key == null) {
            try {
                key = MaskTokens.spaceKey(writeRoot);
            } catch (IOException e) {
                throw new UncheckedIOException(e);   // fail closed: an unreadable key serves nothing
            }
        }
        return key;
    }

    // ── helpers ─────────────────────────────────────────────────────────────────────

    /** The first in-force Entity Type whose {@code classifications[]} claims {@code cls} (Link Analysis' rule). */
    private static java.util.Optional<EntityTypes.EntityType> typeOf(List<EntityTypes.EntityType> types, String cls) {
        String c = cls.trim();
        return types.stream().filter(t -> t.classifications().stream().anyMatch(x -> x.trim().equalsIgnoreCase(c))).findFirst();
    }

    /** True when {@code text} contains any of {@code columns} as a whole identifier, case-insensitively. */
    private static boolean mentions(String text, Set<String> columns) {
        if (text == null || columns.isEmpty()) return false;
        var m = WORD.matcher(text);
        while (m.find()) if (columns.contains(m.group().toLowerCase(Locale.ROOT))) return true;
        return false;
    }

    private static boolean notBlank(Object o) {
        return o != null && !String.valueOf(o).isBlank();
    }
}
