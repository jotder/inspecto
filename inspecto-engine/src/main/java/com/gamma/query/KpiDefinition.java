package com.gamma.query;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static com.gamma.util.Values.trimToNull;

/**
 * A <b>KPI definition</b> (`ASSURE-KPI-DEFINITIONS-1`, WS-20): a Measure over a Dataset with a target, a good
 * direction, RAG bands, a period grain and a comparison period — the {@code kpi} component kind, which the KPI tile
 * reads instead of hand-set Widget inputs. {@link #fromMap} is the one structural validator (fail closed — every
 * write door calls it); whether the Dataset and its columns EXIST is the control plane's check, since it needs the
 * registry and the caller.
 *
 * <p>Content shape: {@code dataset}, {@code measure} ({@code count} | {@code agg(field)}, the shorthand
 * {@link MeasureCompiler#splitShorthand} reads), {@code timeField} (the column a period is cut on), {@code grain},
 * {@code comparison}, {@code direction}, optional {@code target}, optional {@code bands}, optional {@code unit} /
 * {@code format} / {@code title} / {@code description}, the R3 envelope ({@code owner}, {@code shares}) and the
 * provenance {@code requirement} a delivered Requirement stamps.
 *
 * <p><b>Bands.</b> For {@code up} / {@code down}, {@code bands: {green: G, amber: A}} are thresholds: {@code up} is
 * GREEN at {@code >= G}, AMBER at {@code >= A}, else RED, so {@code A <= G}; {@code down} mirrors it
 * ({@code <= G}, {@code <= A}), so {@code G <= A}. For {@code band}, {@code bands: {green: [lo, hi], amber: [lo, hi]}}
 * and green must sit inside amber. Each case is one rule: GREEN inside the green interval, else AMBER inside the
 * amber one, else RED — and "ordered" means the green interval lies inside the amber one.
 */
