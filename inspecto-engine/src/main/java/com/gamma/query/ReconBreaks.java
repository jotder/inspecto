package com.gamma.query;

import java.io.IOException;
import java.math.BigDecimal;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The Reconciliation <b>Break lifecycle</b>, server-side (R2-03, operator 2026-09-26 — reversing the C9
 * decision that kept it in the browser). Pure functions over {@link Break}: {@link #fromSets} turns
 * {@link ReconService#breaks} sets into Breaks, {@link #merge} applies the locked lifecycle against the
 * previously recorded run, and {@link #identity} is the one Java spelling of a Break's identity.
 *
 * <p>🔴 <b>This is a PORT, and the SPA still computes the same values.</b> {@code breaksFromSets},
 * {@code breakKeyOf} and {@code breakId} (inspecto-ui {@code recon-board.ts} / {@code reconciliation-types.ts})
 * build the live Breaks the Breaks page shows, and that page overlays the recorded status/note/first-seen onto
 * them <em>by identity</em>. So {@link #keyText} must render a key byte-identically to the browser's
 * {@code String(value)} of the JSON the server sent — including JavaScript's number spelling ({@code 1.0}
 * is {@code "1"}, {@code 1e-7} is {@code "1e-7"}) — or the overlay silently misses and every recorded
 * resolution reads as open. {@link #jsString} is that rule, pinned by {@code ReconBreaksTest}.
 *
 * <p>Lifecycle ({@link #merge}, the TS {@code mergeBreaks} semantics):
 * <ul>
 *   <li>a Break not seen last run is {@code open} and stamped {@code firstSeenAt = runAt};</li>
 *   <li>a Break still present keeps its {@code firstSeenAt} (⛔ never re-stamped — that would reset every age
 *       to zero on every run), and a {@code resolved} one stays resolved with its note;</li>
 *   <li>an {@code open}/{@code resolved} Break no longer present becomes {@code auto_closed}; one that was
 *       already {@code auto_closed} and is still gone is dropped (bounded history).</li>
 * </ul>
 * ⚠ One deliberate widening over the TS: a still-present {@code open} Break keeps its note too (the operator's
 * R2-03 statement "still present → keep status/note/firstSeenAt"). The TS dropped it, which made a re-open
 * note vanish at the next run.
 */
public final class ReconBreaks {

    private ReconBreaks() {}

    public static final String OPEN = "open";
    public static final String RESOLVED = "resolved";
    public static final String AUTO_CLOSED = "auto_closed";

    /** The four Break types, in the order {@link #fromSets} emits them. */
    public static final List<String> TYPES = List.of("missing_right", "missing_left", "cardinality_break", "value_break");

    /** Separator {@code breakKeyOf} joins the key columns' values with — part of the displayed key. */
    private static final String KEY_SEP = " · ";

    /**
     * One recorded Break — the shape of the SPA's {@code ReconBreak}. {@code keyValues}, {@code column},
     * {@code leftValue}, {@code rightValue}, {@code diff}, {@code note} and {@code firstSeenAt} are optional
     * ({@code null} = absent, and omitted from {@link #toMap}).
     */
    public record Break(String key, Map<String, Object> keyValues, String type, String column,
                        Object leftValue, Object rightValue, Double diff, String status, String note,
                        String firstSeenAt) {

        /** A fresh, {@code open} Break carrying no note and no stamp. */
        static Break fresh(String key, Map<String, Object> keyValues, String type, String column,
                           Object leftValue, Object rightValue, Double diff) {
            return new Break(key, keyValues, type, column, leftValue, rightValue, diff, OPEN, null, null);
        }

        /** An identity-only Break — what a status change on a Break no run has recorded yet appends. */
        public static Break identityOnly(String type, String key, String column, String status, String note) {
            return new Break(key, null, type, column, null, null, null, status, note, null);
        }

        /** {@link ReconBreaks#identity} of this Break. */
        public String id() {
            return identity(type, key, column);
        }

        public Break withStatus(String newStatus, String newNote) {
            return new Break(key, keyValues, type, column, leftValue, rightValue, diff, newStatus, newNote, firstSeenAt);
        }

        Break withFirstSeenAt(String stamp) {
            return new Break(key, keyValues, type, column, leftValue, rightValue, diff, status, note, stamp);
        }

        /** The wire/persisted shape, absent fields omitted (the SPA reads a missing field as undefined). */
        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("key", key);
            if (keyValues != null) m.put("keyValues", keyValues);
            m.put("type", type);
            if (column != null) m.put("column", column);
            if (leftValue != null) m.put("leftValue", leftValue);
            if (rightValue != null) m.put("rightValue", rightValue);
            if (diff != null) m.put("diff", diff);
            m.put("status", status);
            if (note != null) m.put("note", note);
            if (firstSeenAt != null) m.put("firstSeenAt", firstSeenAt);
            return m;
        }

        /** Read a persisted Break back; throws {@link IllegalArgumentException} on a shape it cannot trust. */
        @SuppressWarnings("unchecked")
        public static Break fromMap(Map<String, Object> m) {
            String key = str(m.get("key"));
            String type = str(m.get("type"));
            String status = str(m.get("status"));
            if (key == null || type == null || status == null)
                throw new IllegalArgumentException("a recorded Break needs key, type and status: " + m);
            Object kv = m.get("keyValues");
            Object diff = m.get("diff");
            return new Break(key, kv instanceof Map<?, ?> km ? new LinkedHashMap<>((Map<String, Object>) km) : null,
                    type, str(m.get("column")), m.get("leftValue"), m.get("rightValue"),
                    diff instanceof Number n ? n.doubleValue() : null, status, str(m.get("note")),
                    str(m.get("firstSeenAt")));
        }

        private static String str(Object v) {
            return v == null ? null : v.toString();
        }
    }

    // ── identity ────────────────────────────────────────────────────────────────────

    /**
     * A Break's identity — {@code (type, key, column)} — as ONE string: the lifecycle merge key, the status
     * route's lookup key, and the Incident dedupe grain {@code POST /recon/promote} writes
     * ({@code BREAK-DEDUPE-GRAIN-1}). The SPA's {@code breakId()} renders the byte-identical value; ⛔ the two
     * are one contract and must change together.
     *
     * <p>🔴 Escaped, not merely joined: a key routinely contains the separator ({@code "EU|voice"}), so a plain
     * join would let {@code (break, "EU|voice", "amount")} and {@code (break, "EU", "voice|amount")} collide.
     * Escaping {@code \} then {@code |} makes the rendering injective.
     */
    public static String identity(String type, String key, String column) {
        return esc(type) + '|' + esc(key) + '|' + esc(column == null ? "" : column);
    }

    private static String esc(String part) {
        return (part == null ? "" : part).replace("\\", "\\\\").replace("|", "\\|");
    }

    // ── computing a run's Breaks ─────────────────────────────────────────────────────

    /**
     * Every A↔B Break of {@code spec}, unpaged — the set a recorded run merges. Bounded by {@code cap}: a set
     * at the cap is refused ({@link IllegalArgumentException}, the route's 422) rather than recorded short.
     *
     * <p>🔴 Why refuse instead of truncating: {@link #merge} auto-closes every recorded Break absent from the
     * fresh set, so a truncated set would auto-close every real Break beyond the cut — exactly the defect this
     * replaces, where the Board merged a 200-row page and closed everything outside it.
     *
     * <p>⚠ A↔B only, as the Board always recorded: on a 3-way Reconciliation the A↔C Breaks are live on the
     * Breaks page but never enter the lifecycle (their identity carries no side, so they would collide).
     */
    public static List<Break> compute(ReconService.Spec spec, int cap) throws SQLException, IOException {
        Map<String, ReconService.BreakSet> sets = ReconService.breaks(spec, null, null, 1, cap, 0);
        for (Map.Entry<String, ReconService.BreakSet> e : sets.entrySet())
            if (e.getValue().truncated())
                throw new IllegalArgumentException("reconciliation has more than " + cap + " " + e.getKey()
                        + " Breaks — too many to record; narrow it (filters, tolerances) before recording a run");
        List<Break> out = fromSets(spec, sets);
        if (out.size() > cap)
            throw new IllegalArgumentException("reconciliation has " + out.size() + " Breaks, more than the "
                    + cap + " a run may record; narrow it (filters, tolerances) before recording a run");
        return out;
    }

    /**
     * Port of the SPA's {@code breaksFromSets}: {@link ReconService#breaks} sets → Breaks, all {@code open}.
     * One per missing key, one per cardinality key (its evidence the per-side row count), and one per
     * compare column that is actually outside its tolerance on a value-break row.
     */
    public static List<Break> fromSets(ReconService.Spec spec, Map<String, ReconService.BreakSet> sets) {
        List<String> keyColumns = spec.keyColumns();
        List<Break> out = new ArrayList<>();
        for (Map<String, Object> row : rows(sets, "missing_right"))
            out.add(Break.fresh(keyText(keyOf(row), keyColumns), null, "missing_right", null, null, null, null));
        for (Map<String, Object> row : rows(sets, "missing_left"))
            out.add(Break.fresh(keyText(keyOf(row), keyColumns), null, "missing_left", null, null, null, null));
        for (Map<String, Object> row : rows(sets, "cardinality_break"))
            out.add(Break.fresh(keyText(keyOf(row), keyColumns), keyOf(row), "cardinality_break", null,
                    side(row, "a").get(ReconService.RECORDS), side(row, "b").get(ReconService.RECORDS), null));
        for (Map<String, Object> row : rows(sets, "value_break")) {
            for (ReconService.Measure m : spec.measures()) {
                Object a = side(row, "a").get(m.name());
                Object b = side(row, "b").get(m.name());
                if (aggWithin(a, b, m)) continue;
                Double diff = a != null && b != null ? num(b) - num(a) : null;
                out.add(Break.fresh(keyText(keyOf(row), keyColumns), null, "value_break", m.name(), a, b, diff));
            }
        }
        return out;
    }

    /**
     * Port of the SPA's {@code breakKeyOf}: the key columns' values joined with {@code " · "}, each rendered as
     * the browser's {@code String(value ?? '')} would render the JSON the server sent.
     */
    public static String keyText(Map<String, Object> key, List<String> keyColumns) {
        List<String> parts = new ArrayList<>(keyColumns.size());
        for (String k : keyColumns) parts.add(jsString(key.get(k)));
        return String.join(KEY_SEP, parts);
    }

    /**
     * JavaScript's {@code String(v ?? '')} of the value the control plane's mapper serialises {@code v} to.
     * Numbers go through the JSON double exactly as the browser parses them; {@code java.util.Date} is epoch
     * milliseconds (Jackson's default); temporals are already ISO strings ({@code ReconService.wire}).
     */
    static String jsString(Object v) {
        if (v == null) return "";
        if (v instanceof Number n) return jsNumber(num(n));
        if (v instanceof java.util.Date d) return jsNumber(d.getTime());
        return v.toString();
    }

    /**
     * ECMAScript {@code Number::toString(10)}: the shortest round-trip digits (Java's {@code Double.toString}
     * yields the same digit string since JDK 19), laid out by JavaScript's rules — plain notation for
     * {@code 1e-6 <= |d| < 1e21}, exponent form ({@code 1.5e-7}, {@code 1e+21}) outside it, no {@code .0}.
     */
    static String jsNumber(double d) {
        if (Double.isNaN(d)) return "NaN";
        if (Double.isInfinite(d)) return d > 0 ? "Infinity" : "-Infinity";
        if (d == 0) return "0";   // -0 too: String(-0) === "0"
        BigDecimal bd = new BigDecimal(Double.toString(Math.abs(d))).stripTrailingZeros();
        String digits = bd.unscaledValue().toString();
        int k = digits.length();
        int n = k - bd.scale();   // value = 0.digits × 10^n
        String sign = d < 0 ? "-" : "";
        if (k <= n && n <= 21) return sign + digits + "0".repeat(n - k);
        if (0 < n && n <= 21) return sign + digits.substring(0, n) + "." + digits.substring(n);
        if (-6 < n && n <= 0) return sign + "0." + "0".repeat(-n) + digits;
        int e = n - 1;
        String mantissa = k == 1 ? digits : digits.charAt(0) + "." + digits.substring(1);
        return sign + mantissa + "e" + (e >= 0 ? "+" : "-") + Math.abs(e);
    }

    /**
     * Port of the SPA's {@code aggWithin} / {@code withinTolerance} over the rolled-up values: NULL on one side
     * only is a mismatch, NULL on both matches; exact compares the rendered values; percent is left-relative
     * and a zero left requires equality.
     */
    static boolean aggWithin(Object a, Object b, ReconService.Measure m) {
        if (a == null || b == null) return a == null && b == null;
        if ("exact".equals(m.toleranceType())) return jsString(a).equals(jsString(b));
        double x = num(a);
        double y = num(b);
        if (Double.isNaN(x) || Double.isNaN(y)) return jsString(a).equals(jsString(b));
        double delta = Math.abs(x - y);
        if ("absolute".equals(m.toleranceType())) return delta <= m.tolerance();
        if (x == 0) return delta == 0;
        return (delta / Math.abs(x)) * 100 <= m.tolerance();
    }

    /** The JSON double a value becomes in the browser; NaN for a non-number. */
    private static double num(Object v) {
        if (!(v instanceof Number)) return Double.NaN;
        try {
            return Double.parseDouble(v.toString());
        } catch (NumberFormatException e) {
            return ((Number) v).doubleValue();
        }
    }

    // ── the lifecycle merge ──────────────────────────────────────────────────────────

    /**
     * Merge a run's {@code fresh} Breaks with the {@code previous} recorded ones — see the class note for the
     * rules. {@code runAt} is the ONE instant the run records as {@code lastRunAt} too, so a Break first seen on
     * this run carries exactly the run's own timestamp.
     */
    public static List<Break> merge(List<Break> previous, List<Break> fresh, String runAt) {
        Map<String, Break> prevById = new LinkedHashMap<>();
        for (Break p : previous) prevById.put(p.id(), p);
        Set<String> freshIds = new HashSet<>();
        for (Break b : fresh) freshIds.add(b.id());

        List<Break> out = new ArrayList<>(fresh.size());
        for (Break b : fresh) {
            Break p = prevById.get(b.id());
            // A previously-recorded Break keeps its original sighting; one recorded without a stamp (appended by
            // a status change before any run saw it) is stamped now — the best honest answer.
            Break carried = b.withFirstSeenAt(p == null || p.firstSeenAt() == null ? runAt : p.firstSeenAt());
            if (p != null && RESOLVED.equals(p.status())) carried = carried.withStatus(RESOLVED, p.note());
            else if (p != null && OPEN.equals(p.status()) && p.note() != null) carried = carried.withStatus(OPEN, p.note());
            out.add(carried);
        }
        for (Break p : previous)
            if ((OPEN.equals(p.status()) || RESOLVED.equals(p.status())) && !freshIds.contains(p.id()))
                out.add(p.withStatus(AUTO_CLOSED, p.note()));
        return out;
    }

    // ── helpers ─────────────────────────────────────────────────────────────────────

    private static List<Map<String, Object>> rows(Map<String, ReconService.BreakSet> sets, String type) {
        ReconService.BreakSet set = sets.get(type);
        return set == null ? List.of() : set.rows();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> keyOf(Map<String, Object> row) {
        return row.get("key") instanceof Map<?, ?> k ? (Map<String, Object>) k : Map.of();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> side(Map<String, Object> row, String role) {
        return row.get(role) instanceof Map<?, ?> s ? (Map<String, Object>) s : Map.of();
    }
}
