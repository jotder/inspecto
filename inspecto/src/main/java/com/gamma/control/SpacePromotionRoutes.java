package com.gamma.control;

import com.gamma.access.WriteGates;
import com.gamma.acquire.ConnectionProfile;
import com.gamma.config.io.ConfigCodec;
import com.gamma.config.safety.ConfigSafetyValidator;
import com.gamma.config.safety.SafetyPolicy;
import com.gamma.config.spec.Finding;
import com.gamma.config.spec.Severity;
import com.gamma.event.EventLog;
import com.gamma.service.BundleImporter;
import com.gamma.service.ImportJournal;
import com.gamma.spi.auth.ApiException;
import com.gamma.spi.auth.ErrorCodes;
import com.gamma.spi.http.ApiContext;
import com.gamma.spi.http.RouteModule;
import com.gamma.util.AtomicFiles;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * SE-6b — promotion into an existing Space ({@code docs/okf/product/module/config/spec-space-promotion.md}). Reached
 * as {@code /spaces/{id}/promotion/…} (the Space seam binds the target) or unprefixed for the default Space. Both
 * routes take one JSON body: {@code {bundle: <base64 of a GET /export zip>, parameters?: {NAME: value},
 * previewHash (apply), removals?: false (apply)}} — JSON rather than a raw zip because an approval REPLAYS the
 * recorded body as text ({@code PendingChanges.hold}).
 *
 * <ul>
 *   <li>{@code POST /promotion/preview} — the change set ({@link SpacePromotion#compute}); writes nothing.</li>
 *   <li>{@code POST /promotion/apply} — re-computes it; 409 unless its hash is the preview's; under an approval
 *       policy ONE Pending Change for the whole set (202), else writes it at once.</li>
 * </ul>
 * Both {@code canAdminister}. Gate order: kill switch / no config root 503 → body 400 (413 over
 * {@link #MAX_BODY}) → not a whole-Space bundle or a placeholder without a value 422 → a promoted Pipeline escaping
 * the allowed roots 422 → a kind the caller may not write 403 → stale 409 → hold 202 → write 200.
 */
final class SpacePromotionRoutes implements RouteModule {

    static final int MAX_BODY = 64 << 20;
    /** One apply's re-check and write at a time; never held across the hold (approve takes the store lock first). */
    private static final Object APPLY_LOCK = new Object();

    @Override
    public void register(ApiContext api) {
        api.post("/promotion/preview", ApiContext.withCapability("canAdminister", (e, m) -> preview(api, e)));
        api.post("/promotion/apply", ApiContext.withCapability("canAdminister", (e, m) -> apply(api, e)));
    }

    private record Request(BundleImporter.Bundle bundle, Map<String, String> parameters, String previewHash,
                           boolean removals) {}

    private static Path configRoot(ApiContext api) {
        if (!SpacePromotion.enabled())
            throw new ApiException(503, ErrorCodes.CAPABILITY_UNAVAILABLE,
                    "promotion is switched off on this server (-D" + SpacePromotion.PROPERTY + "=off)");
        return WriteGates.requireWriteRoot(api, "promotion");
    }

    private static Request request(ApiContext api, HttpExchange e, boolean apply) throws IOException {
        api.rawBody(e, MAX_BODY);
        Map<String, Object> body = api.body(e);
        if (!(body.get("bundle") instanceof String b64) || b64.isBlank())
            throw bad("'bundle' (the base64 of a whole-Space export zip) is required");
        byte[] zip;
        try {
            zip = Base64.getDecoder().decode(b64.strip());
        } catch (IllegalArgumentException notBase64) {
            throw bad("'bundle' is not base64");
        }
        Map<String, String> params = new LinkedHashMap<>();
        Object rawParams = body.get("parameters");
        if (rawParams != null) {
            if (!(rawParams instanceof Map<?, ?> pm)) throw bad("'parameters' must be an object of NAME: value");
            for (Map.Entry<?, ?> p : pm.entrySet()) {
                String name = String.valueOf(p.getKey());
                if (!SpacePromotion.PARAM_NAME.matcher(name).matches())
                    throw bad("parameter name '" + name + "' must be upper snake case (" + SpacePromotion.PARAM_NAME + ")");
                if (SpacePromotion.SPACE_PARAM.equals(name))
                    throw bad("'SPACE' is always the target Space's id and cannot be set");
                if (!(p.getValue() instanceof String v) || v.length() > SpacePromotion.MAX_PARAM_VALUE
                        || v.chars().anyMatch(c -> Character.isISOControl(c) || c == '"' || c == '\\'))
                    throw bad("parameter '" + name + "' must be a string of at most " + SpacePromotion.MAX_PARAM_VALUE
                            + " characters without quotes, backslashes or control characters");
                params.put(name, v);
            }
        }
        String hash = null;
        boolean removals = false;
        if (apply) {
            if (!(body.get("previewHash") instanceof String h) || h.isBlank())
                throw bad("'previewHash' (the changeSetHash of the preview being applied) is required");
            hash = h.strip();
            Object r = body.get("removals");
            if (r != null && !(r instanceof Boolean)) throw bad("'removals' must be true or false");
            removals = Boolean.TRUE.equals(r);
        }
        BundleImporter.Bundle bundle;
        try {
            bundle = BundleImporter.parse(zip);
        } catch (IllegalArgumentException | IOException notABundle) {
            throw bad("'bundle' is not a bundle zip: " + notABundle.getMessage());
        }
        if (!"space".equals(bundle.kind()))
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "a promotion takes a whole-Space bundle "
                    + "(GET /spaces/{id}/export); this one is kind '" + bundle.kind() + "'");
        return new Request(bundle, params, hash, removals);
    }

    private static ApiException bad(String why) {
        return new ApiException(400, ErrorCodes.MALFORMED_REQUEST, why);
    }

    private static Object missing(HttpExchange e, SpacePromotion.MissingParameters missing) throws IOException {
        Map<String, Object> refusal = new LinkedHashMap<>();
        refusal.put("error", "the bundle names parameter(s) with no value: " + missing.names + "; nothing was written");
        refusal.put("missingParameters", missing.names);
        return ApiContext.respondJson(e, 422, refusal);
    }

    private static Object broken(HttpExchange e, SpacePromotion.BrokenByBinding broken) throws IOException {
        Map<String, Object> refusal = new LinkedHashMap<>();
        refusal.put("error", "binding the parameters makes " + broken.paths + " unreadable (a value with a comma in an "
                + "array row, say); nothing was written");
        refusal.put("brokenByParameters", broken.paths);
        return ApiContext.respondJson(e, 422, refusal);
    }

    private Object preview(ApiContext api, HttpExchange e) throws IOException {
        Path root = configRoot(api);
        Request r = request(api, e, false);
        String space = EventLog.currentSpaceId();
        SpacePromotion.ChangeSet cs;
        try {
            cs = SpacePromotion.compute(root, r.bundle(), r.parameters(), space);
        } catch (SpacePromotion.MissingParameters m) {
            return missing(e, m);
        } catch (SpacePromotion.BrokenByBinding b) {
            return broken(e, b);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("space", space);
        out.put("sourceSpace", r.bundle().manifest().get("source_space"));
        out.put("changeSetHash", cs.hash());
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (String a : List.of("add", "change", "remove", "same")) counts.put(a, cs.of(a).size());
        out.put("counts", counts);
        List<Map<String, Object>> changes = new ArrayList<>();
        for (SpacePromotion.Change c : cs.changes()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("path", c.path());
            m.put("kind", c.kind());
            m.put("action", c.action());
            changes.add(m);
        }
        out.put("changes", changes);
        out.put("excluded", cs.excluded().stream().map(x -> Map.of("path", x.path(), "reason", x.reason())).toList());
        out.put("parametersUsed", cs.parametersUsed());
        out.put("approval", ApprovalPolicy.forRoot(root).forWholeSet() == null ? "direct" : "pending-change");
        return out;
    }

    private Object apply(ApiContext api, HttpExchange e) throws IOException {
        Path root = configRoot(api);
        Request r = request(api, e, true);
        String space = EventLog.currentSpaceId();
        SpacePromotion.ChangeSet cs;
        try {
            cs = SpacePromotion.compute(root, r.bundle(), r.parameters(), space);
        } catch (SpacePromotion.MissingParameters m) {
            return missing(e, m);
        } catch (SpacePromotion.BrokenByBinding b) {
            return broken(e, b);
        }
        List<SpacePromotion.Change> applied = new ArrayList<>(cs.of("add"));
        applied.addAll(cs.of("change"));
        if (r.removals()) applied.addAll(cs.of("remove"));
        applied.sort((a, b) -> a.path().compareTo(b.path()));

        Map<String, byte[]> toWrite = new LinkedHashMap<>();
        for (SpacePromotion.Change c : applied) if (c.incoming() != null) toWrite.put(c.path(), c.incoming());
        // the gates every import runs, over exactly what this apply writes: containment of a Pipeline's paths (422),
        // a kind the caller may not write on its own route (403), the Decision Rule gate, the Job author stamps
        Map<String, List<Finding>> escaping = escaping(root, toWrite);
        if (!escaping.isEmpty())
            return ApiContext.respondJson(e, 422, Map.of("error", "a promoted Pipeline names a path outside the allowed "
                    + "roots; nothing was written", "findings", escaping));
        ImportCapabilityGuard.checkFiles(e, toWrite);
        toWrite = DecisionRuleGuard.guardImport(e, root,
                new BundleImporter.Bundle("space", Map.of(), new LinkedHashMap<>(toWrite), null)).configEntries();
        toWrite = JobAuthority.stampFiles(e, toWrite);

        if (!cs.hash().equals(r.previewHash())) throw stale();
        if (applied.isEmpty()) return result(space, cs, List.of(), List.of(), r.removals());

        // D-SE7: under any approval policy the whole set is ONE Pending Change (202); approval replays this request
        PendingChanges.hold(api, e, SpacePromotion.KIND, space, SpacePromotion.proposed(space, cs, applied),
                SpacePromotion.current(space, applied), true);

        synchronized (APPLY_LOCK) {
            // re-checked under the lock: nothing changed the target since the gates above read it
            if (!SpacePromotion.compute(root, r.bundle(), r.parameters(), space).hash().equals(cs.hash())) throw stale();
            return write(api, root, space, cs, applied, toWrite, r.removals());
        }
    }

    private static ApiException stale() {
        return new ApiException(409, ErrorCodes.CONFLICT, "the target Space or the bundle changed since the preview "
                + "(its changeSetHash no longer matches); preview again — nothing was written");
    }

    /** ConfigSafetyValidator ERRORs of each promoted Pipeline, judged where it lands. */
    private static Map<String, List<Finding>> escaping(Path root, Map<String, byte[]> toWrite) {
        Map<String, List<Finding>> out = new LinkedHashMap<>();
        for (Map.Entry<String, byte[]> en : toWrite.entrySet()) {
            if (!en.getKey().endsWith("_pipeline.toon")) continue;
            List<Finding> fs;
            try {
                fs = ConfigSafetyValidator.check("pipeline", ConfigCodec.toMap(new String(en.getValue(), StandardCharsets.UTF_8)),
                        SafetyPolicy.defaultPolicy(), root.resolve(en.getKey()).getParent());
            } catch (RuntimeException unparseable) {
                fs = List.of(new Finding(Severity.ERROR, "(parse)", "cannot parse pipeline: " + unparseable.getMessage()));
            }
            List<Finding> errors = fs.stream().filter(f -> f.severity() == Severity.ERROR).toList();
            if (!errors.isEmpty()) out.put(en.getKey(), errors);
        }
        return out;
    }

    /**
     * Writes and removals, all or nothing: each write through the import journal (atomic, prior bytes kept), removals
     * last; any failure puts back what was removed and rolls the journal back. Then the Pipeline history snapshot, and
     * the live registry re-read as an import leaves it.
     */
    private static Object write(ApiContext api, Path root, String space, SpacePromotion.ChangeSet cs,
                                List<SpacePromotion.Change> applied, Map<String, byte[]> toWrite, boolean removals)
            throws IOException {
        ImportJournal journal = new ImportJournal();
        Map<Path, byte[]> removed = new LinkedHashMap<>();
        List<String> written = new ArrayList<>(), removedPaths = new ArrayList<>();
        try {
            for (Map.Entry<String, byte[]> en : toWrite.entrySet()) {
                journal.write(root.resolve(en.getKey()), en.getValue(), ".promote-");
                written.add(en.getKey());
            }
            for (SpacePromotion.Change c : applied) {
                if (!"remove".equals(c.action())) continue;
                Path f = root.resolve(c.path());
                removed.put(f, Files.readAllBytes(f));
                Files.delete(f);
                removedPaths.add(c.path());
            }
        } catch (IOException | RuntimeException failed) {
            for (Map.Entry<Path, byte[]> back : removed.entrySet()) {
                try {
                    AtomicFiles.write(back.getKey(), back.getValue(), ".promote-undo-");
                } catch (IOException undo) {
                    failed.addSuppressed(undo);
                }
            }
            try {
                journal.rollback();
            } catch (IOException undo) {
                failed.addSuppressed(undo);
            }
            throw failed;
        }
        var service = HostContext.of(api).service();
        for (String p : written) {
            Path f = root.resolve(p);
            if (p.endsWith("_pipeline.toon")) PipelineHistory.record(root, f);   // as every Pipeline save
            if (SpacePromotion.isConnection(p)) service.registerConnection(ConnectionProfile.load(f));
        }
        for (Map.Entry<Path, byte[]> gone : removed.entrySet()) {
            String p = root.relativize(gone.getKey()).toString().replace('\\', '/');
            if (p.endsWith("_pipeline.toon")) service.unregisterPipeline(gone.getKey());
            if ("job".equals(SpacePromotion.kindOf(p))) removeJob(root, service, gone.getValue());
            if (SpacePromotion.isConnection(p)) {
                try {
                    Map<String, Object> doc = ConfigCodec.toMap(new String(gone.getValue(), StandardCharsets.UTF_8));
                    Object block = doc.get("connection") instanceof Map<?, ?> c ? c : doc;
                    if (block instanceof Map<?, ?> b && b.get("id") != null) service.unregisterConnection(String.valueOf(b.get("id")));
                } catch (RuntimeException unparseable) {
                    // compute() excluded a connection it could not read, so this does not happen
                }
            }
        }
        service.refreshConfigs();
        return result(space, cs, written, removedPaths, removals);
    }

    /**
     * A removed Job stops now, as after {@code DELETE /jobs/{name}}: dropped from the running JobService, its publication
     * and attachment approvals forgotten (a re-created Job needs new ones). An added or changed Job is read at the next
     * restart, as after {@code POST /import}.
     */
    private static void removeJob(Path root, com.gamma.service.CollectorService service, byte[] removed) throws IOException {
        Map<String, Object> doc;
        try {
            doc = ConfigCodec.toMap(new String(removed, StandardCharsets.UTF_8));
        } catch (RuntimeException unparseable) {
            return;   // the loader never ran a Job it could not read
        }
        String name = JobWriteGuard.name(doc);
        if (name == null || name.isBlank()) return;
        service.jobService().ifPresent(js -> js.removeJob(name));
        com.gamma.job.PublicationApproval.forget(root, name);
        JobWriteGuard.revokeOnDelete(JobRoutes.KIND, null, doc, root);
    }

    private static Map<String, Object> result(String space, SpacePromotion.ChangeSet cs, List<String> written,
                                              List<String> removed, boolean removals) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("space", space);
        out.put("applied", true);
        out.put("changeSetHash", cs.hash());
        out.put("written", written);
        out.put("removed", removed);
        out.put("removalsSkipped", removals ? List.of() : cs.of("remove").stream().map(SpacePromotion.Change::path).toList());
        return out;
    }
}
