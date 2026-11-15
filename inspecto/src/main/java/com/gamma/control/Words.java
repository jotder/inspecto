package com.gamma.control;

import java.util.Map;

/**
 * The words the Add data reasons are written with ({@code LA-FULL-CYCLE-TEST-1} phase 3, finding 10): one place for
 * singular and plural ("1 is not", "3 are not"; "1 distinct value"), and the plain names of the column types, so no
 * sentence a person reads carries an engine type such as {@code TIMESTAMP}. The screen's own helper is {@code plural} in
 * {@code add-data.model.ts}.
 */
final class Words {

    private Words() {}

    /** {@code one} when {@code n} is 1, else {@code many}: {@code pick(1, "is", "are")} is "is". */
    static String pick(long n, String one, String many) {
        return n == 1 ? one : many;
    }

    /** The count with its noun: "1 value", "3 values". */
    static String count(long n, String one, String many) {
        return n + " " + pick(n, one, many);
    }

    /** What a column of an engine type holds, as a plural noun: "dates with a time". */
    static String plural(String type) {
        return PLURAL.getOrDefault(type == null ? "" : type.toUpperCase(java.util.Locale.ROOT), "text");
    }

    /** One value of an engine type, with its article: "a date with a time". */
    static String singular(String type) {
        return SINGULAR.getOrDefault(type == null ? "" : type.toUpperCase(java.util.Locale.ROOT), "text");
    }

    /** The name the screen gives an engine type ({@code TYPE_LABELS} in {@code add-data.model.ts}): "Date and time". */
    static String label(String type) {
        return LABEL.getOrDefault(type == null ? "" : type.toUpperCase(java.util.Locale.ROOT), "Text");
    }

    private static final Map<String, String> PLURAL = Map.of("VARCHAR", "text", "BIGINT", "whole numbers", "DOUBLE", "decimal numbers",
            "DATE", "dates", "TIMESTAMP", "dates with a time", "BOOLEAN", "yes / no values");
    private static final Map<String, String> SINGULAR = Map.of("VARCHAR", "text", "BIGINT", "a whole number", "DOUBLE", "a decimal number",
            "DATE", "a date", "TIMESTAMP", "a date with a time", "BOOLEAN", "a yes / no value");
    private static final Map<String, String> LABEL = Map.of("VARCHAR", "Text", "BIGINT", "Whole number", "DOUBLE", "Decimal number",
            "DATE", "Date", "TIMESTAMP", "Date and time", "BOOLEAN", "Yes / no");
}
