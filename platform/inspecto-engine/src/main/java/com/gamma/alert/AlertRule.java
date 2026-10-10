package com.gamma.alert;

import com.gamma.util.ToonHelper;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * One executable alert rule (v4.1, B5 — the execution half of the agent's draft-only
 * {@code diagnose-and-alert} skill; the operator saving the reviewed draft is what arms it),
 * authored via {@code /alerts/rules*} and persisted as an {@code alert-rule} component under
 * {@code <write-root>/registry} (2026-07-18 — the same CRUD contract as Expectation/Decision Rule).
 *
 * <pre>
 *   alert {
 *     name:       high-error-rate          # kebab-case rule name
 *     metric:     error_rate               # error_rate | failed_batches | rejected_files | duration_ms
 *     comparator: gt                       # gt | gte | lt | lte
 *     threshold:  0.05                     # error_rate is a fraction in (0,1]
 *     window:     1h                       # Ns/Nm/Nh/Nd duration, or Nb = last N batches
 *     severity:   WARNING                  # INFO | WARNING | CRITICAL
 *     onPipeline: EVENTS                   # optional; null/absent = every pipeline
 *     description: Error rate too high     # optional; the human title of a fired Alert / Incident
 *     owner:      alice                    # optional; the Subject its alerts are addressed to (absent = appUser)
 *   }
 * </pre>
 *
 * <p><b>Measure rules (BI-5)</b> — replace {@code metric}+{@code window} with a Dataset measure,
 * evaluated over the current at-rest data via the headless BI evaluator on every sweep:
 *
 * <pre>
 *   alert {
 *     name:       low-total-revenue
 *     dataset:    sales_ds                 # a dataset component id
 *     measure:    sum(amount)              # count | agg(field), agg ∈ count/countDistinct/sum/avg/min/max
 *     comparator: lt
 *     threshold:  1000
 *     severity:   WARNING
 *   }
 * </pre>
 *
 * <p><b>Per-entity measure rules ({@code by}, ASSURE-PER-ENTITY-ALERTS-1)</b> — a Dataset measure rule may add
 * {@code by}, one or more key columns of the Dataset. The Measure is then evaluated per group ({@code GROUP BY}
 * the key columns) and every breaching key raises its OWN Alert (and, at critical/error, its own Incident),
 * named for the key. {@code stormCap} (default {@value #DEFAULT_STORM_CAP}) bounds that: above it ONE aggregate
 * storm Alert reports how many keys breached instead of N. Every {@code by} column must exist in the Dataset's
 * Schema — checked when the rule is saved ({@code AlertRoutes}).
 *
 * <pre>
 *   alert {
 *     name:       high-msisdn-spend
 *     dataset:    usage_ds
 *     measure:    sum(amount)
 *     by:         [msisdn]                 # one or more key columns
 *     stormCap:   100                      # optional; above it one storm Alert replaces N
 *     comparator: gt
 *     threshold:  5000
 *     severity:   CRITICAL
 *   }
 * </pre>
 *
 * <p><b>Investigation rules (LA-23)</b> — watch a Measure over a Link Analysis Investigation's Working Set
 * (the derived relation of LA-20) instead of a Dataset. {@code relation} picks one of the Working Set's three
 * relations and {@code measure} is the SAME Measure shorthand a Dataset measure rule takes, over that relation's
 * columns:
 *
 * <pre>
 *   alert {
 *     name:          big-ring
 *     investigation: inv-42                   # an Investigation id
 *     relation:      entities                 # entities | links | excluded (default entities)
 *     measure:       count                    # count | agg(field), agg ∈ count/countDistinct/sum/avg/min/max
 *     comparator:    gt
 *     threshold:     10
 *     severity:      CRITICAL
 *   }
 * </pre>
 *
 * ⚠ Authored only through {@code POST /inv/investigations/{id}/alert-rules} (the optional
 * {@code inspecto-geo-link} module), which applies the Investigation's owner-only / PDP gate and records the
 * binding beside the Investigation. The generic {@code /alerts/rules} routes refuse this shape, and the
 * evaluator ({@link InvestigationMeasureProbe}) answers nothing for a rule with no matching binding — so a rule
 * written around that route (a hand-edited registry file, a generic component write) never evaluates.
 *
 * <p><b>Row-scoping {@code when} (Rules triad condition-tree promotion, 2026-07-18)</b> — a ledger-metric
 * rule may add a {@code when} condition tree (the same {@code query-types} shape Decision Rules author),
 * restricting the metric math to ledger rows matching it (e.g. only batches tagged a particular way).
 * Evaluated in-JVM via {@link com.gamma.query.ConditionTree#filter} before {@link #breached}; applied
 * after the window selects rows, before the metric aggregates them. Not applicable to a measure rule
 * (no ledger rows to scope).
 *
 * <h3>Metric semantics (over the batches ledger, within the window)</h3>
 * <ul>
 *   <li>{@code error_rate} — {@code 1 - sum(total_output_rows)/sum(total_input_rows)} (0 when no input)</li>
 *   <li>{@code failed_batches} — count of {@code status == FAILED}</li>
 *   <li>{@code rejected_files} — {@code sum(rejected_files)} (the column was renamed from
 *       {@code rejected_count} by D2, 2026-09-22, to match this measure's long-standing name)</li>
 *   <li>\u26a0 {@code error_rate} now tells the truth about a LOST row: {@code total_input_rows} counts
 *       what ARRIVED (parsed + rejected), so a file that silently dropped a record no longer reports
 *       0% error. Alert thresholds tuned against the old, always-reconciling numerator may fire where
 *       they used to stay quiet — that is the defect being corrected, not a regression.</li>
 *   <li>{@code duration_ms} — average {@code duration_ms}</li>
 * </ul>
 */
public record AlertRule(String name, String metric, String comparator, double threshold,
                        String window, String severity, String onPipeline,
                        String dataset, String measure, Object when, String maximumAge,
                        String investigation, String relation, String description,
                        List<String> by, int stormCap, String owner, Map<String, Object> valueMeasure,
                        int healAfterSweeps, Map<String, Object> extra, String runbook) {

    /**
     * Every key {@link #fromMap} models (and the R3 {@code shares} envelope key, which the save routes carry
     * themselves). A key outside this set is carried in {@link #extra} (MODULE-REORG-P4-2).
     */
    static final Set<String> MODELLED = Set.of("name", "metric", "comparator", "threshold", "window", "severity",
            "onPipeline", "dataset", "measure", "when", "maximumAge", "investigation", "relation", "description",
            "by", "stormCap", "owner", "valueMeasure", "healAfterSweeps", "shares", "runbook");
    /** The prefix of an author-owned annotation key: kept through every save, never refused. */
    public static final String AUTHOR_PREFIX = "x-";

    /** The {@code stormCap} a {@code by} rule takes when it declares none. */
    public static final int DEFAULT_STORM_CAP = 100;
    /** The {@code healAfterSweeps} a Measure rule takes when it declares none: heal on the first healthy sweep. */
    public static final int DEFAULT_HEAL_AFTER_SWEEPS = 1;
    /** The most consecutive healthy sweeps a rule may demand before healing (ALERT-HEAL-FLAP-1). */
    public static final int MAX_HEAL_AFTER_SWEEPS = 1000;
    /** A {@code by} key column: a plain identifier (it is still quoted wherever it reaches SQL). */
    private static final java.util.regex.Pattern KEY_COLUMN = java.util.regex.Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    /**
     * The owner of a rule no authenticated {@code Subject} authored — a Personal-edition rule, a hand-edited
     * registry file with no {@code owner:}, a Decision Rule's {@code create-alert} consequence. The same
     * placeholder identity the auth-free core already uses ({@code ApiContext.actor}), and it means
     * <b>unowned</b>: its alerts route to everyone, exactly as before owner routing (DUCKLE-C1 residual 2).
     */
    public static final String UNOWNED = "appUser";

    public static final Set<String> METRICS =
            Set.of("error_rate", "failed_batches", "rejected_files", "duration_ms");
    public static final Set<String> COMPARATORS = Set.of("gt", "gte", "lt", "lte");
    public static final Set<String> SEVERITIES = Set.of("INFO", "WARNING", "CRITICAL");
    /**
     * The Working Set relations an Investigation rule may measure (LA-20's {@code ?of=}). ⚠ A mirror of
     * {@code WorkingSetRoutes.COLUMNS}' keys in the optional module, which this engine cannot see —
     * {@code WorkingSetMeasuresTest} there pins the two equal.
     */
    public static final Set<String> INVESTIGATION_RELATIONS = Set.of("entities", "links", "excluded");
    /**
     * The LA-18 value Measures an Investigation rule may watch over the WHOLE Dataset ({@code valueMeasure.name}).
     * ⚠ A mirror of {@code ValueMeasures.ALERTABLE} in the optional module — {@code ValueMeasuresTest} there pins
     * the two equal. The module validates the rest of the block when the rule is bound.
     */
    public static final Set<String> VALUE_MEASURES = Set.of("passThrough", "velocity", "timeToCashOut",
            "cashOutConcentration", "structuring", "benefitTransfer");

    /** Every pre-{@code runbook} caller of the full shape (no linked Runbook). */
    public AlertRule(String name, String metric, String comparator, double threshold,
                     String window, String severity, String onPipeline,
                     String dataset, String measure, Object when, String maximumAge,
                     String investigation, String relation, String description,
                     List<String> by, int stormCap, String owner, Map<String, Object> valueMeasure,
                     int healAfterSweeps, Map<String, Object> extra) {
        this(name, metric, comparator, threshold, window, severity, onPipeline, dataset, measure, when, maximumAge,
                investigation, relation, description, by, stormCap, owner, valueMeasure, healAfterSweeps, extra, null);
    }

    /** Every pre-{@code extra} caller of the full shape (no unmodelled keys). */
    public AlertRule(String name, String metric, String comparator, double threshold,
                     String window, String severity, String onPipeline,
                     String dataset, String measure, Object when, String maximumAge,
                     String investigation, String relation, String description,
                     List<String> by, int stormCap, String owner, Map<String, Object> valueMeasure,
                     int healAfterSweeps) {
        this(name, metric, comparator, threshold, window, severity, onPipeline, dataset, measure, when, maximumAge,
                investigation, relation, description, by, stormCap, owner, valueMeasure, healAfterSweeps, null);
    }

    /** Every pre-{@code healAfterSweeps} caller of the full shape (the default: heal on the first healthy sweep). */
    public AlertRule(String name, String metric, String comparator, double threshold,
                     String window, String severity, String onPipeline,
                     String dataset, String measure, Object when, String maximumAge,
                     String investigation, String relation, String description,
                     List<String> by, int stormCap, String owner, Map<String, Object> valueMeasure) {
        this(name, metric, comparator, threshold, window, severity, onPipeline, dataset, measure, when, maximumAge,
                investigation, relation, description, by, stormCap, owner, valueMeasure, 0, null);
    }

    /** Every pre-{@code valueMeasure} (pre-LA-18) caller of the full shape. */
    public AlertRule(String name, String metric, String comparator, double threshold,
                     String window, String severity, String onPipeline,
                     String dataset, String measure, Object when, String maximumAge,
                     String investigation, String relation, String description,
                     List<String> by, int stormCap, String owner) {
        this(name, metric, comparator, threshold, window, severity, onPipeline, dataset, measure, when, maximumAge,
                investigation, relation, description, by, stormCap, owner, null, 0);
    }

    /** The historic ledger-metric rule shape (every pre-BI-5 caller). */
    public AlertRule(String name, String metric, String comparator, double threshold,
                     String window, String severity, String onPipeline) {
        this(name, metric, comparator, threshold, window, severity, onPipeline, null, null, null, null);
    }

    /** The BI-5 measure-rule shape (every pre-{@code when} caller). */
    public AlertRule(String name, String metric, String comparator, double threshold,
                     String window, String severity, String onPipeline,
                     String dataset, String measure) {
        this(name, metric, comparator, threshold, window, severity, onPipeline, dataset, measure, null, null);
    }

    /** Every pre-{@code maximumAge} (pre-freshness) caller. */
    public AlertRule(String name, String metric, String comparator, double threshold,
                     String window, String severity, String onPipeline,
                     String dataset, String measure, Object when) {
        this(name, metric, comparator, threshold, window, severity, onPipeline, dataset, measure, when, null);
    }

    /** Every pre-{@code investigation} (pre-LA-23) caller. */
    public AlertRule(String name, String metric, String comparator, double threshold,
                     String window, String severity, String onPipeline,
                     String dataset, String measure, Object when, String maximumAge) {
        this(name, metric, comparator, threshold, window, severity, onPipeline, dataset, measure, when, maximumAge,
                null, null);
    }

    /** Every pre-{@code description} caller (R2-05: the optional human-readable rule name). */
    public AlertRule(String name, String metric, String comparator, double threshold,
                     String window, String severity, String onPipeline,
                     String dataset, String measure, Object when, String maximumAge,
                     String investigation, String relation) {
        this(name, metric, comparator, threshold, window, severity, onPipeline, dataset, measure, when, maximumAge,
                investigation, relation, null);
    }

    /** Every pre-{@code by}, pre-{@code owner} caller: no per-entity grouping, and {@link #UNOWNED}. */
    public AlertRule(String name, String metric, String comparator, double threshold,
                     String window, String severity, String onPipeline,
                     String dataset, String measure, Object when, String maximumAge,
                     String investigation, String relation, String description) {
        this(name, metric, comparator, threshold, window, severity, onPipeline, dataset, measure, when, maximumAge,
                investigation, relation, description, null, 0, null);
    }

    public AlertRule {
        require(name != null && !name.isBlank(), "alert.name is required");
        extra = (extra == null || extra.isEmpty()) ? Map.of()
                : java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(extra));
        description = (description == null || description.isBlank()) ? null : description.trim();
        runbook = (runbook == null || runbook.isBlank()) ? null : runbook.trim();   // the Runbook component it links
        owner = (owner == null || owner.isBlank()) ? UNOWNED : owner.trim();
        metric = lower(metric);
        comparator = lower(comparator);
        // Absent/blank → the spec default (ConfigSpecs.alert: gt). ⚠ Set.of(..).contains(null) THROWS, so
        // a missing comparator used to escape validation as an NPE (HTTP 500) instead of taking the default.
        if (comparator == null || comparator.isEmpty()) comparator = "gt";
        severity = severity == null ? null : severity.trim().toUpperCase(Locale.ROOT);
        if (severity == null || severity.isEmpty()) severity = "WARNING";   // ConfigSpecs.alert default; same NPE trap
        window = window == null ? null : window.trim().toLowerCase(Locale.ROOT);
        dataset = (dataset == null || dataset.isBlank()) ? null : dataset.trim();
        measure = (measure == null || measure.isBlank()) ? null : measure.trim();
        try {
            com.gamma.query.ConditionTree.requireGroupRoot(when);   // a bare root / a string used to match ALL rows
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("alert.when: " + e.getMessage());
        }
        when = (when instanceof Map<?, ?> m && !m.isEmpty()) ? when : null;
        maximumAge = (maximumAge == null || maximumAge.isBlank()) ? null : maximumAge.trim().toLowerCase(Locale.ROOT);
        investigation = (investigation == null || investigation.isBlank()) ? null : investigation.trim();
        relation = (relation == null || relation.isBlank()) ? null : relation.trim().toLowerCase(Locale.ROOT);
        valueMeasure = (valueMeasure == null || valueMeasure.isEmpty()) ? null
                : java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(valueMeasure));
        if (valueMeasure != null) {
            // LA-18 value-measure rule: an Investigation rule whose Measure is a named value Measure over the WHOLE
            // Dataset the Investigation is bound to. It watches the COUNT of entities breaching the Measure's own
            // thresholds (inside the block) and fires when that count is above 0 — one Alert per rule, never per
            // entity (G-42) — so the comparator and threshold are fixed here, as a freshness rule's are.
            require(investigation != null, "alert.valueMeasure requires alert.investigation");
            require(measure == null && relation == null,
                    "an alert.valueMeasure rule takes no alert.measure or alert.relation (the block names the Measure)");
            require(metric == null && dataset == null && maximumAge == null && window == null && when == null,
                    "an alert.valueMeasure rule takes no metric, dataset, maximumAge, window or when");
            require(VALUE_MEASURES.contains(String.valueOf(valueMeasure.get("name"))),
                    "alert.valueMeasure.name must be one of " + VALUE_MEASURES);
            comparator = "gte";
            threshold = 1;
        } else if (investigation != null) {
            // Investigation rule (LA-23): a scalar Measure over one relation of an Investigation's Working Set.
            // It reads the sealed log — never a Dataset, a ledger or a clock — so none of those shapes apply.
            require(dataset == null, "an investigation alert (investigation:) must not also declare a dataset");
            require(maximumAge == null, "an investigation alert (investigation:) must not also declare maximumAge");
            require(metric == null, "an investigation alert (investigation:) must not also declare a ledger metric");
            require(window == null, "an investigation alert (investigation:) takes no window (it reads the "
                    + "Working Set)");
            require(when == null, "alert.when scopes ledger rows; an investigation alert has none");
            if (relation == null) relation = "entities";   // (a valueMeasure rule never reaches here)
            require(INVESTIGATION_RELATIONS.contains(relation),
                    "alert.relation must be one of " + INVESTIGATION_RELATIONS);
            require(com.gamma.query.DatasetMeasureProbe.validMeasure(measure),
                    "alert.measure must be count or agg(field) with agg ∈ count/countDistinct/sum/avg/min/max");
        } else if (maximumAge != null) {
            // Freshness rule (DUCKLE-C1): "this Dataset must have published within maximumAge".
            // It is evaluated on a CLOCK against the last dataset.write Signal, so none of the
            // ledger-metric vocabulary applies — and a batch (Nb) window is not a clock at all,
            // which is why the shape here is \d+[smhd] and not the metric window's \d+[smhdb].
            require(dataset != null, "alert.maximumAge requires alert.dataset");
            // Event-date mode (LA-DAILY-INGEST-1 T8, operator 2026-10-06): `measure: max(<column>)` ages the NEWEST
            // event date in the data instead of the last publication. Any other measure stays refused.
            require(measure == null || EVENT_DATE_MEASURE.matcher(measure).matches(),
                    "a freshness alert (maximumAge:) takes no measure, or max(<event date column>) for event-date freshness");
            require(metric == null, "a freshness alert (maximumAge:) must not also declare a ledger metric");
            require(window == null, "a freshness alert (maximumAge:) takes no window (maximumAge IS the window)");
            require(when == null, "alert.when scopes ledger rows; a freshness alert has none");
            require(maximumAge.matches("\\d+[smhd]"), "alert.maximumAge must be Ns/Nm/Nh/Nd (e.g. 6h, 1d)");
            require(Long.parseLong(maximumAge.substring(0, maximumAge.length() - 1)) > 0,
                    "alert.maximumAge must be a positive duration");
            // A freshness breach is "age exceeded", not a comparator over a threshold: both are fixed
            // here rather than demanded of the author, so no rule can declare a comparator that would
            // silently invert the check.
            comparator = "gt";
            threshold = 0;
        } else if (dataset != null) {
            // Measure rule (BI-5): a scalar Measure over a Dataset; the ledger window does not apply.
            require(metric == null, "a measure alert (dataset:) must not also declare a ledger metric");
            require(window == null, "a measure alert (dataset:) takes no window (it reads current data)");
            require(when == null, "alert.when scopes ledger rows; a measure alert (dataset:) has none");
            require(com.gamma.query.DatasetMeasureProbe.validMeasure(measure),
                    "alert.measure must be count or agg(field) with agg ∈ count/countDistinct/sum/avg/min/max");
        } else {
            require(measure == null, "alert.measure requires alert.dataset");
            require(metric != null && METRICS.contains(metric), "alert.metric must be one of " + METRICS);   // Set.of(..).contains(null) throws
            require(window != null && window.matches("\\d+[smhdb]"), "alert.window must be Ns/Nm/Nh/Nd or Nb");
        }
        require(COMPARATORS.contains(comparator), "alert.comparator must be one of " + COMPARATORS);
        require(SEVERITIES.contains(severity), "alert.severity must be one of " + SEVERITIES);
        require(relation == null || investigation != null, "alert.relation requires alert.investigation");
        if (maximumAge == null) require(threshold > 0, "alert.threshold must be a positive number");
        onPipeline = (onPipeline == null || onPipeline.isBlank()) ? null : onPipeline.trim();
        by = by == null ? List.of() : by.stream().map(c -> c == null ? "" : c.trim()).filter(c -> !c.isEmpty())
                .distinct().toList();
        if (healAfterSweeps != 0) {
            require(dataset != null && maximumAge == null && investigation == null,
                    "alert.healAfterSweeps applies to a Dataset measure rule (dataset: + measure:); this rule is not one");
            require(healAfterSweeps >= 1 && healAfterSweeps <= MAX_HEAL_AFTER_SWEEPS,
                    "alert.healAfterSweeps must be a whole number from 1 to " + MAX_HEAL_AFTER_SWEEPS);
        } else {
            healAfterSweeps = DEFAULT_HEAL_AFTER_SWEEPS;
        }
        if (by.isEmpty()) {
            require(stormCap == 0, "alert.stormCap requires alert.by");
        } else {
            require(dataset != null && maximumAge == null && investigation == null,
                    "alert.by groups a Dataset measure rule (dataset: + measure:); this rule is not one");
            for (String c : by)
                require(KEY_COLUMN.matcher(c).matches(), "alert.by column '" + c + "' must be a plain column name");
            if (stormCap == 0) stormCap = DEFAULT_STORM_CAP;
            require(stormCap >= 1, "alert.stormCap must be a positive whole number");
        }
    }

    /** Whether this measure rule is evaluated per key ({@code by}) rather than as one aggregate. */
    public boolean isGrouped() {
        return !by.isEmpty();
    }

    /**
     * Whether this rule has a real owner — a {@code Subject} id — rather than {@link #UNOWNED}. An owned rule's
     * alerts are addressed to that Subject alone; an unowned one's go to everyone, as they always did.
     */
    public boolean isOwned() {
        return !UNOWNED.equals(owner);
    }

    /** Whether this is a BI-5 measure rule (a Dataset measure) vs a ledger-metric rule. */
    public boolean isMeasureRule() {
        return dataset != null && maximumAge == null;
    }

    /** Whether this is an LA-18 value-measure rule: an Investigation rule over the whole Dataset it is bound to. */
    public boolean isValueMeasureRule() {
        return valueMeasure != null;
    }

    /** Whether this is an LA-23 rule over an Investigation's Working Set — disjoint from the other three kinds. */
    public boolean isInvestigationRule() {
        return investigation != null;
    }

    /**
     * Whether this is a DUCKLE-C1 <b>freshness</b> rule — a Dataset that must have published within
     * {@link #maximumAge}. ⚠ Deliberately disjoint from {@link #isMeasureRule()}: both are authored
     * with {@code dataset:}, and an evaluator that tested only {@code dataset != null} would run a
     * freshness rule through the measure probe with a null measure.
     */
    public boolean isFreshnessRule() {
        return maximumAge != null;
    }

    /** {@code max(<column>)}: the one measure a freshness rule may carry (event-date mode). */
    private static final java.util.regex.Pattern EVENT_DATE_MEASURE =
            java.util.regex.Pattern.compile("max\\(\\s*([A-Za-z_][A-Za-z0-9_]*)\\s*\\)");

    /**
     * The event-date column of an EVENT-DATE freshness rule ({@code maximumAge} + {@code measure: max(col)}), else
     * {@code null} (a write-time freshness rule, or not a freshness rule). Event-date mode ages the newest event date in
     * the data: a DATE counts as covered through its end, so day D is fresh until D+1 00:00 UTC plus {@code maximumAge}.
     */
    public String eventDateColumn() {
        if (maximumAge == null || measure == null) return null;
        java.util.regex.Matcher m = EVENT_DATE_MEASURE.matcher(measure);
        return m.matches() ? m.group(1) : null;
    }

    /** The elapsed-time span a freshness rule allows between publications. */
    public Duration maximumAgeDuration() {
        long n = Long.parseLong(maximumAge.substring(0, maximumAge.length() - 1));
        return switch (maximumAge.charAt(maximumAge.length() - 1)) {
            case 's' -> Duration.ofSeconds(n);
            case 'm' -> Duration.ofMinutes(n);
            case 'h' -> Duration.ofHours(n);
            case 'd' -> Duration.ofDays(n);
            default -> throw new IllegalStateException("not a duration: " + maximumAge);
        };
    }

    /** Parse + validate from the decoded {@code alert { … }} map (or, since the ComponentStore
     *  promotion, a stored {@code alert-rule} component's content — same flat shape, no wrapper). */
    public static AlertRule fromMap(Map<String, Object> alert) {
        require(alert != null, "missing 'alert' block");
        return new AlertRule(
                str(alert.get("name")),
                str(alert.get("metric")),
                str(alert.get("comparator")),
                alert.get("maximumAge") == null ? number(alert.get("threshold")) : 0,
                str(alert.get("window")),
                str(alert.get("severity")),
                str(alert.get("onPipeline")),
                str(alert.get("dataset")),
                str(alert.get("measure")),
                alert.get("when"),
                str(alert.get("maximumAge")),
                str(alert.get("investigation")),
                str(alert.get("relation")),
                str(alert.get("description")),
                columns(alert.get("by")),
                alert.get("stormCap") == null ? 0 : wholeNumber(alert.get("stormCap"), "alert.stormCap"),
                str(alert.get("owner")),
                valueMeasureBlock(alert.get("valueMeasure")),
                alert.get("healAfterSweeps") == null ? 0
                        : wholeNumber(alert.get("healAfterSweeps"), "alert.healAfterSweeps"),
                unmodelled(alert),
                str(alert.get("runbook")));
    }

    /** The keys of {@code alert} this record does not model, in authored order (MODULE-REORG-P4-2). */
    private static Map<String, Object> unmodelled(Map<String, Object> alert) {
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        for (Map.Entry<String, Object> e : alert.entrySet())
            if (!MODELLED.contains(e.getKey())) out.put(e.getKey(), e.getValue());
        return out;
    }

    /**
     * Load a raw {@code {alert: {…}}} TOON file — the agent's {@code diagnose-and-alert} draft shape
     * ({@link com.gamma.assist.Diagnosis#suggestedAlertRuleToon}) and a generic repo-hygiene sanity
     * check ({@code RepoSpacesConfigValidationTest}) still parse this shape directly; the running
     * engine itself no longer boot-scans for it (rules are ComponentStore-backed — see the class doc).
     */
    @SuppressWarnings("unchecked")
    public static AlertRule load(Path path) throws IOException {
        Map<String, Object> root = ToonHelper.load(path.toString());
        Object alert = root.get("alert");
        require(alert instanceof Map, path + " has no 'alert' block");
        return fromMap((Map<String, Object>) alert);
    }

    /** True when the window counts batches ({@code Nb}) rather than elapsed time. */
    public boolean batchWindow() {
        return window.endsWith("b");
    }

    /** The batch count for an {@code Nb} window. */
    public int windowBatches() {
        return Integer.parseInt(window.substring(0, window.length() - 1));
    }

    /** The elapsed-time span for a duration window. */
    public Duration windowDuration() {
        long n = Long.parseLong(window.substring(0, window.length() - 1));
        return switch (window.charAt(window.length() - 1)) {
            case 's' -> Duration.ofSeconds(n);
            case 'm' -> Duration.ofMinutes(n);
            case 'h' -> Duration.ofHours(n);
            case 'd' -> Duration.ofDays(n);
            default -> throw new IllegalStateException("not a duration window: " + window);
        };
    }

    /** Apply the comparator to a computed metric value. */
    public boolean breached(double value) {
        return switch (comparator) {
            case "gt" -> value > threshold;
            case "gte" -> value >= threshold;
            case "lt" -> value < threshold;
            case "lte" -> value <= threshold;
            default -> false;
        };
    }

    /** JSON-ready view for {@code GET /alerts/rules}; also the stored shape, so it carries {@link #extra} too. */
    public Map<String, Object> toMap() {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("name", name);
        if (description != null) m.put("description", description);
        if (runbook != null) m.put("runbook", runbook);
        if (metric != null) m.put("metric", metric);
        if (dataset != null) m.put("dataset", dataset);
        if (investigation != null) m.put("investigation", investigation);
        if (relation != null) m.put("relation", relation);
        if (measure != null) m.put("measure", measure);
        if (valueMeasure != null) m.put("valueMeasure", valueMeasure);
        if (maximumAge != null) m.put("maximumAge", maximumAge);
        m.put("comparator", comparator);
        m.put("threshold", threshold);
        if (window != null) m.put("window", window);
        m.put("severity", severity);
        if (onPipeline != null) m.put("onPipeline", onPipeline);
        if (when != null) m.put("when", when);
        if (!by.isEmpty()) {
            m.put("by", by);
            m.put("stormCap", stormCap);
        }
        if (healAfterSweeps > DEFAULT_HEAL_AFTER_SWEEPS) m.put("healAfterSweeps", healAfterSweeps);
        // Only a real owner is written: this map is also the stored component, whose `owner` key is the R3
        // sharing envelope's (ComponentAccess) - persisting the appUser placeholder there would make it look
        // claimed, and R3 lets only an owner or an access admin change a claimed envelope.
        if (isOwned()) m.put("owner", owner);
        extra.forEach(m::putIfAbsent);   // unmodelled keys ride along; never override a modelled one
        return m;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> valueMeasureBlock(Object v) {
        if (v == null) return null;
        require(v instanceof Map<?, ?>, "alert.valueMeasure must be a block {name, …}");
        return (Map<String, Object>) v;
    }

    private static void require(boolean ok, String message) {
        if (!ok) throw new IllegalArgumentException(message);
    }

    private static String str(Object v) {
        if (v == null) return null;
        String s = String.valueOf(v).trim();
        return s.isEmpty() ? null : s;
    }

    private static String lower(String v) {
        return v == null ? null : v.trim().toLowerCase(Locale.ROOT);
    }

    /** {@code by} as authored: a list, or one comma-separated scalar. */
    private static List<String> columns(Object v) {
        if (v == null) return null;
        List<?> items = v instanceof List<?> l ? l : List.of(String.valueOf(v).split(","));
        return items.stream().map(o -> o == null ? null : String.valueOf(o)).toList();
    }

    private static int wholeNumber(Object v, String field) {
        String s = str(v);
        double d;
        try {
            d = v instanceof Number n ? n.doubleValue() : Double.parseDouble(s == null ? "" : s);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(field + " must be a positive whole number, got '" + s + "'");
        }
        require(d == Math.rint(d) && d >= 1 && d <= Integer.MAX_VALUE, field + " must be a positive whole number");
        return (int) d;
    }

    private static double number(Object v) {
        if (v instanceof Number n) return n.doubleValue();
        String s = str(v);
        require(s != null, "alert.threshold is required");
        try {
            return Double.parseDouble(s);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("alert.threshold must be a number, got '" + s + "'");
        }
    }
}
