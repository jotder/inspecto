package com.gamma.la.api;

import com.gamma.entitystore.EntityTypes;
import com.gamma.util.SqlIdent;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

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
            // Java's full case mappings (sharp s to SS, ligatures, ...) are folded first: DuckDB's upper / lower map one character to one
            case "upper-trim" -> "upper(" + fold("regexp_replace(regexp_replace(" + v + ", '" + WS + "+', ' ', 'g'), '^ | $', '', 'g')", UPPER_FOLDS) + ")";
            case "default" -> "regexp_replace(regexp_replace(regexp_replace(replace(lower(" + fold(v, LOWER_FOLDS) + "), 'ς', 'σ'), '"
                    + WS + "+', ' ', 'g'), '[ .,;:]+$', ''), '^ | $', '', 'g')";
            default -> throw new IllegalArgumentException("normaliser must be one of " + EntityTypes.NORMALISERS + ", got '" + normaliser + "'");
        };
    }

    /**
     * The characters whose full case mapping in {@code Locale.ROOT} (what {@link EntityTypes#normalise} applies) is not their simple
     * one-character mapping (what DuckDB's {@code upper} / {@code lower} apply), to the full mapping: 102 for upper case (sharp s,
     * the ligatures, Greek with ypogegrammeni, ...), one for lower case (dotted capital I), plus the 55 Unicode case pairs newer
     * than DuckDB 2.0.0.0's case tables (LA-ENTITY-KEY-NEW-CASE-PAIRS-1). Read from the running JDK, so the SQL follows the Java
     * side's Unicode version (LA-INDEX-SPELLINGS-1 review; the shared parity fixture holds both to it).
     */
    private static final Map<String, String> UPPER_FOLDS = folds(true), LOWER_FOLDS = folds(false);

    private static Map<String, String> folds(boolean upper) {
        Map<String, String> out = new LinkedHashMap<>();
        for (int cp = 0; cp <= Character.MAX_CODE_POINT; cp++) {
            if (Character.getType(cp) == Character.UNASSIGNED || Character.getType(cp) == Character.SURROGATE) continue;
            String s = new String(Character.toChars(cp));
            String full = upper ? s.toUpperCase(Locale.ROOT) : s.toLowerCase(Locale.ROOT);
            String simple = new String(Character.toChars(upper ? Character.toUpperCase(cp) : Character.toLowerCase(cp)));
            if (!full.equals(simple) || (!full.equals(s) && isDuckDbUncased(cp))) out.put(s, full);
        }
        return Map.copyOf(out);
    }

    /** Code points from Unicode case pairs newer than DuckDB 2.0.0.0's case tables, left as written by DuckDB {@code upper}/{@code lower}. */
    private static boolean isDuckDbUncased(int cp) {
        return switch (cp) {
            case 0x019B, 0xA7DC, // ƛ / Ƛ
                 0x0264, 0xA7CB, // ɤ / Ɤ
                 0x1C89, 0x1C8A, // Cyrillic Tje
                 0xA7CC, 0xA7CD, // S with diagonal stroke
                 0xA7CE, 0xA7CF, // Pharyngeal voiced fricative
                 0xA7D2, 0xA7D3, // Double thorn
                 0xA7D4, 0xA7D5, // Double wynn
                 0xA7DA, 0xA7DB  // Lambda
                    -> true;
            default -> (cp >= 0x10D50 && cp <= 0x10D65) || (cp >= 0x10D70 && cp <= 0x10D85) // Garay
                    || (cp >= 0x16EA0 && cp <= 0x16EB8) || (cp >= 0x16EBB && cp <= 0x16ED3); // Beria Erfe
        };
    }

    /** {@code expr} with each {@code folds} character replaced by its mapping; the chain runs only on a value holding one of them. */
    private static String fold(String expr, Map<String, String> folds) {
        if (folds.isEmpty()) return expr;
        StringBuilder chain = new StringBuilder(expr);
        StringBuilder chars = new StringBuilder();
        for (Map.Entry<String, String> f : new java.util.TreeMap<>(folds).entrySet()) {
            chain.insert(0, "replace(").append(", ").append(SqlIdent.sqlStr(f.getKey())).append(", ")
                    .append(SqlIdent.sqlStr(f.getValue())).append(")");
            chars.append(f.getKey());
        }
        return "(CASE WHEN regexp_matches(" + expr + ", " + SqlIdent.sqlStr("[" + chars + "]") + ") THEN " + chain + " ELSE " + expr + " END)";
    }

    /**
     * The safe prefix length a normaliser declares for the value-shape histogram (plan §4.5, D-EP3): {@code e164} 3 (a country
     * code, never a subscriber prefix), every other normaliser 0 (no prefix histogram).
     */
    static int prefixLength(String normaliser) {
        return "e164".equals(normaliser) ? 3 : 0;
    }
}
