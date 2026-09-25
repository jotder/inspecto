package com.gamma.control;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A shared Dashboard's STORED {@code filter} (a ConditionGroup) as {@code /bi/query} filter terms, so the public
 * share surface applies the same base filter the in-app viewer does (SHARE-SAVED-FILTER-1).
 *
 * <p>This mirrors the SPA's {@code flattenFilters} / {@code filterTerms} in {@code inspecto/viz/dataset-result.service.ts}
 * rule for rule. A group flattens into the implicit AND; an OR with more than one branch cannot be an AND list, so it
 * is REFUSED rather than dropped (an unfiltered share would show more than the Dashboard does). A value is typed by the
 * Dataset's declared column type ({@code number} / {@code boolean}); otherwise it stays text.
 *
 * <p>An empty stored {@code filter:} key arrives as {@code {}}. Anything without an {@code items} list is no filter.
 */
final class SharedDashboardFilter {

    private SharedDashboardFilter() {}

    /** The stored filter's terms — empty when there is none; {@link IllegalArgumentException} when it cannot apply. */
    static List<Map<String, Object>> terms(Object storedFilter, Map<String, Object> dataset) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (storedFilter instanceof Map<?, ?> group) flatten(group, dataset, out);
        return out;
    }

    private static void flatten(Map<?, ?> group, Map<String, Object> dataset, List<Map<String, Object>> out) {
        if (!(group.get("items") instanceof List<?> items) || items.isEmpty()) return;
        if ("OR".equals(group.get("op")) && items.size() > 1)
            throw new IllegalArgumentException("the shared Dashboard's saved filter uses OR, which a share link cannot apply");
        for (Object item : items) {
            if (!(item instanceof Map<?, ?> m)) continue;
            if ("group".equals(m.get("kind"))) flatten(m, dataset, out);
            else out.addAll(conditionTerms(m, dataset));
        }
    }

    private static List<Map<String, Object>> conditionTerms(Map<?, ?> c, Map<String, Object> dataset) {
        String field = c.get("field") == null ? null : String.valueOf(c.get("field"));
        String op = c.get("operator") == null ? "" : String.valueOf(c.get("operator"));
        String type = columnType(dataset, field);
        Object value = c.get("value");
        return switch (op) {
            case "=", "!=", "<", "<=", ">", ">=" -> List.of(term(field, op, typed(value, type)));
            case "contains" -> List.of(term(field, "like", "%" + text(value) + "%"));
            case "startsWith" -> List.of(term(field, "like", text(value) + "%"));
            case "endsWith" -> List.of(term(field, "like", "%" + text(value)));
            case "in" -> {
                List<Object> vs = new ArrayList<>();
                for (String v : text(value).split(",")) vs.add(typed(v.trim(), type));
                yield List.of(term(field, "in", vs));
            }
            case "between" -> List.of(term(field, ">=", typed(value, type)), term(field, "<=", typed(c.get("value2"), type)));
            case "isNull" -> List.of(term(field, "isNull", null));
            case "isNotNull" -> List.of(term(field, "notNull", null));
            default -> throw new IllegalArgumentException("the shared Dashboard's saved filter uses operator '" + op
                    + "', which a share link cannot apply");
        };
    }

    private static Map<String, Object> term(String field, String op, Object value) {
        Map<String, Object> t = new LinkedHashMap<>();
        t.put("field", field);
        t.put("op", op);
        if (value != null) t.put("value", value);
        return t;
    }

    private static String columnType(Map<String, Object> dataset, String field) {
        if (dataset.get("columns") instanceof List<?> cols)
            for (Object col : cols)
                if (col instanceof Map<?, ?> m && field != null && field.equals(m.get("name")) && m.get("type") != null)
                    return String.valueOf(m.get("type"));
        return "string";
    }

    private static Object typed(Object raw, String type) {
        if (raw == null || raw instanceof Number || raw instanceof Boolean) return raw;
        String s = String.valueOf(raw);
        if ("number".equals(type)) {
            try {
                return Double.parseDouble(s);
            } catch (NumberFormatException notANumber) {
                return s;
            }
        }
        if ("boolean".equals(type)) return "true".equals(s);
        return s;
    }

    private static String text(Object v) {
        return v == null ? "" : String.valueOf(v);
    }
}
