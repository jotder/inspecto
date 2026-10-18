package com.gamma.analytics.time;

import com.gamma.util.SqlIdent;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.OptionalDouble;

/**
 * The seasonal profile of a series over calendar slots (ANALYTICS-CORE-1 C5): the mean per {@link HourOfWeek} slot, and
 * the <b>seasonal strength</b> — the share of the series' variance the slots explain,
 * {@code Σ_obs (mean_slot − mean)² / Σ_obs (x − mean)²}, in [0, 1]; empty for a constant series. It answers "is there a
 * weekday×hour shape worth a seasonal baseline?" directly from the observations, where {@link SeasonalityDetector}
 * answers "which period repeats?" from an evenly spaced series.
 *
 * <p>Population arithmetic throughout (no n−1); observations with a NULL value are left out; a slot with no observation
 * is absent from the profile. The caller states the clock when it builds the slot ({@link HourOfWeek}); the SQL form
 * takes it as a slot expression and never reads DuckDB's session zone. Held equal to the JVM form by
 * {@code HourOfWeekDuckDbParityTest}.
 */
public final class SeasonalProfile {

    private SeasonalProfile() {}

    /** One slot's evidence. */
    public record SlotMean(int slot, long n, double mean) {}

    /** The profile: observations, overall mean, slot means ascending by slot, and the strength (empty = constant). */
    public record Profile(long n, double mean, List<SlotMean> slots, OptionalDouble strength) {}

    /** Profile of {@code values[i]} observed in slot {@code slotOf[i]} (each in [0, kind.slots())); NaN is refused. */
    public static Profile of(HourOfWeek kind, int[] slotOf, double[] values) {
        Objects.requireNonNull(kind, "kind");
        if (slotOf.length != values.length) throw new IllegalArgumentException("slots and values differ in length");
        int n = values.length;
        long[] cnt = new long[kind.slots()];
        double[] sum = new double[kind.slots()];
        double total = 0;
        for (int i = 0; i < n; i++) {
            if (Double.isNaN(values[i])) throw new IllegalArgumentException("NaN in series");
            if (slotOf[i] < 0 || slotOf[i] >= kind.slots())
                throw new IllegalArgumentException(kind.wire() + " slot out of range: " + slotOf[i]);
            cnt[slotOf[i]]++;
            sum[slotOf[i]] += values[i];
            total += values[i];
        }
        double mean = n == 0 ? Double.NaN : total / n;
        double ssTotal = 0, ssBetween = 0;
        for (double v : values) ssTotal += (v - mean) * (v - mean);
        List<SlotMean> out = new ArrayList<>();
        for (int s = 0; s < cnt.length; s++) {
            if (cnt[s] == 0) continue;
            double ms = sum[s] / cnt[s];
            out.add(new SlotMean(s, cnt[s], ms));
            ssBetween += cnt[s] * (ms - mean) * (ms - mean);
        }
        return new Profile(n, mean, List.copyOf(out),
                n == 0 || ssTotal == 0 ? OptionalDouble.empty() : OptionalDouble.of(ssBetween / ssTotal));
    }

    /**
     * Per key and slot: {@code <keys…>, slot, n, mean}, ordered by keys then slot. {@code slotExpr} is a
     * {@link HourOfWeek#slotSql} expression over the relation's columns — it is where the clock is stated.
     */
    public static String profileSql(String relation, String slotExpr, String valueColumn, List<String> keys) {
        String k = keyList(keys);
        return "SELECT " + k + "__slot AS slot, count(*) AS n, avg(__x) AS mean FROM "
                + observations(relation, slotExpr, valueColumn, keys) + " GROUP BY " + k + "__slot ORDER BY " + k + "__slot";
    }

    /** Per key: {@code <keys…>, n, strength} (NULL for a constant series), ordered by keys. */
    public static String strengthSql(String relation, String slotExpr, String valueColumn, List<String> keys) {
        String k = keyList(keys);
        String byKey = keys.isEmpty() ? "" : "PARTITION BY " + String.join(", ", quoted(keys));
        String bySlot = keys.isEmpty() ? "PARTITION BY __slot" : byKey + ", __slot";
        String group = keys.isEmpty() ? "" : " GROUP BY " + String.join(", ", quoted(keys));
        String order = keys.isEmpty() ? "" : " ORDER BY " + String.join(", ", quoted(keys));
        return "WITH __o AS (SELECT " + k + "__x, avg(__x) OVER (" + byKey + ") AS __mu, avg(__x) OVER (" + bySlot
                + ") AS __ms FROM " + observations(relation, slotExpr, valueColumn, keys) + ")"
                + " SELECT " + k + "count(*) AS n, sum((__ms - __mu) * (__ms - __mu))"
                + " / NULLIF(sum((__x - __mu) * (__x - __mu)), 0) AS strength FROM __o" + group + order;
    }

    private static String observations(String relation, String slotExpr, String valueColumn, List<String> keys) {
        Objects.requireNonNull(relation, "relation");
        Objects.requireNonNull(slotExpr, "slotExpr");
        Objects.requireNonNull(valueColumn, "valueColumn");
        String v = SqlIdent.q(valueColumn);
        return "(SELECT " + keyList(keys) + slotExpr + " AS __slot, CAST(" + v + " AS DOUBLE) AS __x FROM "
                + SqlIdent.q(relation) + " WHERE " + v + " IS NOT NULL AND " + slotExpr + " IS NOT NULL)";
    }

    private static List<String> quoted(List<String> keys) {
        return keys.stream().map(SqlIdent::q).toList();
    }

    /** {@code "a", "b", } — each key quoted, with a trailing separator (empty for no keys). */
    private static String keyList(List<String> keys) {
        StringBuilder s = new StringBuilder();
        for (String k : keys) s.append(SqlIdent.q(k)).append(", ");
        return s.toString();
    }
}
