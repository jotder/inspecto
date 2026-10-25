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

    /** A masked cell that is not a bare key: one value for every such cell, so it links to nothing (BI-MASKING-ROW-VALUE-LEAKS-1). */
    static final String UNLINKABLE = "masked:withheld";

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
    private Set<String> boundKeys = Set.of();      // a bound saved query's key names (readBound), per request
    private boolean boundAll;                      // ... or every name, when the bound text cannot be traced

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
            else if (claim.test(e.getValue())) masked.add(norm(e.getKey()));
        }
        // The claim-based lineage pass (sibling Datasets AND pipeline schema), which also sees classes outside SENSITIVE that a
        // masked type claims (IMEI, WALLET, SUBSCRIBER; BI-MASKING-SIBLING-TYPED-CLASS-1).
        Map<String, String> claimed = EvidenceMasker.lineageClassification(datasetId, dataset, store, views, claim);
        for (Map.Entry<String, String> e : claimed.entrySet()) {
            if (EvidenceMasker.UNKNOWN_LINEAGE.equals(e.getKey())) unknown = true;
            else masked.add(norm(e.getKey()));
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
                else if (!"NONE".equals(e.getValue()) && claim.test(e.getValue())) masked.add(norm(e.getKey()));
            }
            if (notBlank(dataset.get("view")) || notBlank(dataset.get("sql"))) unknown = true;   // a rename cannot be traced
        }
        // A view or virtual Dataset over a store with classified columns: a rename cannot be traced (EvidenceMasker's rule).
        if ((!lineage.isEmpty() || !claimed.isEmpty()) && (notBlank(dataset.get("view")) || notBlank(dataset.get("sql")))) unknown = true;

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
        return masked.contains(c) || ((unknown || viaText) && !knownClean.contains(c)) || boundAll || boundKeys.contains(c);
    }

    /**
     * A bound saved query's names that carry a key EVEN under a declared-clean name ({@code msisdn AS region}): a Widget's
     * filter or order on them would bisect or rank the raw key (BI-MASKING-ROW-VALUE-LEAKS-1). Text that does not parse,
     * renames in FROM, or holds a multi-column item other than a plain star cannot be traced, so every name is suspect.
     */
    private void readBound(String boundText, boolean viaText) {
        boundKeys = Set.of();
        boundAll = false;
        if (boundText == null || !viaText) return;
        JsonNode node = selectNode(boundText);
        // one base table only: a join or subquery under a star repeats the key under a dedup name (msisdn_1) nothing traces
        boolean untraceable = node == null || hasRenameOrRow(node, relationNames(node))
                || !"BASE_TABLE".equals(node.path("from_table").path("type").asText());
        Set<String> aliases = node == null ? Set.of() : keyAliases(node);
        if (node != null) {
            // DuckDB renames a repeated output name (msisdn_1, k_1): a copy of the key under a name nothing here knows
            JsonNode list = node.path("select_list");
            Set<String> seen = new HashSet<>();
            boolean star = false;
            for (JsonNode item : list) {
                if ("STAR".equals(item.path("type").asText())) { star = true; continue; }
                untraceable |= !seen.add(outputName(item).trim().toLowerCase(Locale.ROOT));
            }
            untraceable |= star && list.size() > 1;   // a star's names collide with the other items' unseen
            for (JsonNode item : list) {
                untraceable |= multiColumn(item) && !("STAR".equals(item.path("type").asText()) && !patternStar(item));
                // an item DuckDB names itself (upper(msisdn), true, NULL): its output name is not known here, and an
                // alias elsewhere that equals it makes DuckDB rename the key copy (true_1)
                untraceable |= item.path("alias").asText("").isEmpty() && !"COLUMN_REF".equals(item.path("type").asText())
                        && !"STAR".equals(item.path("type").asText());
            }
        }
        if (untraceable) boundAll = true;
        else boundKeys = aliases;
    }

    /**
     * A bound saved query whose every output name is traced ({@link #readBound}): a Widget over it is judged like a
     * structured spec — the masked columns plus the names the text gives a key — instead of treating every column as
     * possibly masked, so a chart over {@code SELECT * FROM ds} keeps its clean categories and counts.
     */
    private boolean boundTraced(String boundText) {
        readBound(boundText, true);
        return boundText != null && !boundAll;
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
        boolean viaText = boundText != null && (unknown || readsKey(boundText));
        readBound(boundText, viaText);
        if (viaText && !boundAll) viaText = false;   // traced: only the masked columns and the names the text gives a key
        // DuckDB renames a repeated output name in order (k, k_1, k_1_1), so with a repeat the result's names are not the
        // spec's: a typed value could land on a name judged clean and the key on one nobody tracks (BI-MASKING-GROUPBY-RENAME-1)
        Set<String> seenGroups = new HashSet<>();
        for (String g : spec.groupBy())
            if (!seenGroups.add(g == null ? "" : g.trim().toLowerCase(Locale.ROOT)))
                throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "groupBy repeats '" + g
                        + "' (names are compared ignoring case): the result's repeated columns would be renamed, and a masked key"
                        + " could come back under a name this check cannot follow; the caller needs " + REVEAL);
        if (checkFilters)
            for (MeasureCompiler.Filter f : spec.filters()) refuseIfMasked(f.field(), "filtering", viaText);
        for (String field : whenFields(spec.when())) refuseIfMasked(field, "filtering", viaText);
        // a Measure / ratio id is an aggregate the checks below vet, not a Dataset column (stripCosmeticOrder orders by one)
        Set<String> outputs = new HashSet<>();
        for (MeasureCompiler.Measure m : spec.measures()) if (m.id() != null) outputs.add(m.id());
        for (MeasureCompiler.Ratio r : spec.ratios()) if (r.id() != null) outputs.add(r.id());
        for (MeasureCompiler.Sort s : spec.orderBy())
            if (!(outputs.contains(s.field()) && !s.hidden())) refuseIfMasked(s.field(), "ordering", viaText);
        for (MeasureCompiler.Measure m : spec.measures())
            if (m.field() != null && revealsValues(m.agg()))
                refuseIfMasked(m.field(), m.agg() + " over", viaText);
        for (MeasureCompiler.Ratio r : spec.ratios())
            for (MeasureCompiler.Measure m : List.of(r.num(), r.den()))
                if (m.field() != null && revealsValues(m.agg()))
                    refuseIfMasked(m.field(), "a ratio of " + m.agg() + " over", viaText);
        if (spec.window() != null) refuseIfMasked(spec.window().field(), "windowing", viaText);
        // a capped grouped spec with no ORDER BY is ordered by its group keys (MeasureCompiler, BI-QUERY-TRUNCATION-1): with no
        // Measure to order by instead (stripCosmeticOrder), a masked group key would come back in raw order
        if (spec.orderBy().isEmpty() && spec.measures().isEmpty() && spec.limit() < Integer.MAX_VALUE)
            for (String g : spec.groupBy()) refuseIfMasked(g, "ordering (a grouped result with no Measure is ordered by)", viaText);
    }

    /**
     * The spec with the chart's COSMETIC category ordering ({@link MeasureCompiler.Sort#cosmetic}) over a masked grouped
     * dimension replaced by a descending order on the first Measure, for a caller who cannot reveal: a refusal would blank every
     * chart over a masked key. A spec with no Measure is left for {@link #vet} to refuse. Any other ordering on a masked column is untouched, so {@link #vet} still refuses it.
     */
    MeasureCompiler.Spec stripCosmeticOrder(MeasureCompiler.Spec spec, String boundText) {
        if (!engaged() || spec.measures().isEmpty()) return spec;   // no measure: the compiler's key-order fallback would leak, so vet refuses
        boolean viaText = boundText != null && (unknown || readsKey(boundText));
        readBound(boundText, viaText);
        if (viaText && !boundAll) viaText = false;   // traced: only the masked columns and the names the text gives a key
        List<MeasureCompiler.Sort> kept = new ArrayList<>();
        for (MeasureCompiler.Sort s : spec.orderBy())
            if (!(s.cosmetic() && isMasked(s.field(), viaText))) kept.add(s);
        // no ORDER BY at all: a capped grouped spec is ordered by its group keys (BI-MASKING-STRUCTURED-RESIDUALS-1 (2))
        boolean keyFallback = false;
        if (kept.isEmpty() && spec.limit() < Integer.MAX_VALUE)
            for (String g : spec.groupBy()) keyFallback |= isMasked(g, viaText);
        if (kept.size() == spec.orderBy().size() && !keyFallback) return spec;
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

    /**
     * {@code POST /queries/{id}/run}: as {@link #vetSort(List)}, and when the saved query's text reads a key, a sort on an
     * output the author named ({@code msisdn AS k}) would return the rows in raw key order next to the alias
     * (BI-MASKING-ROW-VALUE-LEAKS-1): only a declared-clean column or a select item that reads no key may be sorted.
     */
    void vetSort(List<QueryExecutor.Sort> sort, String queryText) {
        vetSort(sort);
        if (!engaged() || sort.isEmpty() || !(unknown || mentions(queryText, masked) || opaqueRefs(queryText))) return;
        JsonNode node = selectNode(queryText);
        Set<String> safe = new HashSet<>(knownClean);
        if (node != null && !hasOpaqueRef(node, relationNames(node))) {
            Set<String> aliases = keyAliases(node);
            for (JsonNode item : node.path("select_list")) {
                String alias = item.path("alias").asText("").trim().toLowerCase(Locale.ROOT);
                if (!alias.isEmpty() && !refsKey(item, aliases)) safe.add(alias);
            }
            for (String a : aliases) safe.remove(a);
        } else safe.clear();   // positions and names cannot be traced: nothing is provably clean
        for (QueryExecutor.Sort s : sort)
            if (!safe.contains(s.field().trim().toLowerCase(Locale.ROOT)))
                throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "ordering column '" + s.field()
                        + "' is refused: the query reads a masked key and this column may carry it, so ordering by it would leak"
                        + " its raw order; the caller needs " + REVEAL);
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
        return apply(r, dimensions, sqlText, null, Set.of());
    }

    /**
     * A Widget bound to a saved query: {@code sqlText} is the executed {@code WITH bound AS (..) SELECT ..}, which never parses
     * as one plain SELECT, so which output keeps the Space alias is judged on the saved query's own text
     * ({@code boundQuery}), by output NAME: a Widget column keeps it only when it is one of {@code grantable} (the
     * Widget's raw {@code groupBy} columns that no Measure id shares and no grain buckets) and the query emits that name
     * as a bare key ({@link #bareKeyNames}).
     */
    QueryExecutor.Result apply(QueryExecutor.Result r, List<String> dimensions, String sqlText, String boundQuery,
                               Set<String> grantable) {
        if (!engaged()) return r;
        Set<String> bareNames = null;
        if (boundQuery != null) {
            // The traced rule needs a known lineage and a traced text. Otherwise every column is withheld and nothing keeps the
            // alias: DuckDB renames repeated names (k, k_1, k_1_1), so a name granted from an untraced text could be a chosen value,
            // and with an unknown lineage a grain bucket would escape the name rule.
            boolean traced = boundTraced(boundQuery) && !unknown;
            bareNames = new HashSet<>();
            if (traced) {
                Set<String> g = new HashSet<>();
                for (String s : grantable) g.add(s.trim().toLowerCase(Locale.ROOT));
                bareNames = bareKeyNames(selectNode(boundQuery));
                bareNames.retainAll(g);
                dimensions = new ArrayList<>(g);   // judged like a structured spec (boundKeys added below)
            }
        } else boundKeys = Set.of();
        JsonNode tree = dimensions == null ? selectNode(sqlText) : null;
        // COLUMNS('msi.*') names the key by pattern, so the text need not spell it
        boolean opaque = dimensions == null && (opaqueRefs(sqlText) || boundQuery != null && opaqueRefs(boundQuery));
        boolean viaText = dimensions == null && (unknown || mentions(sqlText, masked) || opaque);
        Set<String> dims = new HashSet<>();
        if (dimensions != null) for (String d : dimensions) dims.add(d.trim().toLowerCase(Locale.ROOT));
        List<ResultSetDescriptor.Column> columns = new ArrayList<>();
        Set<String> hit = new LinkedHashSet<>();
        // Free SQL: which output POSITIONS derive from a masked column (a numeric CAST of a numeric key included), by the
        // DuckDB parse tree; null when the positions cannot be trusted (parse failure, subquery / CTE, a star or any other
        // item that can expand to several columns, a rename in FROM, the row as a value).
        Set<Integer> derived = viaText && !opaque ? derivedPositions(tree) : null;
        // A structured spec's dimensions are Dataset columns: the alias. Free SQL: only a parse-proven bare key keeps it, even when masked by an output name the text built at run time.
        Predicate<Integer> keep = dimensions == null ? keepsSpaceAlias(tree) : p -> true;
        Map<String, Boolean> keepByName = new HashMap<>();
        int pos = -1;
        for (ResultSetDescriptor.Column c : r.columns()) {
            pos++;
            String n = c.name().trim().toLowerCase(Locale.ROOT);
            boolean mask = masked.contains(n) || boundKeys.contains(n)
                    || (dimensions != null && unknown && dims.contains(n) && !knownClean.contains(n))
                    || (viaText && "string".equals(c.type()) && !knownClean.contains(n))
                    || (viaText && derived != null && derived.contains(pos))
                    // positions untrusted: every column is masked, a clean name included (a key can be aliased to one)
                    || (viaText && derived == null);
            if (mask) {
                hit.add(c.name());
                keepByName.merge(c.name(), bareNames != null ? bareNames.contains(n) : keep.test(pos), Boolean::logicalAnd);   // a repeated name: alias only if every copy may
            }
            columns.add(mask ? new ResultSetDescriptor.Column(c.name(), "string", c.role(), c.cardinality()) : c);
        }
        lastMasked = List.copyOf(hit);
        if (hit.isEmpty()) return r;
        List<Map<String, Object>> rows = new ArrayList<>(r.rows().size());
        for (Map<String, Object> row : r.rows()) {
            Map<String, Object> out = new LinkedHashMap<>(row);
            for (String col : hit) {
                Object v = out.get(col);
                if (v != null) out.put(col, keepByName.get(col) ? alias(String.valueOf(v)) : UNLINKABLE);
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
        Set<String> aliases = keyAliases(node);
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

    private static boolean hasOpaqueRef(JsonNode n, Set<String> relations) {
        // any star: a self-join's second copy of the key comes back as msisdn_1, which no name rule sees
        if (n.isObject() && ("STAR".equals(n.path("type").asText()) || "POSITIONAL_REFERENCE".equals(n.path("type").asText()))) return true;
        if (fromRename(n) || rowRef(n, relations)) return true;
        for (JsonNode c : n) if (hasOpaqueRef(c, relations)) return true;
        return false;
    }

    /**
     * The WHOLE statement (a UNION, subquery or CTE included) holds a star, a {@code #n} reference, a {@link #fromRename} or
     * the row as a value, any of which may read a key the text never spells (BI-MASKING-ROW-VALUE-LEAKS-1).
     */
    private static boolean opaqueRefs(String sql) {
        JsonNode node = sql == null ? null : SqlGuard.parseSelectNode(sql);
        return node != null && hasOpaqueRef(node, relationNames(node));
    }

    /**
     * A FROM entry that renames columns out of sight of every name rule: a column alias list ({@code calls t(m)}), a table
     * function ({@code unnest([msisdn])}, {@code range(..)}), PIVOT / UNPIVOT.
     */
    private static boolean fromRename(JsonNode n) {
        if (!n.isObject() || n.has("class")) return false;   // expressions carry a class, table refs do not
        String t = n.path("type").asText();
        return n.path("column_name_alias").size() > 0 || "TABLE_FUNCTION".equals(t) || "PIVOT".equals(t);
    }

    /** A name that is a FROM relation: the whole row as a struct ({@code SELECT c}, {@code to_json(calls)}, {@code main.calls}). */
    private static boolean rowRef(JsonNode n, Set<String> relations) {
        JsonNode names = n.path("column_names");
        return n.isObject() && "COLUMN_REF".equals(n.path("type").asText()) && names.size() > 0
                && relations.contains(names.get(names.size() - 1).asText().trim().toLowerCase(Locale.ROOT));
    }

    /** Every table name and table-ref alias anywhere in the statement, lower-cased. */
    private static Set<String> relationNames(JsonNode node) {
        Set<String> out = new HashSet<>();
        collectRelations(node, out);
        return out;
    }

    private static void collectRelations(JsonNode n, Set<String> out) {
        if (n.isObject() && !n.has("class")) {   // a table ref (BASE_TABLE, SUBQUERY, TABLE_FUNCTION, JOIN, PIVOT ..)
            for (String k : List.of("table_name", "alias")) {
                String v = n.path(k).asText("").trim().toLowerCase(Locale.ROOT);
                if (!v.isEmpty()) out.add(v);
            }
        }
        for (JsonNode c : n) collectRelations(c, out);
    }

    /**
     * Which masked output positions keep the Space alias. The alias is the same in every query, so a cell DERIVED from a key
     * ({@code substr(msisdn,1,1)}, a rank) aliased the same way could be matched against the alias of a value the caller
     * chose ({@code '4'}) and decoded; only a key selected bare keeps it: a plain SELECT whose FROM renames nothing, with
     * either no star (the bare key column items) or nothing but plain stars and column references (every output is a raw
     * column). Every other masked cell gets {@link #UNLINKABLE}.
     */
    private Predicate<Integer> keepsSpaceAlias(JsonNode tree) {
        if (tree == null) return p -> false;
        Set<String> relations = relationNames(tree);
        JsonNode list = tree.path("select_list");
        // a name another item aliases may be a lateral reference to it ('4417..' AS msisdn, msisdn AS k), not the column
        List<String> itemAliases = new ArrayList<>();
        for (JsonNode item : list) itemAliases.add(item.path("alias").asText("").trim().toLowerCase(Locale.ROOT));
        boolean star = false, derivedItem = false;
        Set<Integer> bare = new HashSet<>();
        for (int i = 0; i < list.size(); i++) {
            JsonNode item = list.get(i);
            if ("STAR".equals(item.path("type").asText()) && !patternStar(item)) { star = true; continue; }
            if (multiColumn(item)) return p -> false;   // * REPLACE, COLUMNS, unnest({..}) shift every later position
            JsonNode names = item.path("column_names");
            String name = names.size() == 1 ? names.get(0).asText().trim().toLowerCase(Locale.ROOT) : "";
            boolean lateral = false;
            for (int j = 0; j < itemAliases.size(); j++) lateral |= j != i && !name.isEmpty() && name.equals(itemAliases.get(j));
            if ("COLUMN_REF".equals(item.path("type").asText()) && !rowRef(item, relations) && !lateral) {
                if (refsKey(item, Set.of())) bare.add(i);
            } else derivedItem = true;
        }
        for (Map.Entry<String, JsonNode> e : tree.properties())
            if (!"select_list".equals(e.getKey()) && hasRenameOrRow(e.getValue(), relations)) return p -> false;
        if (star) return derivedItem ? p -> false : p -> true;
        return bare::contains;
    }

    /**
     * The lower-cased output names {@link #keepsSpaceAlias} keeps for {@code node} (a lone plain star keeps every masked
     * column's name). A Widget reads the bound query's columns BY NAME, case-insensitively, first match wins, so a name is
     * proven only when exactly one output carries it ({@code substr(msisdn,1,4) AS m, msisdn AS m} or {@code AS "M"} would
     * hand the derived value the alias) and it has no surrounding blanks ({@code " k"} / {@code "k"}).
     */
    private Set<String> bareKeyNames(JsonNode node) {
        Set<String> out = new HashSet<>();
        if (node == null) return out;
        JsonNode list = node.path("select_list");
        Map<String, Integer> seen = new HashMap<>();
        boolean star = false;
        for (JsonNode item : list) {
            if ("STAR".equals(item.path("type").asText())) { star = true; continue; }
            String name = outputName(item);
            seen.merge(name.trim().toLowerCase(Locale.ROOT), 1, Integer::sum);
        }
        if (star && list.size() > 1) return out;   // a star's names cannot be told apart from the other items'
        Predicate<Integer> keep = keepsSpaceAlias(node);
        for (int i = 0; i < list.size(); i++) {
            if (!keep.test(i)) continue;
            JsonNode item = list.get(i);
            if ("STAR".equals(item.path("type").asText())) { out.addAll(masked); continue; }
            String name = outputName(item);
            String key = name.trim().toLowerCase(Locale.ROOT);
            if (name.equals(name.trim()) && seen.getOrDefault(key, 0) == 1) out.add(key);
        }
        return out;
    }

    private static String outputName(JsonNode item) {
        String alias = item.path("alias").asText("");
        JsonNode names = item.path("column_names");
        return !alias.isEmpty() ? alias : names.size() > 0 ? names.get(names.size() - 1).asText() : "";
    }

    private static boolean hasRenameOrRow(JsonNode n, Set<String> relations) {
        if (fromRename(n) || rowRef(n, relations) || patternStar(n)
                || n.isObject() && "POSITIONAL_REFERENCE".equals(n.path("type").asText())) return true;
        for (JsonNode c : n) if (hasRenameOrRow(c, relations)) return true;
        return false;
    }

    /**
     * The lower-cased names that read a key: every FROM relation name (the row as a value - {@code SELECT c}, {@code to_json(c)},
     * {@code c['msisdn']} - carries the key), and the aliases of select items that read one, followed through lateral
     * aliases ({@code msisdn AS k, k AS j}).
     */
    private Set<String> keyAliases(JsonNode node) {
        Set<String> out = relationNames(node);
        JsonNode list = node.path("select_list");
        for (boolean grew = true; grew; ) {
            grew = false;
            for (JsonNode item : list) {
                String alias = item.path("alias").asText("").trim().toLowerCase(Locale.ROOT);
                if (!alias.isEmpty() && !out.contains(alias) && refsKey(item, out)) grew = out.add(alias);
            }
        }
        return out;
    }

    /** {@code n} reads a key: a masked column, an alias of one, a {@link #patternStar} or a {@link #fromRename} (fail closed). */
    private boolean refsKey(JsonNode n, Set<String> aliases) {
        if (patternStar(n) || fromRename(n) || n.isObject() && "POSITIONAL_REFERENCE".equals(n.path("type").asText())) return true;   // #1 may be the key
        if (rowRef(n, aliases)) return true;   // an alias of a key, or a relation as a row value (c, main.calls)
        if (n.isObject() && "COLUMN_REF".equals(n.path("type").asText()) && n.path("column_names").isArray()) {
            JsonNode names = n.path("column_names");
            String col = names.get(names.size() - 1).asText().trim().toLowerCase(Locale.ROOT);
            if (masked.contains(col) || (unknown && !knownClean.contains(col))) return true;
        }
        for (JsonNode c : n) if (refsKey(c, aliases)) return true;
        return false;
    }

    private boolean windowOrderedByKey(JsonNode n, Set<String> aliases) {
        // PARTITION BY too: DuckDB emits each hash bin's partitions in key order
        if (n.isObject() && "WINDOW".equals(n.path("class").asText())
                && (refsKey(n.path("orders"), aliases) || refsKey(n.path("partitions"), aliases))) return true;
        for (JsonNode c : n) if (windowOrderedByKey(c, aliases)) return true;
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
     * Free SQL run for a caller who cannot reveal - typed ({@code POST /db/query}), a saved query's text
     * ({@code POST /queries/{id}/run}), a bound Widget's query, a rule template's simulate. A masked key may be named only
     * BARE (BI-MASKING-FREE-SQL-ORACLES-1, operator 2026-10-25): a bare selected column (its output is aliased), a bare
     * {@code GROUP BY} column, or the bare argument of {@code count}. Any predicate, {@code CASE}, function or cast over it
     * - in the select list too ({@code CASE WHEN msisdn = '..' THEN error('x') END} answers yes / no by failing) - and any
     * WHERE, HAVING, ORDER BY, JOIN, QUALIFY, subquery or CTE use would test or bisect raw values, so it is refused 422; so is
     * an alias of it, a {@code COLUMNS(..)} pattern, and {@code ORDER BY 1} / {@code ORDER BY ALL} while a key is selected
     * (BI-MASKING-SQL-SHAPE-LEAKS-1). Text that does not parse as one plain SELECT is refused when it names a masked column.
     * The check runs per caller at RUN time, so a saved text that filters on a key still serves revealers.
     */
    void vetAdHocSql(String sql) {
        if (!engaged()) return;
        JsonNode node = selectNode(sql);
        boolean leak = false;
        if (node != null) {
            JsonNode list = node.path("select_list");
            Set<String> aliases = keyAliases(node);
            boolean keySelected = false;
            for (JsonNode item : list) keySelected |= refsKey(item, aliases) || multiColumn(item);   // a plain * selects the key too
            // only a bare use: a column, a star, or count(<bare column>) with no FILTER
            for (JsonNode item : list) leak |= refsKey(item, aliases) && !bareKeyUse(item, aliases);
            leak |= joinsOnKey(node.path("from_table"));
            // a window ordered by a key returns its rows in key order next to the bare alias: the rank leaks
            for (JsonNode item : list) leak |= windowOrderedByKey(item, aliases);
            for (Map.Entry<String, JsonNode> e : node.properties()) {
                if ("select_list".equals(e.getKey())) continue;
                if ("modifiers".equals(e.getKey())) {
                    for (JsonNode mod : e.getValue())
                        if ("ORDER_MODIFIER".equals(mod.path("type").asText())) {
                            for (JsonNode o : mod.path("orders"))
                                // any other star in a term (ORDER BY *, (* LIKE 'msi%')) may sort by the key: refused
                                leak |= positional(o.path("expression")) ? keySelected : refsKey(o, aliases) || hasOpaqueRef(o, aliases);
                        } else leak |= refsKey(mod, aliases);
                } else if ("group_expressions".equals(e.getKey())) {
                    // a bare grouped column is allowed; a computed group (substr(msisdn, 1, 4)) is not
                    for (JsonNode g : e.getValue()) leak |= !"COLUMN_REF".equals(g.path("type").asText()) && refsKey(g, aliases);
                } else if (!"group_sets".equals(e.getKey())) leak |= refsKey(e.getValue(), aliases);   // group_sets: indices only
            }
        } else leak = unknown || mentions(sql, masked) || opaqueRefs(sql);
        if (leak)
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "the SQL compares, computes over, joins or orders by a "
                    + "masked key (or its lineage is untraceable); that would leak its raw values, so a caller needs " + REVEAL
                    + " - a masked column may only be selected, grouped or counted, bare");
    }

    /**
     * A select item that names a key only bare: a column reference, a star, or {@code count} over bare columns with no FILTER.
     * A star whose {@code REPLACE} list or {@code COLUMNS(..)} expression computes over a key is not bare
     * ({@code COLUMNS(* REPLACE (CASE WHEN msisdn = '..' THEN 1 END AS msisdn))}), not even inside {@code count}.
     */
    private boolean bareKeyUse(JsonNode item, Set<String> aliases) {
        if (starComputes(item, aliases)) return false;
        String type = item.path("type").asText();
        // only a TOP-LEVEL star is bare: multiColumn() searches the whole expression, so a CASE around COLUMNS('msisdn') or
        // `.. + 0 * unnest([1])` would pass as "bare" and be an error() oracle (independent verification, 2026-10-25)
        if ("COLUMN_REF".equals(type) || "STAR".equals(type)) return true;
        String fn = item.path("function_name").asText("").toLowerCase(Locale.ROOT);
        return "FUNCTION".equals(item.path("class").asText()) && (fn.equals("count") || fn.equals("count_star"))
                && (item.path("filter").isNull() || item.path("filter").isMissingNode()) && barePlainArguments(item);
    }

    /**
     * A join on a key the ON check cannot see: {@code USING (msisdn)} holds plain names, not column references, and a
     * {@code NATURAL} join matches every same-named column, the key included, whenever a column is masked or untraceable.
     */
    private boolean joinsOnKey(JsonNode n) {
        if (n.isObject()) {
            if ("NATURAL".equals(n.path("ref_type").asText()) && (!masked.isEmpty() || unknown)) return true;
            for (JsonNode u : n.path("using_columns")) {
                String col = u.asText().trim().toLowerCase(Locale.ROOT);
                if (masked.contains(col) || (unknown && !knownClean.contains(col))) return true;
            }
        }
        for (JsonNode c : n) if (joinsOnKey(c)) return true;
        return false;
    }

    private boolean starComputes(JsonNode n, Set<String> aliases) {
        if (n.isObject() && "STAR".equals(n.path("type").asText())
                && (refsKey(n.path("replace_list"), aliases) || n.path("expr").isObject() && !"VALUE_CONSTANT".equals(n.path("expr").path("type").asText())
                        && refsKey(n.path("expr"), aliases))) return true;
        for (JsonNode c : n) if (starComputes(c, aliases)) return true;
        return false;
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

    /**
     * True when {@code text} contains any of {@code columns} as a whole identifier, case-insensitively. A masked column whose
     * name is not one plain word (`"msisdn no"`, an embedded quote, only blanks, a non-ASCII name) cannot be found reliably in
     * text: DuckDB resolves such names by rules (quoting, case folding) that a scan here would have to copy exactly, and each
     * copy missed a shape (BI-MASKING-NONWORD-KEY-NAME-1). So when one is masked, any text counts as reading a key: fail
     * closed, and a traced saved query still keeps its clean columns through the parse tree.
     */
    private static boolean mentions(String text, Set<String> columns) {
        if (text == null || columns.isEmpty()) return false;
        for (String c : columns) if (!WORD.matcher(c).matches()) return true;
        var m = WORD.matcher(text);
        while (m.find()) if (columns.contains(m.group().toLowerCase(Locale.ROOT))) return true;
        return false;
    }

    /** A column name as the masked / declared sets hold it: trimmed and lower-cased, whatever lineage it came through. */
    private static String norm(String name) {
        return name.trim().toLowerCase(Locale.ROOT);
    }

    private static boolean notBlank(Object o) {
        return o != null && !String.valueOf(o).isBlank();
    }
}
