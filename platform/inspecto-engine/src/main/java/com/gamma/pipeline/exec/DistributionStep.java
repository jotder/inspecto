package com.gamma.pipeline.exec;

import com.gamma.analytics.QuantileMode;
import com.gamma.analytics.Stats;
import com.gamma.analytics.distribution.Binning;
import com.gamma.analytics.distribution.CorrelationSql;
import com.gamma.analytics.distribution.OutlierSql;
import com.gamma.etl.Identifiers;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static com.gamma.util.SqlIdent.q;

/**
 * The config grammar of the distribution Steps (ANALYTICS-CORE-1 S3) — {@code transform.stats.outlier}
 * ({@code steps:} kind {@code outlier}), {@code transform.data.binning} ({@code binning}) and
 * {@code transform.stats.correlation} ({@code correlation}) — parsed once so the run ({@link RowShaper}), the recipe
 * compiler and the save-time check refuse the same configs with the same words. The SQL is the Analytics Kernel's
 * ({@link OutlierSql}, {@link Binning}, {@link CorrelationSql}); this class only reads config.
 *
 * <p>🔴 <b>No authored text reaches the SQL as text.</b> Column names are validated against {@link Identifiers} and
 * double-quoted; methods and modes are fixed words; numbers are parsed and re-printed.
 */
public final class DistributionStep {

    private DistributionStep() {}

    static final String OUTLIER = "transform.stats.outlier";
    static final String BINNING = "transform.data.binning";
    static final String CORRELATION = "transform.stats.correlation";

    /** The default prefix of the outlier columns: {@code outlier_lower}, {@code outlier_upper}, {@code outlier_score}, {@code outlier_flag}. */
    public static final String DEFAULT_OUTLIER_PREFIX = "outlier_";
    /** Tukey's k and the robust-z threshold and unit when none is stated. */
    public static final double DEFAULT_K = Stats.TUKEY_K, DEFAULT_THRESHOLD = 3.5, DEFAULT_UNIT = 1.0;
    /** The most correlated columns one Step takes (every pair of them: 1,225 at 50). */
    public static final int MAX_CORRELATION_COLUMNS = 50;

    // ── outlier ──────────────────────────────────────────────────────────────────────────────────────────────────

    /** A parsed {@code outlier} config; {@link #kernel()} is the Analytics Kernel's pass. */
    public record Outlier(OutlierSql.Spec kernel) {
        public List<String> inputColumns() {
            List<String> out = new ArrayList<>(kernel.groupBy());
            out.add(0, kernel.column());
            return out;
        }

        public List<String> addedColumns() {
            return kernel.addedColumns();
        }

        public String select(String input) {
            return OutlierSql.select(q(input), kernel);
        }
    }

    /** Parse and validate an {@code outlier} config; {@link IllegalArgumentException} names the first problem. */
    public static Outlier parseOutlier(Map<?, ?> cfg) {
        known(cfg, OUTLIER, Set.of("column", "method", "k", "quantile_mode", "threshold", "unit", "group_by", "prefix"));
        String column = required(cfg, "column", OUTLIER, "the number column to judge");
        String m = text(cfg, "method", "iqr");
        OutlierSql.Method method = switch (m) {
            case "iqr" -> OutlierSql.Method.IQR;
            case "robust_z" -> OutlierSql.Method.ROBUST_Z;
            default -> throw bad(OUTLIER, "'method' '" + m + "' is not one of [iqr, robust_z]");
        };
        // a key of the other method is read and checked but has no effect (the form may carry its default)
        double k = number(cfg, "k", DEFAULT_K, OUTLIER);
        if (!(k >= 0 && k <= 100)) throw bad(OUTLIER, "'k' must be between 0 and 100, got " + cfg.get("k"));
        double threshold = number(cfg, "threshold", DEFAULT_THRESHOLD, OUTLIER);
        if (!(threshold > 0 && threshold <= 1_000))
            throw bad(OUTLIER, "'threshold' must be above 0 (and at most 1000), got " + cfg.get("threshold"));
        double unit = number(cfg, "unit", DEFAULT_UNIT, OUTLIER);
        if (!(unit > 0)) throw bad(OUTLIER, "'unit' must be above 0 - the smallest change that matters, got " + cfg.get("unit"));
        QuantileMode mode = modeOf(cfg, OUTLIER);
        List<String> groupBy = identList(cfg, "group_by", OUTLIER);
        String prefix = present(cfg, "prefix") ? cfg.get("prefix").toString().trim() : DEFAULT_OUTLIER_PREFIX;
        String flag = derived(prefix, "flag", OUTLIER);
        OutlierSql.Spec spec = new OutlierSql.Spec(column, groupBy, method, k, mode, threshold, unit, flag,
                derived(prefix, "lower", OUTLIER), derived(prefix, "upper", OUTLIER), derived(prefix, "score", OUTLIER));
        distinct(OUTLIER, column, groupBy);
        return new Outlier(spec);
    }

