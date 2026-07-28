package com.gamma.query;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A saved <b>Rule Template</b> — a reusable query whose condition literals are replaced by named
 * {@code :name} binds, so one stored template answers many questions ("orders over :threshold").
 * Persisted as a {@code rule-template} component (authored TOON, so it passes the same
 * {@code ConfigSafetyValidator} path as any other component); this is the server-side model of that blob,
 * which previously had none.
 *
 * <h3>Why {@code :name} and not {@code $name}</h3>
 * The two are <b>different concepts</b>, and {@link Parameters} already documents that they must not be
 * conflated:
 * <ul>
 *   <li>{@code $day(-7)} / {@code $current_user} — a <em>run-time context</em> token resolved from the
 *       clock/session. It can appear anywhere in a statement, so {@link Parameters} must emit it as an
 *       interpolated SQL <em>literal</em>.</li>
 *   <li>{@code :threshold} — a <em>template hole</em> standing in for one value in a known position. That
 *       is exactly what a JDBC bind is for, so these are bound as real
 *       {@link java.sql.PreparedStatement} parameters and never interpolated.</li>
 * </ul>
 * Binding rather than interpolating removes the injection surface instead of widening it, and
 * {@code PreparedStatement} is already house style.
 *
 * <p>A template may contain both. The caller runs {@link #compile} first and {@link Parameters} second:
 * the {@code ?} count is fixed by {@code compile}, and {@code Parameters} emits strings <em>quoted</em>, so
 * a resolved {@code $}-value can neither add nor remove a placeholder. (The reverse order is equally safe
 * — {@code compile} skips string literals — but only one order is exercised by tests, so keep this one.)
 *
 * <h3>Fail closed on undeclared holes</h3>
 * {@link #compile} rejects a {@code :name} that is not a declared parameter. A hole we do not bind would
 * otherwise reach the driver as a live placeholder — the template would either fail obscurely or, worse,
 * bind by position against the wrong value.
 */
public record RuleTemplate(
        String id,
        String name,
        String source,
        List<String> projection,
        Object where,
        String sqlOverride,
        List<Param> params,
        String paramSql) {

    /** One declared bind: its {@code :name}, the column it filters, the operator, and the default value. */
    public record Param(String name, String field, String operator, String value) {}

    /** The result of {@link #compile}: SQL with positional {@code ?} placeholders + the values, in order. */
    public record Compiled(String sql, List<String> binds) {}

    /**
     * Read a stored {@code rule-template} component's config map. Missing/blank optional fields become
     * {@code null} or an empty list rather than throwing — a half-authored template must be readable so
     * the route can report *why* it is unusable.
     */
    @SuppressWarnings("unchecked")
    public static RuleTemplate from(Map<String, Object> config) {
        if (config == null) return null;
        List<String> projection = new ArrayList<>();
        Object proj = config.get("projection");
        if (proj instanceof List<?> l) {
            for (Object o : l) if (o != null) projection.add(String.valueOf(o));
        } else if (proj != null && !"*".equals(String.valueOf(proj))) {
            projection.add(String.valueOf(proj));   // `"*"` means "all columns" — an empty list, not a column named *
        }

        List<Param> params = new ArrayList<>();
        if (config.get("params") instanceof List<?> l)
            for (Object o : l)
                if (o instanceof Map<?, ?> m)
                    params.add(new Param(str(m.get("name")), str(m.get("field")),
                            str(m.get("operator")), str(m.get("value"))));

        return new RuleTemplate(
                str(config.get("id")), str(config.get("name")), str(config.get("source")),
                List.copyOf(projection), config.get("where"), str(config.get("sqlOverride")),
                List.copyOf(params), str(config.get("paramSql")));
    }

    /**
     * The template's SQL with each declared {@code :name} replaced by a positional {@code ?}, plus the
     * ordered bind values — {@code values} first, then the parameter's authored default.
     *
     * <p>⚠ The scan is a real tokenizer, not a regex, because two things in DuckDB SQL look like a bind
     * and are not:
     * <ul>
     *   <li>a <b>cast</b> — {@code amount::INTEGER} must not yield a bind named {@code INTEGER};</li>
     *   <li>a <b>string literal</b> — {@code WHERE note = ':threshold'} is the text, not a hole.</li>
     * </ul>
     * Getting either wrong changes the statement's meaning, so both are covered by tests.
     *
     * @throws IllegalStateException    when the template carries no SQL to run
     * @throws IllegalArgumentException when a {@code :name} in the SQL is not a declared parameter
     */
    public Compiled compile(Map<String, String> values) {
        String text = paramSql != null && !paramSql.isBlank() ? paramSql : sqlOverride;
        if (text == null || text.isBlank())
            throw new IllegalStateException(
                    "rule template '" + id + "' has no SQL to run — it carries neither paramSql nor sqlOverride");

        Map<String, Param> declared = new LinkedHashMap<>();
        for (Param p : params) if (p.name() != null && !p.name().isBlank()) declared.put(p.name(), p);

        StringBuilder sql = new StringBuilder(text.length());
        List<String> binds = new ArrayList<>();
        int i = 0;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (c == '\'') {                       // copy a string literal verbatim ('' is an escaped quote)
                int end = i + 1;
                while (end < text.length()) {
                    if (text.charAt(end) == '\'') {
                        if (end + 1 < text.length() && text.charAt(end + 1) == '\'') end += 2;
                        else { end++; break; }
                    } else end++;
                }
                sql.append(text, i, Math.min(end, text.length()));
                i = end;
                continue;
            }
            if (c == ':' && i + 1 < text.length() && text.charAt(i + 1) == ':') {
                sql.append("::");                  // a cast, not a bind
                i += 2;
                continue;
            }
            if (c == ':' && i + 1 < text.length() && isNameStart(text.charAt(i + 1))) {
                int end = i + 1;
                while (end < text.length() && isNamePart(text.charAt(end))) end++;
                String token = text.substring(i + 1, end);
                Param p = declared.get(token);
                if (p == null)
                    throw new IllegalArgumentException("rule template '" + id + "' references an undeclared "
                            + "parameter ':" + token + "' — declare it in `params` or remove the placeholder");
                String supplied = values == null ? null : values.get(token);
                binds.add(supplied != null ? supplied : p.value());
                sql.append('?');
                i = end;
                continue;
            }
            sql.append(c);
            i++;
        }
        return new Compiled(sql.toString(), List.copyOf(binds));
    }

    private static boolean isNameStart(char c) {
        return Character.isLetter(c) || c == '_';
    }

    private static boolean isNamePart(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }

    private static String str(Object v) {
        if (v == null) return null;
        String s = String.valueOf(v);
        return s.isBlank() ? null : s;
    }
}
