package com.gamma.pipeline.exec;

import com.gamma.etl.Identifiers;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Key readers shared by the rank, sample and pivot Steps' grammars ({@link RankStep}, {@link SampleStep},
 * {@link PivotStep}): every message starts with the node type, so the run, the recipe compiler and the save gate refuse
 * with the same words. Column names are validated against {@link Identifiers}; nothing authored reaches SQL unquoted.
 */
final class StepKeys {

    private StepKeys() {}

    static void known(Map<?, ?> cfg, String kind, Set<String> keys) {
        for (Object k : cfg.keySet())
            if (!keys.contains(String.valueOf(k)))
                throw bad(kind, "does not take '" + k + "' (keys: " + new TreeSet<>(keys) + ")");
    }

    static boolean blank(Object v) {
        return v == null || v.toString().isBlank();
    }

    /** A plain column name, or {@code dflt} when absent ({@code null} dflt = required, refused with {@code what}). */
    static String column(Map<?, ?> cfg, String key, String kind, String dflt, String what) {
        Object v = cfg.get(key);
        if (blank(v)) {
            if (dflt == null) throw bad(kind, "needs '" + key + "' - " + what);
            return dflt;
        }
        return columnName(v, key, kind);
    }

    /** A list of plain column names (a single string is a list of one); empty when absent. */
    static List<String> columns(Map<?, ?> cfg, String key, String kind) {
        List<String> out = new ArrayList<>();
        Object v = cfg.get(key);
        if (v instanceof List<?> l) {
            for (Object o : l) if (!blank(o)) out.add(columnName(o, key, kind));
        } else if (!blank(v)) {
            if (!(v instanceof String)) throw bad(kind, "'" + key + "' must be a list of column names");
            out.add(columnName(v, key, kind));
        }
        return out;
    }

    static String columnName(Object o, String key, String kind) {
        String s = o.toString().trim();
        try {
            Identifiers.validate(s, kind + " " + key);
        } catch (IllegalArgumentException e) {
            throw bad(kind, "'" + key + "' value '" + s + "' is not a plain column name ([A-Za-z_][A-Za-z0-9_]*)");
        }
        return s;
    }

    static boolean bool(Map<?, ?> cfg, String key, String kind, boolean dflt) {
        Object v = cfg.get(key);
        if (blank(v)) return dflt;
        String s = v.toString().trim();
        if ("true".equalsIgnoreCase(s)) return true;
        if ("false".equalsIgnoreCase(s)) return false;
        throw bad(kind, "'" + key + "' must be true or false, got '" + s + "'");
    }

    /** A whole number in {@code [min, max]}, or {@code null} when absent. */
    static Long wholeNumber(Map<?, ?> cfg, String key, String kind, long min, long max) {
        Object v = cfg.get(key);
        if (blank(v)) return null;
        long n;
        try {
            n = Long.parseLong(v.toString().trim());
        } catch (NumberFormatException e) {
            throw bad(kind, "'" + key + "' '" + v + "' is not a whole number");
        }
        if (n < min || n > max) throw bad(kind, "'" + key + "' must be between " + min + " and " + max + ", got " + n);
        return n;
    }

    /**
     * The first refusal against the inbound columns, or {@code null}: a referenced column missing, or an added column
     * colliding with an inbound one.
     */
    static String columnRefusal(String kind, List<String> required, List<String> added, List<String> inbound,
                                String fix) {
        for (String c : required)
            if (inbound.stream().noneMatch(x -> x.equalsIgnoreCase(c)))
                return kind + ": column '" + c + "' is not in the inbound data (have: " + inbound + ")";
        for (String c : added)
            if (inbound.stream().anyMatch(x -> x.equalsIgnoreCase(c)))
                return kind + ": output column '" + c + "' collides with an inbound column - " + fix;
        return null;
    }

    static IllegalArgumentException bad(String kind, String msg) {
        return new IllegalArgumentException(kind + " " + msg);
    }
}
