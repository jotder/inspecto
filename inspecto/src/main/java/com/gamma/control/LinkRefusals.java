package com.gamma.control;

import com.gamma.audit.Event;
import com.gamma.audit.EventType;
import com.gamma.config.io.ConfigLoader;
import com.gamma.config.spec.Finding;
import com.gamma.config.spec.Severity;
import com.gamma.event.EventLog;
import com.gamma.pipeline.LinkCensus;
import com.gamma.pipeline.LinkGraph;
import com.gamma.service.ReservedConfigPaths;
import com.gamma.spi.auth.ApiException;
import com.gamma.spi.auth.ErrorCodes;
import com.gamma.spi.http.ApiContext;
import com.gamma.util.CurrentSpace;
import com.sun.net.httpserver.HttpExchange;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The census-backed refusals of `SPACE-LINK-CENSUS-1` LC-3: a delete of something a census link still points at
 * (D-LC3), and a save of an ACTIVE config that adds a {@link LinkGraph.Status#DANGLING} link (D-LC1). Each has one
 * mode, read live from a system property so a test or an operator can flip it without a restart:
 *
 * <ul>
 *   <li>{@code -Dlinks.delete=shadow|refuse|off}, default {@code shadow}. Every edition, every Space (the "Reach of
 *       D-LC3" paragraph). Shadow: the delete goes through as today; the response carries {@code brokenLinks} and an
 *       AUDIT event ({@code action = config.delete.links-broken}) lists them. Refuse: 409 with {@code dependents};
 *       {@code ?force=true} deletes as today and reports the same list.</li>
 *   <li>{@code -Dlinks.save=shadow|refuse|off}, default {@code shadow}. Shadow: the save succeeds and the response
 *       carries a WARNING finding per added dangling link. Refuse: 422 naming the key and the missing target. Per
 *       D-LC5 ("the refuse modes … on Standard and Advanced") {@code refuse} is honoured on Standard and Advanced
 *       only; on Basic it acts as {@code shadow}.</li>
 * </ul>
 *
 * A Space whose {@code safety-policy.toon} sets {@code links: refuse-activation} gets {@code refuse} where the JVM mode
 * is {@code shadow} ({@link SpaceLinksMode}, LC-4). A value outside the three reads as {@code shadow}: a typo never
 * starts refusing. The existing Pipeline and
 * Connection delete refusals are not this class and stay as they are in every mode.
 */
final class LinkRefusals {

    private static final Logger log = LoggerFactory.getLogger(LinkRefusals.class);

    static final String DELETE_PROPERTY = "links.delete";
    static final String SAVE_PROPERTY = "links.save";
    static final String ERR_LINK_DANGLING = "ERR_LINK_DANGLING";
    static final String WARN_LINK_DANGLING = "WARN_LINK_DANGLING";

    enum Mode { SHADOW, REFUSE, OFF }

    private LinkRefusals() {}

    static Mode mode(String property) {
        String v = System.getProperty(property, "shadow").trim().toLowerCase(Locale.ROOT);
        return switch (v) {
            case "refuse" -> Mode.REFUSE;
            case "off" -> Mode.OFF;
            case "shadow" -> Mode.SHADOW;
            default -> {
                log.warn("[LINKS] -D{}={} is not shadow|refuse|off; acting as shadow", property, v);
                yield Mode.SHADOW;
            }
        };
    }

    /** The save mode in effect: {@code refuse} needs Standard or Advanced (D-LC5). */
    static Mode saveMode() {
        Mode m = mode(SAVE_PROPERTY);
        return forSpace(m == Mode.REFUSE && "basic".equals(BootstrapRoutes.edition()) ? Mode.SHADOW : m);
    }

    /** The delete mode in effect for the calling request's Space. */
    static Mode deleteMode() {
        return forSpace(mode(DELETE_PROPERTY));
    }

    /** LC-4: a Space whose {@code links:} is {@code refuse-activation} turns {@code shadow} into {@code refuse}
     *  ({@link SpaceLinksMode}); {@code off} stays the kill switch. */
    private static Mode forSpace(Mode m) {
        return m == Mode.SHADOW && SpaceLinksMode.of(CurrentSpace.id()).refuses() ? Mode.REFUSE : m;
    }

    // ── delete (D-LC3) ──────────────────────────────────────────────────────────────────────

    /**
     * The census dependents of {@code toKind:id} and the verdict on deleting it. Call before the delete; when
     * {@link #refused()} answer with {@link #respond}, else delete and {@link #report} on the success body.
     */
    record Delete(Mode mode, boolean force, String toKind, String id, List<Map<String, Object>> dependents) {
        boolean refused() {
            return mode == Mode.REFUSE && !force && !dependents.isEmpty();
        }

        Object respond(HttpExchange ex) throws IOException {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("deleted", false);
            body.put("code", ErrorCodes.CONFLICT);
            body.put("error", toKind + " '" + id + "' is used by " + dependents.size() + " config(s): "
                    + summary() + " — repoint or remove them, or re-send with ?force=true");
            body.put("dependents", dependents);
            return ApiContext.respondJson(ex, 409, body);
        }

        /** After a delete that went through: the response's {@code brokenLinks} and the AUDIT event. */
        Map<String, Object> report(HttpExchange ex, Map<String, Object> response) {
            if (mode == Mode.OFF || dependents.isEmpty()) return response;
            Map<String, Object> r = new LinkedHashMap<>(response);
            r.put("brokenLinks", dependents);
            log.warn("[LINKS] {} delete of {} '{}' broke {} link(s): {}", force ? "forced" : "shadow", toKind, id,
                    dependents.size(), summary());
            try {
                EventLog.current().emit(Event.builder(EventType.AUDIT).source("audit")
                        .message(ApiContext.actor(ex) + " deleted " + toKind + " " + id + " used by " + summary())
                        .actor(ApiContext.actor(ex)).actorType(ApiContext.actorType(ex))
                        .action("config.delete.links-broken").actionCategory("configuration")
                        .attr("kind", toKind).attr("id", id).attr("mode", mode.name().toLowerCase(Locale.ROOT))
                        .attr("forced", String.valueOf(force)).attr("dependents", summary()));
            } catch (RuntimeException ignored) {
                // an absent or failing audit sink never undoes the delete it records
            }
            return r;
        }

        private String summary() {
            List<String> s = new ArrayList<>();
            for (Map<String, Object> d : dependents) s.add(d.get("kind") + " " + d.get("name") + " (" + d.get("key") + ")");
            return String.join(", ", s);
        }
    }

    /**
     * Who still links to {@code toKind:id} under {@code writeRoot}: every {@link LinkGraph.Status#RESOLVED} edge
     * naming it (a Pipeline id matched as the graph judges it, trimmed and case-insensitive).
     */
    static Delete delete(Path writeRoot, HttpExchange ex, String toKind, String id) {
        Mode mode = deleteMode();
        boolean force = "true".equalsIgnoreCase(String.valueOf(ApiContext.query(ex, "force")));
        List<Map<String, Object>> deps = new ArrayList<>();
        if (mode != Mode.OFF) {
            boolean pipeline = "pipeline".equals(toKind);
            String want = pipeline ? id.trim().toLowerCase(Locale.ROOT) : id;
            for (LinkGraph.Edge e : LinkGraph.of(writeRoot, ReservedConfigPaths::reserved).edges()) {
                if (e.status() != LinkGraph.Status.RESOLVED || !toKind.equals(e.toKind())) continue;
                // named(): a Pipeline's collector.dataset may be spelled datasets/<id> (SE-1b)
                String v = pipeline ? e.link().named(e.value()).toLowerCase(Locale.ROOT) : e.link().named(e.value());
                if (!v.equals(want)) continue;
                Map<String, Object> d = new LinkedHashMap<>();
                d.put("kind", e.fromKind());
                d.put("name", e.fromName());
                d.put("key", e.key());
                deps.add(d);
            }
        }
        return new Delete(mode, force, toKind, id, deps);
    }

    // ── save (D-LC1) ────────────────────────────────────────────────────────────────────────

    /**
     * The save check: the DANGLING links {@code draft} holds that {@code stored} (the version on disk, or null)
     * did not — same key, same value. A stored version that was itself a draft excuses nothing: activating a draft
     * is judged on every dangling link it holds (D-LC1). A draft ({@code active: false} or {@code enabled: false}, in a Job's
     * {@code job:} section too) is never judged. Refuse mode answers 422 and returns the handled marker; otherwise
     * shadow WARNING findings are appended to {@code findings} and null is returned.
     */
    static Object save(HttpExchange ex, Path writeRoot, String kind, String name, Map<String, Object> draft,
                       Map<String, Object> stored, List<Finding> findings) throws IOException {
        Mode mode = saveMode();
        List<Finding> added = added(mode, writeRoot, kind, name, draft, stored);
        if (added.isEmpty()) return null;
        if (mode == Mode.REFUSE) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("type", kind);
            body.put("written", false);
            body.put("code", ErrorCodes.CONFIG_VALIDATION_FAILED);
            body.put("error", "config adds " + added.size() + " link(s) to something that does not exist; not written");
            body.put("findings", added);
            return ApiContext.respondJson(ex, 422, body);
        }
        log.info("[LINKS] shadow: save of {} '{}' adds {} dangling link(s)", kind, name, added.size());
        findings.addAll(added);
        return null;
    }

    /**
     * {@link #save} for a kind-specific writer whose response has no findings list ({@code /alerts/rules},
     * {@code /decision-rules}, {@code /expectations}, every {@code /jobs} write including enable): refuse mode throws
     * 422 naming each key and missing target; shadow logs what refuse would have refused.
     */
    static void requireLinks(Path writeRoot, String kind, String name, Map<String, Object> draft, Map<String, Object> stored) {
        Mode mode = saveMode();
        List<Finding> added = added(mode, writeRoot, kind, name, draft, stored);
        if (added.isEmpty()) return;
        List<String> what = new ArrayList<>();
        for (Finding f : added) what.add(f.message());
        if (mode == Mode.REFUSE)
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, ERR_LINK_DANGLING + ": " + kind + " '" + name
                    + "' not written: " + String.join("; ", what) + " — create it first, or save as a draft (enabled: false)");
        log.warn("[LINKS] shadow: save of {} '{}' adds {} dangling link(s): {}", kind, name, added.size(), String.join("; ", what));
    }

    private static List<Finding> added(Mode mode, Path writeRoot, String kind, String name, Map<String, Object> draft,
                                       Map<String, Object> stored) {
        if (mode == Mode.OFF || draft == null || LinkCensus.from(kind).isEmpty() || isDraft(kind, draft)) return List.of();
        LinkGraph graph = LinkGraph.of(writeRoot, ReservedConfigPaths::reserved);
        Set<String> before = new HashSet<>();
        if (stored != null && !isDraft(kind, stored))
            for (LinkGraph.Edge e : graph.edgesOf(kind, name, stored))
                if (e.status() == LinkGraph.Status.DANGLING) before.add(e.key() + "\u0000" + e.value());
        List<Finding> added = new ArrayList<>();
        boolean refuse = mode == Mode.REFUSE;
        for (LinkGraph.Edge e : graph.edgesOf(kind, name, draft)) {
            if (e.status() != LinkGraph.Status.DANGLING || before.contains(e.key() + "\u0000" + e.value())) continue;
            added.add(new Finding(refuse ? Severity.ERROR : Severity.WARNING, e.key(),
                    e.key() + " names " + e.toKind() + " '" + e.value() + "', which does not exist in this Space",
                    refuse ? ERR_LINK_DANGLING : WARN_LINK_DANGLING,
                    "create the " + e.toKind() + " first, or save this " + kind + " as a draft (active: false)"));
        }
        return added;
    }

    /** The stored version of a config file, or null when there is none yet. */
    static Map<String, Object> stored(Path file) {
        if (file == null || !Files.isRegularFile(file)) return null;
        try {
            return ConfigLoader.filesystem().decode(file.toString());
        } catch (IOException | RuntimeException ex) {
            return null;   // an unreadable stored file: every link of the draft counts as added
        }
    }

    /** {@code active: false} or {@code enabled: false} (at the root, or in a Job's {@code job:} section). */
    static boolean isDraft(String kind, Map<String, Object> doc) {
        Map<?, ?> d = "job".equals(kind) && doc.get("job") instanceof Map<?, ?> inner ? inner : doc;
        return "false".equalsIgnoreCase(String.valueOf(d.get("active")))
                || "false".equalsIgnoreCase(String.valueOf(d.get("enabled")));
    }
}