public record KpiDefinition(String name, String dataset, MeasureCompiler.Measure measure, String measureText,
                            String timeField, Grain grain, Comparison comparison, Direction direction,
                            Double target, Bands bands, java.time.ZoneId zone) {

    /** The period a KPI is cut on. Wider than {@link MeasureCompiler#GRAINS} (a grouping grain) on purpose. */
    public enum Grain {
        DAY, WEEK, MONTH, QUARTER, YEAR;

        /** The first day of the period that holds {@code d}. Weeks start on Monday, as DuckDB's DATE_TRUNC does. */
        public LocalDate start(LocalDate d) {
            return switch (this) {
                case DAY -> d;
                case WEEK -> d.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
                case MONTH -> d.withDayOfMonth(1);
                case QUARTER -> LocalDate.of(d.getYear(), ((d.getMonthValue() - 1) / 3) * 3 + 1, 1);
                case YEAR -> d.withDayOfYear(1);
            };
        }

        /** {@code d} moved by {@code n} periods (negative = back). */
        public LocalDate plus(LocalDate d, int n) {
            return switch (this) {
                case DAY -> d.plusDays(n);
                case WEEK -> d.plusWeeks(n);
                case MONTH -> d.plusMonths(n);
                case QUARTER -> d.plusMonths(3L * n);
                case YEAR -> d.plusYears(n);
            };
        }

        public String wire() { return name().toLowerCase(Locale.ROOT); }
    }

    /** What the current period is compared with. */
    public enum Comparison {
        PREVIOUS("previous"), LAST_YEAR("last-year"), NONE("none");

        private final String wire;

        Comparison(String wire) { this.wire = wire; }

        public String wire() { return wire; }
    }

    /** Which way is good: higher, lower, or inside a band. */
    public enum Direction {
        UP, DOWN, BAND;

        public String wire() { return name().toLowerCase(Locale.ROOT); }
    }

    /** A RAG band's value: the green and amber intervals (closed; an open side is ±infinity). */
    public record Bands(double greenLo, double greenHi, double amberLo, double amberHi) {}

    /** RAG. {@code null} from {@link #rag} means "no status" (no value, or neither target nor bands). */
    public enum Rag { GREEN, AMBER, RED }

    /** A half-open date window {@code [from, to)}, in the KPI's {@link #zone()}: it starts and ends at local midnight there. */
    public record Window(LocalDate from, LocalDate to) {}

    /** The keys a {@code kpi} component may carry; anything else is refused, never silently dropped. */
    static final Set<String> KEYS = Set.of("name", "title", "description", "dataset", "measure", "timeField",
            "grain", "comparison", "direction", "target", "bands", "unit", "format", "owner", "shares", "requirement", "timezone");

    /**
     * Validate a stored or proposed {@code kpi} content. {@code name} is the component id.
     *
     * @throws IllegalArgumentException naming the first thing wrong (→ 422 at the route)
     */
    public static KpiDefinition fromMap(String name, Map<String, Object> m) {
        return fromMap(name, m, UTC);
    }

    /**
     * {@link #fromMap(String, Map)}, with {@code defaultZone} — the Space's default timezone — used when the KPI states
     * no {@code timezone} of its own. The KPI's own zone always wins.
     */
    public static KpiDefinition fromMap(String name, Map<String, Object> m, java.time.ZoneId defaultZone) {
        if (m == null) throw new IllegalArgumentException("kpi content is required");
        List<String> unknown = m.keySet().stream().filter(k -> !KEYS.contains(k) && !k.startsWith("x-")).sorted().toList();
        if (!unknown.isEmpty())
            throw new IllegalArgumentException("kpi has unknown key(s) " + unknown + " that nothing reads");
        String dataset = trimToNull(m.get("dataset"));
        if (dataset == null) throw new IllegalArgumentException("kpi 'dataset' is required");
        String measureText = trimToNull(m.get("measure"));
        if (!DatasetMeasureProbe.validMeasure(measureText))
            throw new IllegalArgumentException("kpi 'measure' must be count or agg(field) with agg one of "
                    + MeasureCompiler.AGGS + ", got '" + measureText + "'");
        Map<String, Object> split = MeasureCompiler.splitShorthand(List.of(measureText), "kpi").get(0);
        MeasureCompiler.Measure measure = new MeasureCompiler.Measure(
                String.valueOf(split.get("agg")), split.get("field") == null ? null : String.valueOf(split.get("field")));
        String timeField = trimToNull(m.get("timeField"));
        if (timeField == null || !MeasureCompiler.SAFE_IDENT.matcher(timeField).matches())
            throw new IllegalArgumentException("kpi 'timeField' must be a column name, got '" + timeField + "'");
        Grain grain = grain(trimToNull(m.get("grain")));
        Comparison comparison = comparison(trimToNull(m.get("comparison")));
        Direction direction = direction(trimToNull(m.get("direction")));
        Double target = number(m.get("target"), "target");
        Bands bands = bands(direction, m.get("bands"));
        if (direction == Direction.BAND && bands == null)
            throw new IllegalArgumentException("kpi direction 'band' needs 'bands' ({green: [lo, hi], amber: [lo, hi]})");
        if (m.get("format") != null && !(m.get("format") instanceof Map<?, ?>))
            throw new IllegalArgumentException("kpi 'format' must be an object");
        if (m.get("unit") != null && !(m.get("unit") instanceof String))
            throw new IllegalArgumentException("kpi 'unit' must be text");
        return new KpiDefinition(name, dataset, measure, measureText, timeField, grain, comparison, direction, target, bands,
                zone(trimToNull(m.get("timezone")), defaultZone == null ? UTC : defaultZone));
    }

    /** A region zone name (never an offset form), default UTC — the zone a KPI's periods are cut in. */
    private static final java.util.regex.Pattern ZONE_NAME = java.util.regex.Pattern.compile("[A-Za-z][A-Za-z0-9]*(/[A-Za-z0-9_+-]+)*");

    /** The default zone. A region id ("UTC"), not ZoneOffset.UTC, whose id "Z" DuckDB does not know. */
    public static final java.time.ZoneId UTC = java.time.ZoneId.of("UTC");

    private static java.time.ZoneId zone(String s, java.time.ZoneId fallback) {
        if (s == null) return fallback;
        java.time.ZoneId z = regionZone(s);
        if (z != null) return z;
        throw new IllegalArgumentException("kpi 'timezone' must be an IANA zone name such as Asia/Kolkata, got '" + s + "'");
    }

    /** {@code s} as an IANA region zone (never an offset form), or {@code null} when it is not one. Shared with the
     *  Space default-timezone setting so the two accept exactly the same names. */
    public static java.time.ZoneId regionZone(String s) {
        if (s == null) return null;
        try {
            // the first segment may carry digits after a letter (EST5EDT, GMT0); "Z" parses to a ZoneOffset — refused
            if (ZONE_NAME.matcher(s).matches() && java.time.ZoneId.of(s) instanceof java.time.ZoneId z
                    && !(z instanceof java.time.ZoneOffset)) return z;
        } catch (java.time.DateTimeException ignored) {
            // not a zone
        }
        return null;
    }

    private static Grain grain(String s) {
        if (s == null) throw new IllegalArgumentException("kpi 'grain' is required (day, week, month, quarter, year)");
        for (Grain g : Grain.values()) if (g.wire().equals(s)) return g;
        throw new IllegalArgumentException("unknown kpi grain '" + s + "' (day, week, month, quarter, year)");
    }

    private static Comparison comparison(String s) {
        if (s == null) return Comparison.PREVIOUS;
        for (Comparison c : Comparison.values()) if (c.wire().equals(s)) return c;
        throw new IllegalArgumentException("unknown kpi comparison '" + s + "' (previous, last-year, none)");
    }

    private static Direction direction(String s) {
        if (s == null) return Direction.UP;
        for (Direction d : Direction.values()) if (d.wire().equals(s)) return d;
        throw new IllegalArgumentException("unknown kpi direction '" + s + "' (up, down, band)");
    }

    private static Double number(Object v, String what) {
        if (v == null) return null;
        if (v instanceof Number n && Double.isFinite(n.doubleValue())) return n.doubleValue();
        throw new IllegalArgumentException("kpi '" + what + "' must be a finite number, got '" + v + "'");
    }

    private static Bands bands(Direction direction, Object raw) {
        if (raw == null) return null;
        if (!(raw instanceof Map<?, ?> b) || !b.containsKey("green") || !b.containsKey("amber"))
            throw new IllegalArgumentException("kpi 'bands' must be an object with 'green' and 'amber'");
        Bands out;
        if (direction == Direction.BAND) {
            double[] g = interval(b.get("green"), "green"), a = interval(b.get("amber"), "amber");
            out = new Bands(g[0], g[1], a[0], a[1]);
        } else {
            double g = required(b.get("green"), "bands.green"), a = required(b.get("amber"), "bands.amber");
            out = direction == Direction.UP
                    ? new Bands(g, Double.POSITIVE_INFINITY, a, Double.POSITIVE_INFINITY)
                    : new Bands(Double.NEGATIVE_INFINITY, g, Double.NEGATIVE_INFINITY, a);
        }
        if (!(out.amberLo() <= out.greenLo() && out.greenLo() <= out.greenHi() && out.greenHi() <= out.amberHi()))
            throw new IllegalArgumentException("kpi 'bands' are not ordered: for direction '" + direction.wire() + "' "
                    + switch (direction) {
                        case UP -> "amber must be <= green";
                        case DOWN -> "green must be <= amber";
                        case BAND -> "the green interval must lie inside the amber one, each [lo, hi] with lo <= hi";
                    });
        return out;
    }

    private static double[] interval(Object v, String what) {
        if (!(v instanceof List<?> l) || l.size() != 2)
            throw new IllegalArgumentException("kpi 'bands." + what + "' must be [lo, hi] for direction 'band'");
        return new double[] {required(l.get(0), "bands." + what), required(l.get(1), "bands." + what)};
    }

    private static double required(Object v, String what) {
        Double d = number(v, what);
        if (d == null) throw new IllegalArgumentException("kpi '" + what + "' is required");
        return d;
    }

    // ── evaluation arithmetic (pure) ─────────────────────────────────────────────

    /**
     * The current window: the period holding {@code asOf}, <b>to date</b> — {@code [start, min(end, asOf + 1 day))}
     * — so a month read on the 10th holds ten days, not a month.
     */
    public Window current(LocalDate asOf) {
        LocalDate start = grain.start(asOf);
        LocalDate end = grain.plus(start, 1);
        LocalDate cutoff = asOf.plusDays(1).isBefore(end) ? asOf.plusDays(1) : end;
        return new Window(start, cutoff);
    }

    /**
     * The comparison window, or {@code null} for {@code none}: the previous period (or the same period last year,
     * a week being 52 weeks back so it stays a Monday), cut to the SAME elapsed length as {@link #current} — like
     * for like — and never past that period's own end.
     */
    public Window comparison(LocalDate asOf) {
        if (comparison == Comparison.NONE) return null;
        Window cur = current(asOf);
        LocalDate start = switch (comparison) {
            case PREVIOUS -> grain.plus(cur.from(), -1);
            case LAST_YEAR -> grain == Grain.WEEK ? cur.from().minusWeeks(52) : cur.from().minusYears(1);
            case NONE -> throw new IllegalStateException();
        };
        LocalDate end = grain.plus(start, 1);
        LocalDate cutoff = start.plusDays(ChronoUnit.DAYS.between(cur.from(), cur.to()));
        return new Window(start, cutoff.isBefore(end) ? cutoff : end);
    }

    /**
     * The RAG status of {@code value}: from the bands when there are any, else from the target alone (met → GREEN,
     * missed → RED), else {@code null}. {@code null} value → {@code null}.
     */
    public Rag rag(Double value) {
        if (value == null || value.isNaN()) return null;
        double v = value;
        if (bands != null) {
            if (bands.greenLo() <= v && v <= bands.greenHi()) return Rag.GREEN;
            if (bands.amberLo() <= v && v <= bands.amberHi()) return Rag.AMBER;
            return Rag.RED;
        }
        if (target == null || direction == Direction.BAND) return null;
        boolean met = direction == Direction.UP ? v >= target : v <= target;
        return met ? Rag.GREEN : Rag.RED;
    }

    /** The status tone the SPA's shared badge draws for a RAG value — the same mapping as {@code statusTone}. */
    public static String tone(Rag rag) {
        if (rag == null) return "neutral";
        return switch (rag) {
            case GREEN -> "success";
            case AMBER -> "warning";
            case RED -> "error";
        };
    }

    /** Δ% from {@code base} to {@code value} — {@code recon-board.ts#deltaPct}'s rule: a zero base is 0 or undefined. */
    public static Double deltaPct(Double base, Double value) {
        if (base == null || value == null) return null;
        if (base == 0) return value == 0 ? 0.0 : null;
        return (value - base) / Math.abs(base) * 100;
    }
}
