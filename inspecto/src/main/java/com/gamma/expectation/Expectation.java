package com.gamma.expectation;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import static com.gamma.util.Values.trimToNull;

/**
 * One data-quality <b>Expectation</b> (ING-6) — the data-quality third of the Rules triad (Expectation /
 * Alert Rule / Decision Rule; see {@code docs/GLOSSARY.md}). An Expectation validates the records of a
 * target's at-rest data against a Schema-level constraint on one {@code column}; a failing evaluation
 * counts violating records, opens a correlated Incident and fans out an {@code EXPECTATION_FAILED} signal.
 *
 * <p>Authored over HTTP ({@code /expectations*}) and persisted as an {@code expectation} component (its
 * {@code lastResult}/{@code createdAt}/{@code updatedAt} bookkeeping ride the stored content alongside
 * these validated fields). This record is the validated <em>config</em> shape; {@link #fromMap} mirrors
 * the mock contract in {@code expectations.handler.ts} and {@link ExpectationEvaluator} turns it into the
 * server-built violation-count SQL.
 *
 * <pre>
 *   kind         non_null | range | regex | referential | condition | baseline
 *   targetType   pipeline | job                 # the target's at-rest data is queried
 *   target       &lt;name&gt;                        # physicalRef under the space data root
 *   column       &lt;identifier&gt;                  # the column the check applies to (not 'condition')
 *   min,max      &lt;number&gt;                       # range bounds (at least one)
 *   pattern      &lt;regex&gt;                        # regex the value must match
 *   refDataset,refColumn                         # referential lookup relation + column
 *   when         &lt;condition tree&gt;               # 'condition' kind: the violation predicate itself
 *   baselineWindow, measures, columns, maxIncrease, maxDecrease, limitUnit,
 *   groupBy, requireExistingGroups               # 'baseline' kind: see {@link Baseline}
 *   severity     MINOR | MAJOR | CRITICAL        # incident/signal severity on failure
 *   enabled      bool                            # evaluate-all skips disabled expectations
 * </pre>
 *
 * <p><b>{@code condition} kind (Rules triad condition-tree promotion, 2026-07-18)</b> — the author
 * writes an arbitrary {@code when} condition tree (the same {@code query-types} shape Decision Rules
 * author); {@link com.gamma.query.ConditionSql} compiles it straight to the violation predicate, so
 * unlike the other four kinds it needs no {@code column} — the tree names its own field(s), and can
 * span several columns or use any {@code query-types} operator {@code non_null}/{@code range} can't
 * (e.g. {@code contains}, multi-column {@code AND}/{@code OR}). {@code when} is required for this
 * kind and ignored for the other four (each keeps its own hand-built predicate).
 *
 * <p><b>{@code baseline} kind (DUCKLE-C8, 2026-09-24)</b> — not a row predicate at all: it profiles the
 * target's current input and compares it against the median of the last N <em>accepted</em> profiles
 * ({@link BaselineEvaluator}; history in {@link BaselineProfileStore}). Its parameters are the flat keys of
 * {@link Baseline}; {@code baseline} is {@code null} for every other kind.
 */
