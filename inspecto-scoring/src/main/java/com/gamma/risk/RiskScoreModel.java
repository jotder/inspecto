package com.gamma.risk;

import com.gamma.query.MeasureCompiler;
import com.gamma.util.Values;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * A <b>Risk Score</b> model (ASSURE-RISK-SCORE-1, WS-22): one weighted-factor model per entity type, stored as
 * the {@code risk-score} component kind and evaluated by the {@code risk.score} Job.
 *
 * <pre>
 * entityType: subscriber            (subscriber · account · device · sim · dealer · channel · partner, or free-form)
 * highThreshold: 70                 (a score at or above this is "high")
 * (the scores Datasets are ALWAYS risk_scores_&lt;id&gt; and risk_scores_&lt;id&gt;_latest — not authorable)
 * maxEntities: 500000          (optional; entity cap for one run, 1..2000000; default -Drisk.score.maxEntities, else 200000)
 * retainDays: 90                 (optional; prune history runs older than this — OR retainRuns: keep the newest N; OFF by default)
 * dataScope: fraud                  (optional; a data-scoped caller must hold it to read a score)
 * factors[n]:
 *   id: failed_topups
 *   dataset: topups                 (a declared Dataset component)
 *   key: msisdn                     (that Dataset's entity-key column)
 *   measure: count                  (count | agg(field) — the Measure grammar)
 *   filters: [{field: status, op: "=", value: FAILED}]
 *   weight: 2.5
 *   cap: 40                         (optional: the most this factor may contribute)
 *   evidence: [topup_id, amount]    (optional: columns shown beside the factor)
 * </pre>
 *
 * <p>An indicator is a {@link MeasureCompiler} Measure over one Dataset grouped by its key column — the guarded
 * compiler, never author SQL. {@link #fromMap} compiles every factor through it at save time, so a factor the
 * Job could not run is refused before it is stored. The column check against the Dataset's Schema needs the
 * registry and runs at the route ({@link #referencedColumns}).
 */
public record RiskScoreModel(String id, String entityType, double highThreshold, String scoresDataset,
                             String dataScope, String description, List<Factor> factors, WatchList watchList,
                             Integer retainDays, Integer retainRuns, Integer maxEntities) {

    /** The highest per-model {@code maxEntities} a model may author (operator, 2026-10-06): 10x the system default. */
    public static final int MAX_ENTITIES_CEILING = 2_000_000;

    /** The named entity types (D-P1). Anything else that is a plain token is accepted as free-form. */
    public static final List<String> ENTITY_TYPES =
            List.of("subscriber", "account", "device", "sim", "dealer", "channel", "partner");

    static final Pattern SAFE_IDENT = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");
    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_-]*");
    /** A model id: no '-', so {@code risk_scores_<id>} is injective (a-b and a_b cannot share an output). */
    private static final Pattern MODEL_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_]*");
    /** The prefix of every scores Dataset — the output name is derived, never authored. */
    public static final String SCORES_PREFIX = com.gamma.alert.RiskScoreOutputs.SCORES_PREFIX;
    public static final String LATEST_SUFFIX = com.gamma.alert.RiskScoreOutputs.LATEST_SUFFIX;
    static final int MAX_FACTORS = 32;
    static final int MAX_EVIDENCE = 8;
    /** The component envelope the store/route adds (name = id, owner, shares) — accepted, never scored. */
    public static final Set<String> ENVELOPE_KEYS = Set.of("name", "owner", "shares");
    private static final Set<String> MODEL_KEYS = Set.of("name", "owner", "shares", "id", "entityType",
            "highThreshold", "dataScope", "description", "factors", "watchList", "retainDays", "retainRuns", "maxEntities");
    private static final Set<String> FACTOR_KEYS = Set.of("id", "label", "dataset", "key", "measure",
            "filters", "weight", "cap", "evidence");

    /**
     * One factor: {@code contribution = weight × indicator}, then held to at most {@code cap} when one is set.
     *
     * @param filters as authored ({@code {field, op, value}} maps) — compiled by {@link MeasureCompiler}
     */
    public record Factor(String id, String label, String dataset, String key, String measure,
                         List<Map<String, Object>> filters, double weight, Double cap, List<String> evidence) {

        /** The compiled Measure (validated at parse). */
        public MeasureCompiler.Measure compiledMeasure() {
            Map<String, Object> m = MeasureCompiler.splitShorthand(List.of(measure), null).get(0);
            return new MeasureCompiler.Measure(String.valueOf(m.get("agg")),
                    m.get("field") == null ? null : String.valueOf(m.get("field")));
        }

        /**
         * This factor narrowed to ONE entity (S3 preview): an extra {@code key = entityKey} filter, which the
         * {@link MeasureCompiler} renders as a quoted typed literal like every authored filter value — so the
         * preview reads that entity's rows, not the whole Dataset.
         */
        public Factor forEntity(String entityKey) {
            List<Map<String, Object>> narrowed = new ArrayList<>(filters == null ? List.of() : filters);
            narrowed.add(Map.of("field", key, "op", "=", "value", entityKey));
            return new Factor(id, label, dataset, key, measure, List.copyOf(narrowed), weight, cap, evidence);
        }

        /** The indicator's grouped Measure spec: one value per entity key. */
        public MeasureCompiler.Spec valueSpec(int limit) {
            return MeasureCompiler.parse(specBody(List.of(measureBody()), List.of(key)), limit, limit);
        }

        /** The evidence rows' spec: the key plus the evidence columns, under the same filters. */
        public MeasureCompiler.Spec evidenceSpec(int limit) {
            List<String> cols = new ArrayList<>();
            cols.add(key);
            for (String e : evidence) if (!cols.contains(e)) cols.add(e);
            return MeasureCompiler.parse(specBody(List.of(), cols), limit, limit);
        }

        private Map<String, Object> measureBody() {
            MeasureCompiler.Measure m = compiledMeasure();
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("agg", m.agg());
            if (m.field() != null) out.put("field", m.field());
            return out;
        }

        private Map<String, Object> specBody(List<Map<String, Object>> measures, List<String> groupBy) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("dataset", dataset);
            body.put("measures", measures);
            body.put("groupBy", groupBy);
            body.put("filters", filters);
            body.put("orderBy", List.of(Map.of("field", key)));
            return body;
        }
    }

    /** This model with every factor narrowed to one entity ({@link Factor#forEntity}) — the S3 preview's input. */
    public RiskScoreModel forEntity(String entityKey) {
        return new RiskScoreModel(id, entityType, highThreshold, scoresDataset, dataScope, description,
                factors.stream().map(f -> f.forEntity(entityKey)).toList(), watchList, retainDays, retainRuns, maxEntities);
    }

    /** The longest a fed watch entry may live: D-P5 lets only an expiring (at most 24 h) entry skip four-eyes. */
    public static final int MAX_WATCH_TTL_HOURS = 24;
    private static final Pattern LIST_ID = Pattern.compile("[a-z0-9][a-z0-9_-]{0,63}");

    /**
     * The optional watch-list feed (ASSURE-ENTITY-LISTS-1): after each run every {@code high} entity is added to the
     * {@code watch} Entity List {@code list}, expiring {@code ttlHours} (1..24) later. See {@link WatchListFeed}.
     */
    public record WatchList(String list, int ttlHours) {}

    public RiskScoreModel {
        factors = List.copyOf(factors);
    }

    /** The latest-run scores Dataset ({@code risk_scores_<id>_latest}). */
    public String latestDataset() { return scoresDataset + LATEST_SUFFIX; }

    /** The ids of the Datasets the factors read. */
    public Set<String> datasetIds() {
        Set<String> out = new LinkedHashSet<>();
        for (Factor f : factors) out.add(f.dataset());
        return out;
    }

    /** Every column each Dataset must carry: key, measure field, filter fields, evidence (for the Schema check). */
    public Map<String, Set<String>> referencedColumns() {
        Map<String, Set<String>> out = new LinkedHashMap<>();
        for (Factor f : factors) {
            Set<String> cols = out.computeIfAbsent(f.dataset(), d -> new LinkedHashSet<>());
            cols.add(f.key());
            if (f.compiledMeasure().field() != null) cols.add(f.compiledMeasure().field());
            for (Map<String, Object> flt : f.filters()) cols.add(String.valueOf(flt.get("field")));
            cols.addAll(f.evidence());
        }
        return out;
    }

    /**
     * Parse and validate, fail-closed. Every problem is an {@link IllegalArgumentException} naming the field,
     * which the component route maps to 422.
     */
    public static RiskScoreModel fromMap(String id, Map<String, Object> m) {
        if (id == null || !MODEL_ID.matcher(id).matches())
            throw new IllegalArgumentException("risk-score id '" + id + "' must be letters, digits and '_'");
        if (m.containsKey("scoresDataset"))
            throw new IllegalArgumentException("risk-score.scoresDataset is not authorable: the scores Dataset is "
                    + "always '" + SCORES_PREFIX + id + "' (and '" + SCORES_PREFIX + id + LATEST_SUFFIX + "')");
        for (String k : m.keySet())
            if (!MODEL_KEYS.contains(k))
                throw new IllegalArgumentException("risk-score: unknown key '" + k + "' (expected " + MODEL_KEYS + ")");
        String entityType = Values.trimToNull(m.get("entityType"));
        if (entityType == null || !SAFE_IDENT.matcher(entityType).matches())
            throw new IllegalArgumentException("risk-score.entityType is required and must be a plain token "
                    + "(one of " + ENTITY_TYPES + ", or a free-form name)");
        if (m.get("retainDays") != null && m.get("retainRuns") != null)
            throw new IllegalArgumentException("risk-score: set retainDays or retainRuns, not both");
        double high = number(m.get("highThreshold"), "risk-score.highThreshold");
        if (high <= 0 || high > 100)
            throw new IllegalArgumentException("risk-score.highThreshold must be in (0, 100], got " + high);
        String scores = SCORES_PREFIX + id;
        String scope = Values.trimToNull(m.get("dataScope"));
        if (scope != null && !SAFE_IDENT.matcher(scope).matches())
            throw new IllegalArgumentException("risk-score.dataScope '" + scope + "' must be a plain token");

        if (!(m.get("factors") instanceof List<?> raw) || raw.isEmpty())
            throw new IllegalArgumentException("risk-score.factors must list at least one factor");
        if (raw.size() > MAX_FACTORS)
            throw new IllegalArgumentException("risk-score.factors: at most " + MAX_FACTORS + " factors, got " + raw.size());
        List<Factor> factors = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < raw.size(); i++) {
            if (!(raw.get(i) instanceof Map<?, ?> fm))
                throw new IllegalArgumentException("risk-score.factors[" + i + "] must be an object");
            Factor f = factor(i, fm);
            if (!seen.add(f.id()))
                throw new IllegalArgumentException("risk-score.factors: duplicate factor id '" + f.id() + "'");
            factors.add(f);
        }
        return new RiskScoreModel(id, entityType, high, scores, scope, Values.trimToNull(m.get("description")), factors,
                watchList(m.get("watchList")), retain(m.get("retainDays"), "retainDays"),
                retain(m.get("retainRuns"), "retainRuns"), maxEntities(m.get("maxEntities")));
    }

    /** Optional per-model entity cap: a whole number 1..{@link #MAX_ENTITIES_CEILING}; absent = the system default. */
    private static Integer maxEntities(Object raw) {
        if (raw == null) return null;
        double d = number(raw, "risk-score.maxEntities");
        if (d != Math.rint(d) || d < 1 || d > MAX_ENTITIES_CEILING)
            throw new IllegalArgumentException("risk-score.maxEntities must be a whole number 1.." + MAX_ENTITIES_CEILING
                    + ", got " + raw);
        return (int) d;
    }

    /** Optional history retention (a whole number >= 1); absent = keep every run. */
    private static Integer retain(Object raw, String key) {
        if (raw == null) return null;
        double d = number(raw, "risk-score." + key);
        if (d != Math.rint(d) || d < 1 || d > 100_000)
            throw new IllegalArgumentException("risk-score." + key + " must be a whole number >= 1, got " + raw);
        return (int) d;
    }

    private static WatchList watchList(Object raw) {
        if (raw == null) return null;
        if (!(raw instanceof Map<?, ?> w))
            throw new IllegalArgumentException("risk-score.watchList must be an object {list, ttlHours}");
        for (Object k : w.keySet())
            if (!Set.of("list", "ttlHours").contains(String.valueOf(k)))
                throw new IllegalArgumentException("risk-score.watchList: unknown key '" + k + "' (expected [list, ttlHours])");
        String list = Values.trimToNull(w.get("list"));
        if (list == null || !LIST_ID.matcher(list).matches())
            throw new IllegalArgumentException("risk-score.watchList.list must be an Entity List id, got '" + list + "'");
        double ttl = number(w.get("ttlHours"), "risk-score.watchList.ttlHours");
        if (ttl != Math.rint(ttl) || ttl < 1 || ttl > MAX_WATCH_TTL_HOURS)
            throw new IllegalArgumentException("risk-score.watchList.ttlHours must be a whole number in 1.."
                    + MAX_WATCH_TTL_HOURS + " (a longer-lived entry needs four-eyes, D-P5), got " + w.get("ttlHours"));
        return new WatchList(list, (int) ttl);
    }

    private static Factor factor(int i, Map<?, ?> fm) {
        String at = "risk-score.factors[" + i + "]";
        for (Object k : fm.keySet())
            if (!FACTOR_KEYS.contains(String.valueOf(k)))
                throw new IllegalArgumentException(at + ": unknown key '" + k + "' (expected " + FACTOR_KEYS + ")");
        String id = ident(fm.get("id"), at + ".id");
        String dataset = Values.trimToNull(fm.get("dataset"));
        if (dataset == null || !SAFE_ID.matcher(dataset).matches())
            throw new IllegalArgumentException(at + ".dataset is required and must be a Dataset id");
        String key = ident(fm.get("key"), at + ".key");
        String measure = Values.trimToNull(fm.get("measure"));
        if (measure == null) throw new IllegalArgumentException(at + ".measure is required (count or agg(field))");
        double weight = number(fm.get("weight"), at + ".weight");
        Double cap = fm.get("cap") == null ? null : number(fm.get("cap"), at + ".cap");
        if (cap != null && cap < 0) throw new IllegalArgumentException(at + ".cap must be >= 0, got " + cap);

        List<Map<String, Object>> filters = new ArrayList<>();
        if (fm.get("filters") != null) {
            if (!(fm.get("filters") instanceof List<?> fl))
                throw new IllegalArgumentException(at + ".filters must be a list of {field, op, value}");
            for (Object o : fl) {
                if (!(o instanceof Map<?, ?> f))
                    throw new IllegalArgumentException(at + ".filters entries must be {field, op, value}");
                Map<String, Object> copy = new LinkedHashMap<>();
                f.forEach((k, v) -> copy.put(String.valueOf(k), v));
                filters.add(copy);
            }
        }
        List<String> evidence = new ArrayList<>();
        if (fm.get("evidence") != null) {
            if (!(fm.get("evidence") instanceof List<?> el))
                throw new IllegalArgumentException(at + ".evidence must be a list of column names");
            for (Object o : el) evidence.add(ident(o, at + ".evidence"));
            if (evidence.size() > MAX_EVIDENCE)
                throw new IllegalArgumentException(at + ".evidence: at most " + MAX_EVIDENCE + " columns");
        }
        Factor f = new Factor(id, Values.trimToNull(fm.get("label")), dataset, key, measure,
                List.copyOf(filters), weight, cap, List.copyOf(evidence));
        // Compile both queries now: an unknown aggregation, operator or unsafe identifier fails the SAVE.
        try {
            MeasureCompiler.compile(f.valueSpec(1));
            MeasureCompiler.compile(f.evidenceSpec(1));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(at + ": " + e.getMessage(), e);
        }
        return f;
    }

    private static String ident(Object v, String what) {
        String s = Values.trimToNull(v);
        if (s == null || !SAFE_IDENT.matcher(s).matches())
            throw new IllegalArgumentException(what + " '" + s + "' must be a plain column/name token");
        return s;
    }

    /** A finite number: a JSON/TOON number, or a string that parses as one. Anything else fails closed. */
    static double number(Object v, String what) {
        double d;
        if (v instanceof Number n) d = n.doubleValue();
        else if (v instanceof String s && !s.isBlank()) {
            try { d = Double.parseDouble(s.trim()); }
            catch (NumberFormatException e) { throw new IllegalArgumentException(what + " must be numeric, got '" + s + "'"); }
        } else throw new IllegalArgumentException(what + " is required and must be numeric");
        if (!Double.isFinite(d)) throw new IllegalArgumentException(what + " must be a finite number, got " + v);
        return d;
    }
}
