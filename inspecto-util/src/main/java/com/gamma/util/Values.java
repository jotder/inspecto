package com.gamma.util;

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

    /** Filename-safe id: every char outside {@code [A-Za-z0-9._-]} becomes {@code _}; null → {@code "_"}. */
    public static String fileSafe(String s) {
        return s == null ? "_" : s.replaceAll("[^A-Za-z0-9._-]", "_");
    }
}