    // ── binning ──────────────────────────────────────────────────────────────────────────────────────────────────

    /** A parsed {@code binning} config; {@link #kernel()} is the Analytics Kernel's pass. */
    public record Bins(Binning.Spec kernel) {
        public List<String> inputColumns() {
            List<String> out = new ArrayList<>(kernel.groupBy());
            out.add(0, kernel.column());
            return out;
        }

        public List<String> addedColumns() {
            return kernel.addedColumns();
        }

        public String select(String input) {
            return Binning.select(q(input), kernel);
        }
    }

    /** Parse and validate a {@code binning} config; {@link IllegalArgumentException} names the first problem. */
    public static Bins parseBinning(Map<?, ?> cfg) {
        known(cfg, BINNING, Set.of("column", "method", "bins", "min", "max", "quantile_mode", "group_by", "bin_column",
                "label_column"));
        String column = required(cfg, "column", BINNING, "the number column to bin");
        String m = text(cfg, "method", "equal_width");
        Binning.Method method = switch (m) {
            case "equal_width" -> Binning.Method.EQUAL_WIDTH;
            case "quantile" -> Binning.Method.QUANTILE;
            default -> throw bad(BINNING, "'method' '" + m + "' is not one of [equal_width, quantile]");
        };
        Object b = cfg.get("bins");
        if (b == null || b.toString().isBlank())
            throw bad(BINNING, "needs 'bins' - how many bins, 1 to " + Binning.MAX_BINS);
        int bins;
        try {
            bins = Integer.parseInt(b.toString().trim());
        } catch (NumberFormatException e) {
            throw bad(BINNING, "'bins' '" + b + "' is not a whole number");
        }
        if (bins < 1 || bins > Binning.MAX_BINS)
            throw bad(BINNING, "'bins' must be between 1 and " + Binning.MAX_BINS + ", got " + bins);
        // min / max serve equal width, quantile_mode serves quantile; the other method's keys are checked, then ignored
        Double min = present(cfg, "min") ? number(cfg, "min", 0, BINNING) : null;
        Double max = present(cfg, "max") ? number(cfg, "max", 0, BINNING) : null;
        if (min != null && max != null && !(min < max))
            throw bad(BINNING, "'min' must be below 'max', got " + cfg.get("min") + " and " + cfg.get("max"));
        if (method == Binning.Method.QUANTILE) {
            min = null;
            max = null;
        }
        List<String> groupBy = identList(cfg, "group_by", BINNING);
        String binColumn = optionalColumn(cfg, "bin_column", "bin", BINNING);
        String labelColumn = optionalColumn(cfg, "label_column", "bin_label", BINNING);
        if (binColumn.equalsIgnoreCase(labelColumn))
            throw bad(BINNING, "'bin_column' and 'label_column' must differ, both are '" + binColumn + "'");
        distinct(BINNING, column, groupBy);
        return new Bins(new Binning.Spec(column, groupBy, method, bins, modeOf(cfg, BINNING), min, max, binColumn,
                labelColumn));
    }

    // ── correlation ──────────────────────────────────────────────────────────────────────────────────────────────

    /** A parsed {@code correlation} config: every pair {@code (columns[i], columns[j])}, i &lt; j, y = the later one. */
    public record Correlation(List<String> columns, List<String> groupBy) {
        public List<CorrelationSql.Pair> pairs() {
            List<CorrelationSql.Pair> out = new ArrayList<>();
            for (int i = 0; i < columns.size(); i++)
                for (int j = i + 1; j < columns.size(); j++) out.add(new CorrelationSql.Pair(columns.get(i), columns.get(j)));
            return out;
        }

