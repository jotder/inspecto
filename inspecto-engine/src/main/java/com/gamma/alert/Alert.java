package com.gamma.alert;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * One fired alert (v4.1, B5): a rule whose metric breached its threshold for a pipeline's recent
 * batches. Summaries only — no data-plane values. Backs {@code GET /alerts}, newest first.
 *
 * <p>R2-05: {@link #message} and {@link #title} are the words an operator READS on the Alert and the
 * Incident it raises, so they say the comparison in words and group the numbers. The machine ids
 * (rule, metric/measure, comparator, raw threshold and value) stay in this record's fields and in the
 * managed objects' attributes — nothing parses the sentence.
 */
public record Alert(String rule, String severity, String pipeline, String metric, double value,
                    String comparator, double threshold, String window, long epochMillis,
                    String message) {

    /** Ledger metric id → the words an operator reads; anything else goes through {@link #metricLabel}'s fallback. */
    private static final Map<String, String> METRIC_LABELS = Map.of(
            "error_rate", "Error rate",
            "failed_batches", "Failed batches",
            "rejected_files", "Rejected files",
            "duration_ms", "Average duration (ms)");

    private static final Pattern MEASURE = Pattern.compile("([A-Za-z]+)\\(([A-Za-z_][A-Za-z0-9_]*)\\)");

    static Alert of(AlertRule r, String pipeline, double value, long epochMillis) {
        return of(r, pipeline, pipeline, value, epochMillis);
    }

    /**
     * As {@link #of(AlertRule, String, double, long)}, but the sentence names the scope by
     * {@code scopeLabel} (a Dataset's readable name, see {@code AlertService#datasetLabel}) while the
     * {@link #pipeline} field keeps the machine id {@code pipeline}.
     */
    static Alert of(AlertRule r, String pipeline, String scopeLabel, double value, long epochMillis) {
        // A freshness rule (DUCKLE-C1) has no metric, measure, window or threshold — its value is the
        // AGE in seconds, so it gets its own sentence rather than being forced through the comparator
        // phrasing, which would read "freshness is 7200 (threshold gt 0 over 1h)".
        if (r.isFreshnessRule()) {
            String freshMsg = String.format(java.util.Locale.ROOT,
                    "%s: dataset %s has not published for %ss (freshness limit %s)",
                    r.severity(), scopeLabel, trim(value), r.maximumAge());
            return new Alert(r.name(), r.severity(), pipeline, "freshness", value, r.comparator(),
                    r.threshold(), r.maximumAge(), epochMillis, freshMsg);
        }
        // A measure rule (BI-5) has no ledger metric/window: label it by its measure over its dataset.
        // An Investigation rule (LA-23) is labelled by relation + measure, over the SEALED Working Set — it never
        // reads current data, and saying so keeps the alert from over-claiming.
        String metricLabel = r.metric() != null ? r.metric()
                : r.isInvestigationRule() ? r.relation() + " " + r.measure() : r.measure();
        String windowLabel = r.window() != null
                ? (r.batchWindow() ? "the last " + r.windowBatches() + " batches" : "the last " + r.window())
                : r.isInvestigationRule() ? "the sealed Working Set" : "current data";
        String against = switch (r.comparator()) {
            case "gt" -> "above";
            case "gte" -> "at or above";
            case "lt" -> "below";
            case "lte" -> "at or below";
            default -> r.comparator();
        };
        String msg = String.format(Locale.ROOT,
                "%s: %s is %s, %s the threshold of %s (over %s)",
                r.severity(), subject(r, scopeLabel), number(value), against, number(r.threshold()), windowLabel);
        return new Alert(r.name(), r.severity(), pipeline, metricLabel, value, r.comparator(),
                r.threshold(), r.window(), epochMillis, msg);
    }

    /**
     * The title of the ALERT / INCIDENT objects a firing of {@code r} over {@code scope} opens: the
     * rule's {@link AlertRule#description} when it has one, else a sentence built from what it watches
     * (e.g. {@code Sum of exposure_sar on fraud_cases_open is above 298,668}).
     */
    static String title(AlertRule r, String scope) {
        if (r.description() != null) return r.description() + " — " + scope;
        if (r.isFreshnessRule()) return "Dataset " + scope + " has not published within " + r.maximumAge();
        String phrase = switch (r.comparator()) {
            case "gt" -> "is above";
            case "gte" -> "is at least";
            case "lt" -> "is below";
            case "lte" -> "is at most";
            default -> r.comparator();
        };
        return subject(r, scope) + " " + phrase + " " + number(r.threshold());
    }

    /** What was measured, where: {@code Sum of exposure_sar on fraud_cases_open}, {@code Error rate on EVENTS}. */
    private static String subject(AlertRule r, String scope) {
        if (r.metric() != null) return metricLabel(r.metric()) + " on " + scope;
        if (r.isInvestigationRule()) return measureLabel(r.measure()) + " of " + r.relation() + " in " + scope;
        return measureLabel(r.measure()) + " on " + scope;
    }

    /**
     * The words for a ledger metric id ({@link AlertRule#METRICS}): {@code error_rate} → {@code Error rate},
     * {@code duration_ms} → {@code Average duration (ms)} (it IS the average — see {@link AlertRule}). An id
     * not in {@link #METRIC_LABELS} falls back to its snake_case read as words ({@code rejected_rows} →
     * {@code Rejected rows}), so adding a metric can never leave its alert text blank.
     */
    static String metricLabel(String metric) {
        String known = METRIC_LABELS.get(metric);
        if (known != null) return known;
        String words = metric.replace('_', ' ').trim();
        return words.isEmpty() ? metric : Character.toUpperCase(words.charAt(0)) + words.substring(1);
    }

    /** {@code sum(exposure_sar)} → {@code Sum of exposure_sar}; {@code count} → {@code Row count}. */
    static String measureLabel(String measure) {
        if ("count".equals(measure)) return "Row count";
        Matcher m = MEASURE.matcher(measure);
        if (!m.matches()) return measure;
        String agg = switch (m.group(1)) {
            case "count" -> "Count";
            case "countDistinct" -> "Distinct count";
            case "sum" -> "Sum";
            case "avg" -> "Average";
            case "min" -> "Minimum";
            case "max" -> "Maximum";
            default -> null;
        };
        return agg == null ? measure : agg + " of " + m.group(2);
    }

    /**
     * A number for reading: thousands separators and at most two decimals ({@code 373335.09} →
     * {@code 373,335.09}, {@code 298668} → {@code 298,668}). A non-zero value that would round to
     * {@code 0} keeps two significant digits instead ({@code 0.0042}), so a small threshold is never
     * shown as zero. ⚠ {@link Locale#ROOT} symbols, deliberately: the same breach must read the same on
     * every machine, whatever its default locale.
     */
    static String number(double d) {
        if (Double.isNaN(d) || Double.isInfinite(d)) return String.valueOf(d);
        BigDecimal exact = BigDecimal.valueOf(d);
        BigDecimal rounded = exact.setScale(2, RoundingMode.HALF_UP);
        if (rounded.signum() == 0 && exact.signum() != 0)
            rounded = exact.round(new MathContext(2, RoundingMode.HALF_UP));
        DecimalFormat f = new DecimalFormat("#,##0", DecimalFormatSymbols.getInstance(Locale.ROOT));
        f.setMaximumFractionDigits(Math.max(0, rounded.stripTrailingZeros().scale()));
        return f.format(rounded);
    }

    /** JSON-ready view. */
    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("rule", rule);
        m.put("severity", severity);
        m.put("pipeline", pipeline);
        m.put("metric", metric);
        m.put("value", value);
        m.put("comparator", comparator);
        m.put("threshold", threshold);
        m.put("window", window);
        m.put("epochMillis", epochMillis);
        m.put("message", message);
        return m;
    }

    private static String trim(double d) {
        return d == Math.rint(d) ? String.valueOf((long) d) : String.valueOf(d);
    }
}
