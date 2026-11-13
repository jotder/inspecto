package com.gamma.control;

import com.gamma.pipeline.SpaceConfigRoot;
import com.gamma.service.ActivationFacts;
import com.gamma.service.SpaceLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * SE-1b, D-SE12 — the activation check ({@code spec-space-lock.md} §10a): whether a Pipeline or a Job of a Space is
 * held, and why. Installed by {@link ControlApi} into {@link com.gamma.pipeline.ActivationGate}, which both schedulers
 * consult before an automatic run. A held object acts as an inactive one; everything else runs (plan §2 rule 4).
 *
 * <ul>
 *   <li><b>Hold 1, broken links</b> — the Space's {@code links:} mode is {@code refuse-activation}
 *       ({@link SpaceLinksMode}) and the object's own file holds a DANGLING link.</li>
 *   <li><b>Hold 2, changed outside</b> (D-LC4) — the Space's approval policy governs the object's kind
 *       ({@link PendingChanges#governs}) and its file is marked changed outside the server and not yet accepted
 *       ({@code POST /spaces/{id}/integrity/accept} releases it, no restart).</li>
 * </ul>
 *
 * Inputs are the Space lock's latest report only ({@link SpaceLock#latest}): before the first report is ready nothing
 * is held, never on "computing" (D-SE1). Both holds need Standard or Advanced (D-LC5): on Basic nothing is held.
 * Kill switch {@code -Dspace.activation.holds=off}. An object the lock has no fact about costs in-memory map lookups
 * only. Otherwise the Space's opt-ins ({@link #mode}) are read once and cached per Space, keyed on the edition and the
 * modification time and size of {@code safety-policy.toon} and {@code approval.toon}: a check is then two file stats,
 * and a file is read again only after it changed.
 */
final class ActivationHolds {

    private static final Logger log = LoggerFactory.getLogger(ActivationHolds.class);

    /** The kill switch: {@code off} holds nothing anywhere; anything else (default) honours a Space's opt-ins. */
    static final String PROPERTY = "space.activation.holds";

    /** In a Space that opted in, a report older than this is recomputed (at most this often), so a hand fix or a
     *  hand edit is seen with no server write. */
    static final long REFRESH_MS = 30_000;

    /** At most this many links named in one reason. */
    private static final int REASON_LINKS = 3;

    /** space → kind → name currently held, for the held / released log lines. */
    private static final Map<String, String> HELD = new ConcurrentHashMap<>();
    /** space → when its lock was last considered for a refresh. */
    private static final Map<String, Long> REFRESHED = new ConcurrentHashMap<>();

    private ActivationHolds() {}

    static boolean enabled() {
        return !"off".equalsIgnoreCase(System.getProperty(PROPERTY, "on").trim());
    }

    /** One hold on one object. {@code detail}: {@code links} for hold 1, {@code path} and {@code change} for hold 2. */
    record Hold(String kind, String name, String hold, String reason, Map<String, Object> detail) {
        Map<String, Object> body() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("kind", kind);
            m.put("name", name);
            m.put("hold", hold);
            m.put("reason", reason);
            m.putAll(detail);
            return m;
        }
    }

    /** What a Space opted into, as the holds act on it. */
    record Mode(boolean basic, boolean refuseLinks, boolean governsPipeline, boolean governsJob) {
        boolean optedIn() {
            return !basic && (refuseLinks || governsPipeline || governsJob);
        }

        boolean governs(String kind) {
            return "pipeline".equals(kind) ? governsPipeline : "job".equals(kind) && governsJob;
        }
    }

    /** A Space's cached {@link Mode} and the stamp it was read under. */
    private record CachedMode(String stamp, Mode mode) {}

    private static final Map<String, CachedMode> MODES = new ConcurrentHashMap<>();

    /** The Space's opt-ins: cached until the edition or either policy file (time, size) changes. */
    static Mode mode(String spaceId, Path configRoot) {
        String stamp = BootstrapRoutes.edition() + "|" + stamp(configRoot.resolve("safety-policy.toon")) + "|"
                + stamp(configRoot.resolve(ApprovalPolicy.FILE));
        CachedMode c = MODES.get(spaceId);
        if (c != null && c.stamp().equals(stamp)) return c.mode();
        Mode m = new Mode("basic".equals(BootstrapRoutes.edition()), SpaceLinksMode.of(spaceId).refuses(),
                PendingChanges.governs(configRoot, "pipeline"), PendingChanges.governs(configRoot, "job"));
        MODES.put(spaceId, new CachedMode(stamp, m));
        return m;
    }

    private static String stamp(Path file) {
        try {
            BasicFileAttributes a = Files.readAttributes(file, BasicFileAttributes.class);
            return a.lastModifiedTime().toMillis() + ":" + a.size();
        } catch (IOException absent) {
            return "-";
        }
    }

    /** The holds {@code mode} puts on the object whose lock facts are {@code e}; empty when it may run. */
    static List<Hold> holds(String spaceId, Mode mode, ActivationFacts.Entry e) {
        List<Hold> out = new ArrayList<>();
        if (e == null || mode.basic()) return out;
        if (mode.refuseLinks() && !e.dangling().isEmpty()) {
            List<String> named = new ArrayList<>();
            for (Map<String, Object> l : e.dangling()) {
                if (named.size() == REASON_LINKS) {
                    named.add("and " + (e.dangling().size() - REASON_LINKS) + " more");
                    break;
                }
                named.add(l.get("key") + " -> " + l.get("value") + " (" + l.get("toKind") + ")");
            }
            out.add(new Hold(e.kind(), e.name(), "links",
                    "this Space sets links: refuse-activation and the " + e.kind() + " has " + e.dangling().size()
                            + " dangling link(s): " + String.join(", ", named) + "; fix the link to release it",
                    Map.of("links", List.copyOf(e.dangling()))));
        }
        if (mode.governs(e.kind()))
            for (Map<String, Object> o : e.outside())
                out.add(new Hold(e.kind(), e.name(), "changed-outside",
                        "its file " + o.get("path") + " was changed outside the server (" + o.get("change")
                                + ") and this Space's approval policy governs " + e.kind()
                                + "; accept it (POST /spaces/" + spaceId + "/integrity/accept) to release it",
                        Map.of("path", String.valueOf(o.get("path")), "change", String.valueOf(o.get("change")))));
        return out;
    }

    /** The installed {@link com.gamma.pipeline.ActivationGate.Check}: why the object is held, or {@code null}. */
    static String reason(String spaceId, String kind, String name) {
        if (!enabled() || spaceId == null || name == null) return null;
        Path root = SpaceConfigRoot.forSpace(spaceId);
        SpaceLock.Report r = SpaceLock.latest(root);
        if (r == null || !"ready".equals(r.status())) return null;   // never hold on "computing" (D-SE1)
        refreshIfDue(spaceId, root, r);
        ActivationFacts.Entry e = r.facts().of(kind, name);
        String why = null;
        if (e != null) {
            List<String> reasons = new ArrayList<>();
            for (Hold h : holds(spaceId, mode(spaceId, root), e)) reasons.add(h.reason());
            if (!reasons.isEmpty()) why = String.join("; ", reasons);
        }
        logTransition(spaceId, kind, name, why);
        return why;
    }

    /**
     * The integrity report's section: {@code activationHolds} (what applies) and {@code held} (each hold). Only for a
     * {@code ready} report; lists what the check holds whether or not the object is active.
     */
    static void addTo(Map<String, Object> body, String spaceId, Path configRoot) {
        if (!"ready".equals(body.get("status"))) return;
        Map<String, Object> state = new LinkedHashMap<>();
        if (!enabled()) {
            state.put("status", "disabled");
            body.put("activationHolds", state);
            return;
        }
        Mode mode = mode(spaceId, configRoot);
        state.put("status", "on");
        state.put("links", mode.refuseLinks() && !mode.basic());
        List<String> governs = new ArrayList<>();
        if (!mode.basic() && mode.governsPipeline()) governs.add("pipeline");
        if (!mode.basic() && mode.governsJob()) governs.add("job");
        state.put("changedOutside", governs);
        if (mode.basic()) state.put("note", "activation holds need Standard or Advanced (D-LC5); on Basic nothing is held");
        body.put("activationHolds", state);
        SpaceLock.Report r = SpaceLock.latest(configRoot);
        List<Map<String, Object>> held = new ArrayList<>();
        if (r != null && "ready".equals(r.status()))
            for (ActivationFacts.Entry e : r.facts().byObject().values())
                for (Hold h : holds(spaceId, mode, e)) held.add(h.body());
        body.put("held", held);
    }

    /** In a Space that opted in, ask for a fresh computation when the report is older than {@link #REFRESH_MS};
     *  considered at most once per {@link #REFRESH_MS} per Space, so the policy files are read that rarely. */
    private static void refreshIfDue(String spaceId, Path root, SpaceLock.Report r) {
        long now = System.currentTimeMillis();
        Long last = REFRESHED.get(spaceId);
        if (last != null && now - last < REFRESH_MS) return;
        REFRESHED.put(spaceId, now);
        if (!mode(spaceId, root).optedIn()) return;
        try {
            long checked = Instant.parse(String.valueOf(r.body().get("checkedAt"))).toEpochMilli();
            if (now - checked >= REFRESH_MS) SpaceLock.refresh(root);
        } catch (RuntimeException unparsable) {
            SpaceLock.refresh(root);
        }
    }

    private static void logTransition(String spaceId, String kind, String name, String why) {
        String key = spaceId + "\n" + kind + "\n" + name.toLowerCase(Locale.ROOT);
        if (why != null) {
            if (!why.equals(HELD.put(key, why)))
                log.warn("[ACTIVATION] {} '{}' of Space '{}' is held and does not run: {}", kind, name, spaceId, why);
        } else if (HELD.remove(key) != null) {
            log.info("[ACTIVATION] {} '{}' of Space '{}' is released and runs again", kind, name, spaceId);
        }
    }

    /** Tests: forget the held / refresh bookkeeping. */
    static void resetForTest() {
        HELD.clear();
        REFRESHED.clear();
        MODES.clear();
    }
}