        public List<String> inputColumns() {
            List<String> out = new ArrayList<>(columns);
            out.addAll(groupBy);
            return out;
        }

        /** The output columns: the group columns, then {@link CorrelationSql#OUTPUT}. */
        public List<String> outputColumns() {
            List<String> out = new ArrayList<>(groupBy);
            out.addAll(CorrelationSql.OUTPUT);
            return out;
        }

        public String select(String input) {
            return CorrelationSql.select(q(input), pairs(), groupBy);
        }
    }

    /** Parse and validate a {@code correlation} config; {@link IllegalArgumentException} names the first problem. */
    public static Correlation parseCorrelation(Map<?, ?> cfg) {
        known(cfg, CORRELATION, Set.of("columns", "group_by"));
        List<String> columns = identList(cfg, "columns", CORRELATION);
        if (columns.size() < 2)
            throw bad(CORRELATION, "needs at least two 'columns' - every pair of them is correlated");
        if (columns.size() > MAX_CORRELATION_COLUMNS)
            throw bad(CORRELATION, "takes at most " + MAX_CORRELATION_COLUMNS + " 'columns', got " + columns.size());
        Set<String> seen = new HashSet<>();
        for (String c : columns)
            if (!seen.add(c.toLowerCase(Locale.ROOT))) throw bad(CORRELATION, "'columns' names '" + c + "' twice");
        List<String> groupBy = identList(cfg, "group_by", CORRELATION);
        for (String g : groupBy) {
            if (!seen.add(g.toLowerCase(Locale.ROOT)))
                throw bad(CORRELATION, "'" + g + "' is both correlated and grouped by, or grouped by twice");
            if (CorrelationSql.OUTPUT.stream().anyMatch(o -> o.equalsIgnoreCase(g)))
                throw bad(CORRELATION, "group column '" + g + "' collides with an output column " + CorrelationSql.OUTPUT);
        }
        return new Correlation(columns, groupBy);
    }

    // ── checks against the inbound data ──────────────────────────────────────────────────────────────────────────

    /**
     * The first refusal against the inbound columns, or {@code null}: a named column missing, or an added column (or a
     * scratch column the kernel uses) colliding with an inbound one.
     */
    public static String columnRefusal(String kind, List<String> required, List<String> added, List<String> inbound) {
        for (String c : required)
            if (inbound.stream().noneMatch(x -> x.equalsIgnoreCase(c)))
                return kind + ": column '" + c + "' is not in the inbound data (have: " + inbound + ")";
        for (String c : added)
            if (inbound.stream().anyMatch(x -> x.equalsIgnoreCase(c)))
                return kind + ": output column '" + c + "' collides with an inbound column - "
                        + (OUTLIER.equals(kind) ? "set 'prefix'" : "set 'bin_column' / 'label_column'");
        for (String c : inbound)
            if (c.toLowerCase(Locale.ROOT).startsWith("__dist_"))
                return kind + ": inbound column '" + c + "' uses the reserved prefix __dist_ - rename it first";
        return null;
    }

    /** {@code null} when every named column's DuckDB type is a number, else the refusal. */
    public static String numberRefusal(String kind, List<String> columns, Map<String, String> types) {
        for (String c : columns) {
            String t = types.entrySet().stream().filter(e -> e.getKey().equalsIgnoreCase(c)).map(Map.Entry::getValue)
                    .findFirst().orElse("");
            if (!isNumber(t)) return kind + ": '" + c + "' must be a number column, not " + (t.isEmpty() ? "unknown" : t);
        }
        return null;
    }

    /** A DuckDB numeric type name (integers, DECIMAL, FLOAT, DOUBLE). */
    public static boolean isNumber(String duckType) {
        String t = duckType == null ? "" : duckType.toUpperCase(Locale.ROOT).trim();
        return t.startsWith("DECIMAL") || NUMERIC.contains(t);
    }

