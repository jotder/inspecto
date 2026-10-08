package com.gamma.query;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import static com.gamma.util.Values.strOrEmpty;

/**
 * Pure, dependency-free evaluator for the structured condition tree authored in the UI — a faithful
 * Java port of the browser-side offline query engine ({@code inspecto-ui/src/app/inspecto/query/
 * query-eval.ts}) plus its type inference ({@code query-columns.ts}). It exists so the backend can
 * count row matches with <b>exactly</b> the semantics the authoring UI previews — the Decision Rule
 * {@code simulate} route ({@link com.gamma.control} {@code /decision-rules/{name}/simulate}) is the
 * first consumer, evaluating a rule's {@code when} tree over a caller-supplied {@code sampleRows}
 * batch.
 *
 * <p><b>Tree shape</b> (the {@code query-types.ts} model): a group is
 * {@code {kind:'group', op:'AND'|'OR', items:[…]}}; a leaf is
 * {@code {kind:'condition', field, operator, value?, value2?}}. Operators, comparison rules, the
 * case-insensitive substring ops, {@code in}/{@code between}, and the "empty group ⇒ no constraint"
 * rule all mirror the TS reference so a rule counted here matches the same rows it would in-browser.
 * Column types are inferred from the sample rows the same way {@code inferColumns()} does when no
 * metadata is supplied.
 *
 * <p>No filesystem, no SQL, no config surface — it only reads the caller's in-memory rows, so it
 * needs no {@code ConfigSafetyValidator} pass; the sample is bounded by the HTTP request body.
 */
public final class ConditionTree {

    private ConditionTree() {
    }

    private enum ColType { NUMBER, STRING, DATE, BOOLEAN }

    /**
     * Count how many of {@code rows} satisfy the {@code when} tree. An absent tree ({@code null} or an
     * empty map) or an empty group imposes no constraint and matches every row (mirrors
     * {@code matchGroup}'s "no constraint ⇒ true"). Column types are inferred from {@code rows}.
     *
     * @throws IllegalArgumentException when the root is present but not a group — see {@link #requireGroupRoot}
     */
    public static int matched(Object when, List<Map<String, Object>> rows) {
        return filter(when, rows).size();
    }

