package com.gamma.control;

import com.gamma.etl.EditionFeatures;
import com.gamma.job.ArrivalCompactor;
import com.gamma.onboard.ImportFollowOn;
import com.gamma.pipeline.ComponentRegistry;
import com.gamma.pipeline.ComponentStore;
import com.gamma.spi.auth.ApiException;
import com.gamma.spi.auth.ErrorCodes;
import com.gamma.spi.http.ApiContext;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * <b>Storage hygiene of an import Dataset</b> ({@code LA-DATASET-COMPACT-1}, plan {@code la-data-arrival-plan.md} P4,
 * D-5, D-7…D-10): the control plane's caller of the engine's arrival compaction ({@link ArrivalCompactor}, the
 * {@code compact} maintenance task's {@code mode: arrivals}, which owns the merge, the merge map {@code compaction.json},
 * the retention it applies, the crash heal and the registry marking since {@code BASIC-ELT-PROFILE-1} B6).
 * <ul>
 *   <li><b>Compaction</b> (every edition, D-5, D-7): once the Dataset holds more than {@link ArrivalCompactor#THRESHOLD}
 *       files, the import's own follow-on work (inside the person's run, before the index build) merges them into ONE file,
 *       ordered by arrival ({@link #afterImport}); {@code POST /datasets/{id}/compact} (<i>Compact now</i>) does it at any
 *       count.</li>
 *   <li><b>Retention</b> (Standard and up, D-9): the Dataset entry's {@code retention {days | arrivals}}, applied at
 *       compaction time. Set with {@code PUT /datasets/{id}/storage}.</li>
 * </ul>
 * <p>{@code LA-DATASET-COMPACT-2}: a commit of the import Pipeline outside Add data (a feed's scheduled run) compacts too,
 * before the Space's Jobs see it (the {@code import.compaction} step of {@link ImportCommitSteps}); and every compaction
 * tells the installed applications that the files were rewritten ({@link ImportFollowOn#filesRewritten}), so the link index
 * stops answering for removed rows at once. A maintenance Job {@code {task: compact, mode: arrivals}} takes the import's
 * claim ({@link OnboardImport#IN_FLIGHT}) through the {@link ArrivalCompactor.Claims} this class installs.
 */
final class DatasetCompaction {

    private DatasetCompaction() {}

    static void register(ApiContext api) {
        // ⚠ String LITERALS on purpose (CapabilityManifestTest's scanner).
        api.get("/datasets/([^/]+)/storage", ApiContext.withCapability("canUploadData", (e, m) -> storage(api, e, ApiContext.name(m))));
        api.put("/datasets/([^/]+)/storage", ApiContext.withCapability("canAuthorWorkbench",
                (e, m) -> setRetention(api, e, ApiContext.name(m), api.body(e))));
        api.post("/datasets/([^/]+)/compact", ApiContext.withCapability("canUploadData", (e, m) -> compactNow(api, e, ApiContext.name(m))));
        // BASIC-ELT-PROFILE-1 B6: a compact mode: arrivals Job claims the Dataset as an import does, keyed by the Space's
        // write root as the Job's thread (its Space MDC) resolves it - the way every import claim is keyed
        ArrivalCompactor.Claims claims = id -> {
            Path writeRoot = api.writeRoot();
            if (writeRoot == null) return () -> {};   // no Add data here: nothing else changes an import's files
            String key = writeRoot + "|" + id;
            return OnboardImport.IN_FLIGHT.add(key) ? () -> OnboardImport.IN_FLIGHT.remove(key) : null;
        };
        ArrivalCompactor.claims(claims);
        api.onClose(() -> ArrivalCompactor.forget(claims));
    }

    /**
     * The import's own follow-on step (D-7): compact once more than {@link ArrivalCompactor#THRESHOLD} files lie there.
     * Never throws: a compaction that fails leaves the files as they were and says so (the import itself succeeded).
     */
    static Map<String, Object> afterImport(Path writeRoot, Path dataRoot, String id, Map<String, Object> content, String actor) {
        try {
            return compact(writeRoot, dataRoot, id, content, false, actor);
        } catch (Exception failed) {
            return ArrivalCompactor.outcome("FAILED", -1, -1, 0, "the files were not compacted: " + failed.getMessage());
        }
    }

    /** {@link ArrivalCompactor#compact}, telling Add data's follow-ons ({@link OnboardImport#followOns}). The caller holds the import's claim. */
    static Map<String, Object> compact(Path writeRoot, Path dataRoot, String id, Map<String, Object> content, boolean force,
                                       String actor) throws Exception {
        return ArrivalCompactor.compact(dataRoot, id, content, force, OnboardImport.followOns(),
                new ImportFollowOn.Context(writeRoot, dataRoot, id, content, actor));
    }

    // ── routes ─────────────────────────────────────────────────────────────────────────────────────────────────

    /** {@code GET /datasets/{id}/storage}: the files, the last compaction and the retention policy. */
    static Object storage(ApiContext api, HttpExchange ex, String id) throws IOException {
        Path writeRoot = OnboardRoutes.requireSelfImport(api, "Dataset storage");
        Path dataRoot = OnboardRoutes.requireDataRoot(api);
        Map<String, Object> content = OnboardImport.importDataset(writeRoot, ex, id, HostContext.of(api).service());
        return describe(dataRoot, id, content);
    }

    static Map<String, Object> describe(Path dataRoot, String id, Map<String, Object> content) throws IOException {
        List<Path> files = ArrivalCompactor.parquetFiles(dataRoot.resolve(id).resolve("database").toAbsolutePath().normalize());
        long bytes = 0;
        for (Path p : files) bytes += Files.size(p);
        Map<String, Object> last = ArrivalCompactor.summary(dataRoot, id);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("dataset", id);
        out.put("files", files.size());
        out.put("bytes", bytes);
        out.put("threshold", ArrivalCompactor.THRESHOLD);
        out.put("compactedAt", last.get("compactedAt"));
        out.put("compactedArrivals", last.get("compactedArrivals"));
        out.put("removedArrivals", last.get("removedArrivals"));
        ArrivalCompactor.Retention r = ArrivalCompactor.retentionOf(content);
        out.put(ArrivalCompactor.RETENTION, r == null ? null : r.asMap());
        out.put("retentionAvailable", EditionFeatures.present(OnboardImport.AUTOMATION_FEATURE));
        return out;
    }

    /** {@code POST /datasets/{id}/compact}: <i>Compact now</i>, whatever the count; claimed like an import. */
    static Object compactNow(ApiContext api, HttpExchange ex, String id) throws IOException {
        Path writeRoot = OnboardRoutes.requireSelfImport(api, "Compact now");
        Path dataRoot = OnboardRoutes.requireDataRoot(api);
        Map<String, Object> content = OnboardImport.importDataset(writeRoot, ex, id, HostContext.of(api).service());
        String key = writeRoot + "|" + id;
        if (!OnboardImport.IN_FLIGHT.add(key))
            throw new ApiException(409, ErrorCodes.CONFLICT, "'" + id + "' is being imported or deleted right now; compact it when that has finished");
        try {
            Map<String, Object> o = compact(writeRoot, dataRoot, id, content, true, ApiContext.actor(ex));
            Map<String, Object> out = describe(dataRoot, id, content);
            out.put("compaction", o);
            return out;
        } catch (IOException | RuntimeException passOn) {
            throw passOn;
        } catch (Exception failed) {
            throw new ApiException(500, ErrorCodes.INTERNAL, "the files could not be compacted: " + failed.getMessage());
        } finally {
            OnboardImport.IN_FLIGHT.remove(key);
        }
    }

    /**
     * {@code PUT /datasets/{id}/storage {retention: {days: N} | {arrivals: N} | null}}: the retention policy, a Dataset
     * change (Standard and up, {@code canAuthorWorkbench}, an approval policy over Datasets refuses). It takes effect at
     * the next compaction.
     */
    static Object setRetention(ApiContext api, HttpExchange ex, String id, Map<String, Object> body) throws IOException {
        Path writeRoot = OnboardRoutes.requireSelfImport(api, "Retention");
        Path dataRoot = OnboardRoutes.requireDataRoot(api);
        Map<String, Object> current = OnboardImport.importDataset(writeRoot, ex, id, HostContext.of(api).service());
        if (!EditionFeatures.present(OnboardImport.AUTOMATION_FEATURE))
            throw new ApiException(503, ErrorCodes.CAPABILITY_UNAVAILABLE, "A retention policy: "
                    + EditionFeatures.refusal(OnboardImport.AUTOMATION_FEATURE) + "; every file stays, compacted");
        if (body == null || !body.containsKey(ArrivalCompactor.RETENTION))
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "state retention: {days: N}, {arrivals: N} or null");
        Object raw = body.get(ArrivalCompactor.RETENTION);
        ArrivalCompactor.Retention r = null;
        if (raw != null) {
            if (!(raw instanceof Map<?, ?> m) || m.size() != 1
                    || !(m.get("days") instanceof Number || m.get("arrivals") instanceof Number))
                throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "retention is {days: N} or {arrivals: N}");
            Object n = m.get("days") != null ? m.get("days") : m.get("arrivals");
            if (!(n instanceof Integer || n instanceof Long) || ((Number) n).longValue() < 1 || ((Number) n).longValue() > 100_000)
                throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "retention keeps a whole number from 1 to 100000");
            int v = ((Number) n).intValue();
            r = m.get("days") != null ? new ArrivalCompactor.Retention(v, null) : new ArrivalCompactor.Retention(null, v);
        }
        PendingChanges.holdRefusing(api, List.of("dataset"), "a retention policy is a Dataset change");
        ComponentStore store = new ComponentStore(writeRoot.resolve("registry"));
        Map<String, Object> content = new LinkedHashMap<>(current);
        content.remove("name");
        if (r == null) content.remove(ArrivalCompactor.RETENTION);
        else content.put(ArrivalCompactor.RETENTION, r.asMap());
        try {
            ComponentRoutes.validateKind(api, "dataset", id, content);
        } catch (IllegalArgumentException bad) {
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, bad.getMessage());
        }
        ComponentRegistry.Component written = store.write("dataset", id, content);
        return describe(dataRoot, id, written.content());
    }
}
