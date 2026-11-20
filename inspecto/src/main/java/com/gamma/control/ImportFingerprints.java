package com.gamma.control;

import com.gamma.acquire.AcquisitionLedger;
import com.gamma.acquire.AcquisitionLedgers;
import com.gamma.acquire.DuplicatePolicy;
import com.gamma.acquire.LedgerEntry;
import com.gamma.config.safety.PathJail;
import com.gamma.etl.FileNames;
import com.gamma.etl.PipelineConfig;
import com.gamma.event.EventLog;
import com.gamma.pipeline.ComponentRegistry;
import com.gamma.pipeline.ComponentStore;
import com.gamma.service.CollectorService;
import com.gamma.service.SpaceContext;
import com.gamma.spi.http.ApiContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * <b>An import's duplicate refusal, by content, from the engine's fingerprint ledger</b> ({@code BASIC-ELT-PROFILE-1} B3
 * part 2, operator 2026-11-20). An Add data import's Pipeline runs with {@code collector.duplicate: {mode: content}}
 * ({@link #DUPLICATE}): the engine records each file it takes in the {@link AcquisitionLedger} (durable by default on every
 * edition) and skips the same bytes under any name. On top of that the import refuses where the engine skips:
 * <ul>
 *   <li><b>Add a file</b> answers 409 for bytes the ledger holds ({@link #imported}); the upload's own bytes become its
 *       fingerprint ({@link #recordUpload}), also when a new column was left out of the landed copy.</li>
 *   <li><b>A waiting file</b> (Run now, <i>Watch this folder</i>, the files Add a file's run takes along) with bytes the
 *       ledger holds, or the bytes of an earlier waiting file or of the upload, is set aside in {@code refused/} with its
 *       reason, as a file of the wrong columns is ({@link #refuseDuplicates}, the pre-run step {@value #STEP}).</li>
 *   <li><b>Rows set aside are forgotten</b>: <i>Replace everything</i> forgets every fingerprint of the import
 *       ({@link #forgetAll}), <i>Replace the period</i> those of the arrivals whose rows the Dataset no longer holds
 *       ({@link #forgetGone}); a new import forgets what an earlier Dataset of the same id left; each is put back when the
 *       run is undone.</li>
 *   <li><b>At start</b> ({@link #migrate}): an import Pipeline still on the {@code path} default moves onto
 *       {@link #DUPLICATE}, and an import the ledger knows nothing of is seeded from its arrivals sidecar's hashes.</li>
 * </ul>
 */
final class ImportFingerprints {

    private ImportFingerprints() {}

    private static final Logger log = LoggerFactory.getLogger(ImportFingerprints.class);

    /** The pre-run step's name: before the columns are judged, so a duplicate is never judged or kept a column for. */
    static final String STEP = "import.refuse_duplicates";

    /** The import Pipeline's duplicate block: the bytes under any name ({@code DuplicatePolicy.Mode.CONTENT}). */
    static Map<String, Object> duplicate() {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("mode", "content");
        d.put("algorithm", "SHA256");
        return d;
    }

    /** Whether {@code cfg} records the bytes of what it takes (content, or a feed's checksum), so the ledger can be asked. */
    static boolean checked(PipelineConfig cfg) {
        PipelineConfig.Duplicate d = cfg.collector().duplicate();
        return cfg.processing().duplicateCheckEnabled() && DuplicatePolicy.Mode.from(d.mode()).hashes();
    }

    private static AcquisitionLedger ledger() {
        return AcquisitionLedgers.shared();
    }

    private static String source(PipelineConfig cfg) {
        return cfg.collector().id();
    }

    /** The fingerprint of the import with these bytes (sha256, hex), or null; null too when the import does not hash. */
    static LedgerEntry imported(PipelineConfig cfg, String sha256) {
        if (sha256 == null || !checked(cfg)) return null;
        return ledger().findByChecksum(source(cfg), sha256).orElse(null);
    }

    /** The name a person knows a fingerprinted file by: Add data's landed name without its stamp, else the name. */
    static String shown(LedgerEntry e) {
        String name = e.name() != null ? e.name() : e.relativePath();
        java.util.regex.Matcher added = DatasetArrivals.ADD_DATA_NAME.matcher(name);
        return added.matches() ? added.group(1) : name;
    }

    /** The server's local day a fingerprint was recorded, as the arrivals list shows it. */
    static String day(LedgerEntry e) {
        return DatasetNewColumns.localDay(java.time.Instant.ofEpochMilli(e.processedAt()).toString());
    }

    /**
     * After Add a file's run: the upload's bytes as uploaded ({@code sha256}) are the landed file's fingerprint - the engine
     * hashed the landed copy, which differs when a new column was left out of it.
     */
    static void recordUpload(PipelineConfig cfg, Path landed, String sha256) {
        if (landed == null || sha256 == null || !checked(cfg)) return;
        String key = FileNames.safe(cfg, landed.getFileName().toString());
        ledger().find(source(cfg), key).filter(e -> !sha256.equals(e.checksum()))
                .ifPresent(e -> ledger().record(new LedgerEntry(e.sourceId(), e.relativePath(), e.name(), e.size(), sha256,
                        e.etag(), e.version(), e.lastModified(), e.processedAt(), e.status())));
    }

    // ── the pre-run step ────────────────────────────────────────────────────────────────────────────────────────

    /**
     * Set aside in {@code refused/} every waiting file (of the run's {@code only}, when narrowed) whose bytes the import
     * already took, or that an earlier waiting file or the upload being added carries, recorded as a refused arrival with
     * the reason. Nothing for an import whose Pipeline does not hash.
     */
    static void refuseDuplicates(ImportPreRunSteps.Run r) throws IOException {
        PipelineConfig cfg = r.space.service().configFor(r.pipeline()).orElse(null);
        if (cfg == null || !checked(cfg)) return;
        Path dataRoot = r.space.dataRoot();
        Path in = OnboardImport.inbox(dataRoot, r.pipeline());
        Map<String, String> seen = new HashMap<>();   // sha256 → the file of this run that carries it
        if (r.job.sha256 != null && r.upload != null) seen.put(r.job.sha256, r.upload.name());
        List<Map<String, Object>> refused = new ArrayList<>(r.refused);
        for (Path f : DatasetFeed.waiting(in, "." + OnboardImport.extension(r.type))) {
            if (r.only != null && !r.only.contains(f.toAbsolutePath().normalize())) continue;
            String sha = DatasetArrivals.sha256(f);
            if (sha == null) continue;   // unreadable: left for the run, which sets it aside as before
            String name = f.getFileName().toString();
            LedgerEntry first = ledger().findByChecksum(source(cfg), sha).orElse(null);
            String twin = seen.putIfAbsent(sha, name);
            if (first == null && twin == null) continue;
            String reason = first != null
                    ? "the same content was already imported on " + day(first) + " as '" + shown(first) + "'; its rows are in '"
                            + r.id + "', so adding it again would count them twice"
                    : "it has the same content as '" + twin + "', which this run takes";
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("file", name);
            out.put("reason", reason);
            try {
                out.put("setAside", OnboardImport.setAsideRefused(dataRoot, r.pipeline(), f, name, reason));
                refused.add(out);
            } catch (IOException cannotMove) {
                // left in the inbox: the engine skips it as a duplicate
            }
        }
        r.refused = refused;
    }

    // ── forgetting the fingerprints of rows set aside ───────────────────────────────────────────────────────────────

    /**
     * <i>Replace everything</i>, and a new import: forget every fingerprint of the import, so the same bytes may be loaded
     * again; the job's undo records them again.
     */
    static void forgetAll(OnboardImport.Job job, PipelineConfig cfg) {
        forget(job, cfg, ledger().entries(source(cfg)));
    }

    /**
     * <i>Replace the period</i>: forget the fingerprints of the arrivals whose rows the Dataset no longer holds - every
     * output Parquet file of the arrival is gone (moved aside by the replacement) and no compaction merged it. An arrival
     * with rows left keeps its fingerprint: sending it again would count those rows twice.
     */
    static void forgetGone(OnboardImport.Job job, PipelineConfig cfg) {
        Path dataRoot = job.space.dataRoot();
        Path base = dataRoot.resolve(job.id).toAbsolutePath().normalize();
        Map<String, com.gamma.job.ArrivalCompactor.Segment> merged = com.gamma.job.ArrivalCompactor.merged(dataRoot, job.id);
        Set<String> gone = new java.util.HashSet<>();
        for (Map<String, String> r : job.space.service().statusStore().files(cfg)) {
            String paths = r.getOrDefault("output_paths", "");
            if (paths.isBlank()) continue;   // wrote nothing: no rows to have lost
            boolean held = false;
            for (String p : paths.split(";")) {
                if (p.isBlank()) continue;
                Path o = Path.of(p.trim()).toAbsolutePath().normalize();
                if (!PathJail.contains(base, o)) continue;
                String rel = base.resolve("database").relativize(o).toString().replace(java.io.File.separatorChar, '/');
                held |= Files.isRegularFile(o) || merged.containsKey(rel);
            }
            if (!held) gone.add(FileNames.safe(cfg, String.valueOf(r.get("filename"))));   // the ledger's spelling
        }
        if (gone.isEmpty()) return;
        List<LedgerEntry> forget = new ArrayList<>();
        for (LedgerEntry e : ledger().entries(source(cfg)))
            if (gone.contains(e.name()) || gone.contains(e.relativePath())) forget.add(e);
        forget(job, cfg, forget);
    }

    /**
     * The landed upload's fingerprint, forgotten: its rows are not in the Dataset - the run imported nothing (a 422 after
     * the engine may have committed it), or a replace or period run that is undone takes them out again.
     */
    static void forgetLanded(OnboardImport.Job job, PipelineConfig cfg) {
        if (job.landed == null || !checked(cfg)) return;
        ledger().forget(source(cfg), FileNames.safe(cfg, job.landed.getFileName().toString()));
    }

    private static void forget(OnboardImport.Job job, PipelineConfig cfg, List<LedgerEntry> entries) {
        if (entries.isEmpty() || !checked(cfg)) return;
        AcquisitionLedger ledger = ledger();
        for (LedgerEntry e : entries) ledger.forget(e.sourceId(), e.relativePath());
        job.undo.push(() -> {
            for (LedgerEntry e : entries) ledger.record(e);
        });
    }

    // ── at start ────────────────────────────────────────────────────────────────────────────────────────────────

    /**
     * Once per start, every Space: an import Pipeline still on the {@code path} default (made before B3 part 2) is moved
     * onto {@link #duplicate()} through the Pipeline write path (an approval policy over Pipelines or a refused save is
     * logged, and that import keeps refusing nothing by content); then an import the ledger holds no fingerprint of is
     * seeded from its arrivals sidecar - each upload's sha256 whose rows no later <i>Replace everything</i> set aside.
     * Idempotent: a seeded import has fingerprints, so it is not seeded again.
     */
    static void migrate(ApiContext api) {
        HostContext host = HostContext.of(api);
        if (host == null || host.spaces() == null) return;
        for (SpaceContext ctx : host.spaces().all()) {
            String prev = MDC.get(EventLog.SPACE_MDC_KEY);
            MDC.put(EventLog.SPACE_MDC_KEY, ctx.id().value());
            try {
                Path writeRoot = api.writeRoot(), dataRoot = api.dataRoot();
                if (writeRoot == null || dataRoot == null) continue;
                CollectorService service = ctx.service();
                for (ComponentRegistry.Component c : new ComponentStore(writeRoot.resolve("registry")).list("dataset")) {
                    String id = c.name();
                    Map<String, Object> content = c.content();
                    if (!OnboardImport.isOurs(writeRoot, id, content, service)) continue;
                    try {
                        migrate(api, writeRoot, dataRoot, service, id, content);
                    } catch (IOException | RuntimeException e) {
                        log.warn("Dataset '{}': its duplicate check was not moved onto the fingerprint ledger: {}", id, e.getMessage());
                    }
                }
            } catch (RuntimeException e) {
                log.warn("Imports of Space {} were not moved onto the fingerprint ledger: {}", ctx.id().value(), e.toString());
            } finally {
                if (prev == null) MDC.remove(EventLog.SPACE_MDC_KEY);
                else MDC.put(EventLog.SPACE_MDC_KEY, prev);
            }
        }
    }

    private static void migrate(ApiContext api, Path writeRoot, Path dataRoot, CollectorService service, String id,
                                Map<String, Object> content) throws IOException {
        PipelineConfig cfg = service.configFor(id).orElse(null);
        if (cfg == null) return;
        if (DuplicatePolicy.Mode.from(cfg.collector().duplicate().mode()) == DuplicatePolicy.Mode.PATH) {
            Path file = writeRoot.resolve(OnboardImport.CONFIG_DIR).resolve(id + "_pipeline.toon").toAbsolutePath().normalize();
            DatasetFeed.rewritePipeline(api, new DatasetFeed.Import(writeRoot, dataRoot, service, id, content, file),
                    "the import's duplicate check moves onto the fingerprint ledger", p -> {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> collector = p.get("collector") instanceof Map<?, ?> m
                                ? new LinkedHashMap<>((Map<String, Object>) m) : new LinkedHashMap<>();
                        collector.put("duplicate", duplicate());
                        p.put("collector", collector);
                    }, null);
            cfg = service.configFor(id).orElse(null);
            if (cfg == null) return;
            log.info("Dataset '{}': its import refuses the same bytes by the fingerprint ledger (B3)", id);
        }
        if (!checked(cfg) || !ledger().entries(source(cfg)).isEmpty()) return;
        Map<String, Map<String, Object>> held = new LinkedHashMap<>();   // landed name → its upload line
        for (Map<String, Object> m : DatasetArrivals.sidecarLines(dataRoot, id)) {
            if ("system".equals(m.get("sender")) || DatasetArrivals.REFUSED.equals(m.get("status"))) continue;
            if ("replace".equals(m.get("mode"))) held.clear();   // a Replace everything set the earlier rows aside
            if (m.get("sha256") instanceof String sha && !sha.isBlank() && m.get("landed") instanceof String landed)
                held.put(landed, m);
        }
        for (Map.Entry<String, Map<String, Object>> e : held.entrySet()) {
            String key = FileNames.safe(cfg, e.getKey());
            long at;
            try {
                at = java.time.Instant.parse(String.valueOf(e.getValue().get("at"))).toEpochMilli();
            } catch (RuntimeException noInstant) {
                at = 0L;
            }
            ledger().record(new LedgerEntry(source(cfg), key, key, 0L, String.valueOf(e.getValue().get("sha256")),
                    null, null, 0L, at, LedgerEntry.PROCESSED));
        }
        if (!held.isEmpty()) log.info("Dataset '{}': {} fingerprint(s) seeded from its arrivals (B3)", id, held.size());
    }

    /**
     * {@code GET /datasets/{id}/feed}'s {@code alreadyImported} for one waiting file: the import took a file under this name
     * with these bytes, so the next run sets it aside. Hashed only when the ledger holds the name at the same size; a
     * re-send under a new name shows as waiting until the run refuses it.
     */
    static boolean alreadyImported(PipelineConfig cfg, Path inbox, Path file) throws IOException {
        String rel = inbox.relativize(file).toString().replace('\\', '/');
        LedgerEntry prior = ledger().find(source(cfg), FileNames.safe(cfg, rel)).orElse(null);
        if (prior == null || prior.checksum() == null || prior.size() != Files.size(file)) return false;
        return prior.checksum().equals(DatasetArrivals.sha256(file));
    }
}
