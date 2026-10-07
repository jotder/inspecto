package com.gamma.notify;

import com.gamma.audit.AuditAttrs;
import com.gamma.audit.Event;
import com.gamma.audit.EventLevel;
import com.gamma.audit.EventType;

import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The built-in security trigger evaluator (ses-sns-adapter-design §8, T1–T4). An {@link com.gamma.event.EventLog}
 * subscriber that turns the audit rows the control plane already writes into {@link EventType#SECURITY_TRIGGERED}
 * events, which the built-in {@code builtin-security-triggered} Notification Rule maps to category {@code security}.
 *
 * <p><b>Why windowed.</b> {@code security} is critical — nobody can opt out of it — so a trigger that fired once
 * per 401 would let an anonymous caller send an email to every administrator with one request each (§3.6). Every
 * counting trigger therefore fires <b>once per key per window</b> once {@code threshold} matching rows fall inside
 * a sliding window, and stays quiet for the rest of that window.
 *
 * <table>
 *   <tr><th>id</th><th>input</th><th>key</th><th>default</th></tr>
 *   <tr><td>{@code t1}</td><td>{@code ACCESS_DENIED} 403 (not a delivery-status callback)</td><td>actor</td><td>20 in 10 min</td></tr>
 *   <tr><td>{@code t2}</td><td>{@code ACCESS_DENIED} 401</td><td>IP</td><td>50 in 10 min</td></tr>
 *   <tr><td>{@code t3}</td><td>{@code ACCESS_DENIED} 403 on {@code /public/delivery-status/{adapter}}</td><td>adapter</td><td>10 in 60 min</td></tr>
 *   <tr><td>{@code t4}</td><td>{@code AUDIT} of a successful {@code PUT /access/roles} (the {@code roles.toon} write)</td><td>actor</td><td>every change</td></tr>
 *   <tr><td>{@code t5}</td><td>{@code AUDIT} {@code auth.exchange} 200 carrying {@code geo_country}</td><td>subject</td><td>a country not seen before for a subject already seen</td></tr>
 * </table>
 *
 * <p><b>T5 (sign-in from a new country).</b> Needs an operator-supplied GeoIP database ({@code -Dgeoip.db}, D12);
 * without one no row carries {@code geo_country} and T5 is silent. A subject's FIRST observed sign-in sets its
 * baseline and does not fire — otherwise every user's first sign-in after a restart would page every
 * administrator. Countries seen are in-memory (at most {@link #MAX_COUNTRIES_PER_SUBJECT} per subject, subjects
 * in the same LRU bound), so a restart re-baselines. The row's actor is the Subject the minted token verified to,
 * or {@code unknown}, which T5 skips.
 *
 * <p>Thresholds are system properties, the idiom the delivery-status adapters use:
 * {@code notify.security.<id>.threshold} and {@code notify.security.<id>.windowMinutes} for {@code t1}–{@code t3}
 * (a non-positive or unparsable value keeps the default).
 *
 * <p>⚠ <b>T2's key is only as good as the audit row's IP.</b> It is {@code ApiContext.ip}: the socket peer, or the
 * right-most untrusted {@code X-Forwarded-For} hop when — and only when — the direct peer is listed in
 * {@code -Dcontrol.trustedProxies} (SEC review F3). A spoofed header from an unlisted peer therefore counts against
 * that peer, and cannot mint a fresh key per request.
 *
 * <p><b>Memory is bounded.</b> Counts live in one access-ordered map of at most {@link #MAX_KEYS} (trigger, key)
 * entries, least-recently-touched evicted first, and each entry keeps at most {@code threshold} timestamps. An
 * attacker who rotates keys can at worst evict an in-progress count, never grow the heap.
 *
 * @since 4.0.0
 */
public final class SecurityTriggers implements Consumer<Event> {

    /** Most (trigger, key) counts held at once — the LRU bound. */
    public static final int MAX_KEYS = 10_000;

    public static final String T1 = "t1";
    public static final String T2 = "t2";
    public static final String T3 = "t3";
    public static final String T4 = "t4";
    public static final String T5 = "t5";

    /** Most countries remembered per subject for T5 — beyond it the oldest is forgotten. */
    public static final int MAX_COUNTRIES_PER_SUBJECT = 16;

    private static final Pattern DELIVERY_STATUS = Pattern.compile("/public/delivery-status/([^/?]+)");
    private static final String ROLES_ROUTE = "/access/roles";

    /** One counting trigger's settings. */
    record Rule(String id, String title, int threshold, long windowMs) {}

    private final Rule t1, t2, t3;
    private final Consumer<Event> emit;
    private final LongSupplier clock;
    /** The event log this evaluator watches, or null (unit tests): at most ONE evaluator is active per log. */
    private final Object log;

    /**
     * The evaluator currently evaluating each log. Every CollectorService subscribes one, and the default Space's
     * services all share {@code EventLog.global()} — two live (or one leaked, never closed) services on that log
     * would each count the same audit row and fire twice. The NEWEST evaluator on a log owns it — a leaked, never-
     * closed older one must not keep the log and silence the live service's triggers; when the owner {@link #detach()}es,
     * the next evaluator to see an event takes over.
     */
    private static final Map<Object, SecurityTriggers> ACTIVE =
            java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());

    /** Per (trigger, key): the recent matching timestamps (≤ threshold), and when it last fired; for T5, the
     *  countries the subject has signed in from (insertion order, ≤ {@link #MAX_COUNTRIES_PER_SUBJECT}). */
    private static final class Window {
        final ArrayDeque<Long> hits = new ArrayDeque<>();
        long firedAt = Long.MIN_VALUE;
        final java.util.LinkedHashSet<String> countries = new java.util.LinkedHashSet<>();
    }

    private final LinkedHashMap<String, Window> windows = new LinkedHashMap<>(256, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Window> eldest) {
            return size() > MAX_KEYS;
        }
    };

    /** Production: thresholds from system properties, the wall clock, emitting into {@code emit}. */
    public SecurityTriggers(Consumer<Event> emit) {
        this(null, emit);
    }

    /** Production: as above, active only while no other evaluator owns {@code log}. */
    public SecurityTriggers(Object log, Consumer<Event> emit) {
        this(log, emit, System::currentTimeMillis,
                rule(T1, "Repeated authorization refusals", 20, 10),
                rule(T2, "Burst of authentication failures", 50, 10),
                rule(T3, "Rejected delivery-status signatures", 10, 60));
    }

    SecurityTriggers(Consumer<Event> emit, LongSupplier clock, Rule t1, Rule t2, Rule t3) {
        this(null, emit, clock, t1, t2, t3);
    }

    SecurityTriggers(Object log, Consumer<Event> emit, LongSupplier clock, Rule t1, Rule t2, Rule t3) {
        this.log = log;
        if (log != null) ACTIVE.put(log, this);   // the newest service's evaluator owns the log
        this.emit = emit;
        this.clock = clock;
        this.t1 = t1;
        this.t2 = t2;
        this.t3 = t3;
    }

    /** A rule from {@code notify.security.<id>.threshold} / {@code .windowMinutes}, else the defaults. */
    static Rule rule(String id, String title, int defaultThreshold, int defaultWindowMinutes) {
        int threshold = positive(Integer.getInteger("notify.security." + id + ".threshold", defaultThreshold),
                defaultThreshold);
        int minutes = positive(Integer.getInteger("notify.security." + id + ".windowMinutes", defaultWindowMinutes),
                defaultWindowMinutes);
        return new Rule(id, title, threshold, minutes * 60_000L);
    }

    private static int positive(Integer v, int dflt) {
        return v == null || v <= 0 ? dflt : v;
    }

    /** The subscriber: cheap, never throws, never blocks the emitter beyond one synchronized map touch. */
    @Override
    public void accept(Event e) {
        if (e == null) return;
        if (log != null && ACTIVE.computeIfAbsent(log, k -> this) != this) return;   // another evaluator owns it
        try {
            if (EventType.ACCESS_DENIED.equals(e.type())) onDenied(e);
            else if (EventType.AUDIT.equals(e.type())) onAudit(e);
        } catch (RuntimeException ignore) {
            // best effort — a trigger must never break the log it observes
        }
    }

    /** Stop owning the log (service close), so the next evaluator on it takes over. */
    public void detach() {
        if (log != null) ACTIVE.remove(log, this);
    }

    private void onDenied(Event e) {
        int status = status(e);
        String path = attr(e, AuditAttrs.HTTP_PATH);
        if (status == 401) {
            count(t2, attr(e, AuditAttrs.IP), e);
        } else if (status == 403) {
            Matcher m = path == null ? null : DELIVERY_STATUS.matcher(path);
            if (m != null && m.find()) count(t3, m.group(1), e);
            else count(t1, attr(e, AuditAttrs.ACTOR), e);
        }
    }

    private void onAudit(Event e) {
        String path = attr(e, AuditAttrs.HTTP_PATH);
        int status = status(e);
        if ("auth.exchange".equals(attr(e, AuditAttrs.ACTION))) {
            if (status == 200) onSignIn(e);
            return;
        }
        // 202 is a write HELD as a Pending Change — nothing was written yet; its approval replays the PUT and
        // audits a 200 then, which is the change.
        if (path == null || !path.endsWith(ROLES_ROUTE) || status < 200 || status >= 300 || status == 202) return;
        if (!"PUT".equals(attr(e, AuditAttrs.HTTP_METHOD))) return;
        String actor = attr(e, AuditAttrs.ACTOR);
        fire(T4, "Role or capability configuration changed", actor == null ? "unknown" : actor, 1, 0, e);
    }

    private void onSignIn(Event e) {
        String subject = attr(e, AuditAttrs.ACTOR);
        String country = attr(e, AuditAttrs.GEO_COUNTRY);
        if (subject == null || subject.isBlank() || "unknown".equals(subject) || country == null || country.isBlank())
            return;
        boolean fire;
        String previous;
        synchronized (windows) {
            Window w = windows.computeIfAbsent(T5 + "|" + subject, k -> new Window());
            previous = w.countries.isEmpty() ? null : String.join(",", w.countries);
            fire = previous != null && !w.countries.contains(country);
            w.countries.remove(country);   // re-insert: most recent last, so the cap forgets the oldest
            w.countries.add(country);
            if (w.countries.size() > MAX_COUNTRIES_PER_SUBJECT) w.countries.remove(w.countries.iterator().next());
        }
        if (fire) emit.accept(Event.builder(EventType.SECURITY_TRIGGERED)
                .level(EventLevel.WARN)
                .source("security")
                .correlationId(e.correlationId())
                .message(subject + " signed in from " + country + " (previously " + previous + ")")
                .attr("trigger", T5)
                .attr("title", "Sign-in from a new country")
                .attr("key", subject)
                .attr("count", 1)
                .attr("windowMinutes", 0L)
                .attr("cause", e.eventId())
                .build());
    }

    private void count(Rule rule, String key, Event e) {
        if (key == null || key.isBlank()) return;
        long now = clock.getAsLong();
        boolean fire;
        int hits;
        synchronized (windows) {
            Window w = windows.computeIfAbsent(rule.id() + "|" + key, k -> new Window());
            w.hits.addLast(now);
            while (!w.hits.isEmpty() && now - w.hits.peekFirst() >= rule.windowMs()) w.hits.pollFirst();
            while (w.hits.size() > rule.threshold()) w.hits.pollFirst();
            hits = w.hits.size();
            fire = hits >= rule.threshold()
                    && (w.firedAt == Long.MIN_VALUE || now - w.firedAt >= rule.windowMs());
            if (fire) {
                w.firedAt = now;
                w.hits.clear();
            }
        }
        if (fire) fire(rule.id(), rule.title(), key, hits, rule.windowMs() / 60_000L, e);
    }

    private void fire(String trigger, String title, String key, int count, long windowMinutes, Event cause) {
        String message = T4.equals(trigger)
                ? key + " changed the role table (roles.toon)"
                : count + " matching refusals for " + key + " within " + windowMinutes + " min";
        emit.accept(Event.builder(EventType.SECURITY_TRIGGERED)
                .level(EventLevel.WARN)
                .source("security")
                .correlationId(cause.correlationId())
                .message(message)
                .attr("trigger", trigger)
                .attr("title", title)
                .attr("key", key)
                .attr("count", count)
                .attr("windowMinutes", windowMinutes)
                .attr("cause", cause.eventId())
                .build());
    }

    /** How many (trigger, key) counts are held — the LRU bound's test seam. */
    int trackedKeys() {
        synchronized (windows) {
            return windows.size();
        }
    }

    private static String attr(Event e, String k) {
        Object v = e.attributes() == null ? null : e.attributes().get(k);
        return v == null ? null : String.valueOf(v);
    }

    private static int status(Event e) {
        String s = attr(e, AuditAttrs.HTTP_STATUS);
        if (s == null) return -1;
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException bad) {
            return -1;
        }
    }
}
