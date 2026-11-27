package com.gamma.agent.skill;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The slot renderer of {@code insight-brief}: the model writes a line <em>skeleton</em> with placeholders
 * and the server fills them from the cited Insight's stored fields (BI-INSIGHTS-1 P4 §5.2 "slots, not
 * digits"). A figure the model could invent therefore never reaches the paragraph.
 *
 * <p>Formatting mirrors the insights module's templated sentence ({@code InsightScan.fmt} / {@code pct} /
 * {@code share}); that formatter is package-private in another module, so the three one-line formats are
 * restated here and pinned by {@code BriefSlotsTest}.
 */
final class BriefSlots {

    private BriefSlots() {}

    static final List<String> SLOTS = List.of("measure", "slice", "period", "value", "expected", "delta", "pct");
    private static final Pattern SLOT = Pattern.compile("\\{([a-z_]+)\\}");
    private static final int MAX_TEXT = 80;

    /**
     * One Insight as the capability receives it: the line {@code GET /insights/brief} returns, plus the
     * {@code measure} of its Profile. Notes and feedback are not part of it, by construction.
     */
    record Insight(int n, String id, String type, String measure, String dimension, String slice, String period,
                   double value, double expected, double delta, String band, String sentence) {

        static Insight of(int n, Map<?, ?> m) {
            Object id = m.get("insightId");
            if (id == null || String.valueOf(id).isBlank()) throw new IllegalArgumentException("an Insight needs an insightId");
            String type = text(m.get("type"));
            String measure = text(m.get("measure"));
            String sentence = text(m.get("sentence"));
            if (sentence.isBlank()) sentence = type + " in " + (measure.isBlank() ? "this measure" : measure);
            return new Insight(n, String.valueOf(id), type, measure.isBlank() ? "this measure" : measure,
                    text(m.get("dimension")), text(m.get("slice")), text(m.get("period")),
                    num(m, "value"), num(m, "expected"), num(m, "delta"), text(m.get("band")), sentence);
        }

        /** {@code up}, {@code down} or {@code flat}: the sign of the stored delta, the only direction there is. */
        String direction() { return delta > 0 ? "up" : delta < 0 ? "down" : "flat"; }

        private boolean isShare() { return "concentration".equals(type); }

        String slot(String name) {
            return switch (name) {
                case "measure" -> measure;
                case "slice" -> slice.isBlank() ? "all values" : slice;
                case "period" -> period;
                case "value" -> isShare() ? share(value) : fmt(value);
                case "expected" -> isShare() ? share(expected) : fmt(expected);
                case "delta" -> isShare() ? String.format(Locale.ROOT, "%.1f points", Math.abs(delta) * 100) : fmt(Math.abs(delta));
                case "pct" -> expected == 0 ? "n/a" : String.format(Locale.ROOT, "%.1f %%", Math.abs(delta / expected) * 100);
                default -> throw new IllegalArgumentException("unknown slot {" + name + "}");
            };
        }

        /** The numbers this Insight is allowed to put in a sentence: its stored ones and every rendered slot. */
        List<Object> groundedNumbers() {
            List<Object> out = new ArrayList<>(List.of(value, expected, delta, period, slice, measure));
            for (String s : SLOTS) out.add(slot(s));
            return out;
        }

        /** What the model may see of it: no figures, no sentence, no notes. */
        Map<String, Object> forModel() {
            Map<String, Object> o = new LinkedHashMap<>();
            o.put("n", n);
            o.put("type", cut(type));
            o.put("measure", cut(measure));
            o.put("dimension", cut(dimension));
            o.put("slice_value", cut(slice));
            o.put("period", cut(period));
            o.put("band", cut(band));
            o.put("direction", direction());
            return o;
        }
    }

    /** Fills every {@code {slot}} of {@code skeleton} from {@code insight}; an unknown slot is an error. */
    static String render(String skeleton, Insight insight) {
        Matcher m = SLOT.matcher(skeleton);
        StringBuilder sb = new StringBuilder();
        while (m.find()) m.appendReplacement(sb, Matcher.quoteReplacement(clean(insight.slot(m.group(1)))));
        m.appendTail(sb);
        return sb.toString().trim();
    }

    static List<String> slotsIn(String skeleton) {
        List<String> out = new ArrayList<>();
        Matcher m = SLOT.matcher(skeleton);
        while (m.find()) out.add(m.group(1));
        return out;
    }

    static String fmt(double v) {
        return Math.abs(v) >= 100 ? String.format(Locale.ROOT, "%,.0f", v) : String.format(Locale.ROOT, "%.2f", v);
    }

    static String share(double v) { return String.format(Locale.ROOT, "%.1f %%", v * 100); }

    /** One line, no control characters, bounded: stored values are data an upstream feed controls. */
    static String clean(String s) {
        String one = s.replaceAll("\\p{Cntrl}+", " ").replaceAll(" {2,}", " ").trim();
        return one.length() > MAX_TEXT ? one.substring(0, MAX_TEXT - 1) + "…" : one;
    }

    private static String cut(String s) { return clean(s); }

    private static String text(Object o) { return o == null ? "" : String.valueOf(o); }

    private static double num(Map<?, ?> m, String key) {
        if (m.get(key) instanceof Number n) return n.doubleValue();
        throw new IllegalArgumentException("an Insight needs a numeric '" + key + "'");
    }
}
