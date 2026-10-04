package com.gamma.la.api;

import com.gamma.la.core.InvestigationStore;
import com.gamma.la.core.LinkEventTypes;
import com.gamma.la.core.DatasetProviders;
import com.gamma.la.core.LinkIds;
import com.gamma.la.core.SnapshotStore;
import com.gamma.control.ApiContext;
import com.gamma.control.ApiException;
import com.gamma.control.ErrorCodes;
import com.gamma.control.ComponentAccess;
import com.gamma.control.RouteModule;
import com.gamma.event.Event;
import com.gamma.event.EventLog;
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
 * The <b>Dossier</b> of an Investigation (LA-12, {@code docs/archived-documents/plans-archive/link-analysis-backlog-plan.md} §2.7, §3.4) —
 * built by {@link GraphDossierBuilder} from what {@link SnapshotStore} holds, and verifiable later against the store.
 *
 * <ul>
 *   <li>{@code GET /inv/investigations/{id}/dossier?at=&snapshots=a,b&format=json|steps|method|html} — the dossier.
 *       {@code json} (default) answers the whole dossier in the envelope, all three renderings included;
 *       {@code steps} and {@code method} answer that one rendering as {@code text/plain}, ready to hand over; {@code html} answers one self-contained printable page ({@link DossierHtml}).</li>
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

    static final int MAX_STEPS = 5_000;
    static final int MAX_SNAPSHOTS = 20;

    @Override
    public void register(ApiContext api) {
        api.get("/inv/investigations/([^/]+)/dossier", (e, m) -> dossier(api, e, m.group(1)));
        api.post("/inv/investigations/([^/]+)/dossier/verify", (e, m) -> verify(api, e, m.group(1), api.body(e)));
    }

    record Opened(InvestigationRoutes.Inv inv, InvestigationStore store, Path writeRoot, String id, String headerRaw) {}

    /** A built Dossier, already masked as it leaves, with the masking that was applied. */
    record Masked(Map<String, Object> dossier, EntityMasking mask) {}

    /** The {@code at} query parameter: a step count in {@code [0, log.size()]}, default the whole log; 422 otherwise. */
    static int parseAt(HttpExchange ex, List<String> log) {
        int at = log.size();
        String rawAt = ApiContext.query(ex, "at");
        if (rawAt != null && !rawAt.isBlank()) {
            try {
                at = Integer.parseInt(rawAt.trim());
            } catch (NumberFormatException e) {
                throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "at must be an integer, got '" + rawAt + "'");
            }
            if (at < 0 || at > log.size())
                throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "at must be between 0 and " + log.size() + ", got " + at);
        }
        if (at > MAX_STEPS)
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "a dossier covers at most " + MAX_STEPS + " steps; pass 'at'");
        return at;
    }

    /** The {@code snapshots} query parameter, comma separated. */
    static List<String> parseSnapshotIds(HttpExchange ex) {
        List<String> snapshotIds = new ArrayList<>();
        String rawSnaps = ApiContext.query(ex, "snapshots");
        if (rawSnaps != null)
            for (String s : rawSnaps.split(",")) if (!s.isBlank()) snapshotIds.add(s.trim());
        return snapshotIds;
    }

    /** Build the Dossier at {@code at} over the given snapshots and mask it per the Space's {@code maskingMode} (D-U6). */
    @SuppressWarnings("unchecked")
    static Masked maskedDossier(HttpExchange ex, Opened inv, List<String> log, int at, List<String> snapshotIds)
            throws IOException {
        GraphDossierBuilder.Input in = input(inv, log, at, snapshots(ex, inv, snapshotIds, true));
        Map<String, Object> built = GraphDossierBuilder.build(in);
        // D-U6: the dossier is masked as it leaves — the manifest (hashes only) is unaffected, and custody is
        // verified against the store by /dossier/verify, which never sees a pseudonym.
        EntityMasking mask = EntityMasking.of(inv.inv(), snapshotEntityIds(in.snapshots()));
        Map<String, Object> dossier = (Map<String, Object>) LinkIds.stamp(mask.apply(built));   // D-U9: link ids minted from what the caller sees
        return new Masked(dossier, mask);
    }

    /** {@code GET /inv/investigations/{id}/dossier}. Gates: 503 → 422 id → 404 absent/not owner/R3 → 422 params. */
    private Object dossier(ApiContext api, HttpExchange ex, String id) throws IOException {
        Opened inv = open(api, ex, id);
        String format = Optional.ofNullable(ApiContext.query(ex, "format")).orElse("json");
        if (!List.of("json", "steps", "method", "html").contains(format))
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "format must be json, steps, method or html, got '" + format + "'");
        List<String> log = inv.store().log(InvestigationStore.Scope.main(id));
        int at = parseAt(ex, log);
        List<String> snapshotIds = parseSnapshotIds(ex);
        Masked built = maskedDossier(ex, inv, log, at, snapshotIds);
        EntityMasking mask = built.mask();
        Map<String, Object> dossier = built.dossier();
        @SuppressWarnings("unchecked") Map<String, Object> manifest = (Map<String, Object>) dossier.get("manifest");
        @SuppressWarnings("unchecked") Map<String, Object> integrity = (Map<String, Object>) dossier.get("integrity");
        int stepsAt = at;
        emit(ex, LinkEventTypes.LINK_DOSSIER_BUILT, "link.dossier.built",
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
        // Comparison mode: an OPTIONAL, explicitly UNSEALED section - a live Dataset read, so outside the manifest and the
        // integrity check (the rest of the Dossier reads no Dataset). Only when windows are asked for (aFrom..bTo).
        Map<String, Map<String, Object>> compareWindows = WindowComparison.windows(ex);
        if (compareWindows != null) out.put("comparison", comparison(api, ex, inv, log, at, compareWindows, mask));
        @SuppressWarnings("unchecked") Map<String, Object> renderings = (Map<String, Object>) dossier.get("renderings");
        return switch (format) {
            case "steps" -> ApiContext.respondText(ex,
                    String.join("\n", castStrings(renderings.get("steps"))) + "\n", "text/plain; charset=utf-8");
            case "method" -> ApiContext.respondText(ex, String.valueOf(renderings.get("method")),
                    "text/plain; charset=utf-8");
            case "html" -> {
                // LA-DOSSIER-OUTPUT-1: printable, self-contained; the CSP forbids any fetch or script even if an
                // escaping bug ever let markup through.
                ex.getResponseHeaders().set("Content-Security-Policy", "default-src 'none'; style-src 'unsafe-inline'");
                yield ApiContext.respondText(ex, DossierHtml.render(out), "text/html; charset=utf-8");
            }
            default -> out;
        };
    }

    /** The Dossier's comparison section: the diff at step {@code at}, masked like the rest, labelled live and unsealed. */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> comparison(ApiContext api, HttpExchange ex, Opened inv, List<String> log, int at,
                                                  Map<String, Map<String, Object>> windows, EntityMasking mask) throws IOException {
        List<Map<String, Object>> prefix = new ArrayList<>();
        for (String line : log.subList(0, at)) prefix.add(ApiContext.JSON.readValue(line, Map.class));
        Map<String, Object> raw = WindowComparison.compare(api, ex, inv.inv(),
                com.gamma.la.core.InvestigationEvaluator.evaluate(prefix, -1, null), windows);
        InvestigationComparisonRoutes.emit(ex, inv.id(), inv.inv().dataset(), raw);
        return (Map<String, Object>) mask.apply(raw);
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
        Map<String, Object> result = verifyManifest(ex, inv, id, submitted);
        emit(ex, LinkEventTypes.LINK_DOSSIER_VERIFIED, "link.dossier.verified",
                "link.dossier.verified — " + id + (Boolean.TRUE.equals(result.get("verified")) ? "" : " (FAILED)"),
                b -> b.attr("investigationId", id).attr("verified", result.get("verified"))
                        .attr("submittedRoot", result.get("submittedRoot")).attr("currentRoot", result.get("currentRoot"))
                        .attr("changed", ((List<?>) result.get("changed")).size()));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", id);
        out.putAll(result);
        return out;
    }

    /**
     * Rebuild the manifest for the submitted position and snapshots from the store as it is NOW and compare it entry by
     * entry. 422 when the body is not a manifest for this Investigation. Shared by {@code /dossier/verify} and the
     * bundle's custody check, so the two cannot disagree about what "verified" means.
     */
    @SuppressWarnings("unchecked")
    static Map<String, Object> verifyManifest(HttpExchange ex, Opened inv, String id, Map<String, Object> submitted)
            throws IOException {
        if (!(submitted.get("root") instanceof String) || !(submitted.get("artefacts") instanceof List<?>)
                || !(submitted.get("at") instanceof Number n))
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "body must carry a dossier manifest {root, at, artefacts, ...}");
        if (!id.equals(submitted.get("investigation")))
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "the manifest is for investigation '" + submitted.get("investigation")
                    + "', not '" + id + "'");
        List<String> log = inv.store().log(InvestigationStore.Scope.main(id));
        int at = Math.max(0, Math.min(n.intValue(), Math.min(log.size(), MAX_STEPS)));
        List<String> ids = new ArrayList<>();
        if (submitted.get("snapshots") instanceof List<?> l) for (Object o : l) ids.add(String.valueOf(o));
        if (ids.size() > MAX_SNAPSHOTS) throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "at most " + MAX_SNAPSHOTS + " snapshots");

        GraphDossierBuilder.Input in = input(inv, log, at, snapshots(ex, inv, ids, false));
        Map<String, Object> dossier = GraphDossierBuilder.build(in);
        boolean intact = Boolean.TRUE.equals(((Map<String, Object>) dossier.get("integrity")).get("intact"));
        Map<String, Object> result = GraphDossierBuilder.verify(submitted,
                (Map<String, Object>) dossier.get("manifest"), intact);
        result.put("integrity", dossier.get("integrity"));
        return result;
    }

    // ── helpers ────────────────────────────────────────────────────────────────────────────────────────

    static GraphDossierBuilder.Input input(Opened inv, List<String> log, int at,
                                                   List<GraphDossierBuilder.Snapshot> snaps) throws IOException {
        Map<Integer, String> sets = new HashMap<>();
        for (int step = 1; step <= at; step++) sets.put(step, inv.store().set(inv.id(), step).orElse(null));
        return new GraphDossierBuilder.Input(inv.id(), inv.headerRaw(), log, sets, snaps, at);
    }

    /**
     * The included snapshots, each: a safe id (422), sealed (404 — or, when {@code strict} is false, silently left
     * out so verification reports it missing), anchored to this Investigation (422) and over a viewable Dataset (404).
     */
    static List<GraphDossierBuilder.Snapshot> snapshots(HttpExchange ex, Opened inv, List<String> ids,
                                                                boolean strict) throws IOException {
        if (ids.size() > MAX_SNAPSHOTS) throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "at most " + MAX_SNAPSHOTS + " snapshots");
        List<GraphDossierBuilder.Snapshot> out = new ArrayList<>();
        for (String sid : new LinkedHashSet<>(ids)) {
            if (!SnapshotStore.SAFE_ID.matcher(sid).matches())
                throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "snapshot id must match " + SnapshotStore.SAFE_ID.pattern() + ", got '" + sid + "'");
            String raw = new SnapshotStore(inv.writeRoot()).read(sid);   // a snapshot, not an Investigation record
            if (raw == null) {
                if (strict) throw new ApiException(404, ErrorCodes.NOT_FOUND, "no sealed snapshot '" + sid + "'");
                continue;
            }
            @SuppressWarnings("unchecked") Map<String, Object> snap = ApiContext.JSON.readValue(raw, Map.class);
            if (!inv.id().equals(snap.get("investigationId")))
                throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "snapshot '" + sid + "' is not anchored to investigation '" + inv.id()
                        + "' (its investigationId is " + snap.get("investigationId") + ")");
            if (snap.get("origin") instanceof Map<?, ?> origin && origin.get("dataset") != null) {
                String ds = String.valueOf(origin.get("dataset"));
                Optional<Map<String, Object>> content = DatasetProviders.require().dataset(inv.writeRoot(), ds);
                if (content.isPresent() && !ComponentAccess.canView(ex, content.get()))
                    throw new ApiException(404, ErrorCodes.NOT_FOUND, "no sealed snapshot '" + sid + "'");
            }
            out.add(new GraphDossierBuilder.Snapshot(sid, raw));
        }
        return out;
    }

    /** Through the ONE Investigation gate ({@link InvestigationRoutes#open}: 503 → 422 unsafe id → 403 → 404 absent,
     *  not the owner, R3 or an Enterprise policy DENY), keeping the header's raw bytes for the manifest. */
    static Opened open(ApiContext api, HttpExchange ex, String id) throws IOException {
        InvestigationRoutes.Inv inv = InvestigationRoutes.openForRead(api, ex, id);
        String raw = inv.store().header(id).orElse(null);
        if (raw == null) throw new ApiException(404, ErrorCodes.NOT_FOUND, "no investigation '" + id + "'");
        return new Opened(inv, inv.store(), inv.writeRoot(), id, raw);
    }

    /** The node ids an included snapshot carries (its {@code nodes[].id} and score-vector keys) — so that
     *  {@code maskingMode all} masks them in the score tables too, not only the ids the log names. */
    static List<String> snapshotEntityIds(List<GraphDossierBuilder.Snapshot> snaps) throws IOException {
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
    static void emit(HttpExchange ex, String type, String action, String message,
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
