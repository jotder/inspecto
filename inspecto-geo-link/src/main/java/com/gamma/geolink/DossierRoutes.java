package com.gamma.geolink;

import com.gamma.control.ApiContext;
import com.gamma.control.ApiException;
import com.gamma.control.ComponentAccess;
import com.gamma.control.RouteModule;
import com.gamma.event.Event;
import com.gamma.event.EventLog;
import com.gamma.event.EventType;
import com.gamma.pipeline.ComponentRegistry;
import com.gamma.pipeline.ComponentStore;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.UnaryOperator;

/**
 * The <b>Dossier</b> of an Investigation (LA-12, {@code docs/superpower/link-analysis-backlog-plan.md} §2.7, §3.4) —
 * built by {@link GraphDossierBuilder} from what {@link SnapshotStore} holds, and verifiable later against the store.
 *
 * <ul>
 *   <li>{@code GET /inv/investigations/{id}/dossier?at=&snapshots=a,b&format=json|steps|method} — the dossier.
 *       {@code json} (default) answers the whole dossier in the envelope, all three renderings included;
 *       {@code steps} and {@code method} answer that one rendering as {@code text/plain}, ready to hand over.</li>
 *   <li>{@code POST /inv/investigations/{id}/dossier/verify} — body {@code {manifest}} (or a whole dossier): rebuilds
 *       the manifest from the store NOW and names every artefact whose bytes changed, went missing or appeared.</li>
 * </ul>
 *
 * <p><b>Access mirrors the Investigation's.</b> Owner-only — a non-owner gets a 404 indistinguishable from absence —
 * and a bound Dataset the caller can no longer view reads as absent (R3). Building a dossier reads NO Dataset: every
 * fact in it comes from the sealed log, so there is no {@code InvRoutes.relationFor} call to make. An included
 * snapshot must be ANCHORED to this Investigation (its body carries {@code investigationId}) and its origin
 * Dataset must be viewable — so a dossier can never become a way to read someone else's snapshot.
 *
 * <p>Both routes persist nothing — the dossier is regenerated deterministically from the store, and its manifest root
 * does not depend on when it was built — so neither is capability-gated: the GET is a read, the verify POST takes the
 * read-shaped exemption. Both are audited (LA-04), because issuing and checking evidence are acts the trail must show.
 *
 * <p><b>Masked (LA-19, D-U6).</b> The dossier's entity ids — in the lists, the ledger, the three renderings and the
 * embedded snapshots' score tables — are masked per the Space's {@code maskingMode} ({@link EntityMasking}). The
 * manifest carries only hashes, over the RAW store, so {@code verify} is unaffected; a reader holding a masked dossier
 * verifies custody through the route, not by re-hashing the masked lists.
 *
 * <p>⚠ {@code open()} duplicates {@code InvestigationRoutes.open} rather than sharing it, to leave that class
 * untouched under a parallel lane; fold them together later (as {@code relationFor} is already duplicated there).
 */
public final class DossierRoutes implements RouteModule {

    private static final int MAX_STEPS = 5_000;
    private static final int MAX_SNAPSHOTS = 20;

    @Override
    public void register(ApiContext api) {
        api.get("/inv/investigations/([^/]+)/dossier", (e, m) -> dossier(api, e, m.group(1)));
        api.post("/inv/investigations/([^/]+)/dossier/verify", (e, m) -> verify(api, e, m.group(1), api.body(e)));
    }

    private record Opened(InvestigationRoutes.Inv inv, SnapshotStore store, Path writeRoot, String id, String headerRaw) {}

