package com.gamma.util;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Null-safe value coercion helpers shared across modules. Each method replaces a family of
 * per-file {@code private static} copies; the names encode the exact semantics (trim vs no-trim,
 * null vs empty fallback) because the historical copies differed subtly — pick the method that
 * matches the site, never "the closest one".
 */
public final class Values {

    private Values() {}

    /** Null-safe {@code toString}: {@code null} stays {@code null}. No trimming. */
    public static String str(Object v) {
        return v == null ? null : v.toString();
    }

    /** Null-safe {@code toString}: {@code null} becomes {@code ""}. No trimming. */
    public static String strOrEmpty(Object v) {
        return v == null ? "" : String.valueOf(v);
    }

    /** Null-safe trimmed {@code toString}: {@code null} becomes {@code ""}. */
    public static String trimOrEmpty(Object v) {
        return v == null ? "" : String.valueOf(v).trim();
    }

    /** Trimmed {@code toString}; {@code null} or blank collapses to {@code null}. */
    public static String trimToNull(Object v) {
        if (v == null) return null;
        String s = String.valueOf(v).trim();
        return s.isEmpty() ? null : s;
    }

    /** {@code null} or blank collapses to {@code null}; non-blank values are NOT trimmed. */
    public static String blankToNull(Object v) {
        if (v == null) return null;
        String s = v.toString();
        return s.isBlank() ? null : s;
    }

    /** Lenient int coercion: {@link Number} narrows, strings parse (trimmed), anything else → fallback. */
    public static int intOr(Object v, int fallback) {
        if (v instanceof Number n) return n.intValue();
        if (v != null) {
            try {
                return Integer.parseInt(v.toString().trim());
            } catch (NumberFormatException ignored) {
            }
        }
        return fallback;
    }

    /** Puts {@code key=v} only when {@code v} is non-null. */
    public static void putIfPresent(Map<String, Object> m, String key, Object v) {
        if (v != null) m.put(key, v);
    }

    // ── typed map access ─────────────────────────────────────────────────────
    // Two map-at variants with DIFFERENT semantics on a non-map value — pick the one that matches
    // the site being replaced, never "the closest one" (same rule as the string helpers above):
    //   mapAt      — instanceof-guarded semantics: non-map (or null/absent) → null.
    //   castMapAt  — bare-cast semantics: absent → null, non-map → ClassCastException,
    //                byte-identical to the historical `(Map<String,Object>) m.get(key)` idiom.

    /** Null-tolerant nested map: null map, absent key, or a non-{@code Map} value → {@code null}. */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> mapAt(Map<String, Object> m, String key) {
        if (m == null) return null;
        return m.get(key) instanceof Map<?, ?> mm ? (Map<String, Object>) mm : null;
    }

    /**
     * Exact-preserving nested map: {@code (Map<String,Object>) m.get(key)} semantics — {@code null}
     * when absent, {@link ClassCastException} when the value is not a map. Use for sweeping sites
     * that are bare casts today; {@link #mapAt} only where the site already instanceof-guards.
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> castMapAt(Map<String, Object> m, String key) {
        return (Map<String, Object>) m.get(key);
    }

    /**
     * Exact-preserving nested list: {@code (List<Object>) m.get(key)} semantics — {@code null}
     * when absent, {@link ClassCastException} when the value is not a list.
     */
    @SuppressWarnings("unchecked")
    public static List<Object> listAt(Map<String, Object> m, String key) {
        return (List<Object>) m.get(key);
    }

    /**
     * A list-of-objects config value as {@code List<Map<String,Object>>} — absent ⇒ empty. Fails fast
     * ({@link IllegalArgumentException} naming {@code where}) on anything that is not a list of maps,
     * because the alternative is a consumer seeing a shape it silently skips.
     */
    @SuppressWarnings("unchecked")
    public static List<Map<String, Object>> listOfMapsAt(Map<String, Object> m, String key, String where) {
        Object value = m.get(key);
        if (value == null) return List.of();
        if (!(value instanceof List<?> list))
            throw new IllegalArgumentException(where + " must be a list of objects");
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object o : list) {
            if (!(o instanceof Map<?, ?> mm))
                throw new IllegalArgumentException(where + " entries must be objects, got: " + o);
            out.add((Map<String, Object>) mm);
        }
        return out;
    }

    /** Filename-safe id: every char outside {@code [A-Za-z0-9._-]} becomes {@code _}; null → {@code "_"}. */
    public static String fileSafe(String s) {
        return s == null ? "_" : s.replaceAll("[^A-Za-z0-9._-]", "_");
    }
}
