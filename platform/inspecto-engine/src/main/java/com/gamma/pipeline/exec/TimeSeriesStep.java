package com.gamma.pipeline.exec;

import com.gamma.analytics.forecast.ForecastMethod;
import com.gamma.analytics.forecast.ForecastOutput;
import com.gamma.analytics.forecast.ForecastSql;
import com.gamma.analytics.forecast.Forecaster;
import com.gamma.analytics.score.EntityCap;
import com.gamma.analytics.time.ChangePoint;
import com.gamma.analytics.time.TimeGrain;
import com.gamma.analytics.time.TimeSeriesSql;
import com.gamma.etl.Identifiers;
import com.gamma.query.MeasureCompiler;
import com.gamma.util.SqlIdent;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The config grammar of the time-series Steps — {@code transform.timeseries.resample} ({@code steps:} kind
 * {@code resample}) and {@code transform.timeseries.shift} (kind {@code shift}), ANALYTICS-CORE-1 M5, and
 * {@code transform.timeseries.forecast} (kind {@code forecast}, S5; the kernel's {@link Forecaster}) and
 * {@code transform.timeseries.changepoint} (kind {@code changepoint}, ANALYTICS-STEPS-TS-1; the kernel's
 * {@link ChangePoint}, over a residual series and its scale σ) — parsed once, so
 * the run ({@link RowShaper}), the recipe compiler and the save-time check refuse the same configs with the same words.
 * The SQL is the Analytics Kernel's ({@link TimeSeriesSql}, bucketing by {@link TimeGrain}); the resample's aggregates
 * are the Measure grammar's ({@link MeasureCompiler#aggregateSql}).
 *
 * <p>🔴 <b>No authored text reaches the SQL as text.</b> Column names are validated against {@link Identifiers} and
 * double-quoted; the grain is a fixed word; the zone must be a known region id; bounds and periods are parsed values
 * re-printed.
 *
 * <p><b>The clock is always stated.</b> Without {@code timezone} the time column must be a {@code DATE} or a zone-less
 * {@code TIMESTAMP} and is bucketed on its own clock; a {@code TIMESTAMP WITH TIME ZONE} column needs {@code timezone},
 * and is bucketed on that zone's wall clock — never DuckDB's session zone, which is the host's.
 */
public final class TimeSeriesStep {

    private TimeSeriesStep() {}

    /** The resample's series cap per key: a run whose bounds span this many buckets or more fails. */
    public static final long MAX_BUCKETS = 100_000;
    /** The largest shift, in buckets, either way. */
    public static final int MAX_PERIODS = 10_000;

    static final String RESAMPLE = "transform.timeseries.resample";
    static final String SHIFT = "transform.timeseries.shift";
    static final String FORECAST = "transform.timeseries.forecast";
    static final String CHANGEPOINT = "transform.timeseries.changepoint";

    /** The longest residual series a change-point takes: the kernel's recursion advances one point per step. */
    public static final int MAX_CHANGEPOINT_POINTS = 10_000;
    /** The most series one change-point run takes (the score contract's default cap, as the forecast's). */
    public static final long MAX_CHANGEPOINT_SERIES = EntityCap.DEFAULT;
    /** The kernel's rule constants (the Business Assurance template's): z 4, regime run 3, CUSUM k 0.25, h 8. */
    public static final ChangePoint.Params CHANGEPOINT_DEFAULTS = ChangePoint.Params.of(1.0);
    /** The columns a change-point adds, 0/1 each: the kernel's flags. */
    public static final List<String> CHANGEPOINT_FLAGS = List.of("outside_band", "regime_change", "drift_change", "warmup");

    /** The longest season a forecast takes, in buckets. */
    public static final int MAX_FORECAST_PERIOD = 1_000;
    /** The most buckets a forecast looks ahead. */
    public static final int MAX_FORECAST_HORIZON = 1_000;
    /** The longest series a forecast takes: Holt-Winters advances one bucket per recursion step. */
    public static final long MAX_FORECAST_POINTS = 10_000;
    /** The most series one forecast run takes; past it the run fails (D-AD10, the score contract's default cap). */
    public static final long MAX_FORECAST_SERIES = EntityCap.DEFAULT;

    /** A parsed resample config. */
    public record Resample(String timeColumn, TimeGrain grain, ZoneId zone, List<String> groupBy,
                           List<MeasureCompiler.Measure> measures, String bucketColumn, LocalDateTime from,
                           LocalDateTime to) {

        /** Every column the inbound relation must carry. */
        public List<String> inputColumns() {
            List<String> out = new ArrayList<>(groupBy);
            out.add(timeColumn);
            for (MeasureCompiler.Measure m : measures) if (m.field() != null) out.add(m.field());
            return out;
        }

        /** The output columns: the keys, the bucket, one per measure (its grammar id). */
        public List<String> outputColumns() {
            List<String> out = new ArrayList<>(groupBy);
            out.add(bucketColumn);
            for (MeasureCompiler.Measure m : measures) out.add(m.id());
            return out;
        }

        /** The kernel's resample query over {@code input}. */
        public String select(String input) {
            List<TimeSeriesSql.Measure> ms = new ArrayList<>();
            for (MeasureCompiler.Measure m : measures)
                ms.add(new TimeSeriesSql.Measure(MeasureCompiler.aggregateSql(m), m.id(),
                        TimeSeriesSql.Fill.forAggregate(m.agg())));
            return TimeSeriesSql.resample(new TimeSeriesSql.Resample(input, timeColumn, grain, zone, groupBy, ms,
                    bucketColumn, from, to, MAX_BUCKETS));
        }
    }

    /** A parsed shift config. */
    public record Shift(String timeColumn, TimeGrain grain, ZoneId zone, List<String> groupBy, List<String> columns,
                        int periods, String suffix) {

        public List<String> inputColumns() {
            List<String> out = new ArrayList<>(groupBy);
            out.add(timeColumn);
            out.addAll(columns);
            return out;
        }

        /** The columns the shift adds: each shifted column plus the suffix. */
        public List<String> addedColumns() {
            return columns.stream().map(c -> c + suffix).toList();
        }

        private TimeSeriesSql.Shift kernel(String input) {
            List<TimeSeriesSql.Shifted> xs = new ArrayList<>();
            for (String c : columns) xs.add(new TimeSeriesSql.Shifted(c, c + suffix));
            return new TimeSeriesSql.Shift(input, timeColumn, grain, zone, groupBy, periods, xs);
        }

        public String select(String input) {
            return TimeSeriesSql.shift(kernel(input));
        }

        /** Counts (key, bucket) pairs holding more than one row — the run refuses a non-zero answer. */
        public String duplicatesSql(String input) {
            return TimeSeriesSql.duplicateBucketsSql(kernel(input));
        }
    }

    /** A parsed forecast config: the series, and the kernel's model spec. */
    public record Forecast(String timeColumn, TimeGrain grain, String valueColumn, List<String> groupBy,
                           Forecaster.Spec model) {

        public List<String> inputColumns() {
            List<String> out = new ArrayList<>(groupBy);
            out.add(timeColumn);
            out.add(valueColumn);
            return out;
        }

        /** The output columns: the keys, the time column, then {@link ForecastOutput}'s. */
        public List<String> outputColumns() {
            List<String> out = new ArrayList<>(groupBy);
            out.add(timeColumn);
            out.addAll(ForecastOutput.valueColumnNames());
            return out;
        }

        private ForecastSql.Spec kernel(String input, String timeType) {
            return new ForecastSql.Spec(input, timeColumn, timeType, grain, groupBy, valueColumn, model);
        }

        /** The kernel's forecast query; {@code timeType} is the time column's DuckDB type. */
        public String select(String input, String timeType) {
            return ForecastSql.select(kernel(input, timeType));
        }

        /** The kernel's regularity check: one row of problem counts ({@link ForecastSql#checkSql}). */
        public String checkSql(String input, String timeType) {
            return ForecastSql.checkSql(kernel(input, timeType));
        }

        /**
         * The refusal for a check row, or {@code null}: {@code series, off_grain, null_time, null_value,
         * irregular_series, min_points, max_points}.
         */
        public String checkRefusal(long series, long offGrain, long nullTime, long nullValue, long irregular,
                                   long minPoints, long maxPoints) {
            String g = grain.wire();
            if (series > MAX_FORECAST_SERIES)
                return FORECAST + ": " + series + " series is more than the cap of " + MAX_FORECAST_SERIES
                        + " - narrow the input or the group_by";
            if (nullTime > 0)
                return FORECAST + ": " + nullTime + " row(s) have an empty '" + timeColumn + "' - filter them out first";
            if (offGrain > 0)
                return FORECAST + ": " + offGrain + " '" + timeColumn + "' value(s) are not the start of a " + g
                        + " - resample to the " + g + " grain first";
            if (irregular > 0)
                return FORECAST + ": " + irregular + " series have a missing or repeated " + g + " - a forecast needs "
                        + "one row per key per " + g + " with no gap; resample first";
            if (nullValue > 0)
                return FORECAST + ": " + nullValue + " empty '" + valueColumn + "' value(s) - fill them first (a "
                        + "resample with count or sum fills an empty bucket with 0)";
            if (series > 0 && minPoints < model.minPoints())
                return FORECAST + ": a series has " + minPoints + " " + g + "(s); with period " + model.period()
                        + " every series needs at least " + model.minPoints() + " (two seasons)";
            if (maxPoints > MAX_FORECAST_POINTS)
                return FORECAST + ": a series has " + maxPoints + " " + g + "s, more than the cap of "
                        + MAX_FORECAST_POINTS + " - use a coarser grain or a shorter history";
            return null;
        }
    }

    /** Parse and validate a forecast config; {@link IllegalArgumentException} names the first problem. */
    public static Forecast parseForecast(Map<?, ?> cfg) {
        known(cfg, FORECAST, Set.of("time_column", "grain", "value_column", "group_by", "method", "period", "horizon",
                "levels", "alpha", "beta", "gamma"));
        String time = required(cfg, "time_column", FORECAST, "the bucket column of the series");
        TimeGrain grain = grain(cfg, FORECAST);
        String value = required(cfg, "value_column", FORECAST, "the number to forecast");
        List<String> groupBy = identList(cfg, "group_by", FORECAST);
        ForecastMethod method = ForecastMethod.HOLT_WINTERS;
        Object m = cfg.get("method");
        if (m != null && !m.toString().isBlank()) {
            try {
                method = ForecastMethod.parse(m.toString());
            } catch (IllegalArgumentException e) {
                throw bad(FORECAST, "'method' '" + m + "' is not holt_winters or seasonal_naive");
            }
        }
        int period = wholeNumber(cfg, "period", FORECAST, 2, MAX_FORECAST_PERIOD, "buckets per season, e.g. 7 for daily data "
                + "with a weekly pattern");
        int horizon = wholeNumber(cfg, "horizon", FORECAST, 1, MAX_FORECAST_HORIZON, "how many buckets ahead to forecast");
        List<Double> levels = new ArrayList<>();
        Object lv = cfg.get("levels");
        List<?> raw = lv instanceof List<?> l ? l : lv == null || lv.toString().isBlank() ? List.of()
                : List.of(lv.toString().split(","));
        for (Object o : raw) {
            if (o == null || o.toString().isBlank()) continue;
            levels.add(fraction(o, "levels", true));
        }
        if (levels.isEmpty()) levels.add(0.95);
        levels.sort(null);
        Forecaster.Spec model;
        try {
            model = new Forecaster.Spec(method, period, weight(cfg, "alpha", Forecaster.DEFAULT_ALPHA),
                    weight(cfg, "beta", Forecaster.DEFAULT_BETA), weight(cfg, "gamma", Forecaster.DEFAULT_GAMMA),
                    horizon, levels);
        } catch (IllegalArgumentException e) {
            throw bad(FORECAST, e.getMessage());
        }
        Forecast f = new Forecast(time, grain, value, List.copyOf(groupBy), model);
        Set<String> seen = new HashSet<>();
        for (String c : f.outputColumns())
            if (!seen.add(c.toLowerCase(Locale.ROOT)))
                throw bad(FORECAST, "output column '" + c + "' appears twice - the group_by columns and the time column "
                        + "must differ from each other and from " + ForecastOutput.valueColumnNames());
        return f;
    }

    /**
     * {@code null} when a forecast's time and value columns have usable types, else the refusal: the time column a
     * {@code DATE} or zone-less {@code TIMESTAMP}, the value column a number.
     */
    public static String forecastTypeRefusal(String timeColumn, String timeType, String valueColumn, String valueType) {
        String t = timeType == null ? "" : timeType.toUpperCase(Locale.ROOT).trim();
        if (!ForecastSql.TIME_TYPES.contains(t))
            return FORECAST + ": '" + timeColumn + "' must be a DATE or TIMESTAMP bucket column, not " + timeType
                    + " (a TIMESTAMP WITH TIME ZONE: resample it with a timezone first)";
        String v = valueType == null ? "" : valueType.toUpperCase(Locale.ROOT).trim();
        if (!(v.startsWith("DECIMAL") || NUMERIC.contains(v)))
            return FORECAST + ": '" + valueColumn + "' must be a number, not " + valueType;
        return null;
    }

    static final Set<String> NUMERIC = Set.of("TINYINT", "SMALLINT", "INTEGER", "BIGINT", "HUGEINT", "UTINYINT",
            "USMALLINT", "UINTEGER", "UBIGINT", "UHUGEINT", "FLOAT", "DOUBLE");

    /** Parse and validate a resample config; {@link IllegalArgumentException} names the first problem. */
    public static Resample parseResample(Map<?, ?> cfg) {
        known(cfg, RESAMPLE, Set.of("time_column", "grain", "timezone", "group_by", "measures", "bucket_column",
                "from", "to"));
        String time = required(cfg, "time_column", RESAMPLE, "the column the series is bucketed on");
        TimeGrain grain = grain(cfg, RESAMPLE);
        ZoneId zone = zone(cfg, RESAMPLE);
        List<String> groupBy = identList(cfg, "group_by", RESAMPLE);
        if (!(cfg.get("measures") instanceof List<?> raw) || raw.isEmpty())
            throw bad(RESAMPLE, "needs at least one 'measures' entry - count or agg(field), e.g. sum(amount)");
        List<MeasureCompiler.Measure> measures = new ArrayList<>();
        for (Map<String, Object> m : MeasureCompiler.splitShorthand(raw, RESAMPLE)) {
            MeasureCompiler.Measure measure = new MeasureCompiler.Measure(String.valueOf(m.get("agg")),
                    m.get("field") == null ? null : String.valueOf(m.get("field")).trim());
            try {
                MeasureCompiler.aggregateSql(measure);
            } catch (IllegalArgumentException e) {
                throw bad(RESAMPLE, "measure '" + measure.agg() + (measure.field() == null ? "" : "(" + measure.field() + ")")
                        + "': " + e.getMessage());
            }
            measures.add(measure);
        }
        Object bc = cfg.get("bucket_column");
        String bucket = bc == null || bc.toString().isBlank() ? "bucket" : columnName(bc, "bucket_column", RESAMPLE);
        LocalDateTime from = bound(cfg, "from", RESAMPLE);
        LocalDateTime to = bound(cfg, "to", RESAMPLE);
        if (from != null && to != null && grain.truncate(from).isAfter(grain.truncate(to)))
            throw bad(RESAMPLE, "'from' " + cfg.get("from") + " is after 'to' " + cfg.get("to"));
        Resample r = new Resample(time, grain, zone, List.copyOf(groupBy), List.copyOf(measures), bucket, from, to);
        Set<String> seen = new HashSet<>();
        for (String c : r.outputColumns())
            if (!seen.add(c.toLowerCase(Locale.ROOT)))
                throw bad(RESAMPLE, "output column '" + c + "' appears twice - a group_by column, the bucket_column "
                        + "and the measure ids (count, sum_amount…) must all differ");
        return r;
    }

    /** Parse and validate a shift config; {@link IllegalArgumentException} names the first problem. */
    public static Shift parseShift(Map<?, ?> cfg) {
        known(cfg, SHIFT, Set.of("time_column", "grain", "timezone", "group_by", "columns", "periods", "suffix"));
        String time = required(cfg, "time_column", SHIFT, "the bucket column the series is ordered on");
        TimeGrain grain = grain(cfg, SHIFT);
        ZoneId zone = zone(cfg, SHIFT);
        List<String> groupBy = identList(cfg, "group_by", SHIFT);
        List<String> columns = identList(cfg, "columns", SHIFT);
        if (columns.isEmpty()) throw bad(SHIFT, "needs a non-empty 'columns' list - the values to bring forward");
        int periods = 1;
        Object p = cfg.get("periods");
        if (p != null && !p.toString().isBlank()) {
            try {
                periods = Integer.parseInt(p.toString().trim());
            } catch (NumberFormatException e) {
                throw bad(SHIFT, "'periods' '" + p + "' is not a whole number");
            }
            if (periods == 0 || Math.abs(periods) > MAX_PERIODS)
                throw bad(SHIFT, "'periods' must be a non-zero whole number between -" + MAX_PERIODS + " and "
                        + MAX_PERIODS + " (1 = the previous bucket, -1 = the next)");
        }
        Object sx = cfg.get("suffix");
        String suffix = sx == null || sx.toString().isBlank() ? "_prev" : sx.toString().trim();
        for (String c : columns) columnName(c + suffix, "suffix", SHIFT);
        return new Shift(time, grain, zone, List.copyOf(groupBy), List.copyOf(columns), periods, suffix);
    }

    /** A parsed change-point config: the residual series and the kernel's rule. */
    public record Changes(String orderColumn, String residualColumn, String sigmaColumn, Double sigma,
                          List<String> groupBy, ChangePoint.Params params) {

        /** The column the kernel reads σ from when {@code sigma} is stated as a number. */
        static final String SIGMA = "__cp_sigma";

        public List<String> inputColumns() {
            List<String> out = new ArrayList<>(groupBy);
            out.add(orderColumn);
            out.add(residualColumn);
            if (sigmaColumn != null) out.add(sigmaColumn);
            return out;
        }

        /** The flag columns every inbound row gains. */
        public List<String> addedColumns() {
            return CHANGEPOINT_FLAGS;
        }

        /** The relation the kernel reads: {@code input} itself, or (σ a number) {@code input} with σ as a column. */
        public String kernelInputSql(String input) {
            // a parsed finite double re-printed (Java's shortest round-trip form), never authored text
            return "SELECT *, CAST(" + Double.toString(sigma) + " AS DOUBLE) AS " + SqlIdent.q(SIGMA) + " FROM " + SqlIdent.q(input);
        }

        /** Every inbound row, ordered by key then order, with the kernel's flags (NULL on a row with an empty residual). */
        public String select(String input, String kernelInput) {
            String kernel = ChangePoint.sql(kernelInput, orderColumn, residualColumn,
                    sigmaColumn == null ? SIGMA : sigmaColumn, groupBy, params);
            StringBuilder on = new StringBuilder("i." + SqlIdent.q(residualColumn) + " IS NOT NULL AND i."
                    + SqlIdent.q(orderColumn) + " = c." + SqlIdent.q(orderColumn));
            StringBuilder order = new StringBuilder();
            for (String k : groupBy) {
                on.append(" AND i.").append(SqlIdent.q(k)).append(" IS NOT DISTINCT FROM c.").append(SqlIdent.q(k));
                order.append("i.").append(SqlIdent.q(k)).append(", ");
            }
            StringBuilder flags = new StringBuilder();
            for (String f : CHANGEPOINT_FLAGS) flags.append(", c.").append(SqlIdent.q(f));
            return "SELECT i.*" + flags + " FROM " + SqlIdent.q(input) + " i LEFT JOIN (" + kernel + ") c ON " + on
                    + " ORDER BY " + order + "i." + SqlIdent.q(orderColumn);
        }

        /**
         * One row of problem counts over the kernel's input: {@code series, max_points, duplicated, null_order,
         * bad_residual, bad_sigma} — see {@link #checkRefusal}.
         */
        public String checkSql(String kernelInput) {
            String rel = SqlIdent.q(kernelInput), ord = SqlIdent.q(orderColumn), res = SqlIdent.q(residualColumn);
            String sg = "CAST(" + SqlIdent.q(sigmaColumn == null ? SIGMA : sigmaColumn) + " AS DOUBLE)";
            StringBuilder keys = new StringBuilder();
            for (String k : groupBy) keys.append(keys.length() == 0 ? "" : ", ").append(SqlIdent.q(k));
            String byKey = keys.length() == 0 ? "" : " GROUP BY " + keys;
            String live = " WHERE " + res + " IS NOT NULL";
            return "SELECT (SELECT count(*) FROM (SELECT DISTINCT " + (keys.length() == 0 ? "1" : keys) + " FROM " + rel
                    + live + ")) AS series, coalesce((SELECT max(__n) FROM (SELECT count(*) AS __n FROM " + rel + live + byKey
                    + ")), 0) AS max_points, (SELECT count(*) FROM (SELECT 1 FROM " + rel + live + " GROUP BY "
                    + (keys.length() == 0 ? "" : keys + ", ") + ord + " HAVING count(*) > 1)) AS duplicated, "
                    + "(SELECT count(*) FROM " + rel + live + " AND " + ord + " IS NULL) AS null_order, "
                    + "(SELECT count(*) FROM " + rel + live + " AND NOT isfinite(CAST(" + res + " AS DOUBLE))) AS bad_residual, "
                    + "(SELECT count(*) FROM " + rel + live + " AND NOT coalesce(" + sg + " > 0 AND isfinite(" + sg
                    + "), false)) AS bad_sigma";
        }

        /** The refusal for a {@link #checkSql} row, or {@code null}. */
        public String checkRefusal(long series, long maxPoints, long duplicated, long nullOrder, long badResidual,
                                   long badSigma) {
            if (series > MAX_CHANGEPOINT_SERIES)
                return CHANGEPOINT + ": " + series + " series is more than the cap of " + MAX_CHANGEPOINT_SERIES
                        + " - narrow the input or the group_by";
            if (maxPoints > MAX_CHANGEPOINT_POINTS)
                return CHANGEPOINT + ": a series has " + maxPoints + " points, more than the cap of "
                        + MAX_CHANGEPOINT_POINTS + " - use a coarser grain or a shorter history";
            if (duplicated > 0)
                return CHANGEPOINT + ": " + duplicated + " '" + orderColumn + "' value(s) repeat within a series - a "
                        + "series needs one row per key per point; resample first";
            if (nullOrder > 0)
                return CHANGEPOINT + ": " + nullOrder + " row(s) have a residual but an empty '" + orderColumn
                        + "' - filter them out first";
            if (badResidual > 0)
                return CHANGEPOINT + ": " + badResidual + " '" + residualColumn + "' value(s) are not finite numbers";
            if (badSigma > 0)
                return CHANGEPOINT + ": " + badSigma + " row(s) have a residual but no sigma above 0 in '" + sigmaColumn
                        + "' - sigma is the residual scale and must be a positive number on every row";
            return null;
        }
    }

    /** Parse and validate a change-point config; {@link IllegalArgumentException} names the first problem. */
    public static Changes parseChangePoint(Map<?, ?> cfg) {
        known(cfg, CHANGEPOINT, Set.of("order_column", "residual_column", "sigma", "sigma_column", "group_by", "z",
                "regime_run", "cusum_k", "cusum_h", "warmup", "rebase"));
        String order = required(cfg, "order_column", CHANGEPOINT, "the column the series is ordered on (e.g. the bucket)");
        String residual = required(cfg, "residual_column", CHANGEPOINT, "actual minus expected, e.g. a forecast's error");
        boolean hasSigma = cfg.get("sigma") != null && !cfg.get("sigma").toString().isBlank();
        boolean hasColumn = cfg.get("sigma_column") != null && !cfg.get("sigma_column").toString().isBlank();
        if (hasSigma == hasColumn)
            throw bad(CHANGEPOINT, "needs exactly one of 'sigma' (the residual scale as a number) or 'sigma_column' "
                    + "(a column holding it per row, constant per series)");
        Double sigma = hasSigma ? number(cfg, "sigma", CHANGEPOINT, 0, false, 0) : null;
        String sigmaColumn = hasColumn ? columnName(cfg.get("sigma_column"), "sigma_column", CHANGEPOINT) : null;
        List<String> groupBy = identList(cfg, "group_by", CHANGEPOINT);
        ChangePoint.Params d = CHANGEPOINT_DEFAULTS;
        Object rb = cfg.get("rebase");
        boolean rebase = false;
        if (rb != null && !rb.toString().isBlank()) {
            String r = rb.toString().trim().toLowerCase(Locale.ROOT);
            if (!r.equals("true") && !r.equals("false")) throw bad(CHANGEPOINT, "'rebase' '" + rb + "' is not true or false");
            rebase = r.equals("true");
        }
        ChangePoint.Params params = new ChangePoint.Params(sigma == null ? 1.0 : sigma,
                number(cfg, "z", CHANGEPOINT, 0, false, d.z()),
                wholeNumber(cfg, "regime_run", CHANGEPOINT, 2, MAX_CHANGEPOINT_POINTS, d.regimeRun()),
                number(cfg, "cusum_k", CHANGEPOINT, 0, true, d.cusumK()),
                number(cfg, "cusum_h", CHANGEPOINT, 0, false, d.cusumH()),
                wholeNumber(cfg, "warmup", CHANGEPOINT, 0, MAX_CHANGEPOINT_POINTS, d.warmup()), rebase);
        Changes c = new Changes(order, residual, sigmaColumn, sigma, List.copyOf(groupBy), params);
        Set<String> seen = new HashSet<>();
        for (String col : c.inputColumns())
            if (!seen.add(col.toLowerCase(Locale.ROOT)))
                throw bad(CHANGEPOINT, "column '" + col + "' is named twice - the group_by, order, residual and sigma "
                        + "columns must all differ");
        return c;
    }

    /**
     * {@code null} when a change-point's flag columns (and, σ a number, its σ column) are free, its residual (and σ)
     * columns are numbers and its order column a number, DATE or TIMESTAMP, else the refusal; {@code types} maps every
     * inbound column to its DuckDB type.
     */
    public static String changePointRefusal(Changes spec, Map<String, String> types) {
        List<String> added = new ArrayList<>(CHANGEPOINT_FLAGS);
        if (spec.sigma() != null) added.add(Changes.SIGMA);
        for (String f : added)
            if (types.keySet().stream().anyMatch(x -> x.equalsIgnoreCase(f)))
                return CHANGEPOINT + ": output column '" + f + "' collides with an inbound column - rename or drop it first";
        List<String> numbers = spec.sigmaColumn() == null ? List.of(spec.residualColumn())
                : List.of(spec.residualColumn(), spec.sigmaColumn());
        for (String c : numbers) {
            String t = typeOf(types, c);
            if (!(t.startsWith("DECIMAL") || NUMERIC.contains(t)))
                return CHANGEPOINT + ": '" + c + "' must be a number, not " + (t.isEmpty() ? "unknown" : t);
        }
        // a text order sorts '10' before '9', so the series would be read out of order with no error
        String o = typeOf(types, spec.orderColumn());
        if (!(o.startsWith("DECIMAL") || NUMERIC.contains(o) || o.equals("DATE") || o.startsWith("TIMESTAMP")))
            return CHANGEPOINT + ": '" + spec.orderColumn() + "' must be a number, DATE or TIMESTAMP, not "
                    + (o.isEmpty() ? "unknown" : o) + " - text sorts 10 before 9";
        return null;
    }

    private static String typeOf(Map<String, String> types, String column) {
        return types.entrySet().stream().filter(e -> e.getKey().equalsIgnoreCase(column)).map(Map.Entry::getValue)
                .findFirst().orElse("").toUpperCase(Locale.ROOT).trim();
    }

    /**
     * The first refusal against the inbound columns, or {@code null}: a referenced column missing, or (shift) an
     * added column that collides with an inbound one.
     */
    public static String columnRefusal(String kind, List<String> required, List<String> added, List<String> inbound) {
        for (String c : required)
            if (inbound.stream().noneMatch(x -> x.equalsIgnoreCase(c)))
                return kind + ": column '" + c + "' is not in the inbound data (have: " + inbound + ")";
        for (String c : added)
            if (inbound.stream().anyMatch(x -> x.equalsIgnoreCase(c)))
                return kind + ": output column '" + c + "' collides with an inbound column - change 'suffix'";
        return null;
    }

    /**
     * {@code null} when the time column's DuckDB type fits the stated clock, else the refusal: a zone-less clock needs
     * a {@code DATE} or {@code TIMESTAMP}; a {@code timezone} needs a {@code TIMESTAMP WITH TIME ZONE}.
     */
    public static String typeRefusal(String kind, String column, ZoneId zone, String duckType) {
        String t = duckType == null ? "" : duckType.toUpperCase(Locale.ROOT).trim();
        boolean zoned = t.equals("TIMESTAMPTZ") || t.equals("TIMESTAMP WITH TIME ZONE");
        boolean wall = t.equals("DATE") || (t.startsWith("TIMESTAMP") && !zoned);
        if (zone == null && zoned)
            return kind + ": '" + column + "' is a TIMESTAMP WITH TIME ZONE - set 'timezone' (e.g. UTC or "
                    + "Europe/Berlin) to say whose calendar the buckets follow; DuckDB's own zone is the host's";
        if (zone == null && !wall)
            return kind + ": '" + column + "' must be a DATE or TIMESTAMP column, not " + duckType;
        if (zone != null && !zoned)
            return kind + ": 'timezone' applies to a TIMESTAMP WITH TIME ZONE column; '" + column + "' is " + duckType
                    + " - drop 'timezone' to bucket it on its own clock";
        return null;
    }

    // ── helpers ──────────────────────────────────────────────────────────────────────────────────────────────────

    static void known(Map<?, ?> cfg, String kind, Set<String> keys) {
        for (Object k : cfg.keySet())
            if (!keys.contains(String.valueOf(k)))
                throw bad(kind, "does not take '" + k + "' (keys: " + new java.util.TreeSet<>(keys) + ")");
    }

    static String required(Map<?, ?> cfg, String key, String kind, String what) {
        Object v = cfg.get(key);
        if (v == null || v.toString().isBlank()) throw bad(kind, "needs a '" + key + "' - " + what);
        return columnName(v, key, kind);
    }

    static TimeGrain grain(Map<?, ?> cfg, String kind) {
        Object g = cfg.get("grain");
        if (g == null || g.toString().isBlank())
            throw bad(kind, "needs a 'grain' - one of " + grains());
        try {
            return TimeGrain.parse(g.toString());
        } catch (IllegalArgumentException e) {
            throw bad(kind, "'grain' '" + g + "' is not one of " + grains());
        }
    }

    private static List<String> grains() {
        return java.util.Arrays.stream(TimeGrain.values()).map(TimeGrain::wire).toList();
    }

    static ZoneId zone(Map<?, ?> cfg, String kind) {
        Object z = cfg.get("timezone");
        if (z == null || z.toString().isBlank()) return null;
        try {
            ZoneId zone = ZoneId.of(z.toString().trim());
            TimeGrain.zoneId(zone);
            return zone;
        } catch (DateTimeException | IllegalArgumentException e) {
            throw bad(kind, "'timezone' '" + z + "' is not a known region id (e.g. UTC, Europe/Berlin, Asia/Kolkata)");
        }
    }

    static LocalDateTime bound(Map<?, ?> cfg, String key, String kind) {
        Object v = cfg.get(key);
        if (v == null || v.toString().isBlank()) return null;
        String s = v.toString().trim();
        try {
            return s.length() == 10 ? LocalDate.parse(s).atStartOfDay() : LocalDateTime.parse(s.replace(' ', 'T'));
        } catch (DateTimeException e) {
            throw bad(kind, "'" + key + "' '" + s + "' is not a date (2026-01-31) or date-time (2026-01-31T08:00)");
        }
    }

    static int wholeNumber(Map<?, ?> cfg, String key, String kind, int min, int max, String what) {
        Object v = cfg.get(key);
        if (v == null || v.toString().isBlank()) throw bad(kind, "needs a '" + key + "' - " + what);
        int n;
        try {
            n = Integer.parseInt(v.toString().trim());
        } catch (NumberFormatException e) {
            throw bad(kind, "'" + key + "' '" + v + "' is not a whole number");
        }
        if (n < min || n > max) throw bad(kind, "'" + key + "' must be between " + min + " and " + max + ", got " + n);
        return n;
    }

    /** An optional whole number in [min, max], {@code dflt} when blank. */
    static int wholeNumber(Map<?, ?> cfg, String key, String kind, int min, int max, int dflt) {
        Object v = cfg.get(key);
        return v == null || v.toString().isBlank() ? dflt : wholeNumber(cfg, key, kind, min, max, "");
    }

    /** An optional number, {@code dflt} when blank; refused unless finite and {@code > min} ({@code ≥ min} when {@code closed}). */
    static double number(Map<?, ?> cfg, String key, String kind, double min, boolean closed, double dflt) {
        Object v = cfg.get(key);
        if (v == null || v.toString().isBlank()) return dflt;
        double d;
        try {
            d = Double.parseDouble(v.toString().trim());
        } catch (NumberFormatException e) {
            throw bad(kind, "'" + key + "' value '" + v + "' is not a number");
        }
        if (!Double.isFinite(d) || (closed ? d < min : d <= min))
            throw bad(kind, "'" + key + "' must be " + (closed ? "at least " : "more than ") + min + ", got " + v);
        return d;
    }

    private static double weight(Map<?, ?> cfg, String key, double dflt) {
        Object v = cfg.get(key);
        return v == null || v.toString().isBlank() ? dflt : fraction(v, key, false);
    }

    /** A number in [0, 1] ({@code open}: strictly between). */
    private static double fraction(Object v, String key, boolean open) {
        double d;
        try {
            d = Double.parseDouble(v.toString().trim());
        } catch (NumberFormatException e) {
            throw bad(FORECAST, "'" + key + "' value '" + v + "' is not a number");
        }
        if (open ? !(d > 0 && d < 1) : !(d >= 0 && d <= 1))
            throw bad(FORECAST, "'" + key + "' value " + v + " must be " + (open ? "between 0 and 1, e.g. 0.95"
                    : "from 0 to 1"));
        return d;
    }

    static List<String> identList(Map<?, ?> cfg, String key, String kind) {
        List<String> out = new ArrayList<>();
        Object v = cfg.get(key);
        if (v instanceof List<?> l) {
            for (Object o : l) if (o != null && !o.toString().isBlank()) out.add(columnName(o, key, kind));
        } else if (v != null && !v.toString().isBlank()) {
            if (!(v instanceof String)) throw bad(kind, "'" + key + "' must be a list of column names");
            out.add(columnName(v, key, kind));
        }
        return out;
    }

    static String columnName(Object o, String where, String kind) {
        String s = o.toString().trim();
        try {
            Identifiers.validate(s, kind + " " + where);
        } catch (IllegalArgumentException e) {
            throw bad(kind, "'" + where + "' value '" + s + "' is not a plain column name ([A-Za-z_][A-Za-z0-9_]*)");
        }
        return s;
    }

    static IllegalArgumentException bad(String kind, String msg) {
        return new IllegalArgumentException(kind + " " + msg);
    }
}