public record Expectation(String name, String description, String targetType, String target, String column,
                          String kind, Double min, Double max, String pattern,
                          String refDataset, String refColumn, Object when, String severity, boolean enabled,
                          Baseline baseline) {

    public static final Set<String> KINDS = Set.of("non_null", "range", "regex", "referential", "condition",
            "baseline");
    public static final Set<String> TARGET_TYPES = Set.of("pipeline", "job");
    public static final Set<String> SEVERITIES = Set.of("MINOR", "MAJOR", "CRITICAL");

    public Expectation {
        require(name != null && !name.isBlank(), "expectation.name is required");
        kind = lower(kind);
        require(KINDS.contains(kind), "expectation.kind must be one of " + KINDS);
        targetType = targetType == null ? "pipeline" : targetType.trim().toLowerCase(Locale.ROOT);
        require(TARGET_TYPES.contains(targetType), "expectation.targetType must be one of " + TARGET_TYPES);
        require(target != null && !target.isBlank(), "expectation.target is required");
        boolean namesOwnColumns = "condition".equals(kind) || "baseline".equals(kind);
        require(namesOwnColumns || (column != null && !column.isBlank()),
                "expectation.column is required (except for kinds: condition, baseline)");
        target = target.trim();
        column = column == null ? null : column.trim();
        severity = severity == null ? "MAJOR" : severity.trim().toUpperCase(Locale.ROOT);
        require(SEVERITIES.contains(severity), "expectation.severity must be one of " + SEVERITIES);
        pattern = trimToNull(pattern);
        refDataset = trimToNull(refDataset);
        refColumn = trimToNull(refColumn);
        when = (when instanceof Map<?, ?> m && !m.isEmpty()) ? when : null;

        switch (kind) {
            case "range" -> require(min != null || max != null,
                    "expectation.range needs at least one of min/max");
            case "regex" -> require(pattern != null, "expectation.regex needs a pattern");
            case "referential" -> require(refDataset != null && refColumn != null,
                    "expectation.referential needs refDataset and refColumn");
            case "condition" -> require(when != null, "expectation.condition needs a 'when' condition tree");
            case "baseline" -> require(baseline != null, "expectation.baseline needs its baseline parameters");
            default -> { /* non_null carries no extra params */ }
        }
        if (!"baseline".equals(kind)) baseline = null;   // ignored for every other kind, like 'when'
    }

    /**
     * The {@code baseline} kind's parameters — flat keys on the expectation body. One limit pair applies to
     * every checked cell (each measure × column × group); an author wanting different limits per measure
     * writes a second baseline Expectation on the same target.
     *
     * @param window                the last N accepted profiles whose median is the baseline (1..{@value #MAX_WINDOW})
     * @param measures              which profile measures are compared ({@link #MEASURES}); default {@code row_count}
     * @param columns               the columns the per-column measures apply to (required when one is chosen)
     * @param maxIncrease           allowed rise over the baseline; {@code null} = unbounded upward
     * @param maxDecrease           allowed fall under the baseline; {@code null} = unbounded downward
     * @param limitUnit             {@code percent} (of the baseline) or {@code absolute}
     * @param groupBy               profile per group of these columns instead of over the whole input
     * @param requireExistingGroups a group the baseline has but the current input lacks is a violation —
     *                              the missing partition that normal-looking totals hide
     */
    public record Baseline(int window, List<String> measures, List<String> columns, Double maxIncrease,
                           Double maxDecrease, String limitUnit, List<String> groupBy,
                           boolean requireExistingGroups) {

        public static final int DEFAULT_WINDOW = 7;
        public static final int MAX_WINDOW = 100;
        /** Cap on {@code columns} / {@code groupBy} — every profiled column costs five aggregates per group. */
        public static final int MAX_COLUMNS = 50;
        public static final List<String> MEASURES = List.of("row_count", "null_count", "null_rate",
                "distinct_count", "min", "max", "mean");
        public static final Set<String> LIMIT_UNITS = Set.of("percent", "absolute");
        private static final java.util.regex.Pattern IDENT = java.util.regex.Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

        public Baseline {
            require(window >= 1 && window <= MAX_WINDOW,
                    "expectation.baselineWindow must be between 1 and " + MAX_WINDOW);
            measures = measures == null || measures.isEmpty() ? List.of("row_count") : List.copyOf(measures);
            for (String ms : measures)
                require(MEASURES.contains(ms), "expectation.measures entries must be one of " + MEASURES);
            columns = idents(columns, "columns");
            groupBy = idents(groupBy, "groupBy");
            boolean perColumn = measures.stream().anyMatch(ms -> !"row_count".equals(ms));
            require(!perColumn || !columns.isEmpty(),
                    "expectation.columns is required when a per-column measure is chosen");
            require(perColumn || columns.isEmpty(),
                    "expectation.columns needs a per-column measure (only row_count was chosen)");
            require(maxIncrease != null || maxDecrease != null,
                    "expectation.baseline needs at least one of maxIncrease/maxDecrease");
            require(maxIncrease == null || (Double.isFinite(maxIncrease) && maxIncrease >= 0),
                    "expectation.maxIncrease must be a non-negative number");
            require(maxDecrease == null || (Double.isFinite(maxDecrease) && maxDecrease >= 0),
                    "expectation.maxDecrease must be a non-negative number");
            limitUnit = limitUnit == null ? "percent" : limitUnit.trim().toLowerCase(Locale.ROOT);
            require(LIMIT_UNITS.contains(limitUnit), "expectation.limitUnit must be one of " + LIMIT_UNITS);
            require(!requireExistingGroups || !groupBy.isEmpty(),
                    "expectation.requireExistingGroups needs groupBy");
        }

        private static List<String> idents(List<String> raw, String key) {
            if (raw == null) return List.of();
            List<String> out = new ArrayList<>();
            for (String c : raw) {
                require(c != null && IDENT.matcher(c).matches(),
                        "expectation." + key + " entries must be plain column identifiers, got '" + c + "'");
                if (!out.contains(c)) out.add(c);
            }
            require(out.size() <= MAX_COLUMNS, "expectation." + key + " allows at most " + MAX_COLUMNS + " columns");
            return List.copyOf(out);
        }

        static Baseline fromMap(Map<String, Object> m) {
            Double window = number(m.get("baselineWindow"));
            require(window == null || window == Math.rint(window),
                    "expectation.baselineWindow must be a whole number");
            return new Baseline(window == null ? DEFAULT_WINDOW : window.intValue(),
                    strings(m.get("measures"), "measures"), strings(m.get("columns"), "columns"),
                    number(m.get("maxIncrease")), number(m.get("maxDecrease")),
                    trimToNull(m.get("limitUnit")), strings(m.get("groupBy"), "groupBy"),
                    "true".equalsIgnoreCase(String.valueOf(m.get("requireExistingGroups"))));
        }

        void putInto(Map<String, Object> m) {
            m.put("baselineWindow", window);
            m.put("measures", measures);
            m.put("columns", columns);
            m.put("maxIncrease", maxIncrease);
            m.put("maxDecrease", maxDecrease);
            m.put("limitUnit", limitUnit);
            m.put("groupBy", groupBy);
            m.put("requireExistingGroups", requireExistingGroups);
        }

        private static List<String> strings(Object v, String key) {
            if (v == null) return null;
            require(v instanceof List<?>, "expectation." + key + " must be a list");
            List<String> out = new ArrayList<>();
            for (Object o : (List<?>) v) out.add(o == null ? null : String.valueOf(o).trim());
            return out;
        }
    }

    /** Parse + validate from the decoded expectation map (the upsert body or a stored component's content). */
    public static Expectation fromMap(Map<String, Object> m) {
        require(m != null, "missing expectation body");
        return new Expectation(
                trimToNull(m.get("name")),
                trimToNull(m.get("description")),
                trimToNull(m.get("targetType")),
                trimToNull(m.get("target")),
                trimToNull(m.get("column")),
                trimToNull(m.get("kind")),
                number(m.get("min")),
                number(m.get("max")),
                trimToNull(m.get("pattern")),
                trimToNull(m.get("refDataset")),
                trimToNull(m.get("refColumn")),
                m.get("when"),
                trimToNull(m.get("severity")),
                m.get("enabled") == null || !"false".equalsIgnoreCase(String.valueOf(m.get("enabled"))),
                "baseline".equalsIgnoreCase(String.valueOf(m.get("kind")).trim()) ? Baseline.fromMap(m) : null);
    }

    /** JSON/TOON-ready view of the validated config fields (bookkeeping keys are added by the route). */
    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("description", description == null ? "" : description);
        m.put("targetType", targetType);
        m.put("target", target);
        m.put("column", column);
        m.put("kind", kind);
        m.put("min", min);
        m.put("max", max);
        m.put("pattern", pattern);
        m.put("refDataset", refDataset);
        m.put("refColumn", refColumn);
        if (when != null) m.put("when", when);
        if (baseline != null) baseline.putInto(m);
        m.put("severity", severity);
        m.put("enabled", enabled);
        return m;
    }

    private static void require(boolean ok, String message) {
        if (!ok) throw new IllegalArgumentException(message);
    }

    private static String lower(String v) {
        return v == null ? null : v.trim().toLowerCase(Locale.ROOT);
    }

    private static Double number(Object v) {
        if (v == null) return null;
        if (v instanceof Number n) return n.doubleValue();
        String s = String.valueOf(v).trim();
        if (s.isEmpty()) return null;
        try {
            return Double.parseDouble(s);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("expectation numeric bound must be a number, got '" + s + "'");
        }
    }
}
