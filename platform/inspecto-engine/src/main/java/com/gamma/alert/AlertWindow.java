package com.gamma.alert;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The {@code window:} block of a windowed Measure Alert Rule (PA-9b, D-PA40): the Measure is evaluated per
 * event-time Window of one Stream Dataset, on each commit, instead of over the whole Dataset on a sweep.
 *
 * <pre>
 *   window: {type: sliding, size: 10m, slide: 1m, time: event_ts, lateness: 2m, distinctOn: [topic, partition, offset]}
 * </pre>
 *
 * <ul>
 *   <li>{@code type} — {@code tumbling} (back-to-back windows of {@code size}) or {@code sliding} (windows of
 *       {@code size} starting every {@code slide}). Window starts are aligned to the epoch, so
 *       {@code size} must be a whole multiple of {@code slide}.</li>
 *   <li>{@code time} — the event-time column, a TIMESTAMP in the Dataset's Schema. Event time only: arrival time is
 *       ingest time, and a replayed slice would move it.</li>
 *   <li>{@code lateness} — how long after a window's end an event may still arrive and count. A window
 *       {@code [lo, hi)} is closed once the event-time Watermark reaches {@code hi + lateness}; an event that lands
 *       only in closed windows is kept in the Dataset but alerted on by nothing (a late event).</li>
 *   <li>{@code distinctOn} — optional: the columns that identify one event (a Kafka Stream's
 *       {@code topic, partition, offset}). The window buffer keeps one row per identity, so a slice the
 *       at-least-once lane re-delivers does not inflate a count (D-PA41).</li>
 * </ul>
 *
 * <p>Bounds (D-PA40): {@code size + lateness} at most {@link #MAX_SPAN} (one hour), and at most {@link #MAX_SLIDES}
 * slides per window. Anything longer is a batch Alert Rule on a schedule.
 */
public record AlertWindow(String type, Duration size, Duration slide, String time, Duration lateness,
                          List<String> distinctOn) {

    public static final String TUMBLING = "tumbling";
    public static final String SLIDING = "sliding";
    public static final Set<String> TYPES = Set.of(TUMBLING, SLIDING);
    /** The longest {@code size + lateness} a windowed rule may hold (D-PA40). */
    public static final Duration MAX_SPAN = Duration.ofHours(1);
    /** The most windows one event may fall in ({@code size / slide}). */
    public static final int MAX_SLIDES = 60;
    /** The keys a {@code window:} block may carry. */
    static final Set<String> KEYS = Set.of("type", "size", "slide", "time", "lateness", "distinctOn");

    private static final java.util.regex.Pattern COLUMN = java.util.regex.Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");
    private static final java.util.regex.Pattern DURATION = java.util.regex.Pattern.compile("(\\d{1,6})([smh])");   // 6 digits: far above the 1 h bound, never an overflow

    public AlertWindow {
        require(type != null && TYPES.contains(type), "alert.window.type must be one of " + TYPES);
        require(size != null && size.isPositive(), "alert.window.size must be a positive duration (Ns, Nm or Nh)");
        if (TUMBLING.equals(type)) {
            require(slide == null || slide.equals(size), "a tumbling alert.window takes no slide (it slides by its size)");
            slide = size;
        } else {
            require(slide != null && slide.isPositive(), "a sliding alert.window needs a positive slide (Ns, Nm or Nh)");
            require(slide.compareTo(size) <= 0, "alert.window.slide must not exceed alert.window.size");
            require(size.toMillis() % slide.toMillis() == 0, "alert.window.size must be a whole multiple of alert.window.slide");
        }
        lateness = lateness == null ? Duration.ZERO : lateness;
        require(!lateness.isNegative(), "alert.window.lateness must not be negative");
        require(size.plus(lateness).compareTo(MAX_SPAN) <= 0,
                "alert.window size + lateness must be at most 1h; a longer window is a batch Alert Rule on a schedule");
        require(size.toMillis() / slide.toMillis() <= MAX_SLIDES,
                "alert.window may slide at most " + MAX_SLIDES + " times per window (size / slide)");
        require(time != null && COLUMN.matcher(time).matches(),
                "alert.window.time must name the event-time column (a plain column name)");
        distinctOn = distinctOn == null ? List.of() : List.copyOf(distinctOn);
        for (String c : distinctOn)
            require(c != null && COLUMN.matcher(c).matches(), "alert.window.distinctOn column '" + c + "' must be a plain column name");
        require(distinctOn.stream().distinct().count() == distinctOn.size(), "alert.window.distinctOn names a column twice");
    }

    /** How many windows one event falls in: 1 for tumbling, {@code size / slide} for sliding. */
    public int slides() {
        return (int) (size.toMillis() / slide.toMillis());
    }

    /** Parse the authored block; every refusal is an {@link IllegalArgumentException} naming the key. */
    public static AlertWindow fromMap(Object raw) {
        require(raw instanceof Map<?, ?>, "alert.window on a Dataset measure rule must be a block "
                + "{type, size, slide, time, lateness}");
        Map<?, ?> m = (Map<?, ?>) raw;
        for (Object k : m.keySet())
            require(KEYS.contains(String.valueOf(k)), "alert.window." + k + " is not a window key (one of " + KEYS + ")");
        return new AlertWindow(lower(m.get("type")), duration(m.get("size"), "size"), duration(m.get("slide"), "slide"),
                str(m.get("time")), duration(m.get("lateness"), "lateness"), columns(m.get("distinctOn")));
    }

    /** The stored shape: durations in their shortest whole unit. */
    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", type);
        m.put("size", text(size));
        if (SLIDING.equals(type)) m.put("slide", text(slide));
        m.put("time", time);
        if (!lateness.isZero()) m.put("lateness", text(lateness));
        if (!distinctOn.isEmpty()) m.put("distinctOn", distinctOn);
        return m;
    }

    /** {@code 10m}, {@code 90s}, {@code 1h}: the largest unit that divides the duration. */
    static String text(Duration d) {
        long s = d.toSeconds();
        if (s % 3600 == 0) return s / 3600 + "h";
        if (s % 60 == 0) return s / 60 + "m";
        return s + "s";
    }

    private static Duration duration(Object v, String key) {
        String s = str(v);
        if (s == null) return null;
        java.util.regex.Matcher dm = DURATION.matcher(s.toLowerCase(Locale.ROOT));
        require(dm.matches(), "alert.window." + key + " must be Ns, Nm or Nh (e.g. 30s, 10m, 1h), got '" + s + "'");
        long n = Long.parseLong(dm.group(1));
        return switch (dm.group(2)) {
            case "s" -> Duration.ofSeconds(n);
            case "m" -> Duration.ofMinutes(n);
            default -> Duration.ofHours(n);
        };
    }

    private static List<String> columns(Object v) {
        if (v == null) return null;
        List<?> items = v instanceof List<?> l ? l : List.of(String.valueOf(v).split(","));
        List<String> out = new ArrayList<>();
        for (Object o : items) out.add(o == null ? null : String.valueOf(o).trim());
        return out;
    }

    private static String str(Object v) {
        if (v == null) return null;
        String s = String.valueOf(v).trim();
        return s.isEmpty() ? null : s;
    }

    private static String lower(Object v) {
        String s = str(v);
        return s == null ? null : s.toLowerCase(Locale.ROOT);
    }

    private static void require(boolean ok, String message) {
        if (!ok) throw new IllegalArgumentException(message);
    }
}
