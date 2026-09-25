package com.gamma.control;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * UIE-5 (d): a shared Dashboard's DEFAULT date range as {@code /bi/query} filter terms, so a share link is fenced to
 * the range the Dashboard opens on — the recipient can neither widen nor drop it (its sent filters are ignored).
 *
 * <p>This mirrors the SPA's {@code dashboard-date-range.ts} rule for rule: presets count back from the Dashboard's
 * {@code asOf} day (or today, in the server's calendar, when it has none); both ends are inclusive days; the terms
 * are half-open ({@code field >= from AND field < to + 1 day}) so a TIMESTAMP column keeps every instant of the last
 * day. The range applies only to a Dataset that declares the {@code dateField} column (as a column or a calculated
 * column) — the same per-tile scoping the viewer uses. A {@code defaultRange} that is set but cannot be read is
 * REFUSED, never dropped: an unranged share would show more than the Dashboard does.
 */
final class DashboardDateRange {

    private DashboardDateRange() {}

    /** The range terms for one Dataset — empty when the Dashboard has no range or the Dataset lacks the column. */
    static List<Map<String, Object>> terms(Map<String, Object> dashboard, Map<String, Object> dataset, LocalDate today) {
        String field = dashboard.get("dateField") instanceof String s ? s.trim() : "";
        Object selection = dashboard.get("defaultRange");
        if (field.isEmpty() || selection == null || !hasColumn(dataset, field)) return List.of();
        LocalDate anchor = day(dashboard.get("asOf"));
        LocalDate[] span = resolve(selection, anchor == null ? today : anchor);
        if (span == null)
            throw new IllegalArgumentException("the shared Dashboard's default range cannot be read, so a share link cannot apply it");
        return List.of(term(field, ">=", span[0].toString()), term(field, "<", span[1].plusDays(1).toString()));
    }

    /** {@code [from, to]}, both inclusive, or null for a selection that is neither a known preset nor a valid span. */
    static LocalDate[] resolve(Object selection, LocalDate anchor) {
        if (selection instanceof Map<?, ?> m) {
            LocalDate from = day(m.get("from"));
            LocalDate to = day(m.get("to"));
            return from != null && to != null && !from.isAfter(to) ? new LocalDate[] {from, to} : null;
        }
        if (!(selection instanceof String preset)) return null;
        LocalDate from = switch (preset) {
            case "last-7-days" -> anchor.minusDays(6);
            case "last-30-days" -> anchor.minusDays(29);
            case "last-90-days" -> anchor.minusDays(89);
            case "month-to-date" -> anchor.withDayOfMonth(1);
            case "quarter-to-date" -> LocalDate.of(anchor.getYear(), (anchor.getMonthValue() - 1) / 3 * 3 + 1, 1);
            case "year-to-date" -> anchor.withDayOfYear(1);
            case "last-12-months" -> anchor.minusMonths(12).plusDays(1);   // minusMonths clamps to the month's end
            default -> null;
        };
        return from == null ? null : new LocalDate[] {from, anchor};
    }

    private static boolean hasColumn(Map<String, Object> dataset, String field) {
        for (String key : List.of("columns", "calculated"))
            if (dataset.get(key) instanceof List<?> cols)
                for (Object col : cols)
                    if (col instanceof Map<?, ?> m && field.equals(m.get("name"))) return true;
        return false;
    }

    /** A real {@code YYYY-MM-DD} day, or null. */
    private static LocalDate day(Object v) {
        if (!(v instanceof String s) || !s.matches("\\d{4}-\\d{2}-\\d{2}")) return null;
        try {
            return LocalDate.parse(s);
        } catch (DateTimeParseException notADay) {
            return null;
        }
    }

    private static Map<String, Object> term(String field, String op, Object value) {
        Map<String, Object> t = new LinkedHashMap<>();
        t.put("field", field);
        t.put("op", op);
        t.put("value", value);
        return t;
    }
}