    /** {@code GET /inv/investigations/{id}/dossier}. Gates: 503 → 422 id → 404 absent/not owner/R3 → 422 params. */
    private Object dossier(ApiContext api, HttpExchange ex, String id) throws IOException {
        Opened inv = open(api, ex, id);
        String format = Optional.ofNullable(ApiContext.query(ex, "format")).orElse("json");
        if (!List.of("json", "steps", "method").contains(format))
            throw new ApiException(422, "format must be json, steps or method, got '" + format + "'");
        List<String> log = inv.store().readLog(id);
        int at = log.size();
        String rawAt = ApiContext.query(ex, "at");
        if (rawAt != null && !rawAt.isBlank()) {
            try {
                at = Integer.parseInt(rawAt.trim());
            } catch (NumberFormatException e) {
                throw new ApiException(422, "at must be an integer, got '" + rawAt + "'");
            }
            if (at < 0 || at > log.size())
                throw new ApiException(422, "at must be between 0 and " + log.size() + ", got " + at);
        }
        if (at > MAX_STEPS)
            throw new ApiException(422, "a dossier covers at most " + MAX_STEPS + " steps; pass 'at'");
        List<String> snapshotIds = new ArrayList<>();
        String rawSnaps = ApiContext.query(ex, "snapshots");
        if (rawSnaps != null)
            for (String s : rawSnaps.split(",")) if (!s.isBlank()) snapshotIds.add(s.trim());

        GraphDossierBuilder.Input in = input(inv, log, at, snapshots(ex, inv, snapshotIds, true));
        Map<String, Object> built = GraphDossierBuilder.build(in);
        // D-U6: the dossier is masked as it leaves — the manifest (hashes only) is unaffected, and custody is
        // verified against the store by /dossier/verify, which never sees a pseudonym.
        EntityMasking mask = EntityMasking.of(inv.inv(), snapshotEntityIds(in.snapshots()));
        @SuppressWarnings("unchecked") Map<String, Object> dossier = (Map<String, Object>) mask.apply(built);
        @SuppressWarnings("unchecked") Map<String, Object> manifest = (Map<String, Object>) dossier.get("manifest");
        @SuppressWarnings("unchecked") Map<String, Object> integrity = (Map<String, Object>) dossier.get("integrity");
        int stepsAt = at;
        emit(ex, EventType.LINK_DOSSIER_BUILT, "link.dossier.built",
                "link.dossier.built — " + id + " at step " + at + (Boolean.TRUE.equals(integrity.get("intact"))
                        ? "" : " (INTEGRITY FAILURE)"),
                b -> b.attr("investigationId", id).attr("at", stepsAt).attr("format", format)
                        .attr("root", manifest.get("root")).attr("intact", integrity.get("intact"))
                        .attr("snapshots", snapshotIds.size()));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", id);
        out.put("generatedAt", java.time.Instant.now().toString());   // NOT part of the manifest root
        out.putAll(dossier);
        out.put("masking", mask.describe());
        @SuppressWarnings("unchecked") Map<String, Object> renderings = (Map<String, Object>) dossier.get("renderings");
        return switch (format) {
            case "steps" -> ApiContext.respondText(ex,
                    String.join("\n", castStrings(renderings.get("steps"))) + "\n", "text/plain; charset=utf-8");
            case "method" -> ApiContext.respondText(ex, String.valueOf(renderings.get("method")),
                    "text/plain; charset=utf-8");
            default -> out;
        };
    }

    /**
     * {@code POST /inv/investigations/{id}/dossier/verify} — body {@code {manifest}} or a whole dossier. Rebuilds the
     * manifest for the same position and snapshots from the store as it is NOW. A snapshot or step that has since
     * disappeared is reported under {@code missing} rather than refused: verification must report tampering, never
     * turn it into an error that hides what changed.
     */
    @SuppressWarnings("unchecked")
    private Object verify(ApiContext api, HttpExchange ex, String id, Map<String, Object> body) throws IOException {
        Opened inv = open(api, ex, id);
        Object raw = body.get("manifest") instanceof Map<?, ?> m ? m : body;
        Map<String, Object> submitted = (Map<String, Object>) raw;
        if (!(submitted.get("root") instanceof String) || !(submitted.get("artefacts") instanceof List<?>)
                || !(submitted.get("at") instanceof Number n))
            throw new ApiException(422, "body must carry a dossier manifest {root, at, artefacts, ...}");
        if (!id.equals(submitted.get("investigation")))
            throw new ApiException(422, "the manifest is for investigation '" + submitted.get("investigation")
                    + "', not '" + id + "'");
        List<String> log = inv.store().readLog(id);
        int at = Math.max(0, Math.min(n.intValue(), Math.min(log.size(), MAX_STEPS)));
        List<String> ids = new ArrayList<>();
        if (submitted.get("snapshots") instanceof List<?> l) for (Object o : l) ids.add(String.valueOf(o));
        if (ids.size() > MAX_SNAPSHOTS) throw new ApiException(422, "at most " + MAX_SNAPSHOTS + " snapshots");

        GraphDossierBuilder.Input in = input(inv, log, at, snapshots(ex, inv, ids, false));
        Map<String, Object> dossier = GraphDossierBuilder.build(in);
        boolean intact = Boolean.TRUE.equals(((Map<String, Object>) dossier.get("integrity")).get("intact"));
        Map<String, Object> result = GraphDossierBuilder.verify(submitted,
                (Map<String, Object>) dossier.get("manifest"), intact);
        result.put("integrity", dossier.get("integrity"));
        emit(ex, EventType.LINK_DOSSIER_VERIFIED, "link.dossier.verified",
                "link.dossier.verified — " + id + (Boolean.TRUE.equals(result.get("verified")) ? "" : " (FAILED)"),
                b -> b.attr("investigationId", id).attr("verified", result.get("verified"))
                        .attr("submittedRoot", result.get("submittedRoot")).attr("currentRoot", result.get("currentRoot"))
                        .attr("changed", ((List<?>) result.get("changed")).size()));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", id);
        out.putAll(result);
        return out;
    }

    // ── helpers ────────────────────────────────────────────────────────────────────────────────────────

