package com.gamma.la.api;

import com.gamma.entitystore.EntityTypes;

/**
 * The SQL mirror of {@link EntityTypes#normalise} (Entity profile plan D-EP5: one Entity is the NORMALISED typed key), so the
 * Entity profile counts Entities in DuckDB instead of streaming values to the JVM. One expression per closed normaliser; the
 * whitespace class is the JavaScript {@code \s} set {@code EntityTypes} spells out, written for RE2. {@code EntityKeySqlTest}
 * holds the two to the same answers. An empty key (a value the normaliser folds to nothing) is not an Entity.
 */
final class EntityKeySql {

    private EntityKeySql() {}

    /** {@code EntityTypes.WS} in RE2 syntax. */
    private static final String WS = "[\\t\\n\\x{000B}\\f\\r \\x{00A0}\\x{1680}\\x{2000}-\\x{200A}\\x{2028}\\x{2029}\\x{202F}\\x{205F}\\x{3000}\\x{FEFF}]";

    /** The normalised key of the quoted column {@code col} under {@code normaliser}, as VARCHAR ('' when nothing is left). */
    static String key(String normaliser, String col) {
        String v = "CAST(" + col + " AS VARCHAR)";
        return switch (normaliser) {
            case "digits" -> "regexp_replace(" + v + ", '[^0-9]', '', 'g')";
            case "e164" -> {
                String t = "regexp_replace(" + v + ", '^" + WS + "+|" + WS + "+$', '', 'g')";
                String d = "regexp_replace(" + t + ", '[^0-9]', '', 'g')";
                yield "(CASE WHEN starts_with(" + t + ", '+') THEN (CASE WHEN " + d + " = '' THEN '' ELSE '+' || " + d + " END)"
                        + " WHEN starts_with(" + d + ", '00') THEN (CASE WHEN " + d + " = '00' THEN '' ELSE '+' || substr(" + d + ", 3) END)"
                        + " ELSE " + d + " END)";
            }
            // Java's full upper-case mapping turns sharp s into SS; DuckDB's upper keeps it, so it is folded first
            case "upper-trim" -> "upper(replace(regexp_replace(regexp_replace(" + v + ", '" + WS + "+', ' ', 'g'), '^ | $', '', 'g'), 'ß', 'SS'))";
            case "default" -> "regexp_replace(regexp_replace(regexp_replace(replace(lower(" + v + "), 'ς', 'σ'), '"
                    + WS + "+', ' ', 'g'), '[ .,;:]+$', ''), '^ | $', '', 'g')";
            default -> throw new IllegalArgumentException("normaliser must be one of " + EntityTypes.NORMALISERS + ", got '" + normaliser + "'");
        };
    }

    /**
     * The safe prefix length a normaliser declares for the value-shape histogram (plan §4.5, D-EP3): {@code e164} 3 (a country
     * code, never a subscriber prefix), every other normaliser 0 (no prefix histogram).
     */
    static int prefixLength(String normaliser) {
        return "e164".equals(normaliser) ? 3 : 0;
    }
}