    /**
     * The subset of {@code rows} satisfying the {@code when} tree, in their original order — the
     * row-level analogue of {@link #matched}, used where the matching rows themselves are needed
     * (e.g. an Alert Rule's {@code when} pre-filter over ledger rows) rather than just a count.
     */
    public static List<Map<String, Object>> filter(Object when, List<Map<String, Object>> rows) {
        requireGroupRoot(when);
        if (rows == null || rows.isEmpty()) return List.of();
        Map<String, ColType> types = inferColumns(rows);
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> row : rows) if (matchGroup(when, row, types)) out.add(row);
        return out;
    }

    /**
     * The root rule every condition-tree consumer shares (this evaluator and {@link ConditionSql}): an
     * absent tree ({@code null} or an empty map) is fine and means "no constraint"; otherwise the root must
     * be a group. A bare leaf ({@code {kind:'condition',…}} or a kind-less {@code {field,operator,value}}),
     * a string or a list used to read as "no constraint" and matched EVERY row — an Alert Rule fired on all
     * of them — so it is refused instead. Callers validate with this at save time.
     *
     * @throws IllegalArgumentException when the root is present but is not a group
     */
    public static void requireGroupRoot(Object when) {
        if (when != null && !(when instanceof Map<?, ?> m && (m.isEmpty() || isGroup(m))))
            throw new IllegalArgumentException("the condition tree's root must be a group "
                    + "({kind:'group', op:'AND'|'OR', items:[...]}), not a bare condition or other value; "
                    + "wrap a single condition in a one-item group");
        validate(when);
    }

    // ── STRICT mode (opt-in; the lenient API above is untouched) ────────────────────

    private static final Set<String> KNOWN_OPS = Set.of("=", "!=", "<", "<=", ">", ">=", "between", "in",
            "contains", "startsWith", "endsWith", "matches", "isNull", "isNotNull");
    private static final Set<String> ORDERING_OPS = Set.of("<", "<=", ">", ">=", "between");

    /**
     * Strict twin of {@link #matched}: the tree is first checked by {@link #validateStrict}, then evaluated
     * with identical semantics for present data except that a leaf whose field is ABSENT from the row is
     * {@code false} (never "no constraint"). For fail-closed consumers (access-style decisions) that must
     * never read an authoring-time leniency as "allow".
     *
     * @throws IllegalArgumentException with a path-pointing message when the tree is not strictly valid
     */
    public static int matchedStrict(Object when, List<Map<String, Object>> rows) {
        return filterStrict(when, rows).size();
    }

    /** Strict twin of {@link #filter}; see {@link #matchedStrict}. */
    public static List<Map<String, Object>> filterStrict(Object when, List<Map<String, Object>> rows) {
        validateStrict(when);
        if (rows == null || rows.isEmpty()) return List.of();
        Map<String, ColType> types = inferColumns(rows);
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> row : rows) if (matchGroupStrict(when, row, types)) out.add(row);
        return out;
    }

    /**
     * Refuses every silent leniency of the default API: a non-map or bare-leaf root, an empty group, a
     * non-map item, an incomplete leaf (missing field / operator / required value), an unknown operator or
     * group {@code op}, an {@code in} with no members, an ordering operator ({@code < <= > >= between})
     * whose operand is neither a number nor an ISO date/time; plus the extension rules of {@link #validate}.
     *
     * @throws IllegalArgumentException naming the path of the offender, e.g. {@code items[2].field: incomplete condition}
     */
    public static void validateStrict(Object when) {
        if (!(when instanceof Map<?, ?> root))
            throw new IllegalArgumentException("root: the condition tree must be a group object");
        if (!isGroup(root))
            throw new IllegalArgumentException("root: the root must be a group, not a bare condition");
        strictGroup(root, "");
        validate(when);
    }

    private static void strictGroup(Map<?, ?> g, String prefix) {
        Object op = g.get("op");
        if (op != null && !"AND".equalsIgnoreCase(String.valueOf(op)) && !"OR".equalsIgnoreCase(String.valueOf(op)))
            throw new IllegalArgumentException(prefix + "op: unknown group operator '" + op + "' (AND or OR)");
        String key = g.get("items") != null ? "items" : "conditions";
        if (!(g.get(key) instanceof List<?> items) || items.isEmpty())
            throw new IllegalArgumentException(prefix + key + ": empty group");
        for (int i = 0; i < items.size(); i++) {
            String at = prefix + key + "[" + i + "]";
            if (!(items.get(i) instanceof Map<?, ?> m))
                throw new IllegalArgumentException(at + ": not a condition or group object");
            if (isGroup(m)) strictGroup(m, at + ".");
            else strictLeaf(m, at);
        }
    }

    private static void strictLeaf(Map<?, ?> c, String at) {
        if (strOrEmpty(c.get("field")).isEmpty()) throw new IllegalArgumentException(at + ".field: incomplete condition");
        String operator = strOrEmpty(c.get("operator"));
        if (operator.isEmpty()) throw new IllegalArgumentException(at + ".operator: incomplete condition");
        if (!KNOWN_OPS.contains(operator))
            throw new IllegalArgumentException(at + ".operator: unknown operator '" + operator + "'");
        if (!isComplete(c)) throw new IllegalArgumentException(at + ".value: incomplete condition");
        boolean cellOperand = !strOrEmpty(c.get("valueField")).isEmpty();
        String value = strOrEmpty(c.get("value"));
        if (operator.equals("in") && !cellOperand) {
            boolean any = false;
            for (String x : value.split(",")) any |= !x.trim().isEmpty();
            if (!any) throw new IllegalArgumentException(at + ".value: 'in' with an empty list");
        }
        if (ORDERING_OPS.contains(operator) && !cellOperand) {
            requireOrderable(value, at + ".value");
            if (operator.equals("between")) requireOrderable(strOrEmpty(c.get("value2")), at + ".value2");
        }
    }

    private static void requireOrderable(String operand, String at) {
        boolean number;
        try {
            Double.parseDouble(operand.trim());
            number = !operand.isBlank();
        } catch (NumberFormatException e) {
            number = false;
        }
        if (!number && toEpoch(operand) == null)
            throw new IllegalArgumentException(at + ": operand '" + operand + "' is neither a number nor an ISO date/time");
    }

    private static boolean matchGroupStrict(Object node, Map<String, Object> row, Map<String, ColType> types) {
        Map<?, ?> g = (Map<?, ?>) node;
        Object rawItems = g.get("items");
        if (rawItems == null) rawItems = g.get("conditions");
        boolean or = "OR".equalsIgnoreCase(String.valueOf(g.get("op") == null ? "AND" : g.get("op")));
        boolean any = false;
        boolean all = true;
        for (Object it : (List<?>) rawItems) {
            @SuppressWarnings("unchecked") Map<String, Object> item = (Map<String, Object>) it;
            boolean res = isGroup(item) ? matchGroupStrict(item, row, types)
                    : row.containsKey(strOrEmpty(item.get("field"))) && matchCondition(item, row, types);
            any |= res;
            all &= res;
        }
        boolean res = or ? any : all;
        return flag(g, "negate") ? !res : res;
    }

    // ── Condition Language extensions (not / valueField / ignoreCase / matches) ─────

    /** Longest {@code matches} pattern accepted. Beyond this cap there is NO catastrophic-backtracking
     *  mitigation in the in-JVM evaluator ({@code java.util.regex} is backtracking; DuckDB's RE2 is linear). */
    public static final int MAX_PATTERN_LENGTH = 256;

    private static final Set<String> VALUE_FIELD_OPS = Set.of("=", "!=", "<", "<=", ">", ">=", "contains", "startsWith", "endsWith");
    private static final Set<String> IGNORE_CASE_OPS = Set.of("=", "!=", "in", "contains", "startsWith", "endsWith", "matches");
    /** Constructs {@code java.util.regex} supports but DuckDB's RE2 does not: lookaround, atomic groups,
     *  possessive quantifiers, back-references. Refused so a pattern accepted here runs in both backends. */
    private static final Pattern NON_RE2 = Pattern.compile("\\(\\?(=|!|<=|<!|>|<[A-Za-z])|\\\\[1-9k]|[*+?}]\\+");

    /** {@code true} for a boolean {@code true} or the string {@code "true"} (TOON may hand either). */
    static boolean flag(Map<?, ?> m, String key) {
        Object v = m.get(key);
        return Boolean.TRUE.equals(v) || (v instanceof String s && "true".equalsIgnoreCase(s.trim()));
    }

    /**
     * Fail-closed check of the extension keys over the whole tree: {@code valueField} only on the
     * operators that compare two cells and never together with {@code value}; {@code ignoreCase} only on
     * string operators; a {@code matches} pattern must be at most {@link #MAX_PATTERN_LENGTH} long, compile,
     * and avoid constructs RE2 lacks. Trees that use none of the extensions are never rejected here.
     *
     * @throws IllegalArgumentException naming the offending condition and a plain reason
     */
    public static void validate(Object node) {
        if (!(node instanceof Map<?, ?> g)) return;
        Object rawItems = g.get("items");
        if (rawItems == null) rawItems = g.get("conditions");
        if (!(rawItems instanceof List<?> items)) return;
        for (Object it : items) {
            if (!(it instanceof Map<?, ?> m)) continue;
            if (isGroup(m)) validate(m);
            else validateLeaf(m);
        }
    }

    private static void validateLeaf(Map<?, ?> c) {
        String field = strOrEmpty(c.get("field"));
        String operator = strOrEmpty(c.get("operator"));
        String valueField = strOrEmpty(c.get("valueField"));
        String at = "condition on '" + field + "' (" + operator + "): ";
        if (!valueField.isEmpty()) {
            if (!VALUE_FIELD_OPS.contains(operator))
                throw new IllegalArgumentException(at + "valueField is only valid with = != < <= > >= contains startsWith endsWith");
            if (!strOrEmpty(c.get("value")).isEmpty())
                throw new IllegalArgumentException(at + "set either value or valueField, not both");
        }
        if (flag(c, "ignoreCase") && !IGNORE_CASE_OPS.contains(operator))
            throw new IllegalArgumentException(at + "ignoreCase is only valid with = != in contains startsWith endsWith matches");
        if (operator.equals("matches")) {
            String pat = strOrEmpty(c.get("value"));
            if (pat.isEmpty()) return; // still being built
            if (pat.length() > MAX_PATTERN_LENGTH)
                throw new IllegalArgumentException(at + "pattern longer than " + MAX_PATTERN_LENGTH + " characters");
            if (NON_RE2.matcher(pat).find())
                throw new IllegalArgumentException(at + "pattern uses lookaround, atomic groups, possessive quantifiers or back-references, which the SQL backend (RE2) does not support");
            try {
                Pattern.compile(pat);
            } catch (PatternSyntaxException e) {
                throw new IllegalArgumentException(at + "invalid regular expression: " + e.getDescription());
            }
        }
    }

    // ── tree walk (port of matchGroup / matchCondition / isComplete) ────────────────

    @SuppressWarnings("unchecked")
    private static boolean matchGroup(Object node, Map<String, Object> row, Map<String, ColType> types) {
        if (!(node instanceof Map<?, ?> g)) return true; // absent/garbage tree ⇒ no constraint
        Object rawItems = g.get("items");
        if (rawItems == null) rawItems = g.get("conditions");
        List<?> items = rawItems instanceof List<?> l ? l : List.of();
        Object op = g.get("op");
        boolean or = "OR".equalsIgnoreCase(op == null ? "AND" : String.valueOf(op));
        boolean any = false;
        boolean all = true;
        int evaluated = 0;
        for (Object it : items) {
            if (!(it instanceof Map<?, ?> m)) continue;
            Map<String, Object> item = (Map<String, Object>) m;
            boolean res;
            if (isGroup(item)) {
                res = matchGroup(item, row, types);
            } else if (isComplete(item)) {
                res = matchCondition(item, row, types);
            } else {
                continue; // still-being-built leaf contributes nothing (parity with the UI preview)
            }
            evaluated++;
            any |= res;
            all &= res;
        }
        if (evaluated == 0) return true; // empty / incomplete group ⇒ no constraint (also when negated)
        boolean res = or ? any : all;
        return flag(g, "negate") ? !res : res;
    }

    /** A node is a group when it declares {@code kind:'group'}, or (kind absent) it carries a nested
     *  item list rather than a leaf's {@code field}/{@code operator}. */
    private static boolean isGroup(Map<?, ?> m) {
        Object kind = m.get("kind");
        if ("group".equals(kind)) return true;
        if ("condition".equals(kind)) return false;
        return m.containsKey("items") || m.containsKey("conditions");
    }

    /** A leaf contributes to the predicate only once it has enough input to evaluate (port of
     *  {@code isComplete}). */
    static boolean isComplete(Map<?, ?> c) {
        String field = strOrEmpty(c.get("field"));
        String operator = strOrEmpty(c.get("operator"));
        if (field.isEmpty() || operator.isEmpty()) return false;
        if (operator.equals("isNull") || operator.equals("isNotNull")) return true;
        if (operator.equals("between")) return !strOrEmpty(c.get("value")).isEmpty() && !strOrEmpty(c.get("value2")).isEmpty();
        if (!strOrEmpty(c.get("valueField")).isEmpty()) return true;
        Object v = c.get("value");
        return v != null && !String.valueOf(v).isEmpty();
    }

    private static boolean matchCondition(Map<String, Object> c, Map<String, Object> row, Map<String, ColType> types) {
        String field = strOrEmpty(c.get("field"));
        String operator = strOrEmpty(c.get("operator"));
        Object raw = row.get(field);
        ColType t = types.getOrDefault(field, ColType.STRING);

        if (operator.equals("isNull")) return raw == null || "".equals(raw);
        if (operator.equals("isNotNull")) return raw != null && !"".equals(raw);
        if (raw == null) return false;

        boolean ic = flag(c, "ignoreCase");
        String valueField = strOrEmpty(c.get("valueField"));
        if (!valueField.isEmpty()) return matchFields(operator, raw, row.get(valueField), ic);

        String s = String.valueOf(raw).toLowerCase(Locale.ROOT);
        String value = strOrEmpty(c.get("value"));
        String value2 = strOrEmpty(c.get("value2"));
        return switch (operator) {
            case "contains" -> s.contains(value.toLowerCase(Locale.ROOT));
            case "startsWith" -> s.startsWith(value.toLowerCase(Locale.ROOT));
            case "endsWith" -> s.endsWith(value.toLowerCase(Locale.ROOT));
            case "matches" -> Pattern.compile(value, ic ? Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE : 0)
                    .matcher(String.valueOf(raw)).find(); // partial match, like regexp_matches
            case "in" -> {
                for (String x : value.split(",")) {
                    String xt = x.trim();
                    if (!xt.isEmpty() && cmp(raw, xt, t, ic) == 0) yield true;
                }
                yield false;
            }
            case "between" -> cmp(raw, value, t, false) >= 0 && cmp(raw, value2, t, false) <= 0;
            case "=" -> cmp(raw, value, t, ic) == 0;
            case "!=" -> cmp(raw, value, t, ic) != 0;
            case "<" -> cmp(raw, value, t, false) < 0;
            case "<=" -> cmp(raw, value, t, false) <= 0;
            case ">" -> cmp(raw, value, t, false) > 0;
            case ">=" -> cmp(raw, value, t, false) >= 0;
            default -> false;
        };
    }

    /**
     * Field-to-field comparison. A literal operand takes its type from the left column; a right-hand cell
     * has no such anchor, so the rule is per row and symmetric (exactly what {@link ConditionSql} emits):
     * both cells numeric &rArr; compare as numbers; else both date-like &rArr; compare as instants; else
     * compare as strings (case-folded when {@code ignoreCase}). A null right-hand cell never matches.
     */
    private static boolean matchFields(String operator, Object a, Object b, boolean ic) {
        if (b == null) return false;
        String sa = String.valueOf(a).toLowerCase(Locale.ROOT), sb = String.valueOf(b).toLowerCase(Locale.ROOT);
        return switch (operator) {
            case "contains" -> sa.contains(sb);
            case "startsWith" -> sa.startsWith(sb);
            case "endsWith" -> sa.endsWith(sb);
            default -> {
                int d = cmpCells(a, b, ic);
                yield switch (operator) {
                    case "=" -> d == 0;
                    case "!=" -> d != 0;
                    case "<" -> d < 0;
                    case "<=" -> d <= 0;
                    case ">" -> d > 0;
                    case ">=" -> d >= 0;
                    default -> false;
                };
            }
        };
    }

    private static int cmpCells(Object a, Object b, boolean ic) {
        Double na = numericCell(a), nb = numericCell(b);
        if (na != null && nb != null) return na.doubleValue() == nb.doubleValue() ? 0 : na < nb ? -1 : 1;
        String sa = String.valueOf(a), sb = String.valueOf(b);
        if (dateShape(sa) && dateShape(sb)) {
            Long ea = toEpoch(sa), eb = toEpoch(sb);
            if (ea != null && eb != null) return ea.equals(eb) ? 0 : ea < eb ? -1 : 1;
        }
        if (ic) {
            sa = sa.toLowerCase(Locale.ROOT);
            sb = sb.toLowerCase(Locale.ROOT);
        }
        int d = sa.compareTo(sb);
        return d == 0 ? 0 : d < 0 ? -1 : 1;
    }

    private static Double numericCell(Object v) {
        if (v instanceof Number n) return n.doubleValue();
        String s = String.valueOf(v).trim();
        return s.matches("-?\\d+(\\.\\d+)?") ? Double.parseDouble(s) : null;
    }

    /** The shape guard {@code inferType} uses: a 4-digit year and a date/time separator. */
    private static boolean dateShape(String s) {
        return s.matches(".*\\d{4}.*") && s.matches(".*[-/:T].*");
    }

    // ── typed comparison (port of cmp) ──────────────────────────────────────────────

    /** Compare a row value with a typed string operand; returns &lt;0, 0 or &gt;0 (port of {@code cmp}). */
    private static int cmp(Object raw, String operand, ColType type, boolean ignoreCase) {
        if (type == ColType.NUMBER) {
            double a = toNumber(raw), b = toNumber(operand);
            return a == b ? 0 : a < b ? -1 : 1; // NaN falls through to 1, matching JS
        }
        if (type == ColType.DATE) {
            Long a = toEpoch(String.valueOf(raw)), b = toEpoch(operand);
            if (a != null && b != null) return a.equals(b) ? 0 : a < b ? -1 : 1;
            // both-not-parseable ⇒ fall through to string compare (matches the TS guard)
        }
        if (type == ColType.BOOLEAN) {
            boolean a = raw instanceof Boolean bo ? bo : "true".equalsIgnoreCase(String.valueOf(raw));
            boolean b = "true".equalsIgnoreCase(operand);
            return a == b ? 0 : a ? 1 : -1;
        }
        String a = String.valueOf(raw);
        if (ignoreCase) {
            a = a.toLowerCase(Locale.ROOT);
            operand = operand.toLowerCase(Locale.ROOT);
        }
        return a.equals(operand) ? 0 : a.compareTo(operand) < 0 ? -1 : 1;
    }

    /** Mimic JS {@code Number()}: numeric stays numeric, blank ⇒ 0, otherwise parse or {@code NaN}. */
    private static double toNumber(Object v) {
        if (v instanceof Number n) return n.doubleValue();
        String s = String.valueOf(v).trim();
        if (s.isEmpty()) return 0d;
        try {
            return Double.parseDouble(s);
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
    }

    /** Best-effort {@code Date.parse} equivalent: ISO instant/date-time/date; {@code null} if unparseable. */
    private static Long toEpoch(String s) {
        if (s == null || s.isBlank()) return null;
        String v = s.trim();
        try {
            return Instant.parse(v).toEpochMilli();
        } catch (Exception ignored) {
            // try the next shape
        }
        try {
            return LocalDateTime.parse(v).toInstant(ZoneOffset.UTC).toEpochMilli();
        } catch (Exception ignored) {
            // try the next shape
        }
        try {
            return LocalDate.parse(v).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
        } catch (Exception ignored) {
            return null;
        }
    }

    // ── type inference (port of inferColumns / inferType) ───────────────────────────

    private static Map<String, ColType> inferColumns(List<Map<String, Object>> rows) {
        Map<String, ColType> out = new LinkedHashMap<>();
        if (rows.isEmpty()) return out;
        for (String name : rows.get(0).keySet()) out.put(name, inferType(name, rows));
        return out;
    }

    private static ColType inferType(String name, List<Map<String, Object>> rows) {
        for (Map<String, Object> r : rows) {
            Object v = r.get(name);
            if (v == null || "".equals(v)) continue;
            if (v instanceof Number) return ColType.NUMBER;
            if (v instanceof Boolean) return ColType.BOOLEAN;
            String s = String.valueOf(v);
            if (s.matches("-?\\d+(\\.\\d+)?")) return ColType.NUMBER;
            if (s.matches("(?i)(true|false)")) return ColType.BOOLEAN;
            // date: needs a 4-digit year + a date/time separator, so plain ids don't read as dates
            if (s.matches(".*\\d{4}.*") && s.matches(".*[-/:T].*") && toEpoch(s) != null) return ColType.DATE;
            return ColType.STRING;
        }
        return ColType.STRING;
    }
}