    private static GraphDossierBuilder.Input input(Opened inv, List<String> log, int at,
                                                   List<GraphDossierBuilder.Snapshot> snaps) throws IOException {
        Map<Integer, String> sets = new HashMap<>();
        for (int step = 1; step <= at; step++) sets.put(step, inv.store().readSet(inv.id(), step));
        return new GraphDossierBuilder.Input(inv.id(), inv.headerRaw(), log, sets, snaps, at);
    }

    /**
     * The included snapshots, each: a safe id (422), sealed (404 — or, when {@code strict} is false, silently left
     * out so verification reports it missing), anchored to this Investigation (422) and over a viewable Dataset (404).
     */
    private static List<GraphDossierBuilder.Snapshot> snapshots(HttpExchange ex, Opened inv, List<String> ids,
                                                                boolean strict) throws IOException {
        if (ids.size() > MAX_SNAPSHOTS) throw new ApiException(422, "at most " + MAX_SNAPSHOTS + " snapshots");
        List<GraphDossierBuilder.Snapshot> out = new ArrayList<>();
        for (String sid : new LinkedHashSet<>(ids)) {
            if (!SnapshotStore.SAFE_ID.matcher(sid).matches())
                throw new ApiException(422, "snapshot id must match " + SnapshotStore.SAFE_ID.pattern() + ", got '" + sid + "'");
            String raw = inv.store().read(sid);
            if (raw == null) {
                if (strict) throw new ApiException(404, "no sealed snapshot '" + sid + "'");
                continue;
            }
            @SuppressWarnings("unchecked") Map<String, Object> snap = ApiContext.JSON.readValue(raw, Map.class);
            if (!inv.id().equals(snap.get("investigationId")))
                throw new ApiException(422, "snapshot '" + sid + "' is not anchored to investigation '" + inv.id()
                        + "' (its investigationId is " + snap.get("investigationId") + ")");
            if (snap.get("origin") instanceof Map<?, ?> origin && origin.get("dataset") != null) {
                String ds = String.valueOf(origin.get("dataset"));
                Optional<Map<String, Object>> content = new ComponentStore(inv.writeRoot().resolve("registry"))
                        .get("dataset", ds).map(ComponentRegistry.Component::content);
                if (content.isPresent() && !ComponentAccess.canView(ex, content.get()))
                    throw new ApiException(404, "no sealed snapshot '" + sid + "'");
            }
            out.add(new GraphDossierBuilder.Snapshot(sid, raw));
        }
        return out;
    }

    /** Through the ONE Investigation gate ({@link InvestigationRoutes#open}: 503 → 422 unsafe id → 403 → 404 absent,
     *  not the owner, R3 or an Enterprise policy DENY), keeping the header's raw bytes for the manifest. */
    private static Opened open(ApiContext api, HttpExchange ex, String id) throws IOException {
        InvestigationRoutes.Inv inv = InvestigationRoutes.open(api, ex, id);
        String raw = inv.store().readInvestigation(id);
        if (raw == null) throw new ApiException(404, "no investigation '" + id + "'");
        return new Opened(inv, inv.store(), inv.writeRoot(), id, raw);
    }

    /** The node ids an included snapshot carries (its {@code nodes[].id} and score-vector keys) — so that
     *  {@code maskingMode all} masks them in the score tables too, not only the ids the log names. */
    private static List<String> snapshotEntityIds(List<GraphDossierBuilder.Snapshot> snaps) throws IOException {
        List<String> out = new ArrayList<>();
        for (GraphDossierBuilder.Snapshot s : snaps) {
            @SuppressWarnings("unchecked") Map<String, Object> snap = ApiContext.JSON.readValue(s.raw(), Map.class);
            if (snap.get("nodes") instanceof List<?> nodes)
                for (Object n : nodes) if (n instanceof Map<?, ?> m && m.get("id") != null) out.add(String.valueOf(m.get("id")));
            if (snap.get("metrics") instanceof Map<?, ?> metrics)
                for (Object v : metrics.values()) if (v instanceof Map<?, ?> vector) for (Object k : vector.keySet()) out.add(String.valueOf(k));
        }
        return out;
    }

    private static List<String> castStrings(Object o) {
        List<String> out = new ArrayList<>();
        if (o instanceof List<?> l) for (Object v : l) out.add(String.valueOf(v));
        return out;
    }

    /** Best-effort audit (LA-04 pattern): an audit failure never fails the dossier. */
    private static void emit(HttpExchange ex, String type, String action, String message,
                             UnaryOperator<Event.Builder> attrs) {
        try {
            Event.Builder b = Event.builder(type).source("inv").message(message)
                    .actor(ApiContext.actor(ex)).actorType(ApiContext.actorType(ex))
                    .action(action).actionCategory("analysis");
            EventLog.current().emit(attrs.apply(b));
        } catch (RuntimeException ignored) {
            // best effort
        }
    }
}
