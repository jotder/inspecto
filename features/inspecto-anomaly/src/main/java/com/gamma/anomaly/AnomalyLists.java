package com.gamma.anomaly;

import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * An Anomaly Model's Entity List hooks (design §9, slice S4): {@code watchList: {list, ttlHours}} feeds every
 * {@code high} entity to a {@code watch} Entity List after the run (expiring 1..24 h later, D-P5), and
 * {@code exclusionList: <id>} names an {@code exclusion} Entity List whose live members are removed before the
 * scores are written — counted as {@code excluded} in the run log, never silently. Both act around the scoring, not
 * in it, so they live beside {@link AnomalyModel} rather than in it. Validated fail closed.
 */
public record AnomalyLists(WatchList watchList, String exclusionList) {

    /** The longest a fed watch entry may live: D-P5 lets only an expiring (at most 24 h) entry skip four-eyes. */
    public static final int MAX_WATCH_TTL_HOURS = 24;
    private static final Pattern LIST_ID = Pattern.compile("[a-z0-9][a-z0-9_-]{0,63}");
    private static final Set<String> WATCH_KEYS = Set.of("list", "ttlHours");

    /** Feed every {@code high} entity to the {@code watch} list {@code list}, expiring {@code ttlHours} later. */
    public record WatchList(String list, int ttlHours) {}

    /** The hooks of a stored model's content; absent keys are {@code null}. */
    public static AnomalyLists of(Map<String, Object> m) {
        return new AnomalyLists(watchList(m.get("watchList")), exclusionList(m.get("exclusionList")));
    }

    private static WatchList watchList(Object raw) {
        if (raw == null) return null;
        if (!(raw instanceof Map<?, ?> w))
            throw new IllegalArgumentException("anomaly-model.watchList must be an object {list, ttlHours}");
        for (Object k : w.keySet())
            if (!WATCH_KEYS.contains(String.valueOf(k)))
                throw new IllegalArgumentException("anomaly-model.watchList: unknown key '" + k + "' (expected [list, ttlHours])");
        String list = listId(w.get("list"), "anomaly-model.watchList.list");
        double ttl = w.get("ttlHours") == null ? MAX_WATCH_TTL_HOURS
                : AnomalyModel.number(w.get("ttlHours"), "anomaly-model.watchList.ttlHours");
        if (ttl != Math.rint(ttl) || ttl < 1 || ttl > MAX_WATCH_TTL_HOURS)
            throw new IllegalArgumentException("anomaly-model.watchList.ttlHours must be a whole number in 1.."
                    + MAX_WATCH_TTL_HOURS + " (D-P5), got " + w.get("ttlHours"));
        return new WatchList(list, (int) ttl);
    }

    private static String exclusionList(Object raw) {
        return raw == null ? null : listId(raw, "anomaly-model.exclusionList");
    }

    private static String listId(Object raw, String what) {
        if (!(raw instanceof String s) || !LIST_ID.matcher(s.trim()).matches())
            throw new IllegalArgumentException(what + " must be an Entity List id, got '" + raw + "'");
        return s.trim();
    }
}