    private static final Set<String> NUMERIC = Set.of("TINYINT", "SMALLINT", "INTEGER", "BIGINT", "HUGEINT", "UTINYINT",
            "USMALLINT", "UINTEGER", "UBIGINT", "UHUGEINT", "FLOAT", "DOUBLE");

    // ── helpers ──────────────────────────────────────────────────────────────────────────────────────────────────

    private static void known(Map<?, ?> cfg, String kind, Set<String> keys) {
        for (Object k : cfg.keySet())
            if (!keys.contains(String.valueOf(k)))
                throw bad(kind, "does not take '" + k + "' (keys: " + new TreeSet<>(keys) + ")");
    }

    private static boolean present(Map<?, ?> cfg, String key) {
        Object v = cfg.get(key);
        return v != null && !v.toString().isBlank();
    }

    private static String text(Map<?, ?> cfg, String key, String dflt) {
        return present(cfg, key) ? cfg.get(key).toString().trim().toLowerCase(Locale.ROOT) : dflt;
    }

    private static QuantileMode modeOf(Map<?, ?> cfg, String kind) {
        String m = text(cfg, "quantile_mode", "continuous");
        return switch (m) {
            case "continuous" -> QuantileMode.CONTINUOUS;
            case "discrete" -> QuantileMode.DISCRETE;
            default -> throw bad(kind, "'quantile_mode' '" + m + "' is not one of [continuous, discrete]");
        };
    }

    private static double number(Map<?, ?> cfg, String key, double dflt, String kind) {
        if (!present(cfg, key)) return dflt;
        double d;
        try {
            d = Double.parseDouble(cfg.get(key).toString().trim());
        } catch (NumberFormatException e) {
            throw bad(kind, "'" + key + "' '" + cfg.get(key) + "' is not a number");
        }
        if (!Double.isFinite(d)) throw bad(kind, "'" + key + "' must be a finite number, got " + cfg.get(key));
        return d;
    }

    private static String required(Map<?, ?> cfg, String key, String kind, String what) {
        if (!present(cfg, key)) throw bad(kind, "needs a '" + key + "' - " + what);
        return columnName(cfg.get(key), key, kind);
    }

    private static String optionalColumn(Map<?, ?> cfg, String key, String dflt, String kind) {
        return present(cfg, key) ? columnName(cfg.get(key), key, kind) : dflt;
    }

    /** {@code prefix + suffix} as a plain column name. */
    private static String derived(String prefix, String suffix, String kind) {
        String name = prefix + suffix;
        try {
            Identifiers.validate(name, kind + " prefix");
        } catch (IllegalArgumentException e) {
            throw bad(kind, "'prefix' '" + prefix + "' does not make a plain column name ([A-Za-z_][A-Za-z0-9_]*): " + name);
        }
        return name;
    }

    private static void distinct(String kind, String column, List<String> groupBy) {
        Set<String> seen = new HashSet<>();
        seen.add(column.toLowerCase(Locale.ROOT));
        for (String g : groupBy)
            if (!seen.add(g.toLowerCase(Locale.ROOT)))
                throw bad(kind, "'" + g + "' is both the column and a group, or grouped by twice");
    }

    private static List<String> identList(Map<?, ?> cfg, String key, String kind) {
        List<String> out = new ArrayList<>();
        Object v = cfg.get(key);
        if (v instanceof List<?> l) {
            for (Object o : l) if (o != null && !o.toString().isBlank()) out.add(columnName(o, key, kind));
        } else if (v != null && !v.toString().isBlank()) {
            if (!(v instanceof String)) throw bad(kind, "'" + key + "' must be a list of column names");
            for (String s : v.toString().split(","))
                if (!s.isBlank()) out.add(columnName(s, key, kind));
        }
        return out;
    }

    private static String columnName(Object o, String where, String kind) {
        String s = o.toString().trim();
        try {
            Identifiers.validate(s, kind + " " + where);
        } catch (IllegalArgumentException e) {
            throw bad(kind, "'" + where + "' value '" + s + "' is not a plain column name ([A-Za-z_][A-Za-z0-9_]*)");
        }
        return s;
    }

    private static IllegalArgumentException bad(String kind, String msg) {
        return new IllegalArgumentException(kind + " " + msg);
    }
}
