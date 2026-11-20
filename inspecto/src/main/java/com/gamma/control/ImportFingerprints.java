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
 *       ledger holds, a file name the import took with other bytes, or the bytes of an earlier waiting file or of the
 *       upload, is set aside in {@code refused/} with its reason, as a file of the wrong columns is
 *       ({@link #refuseDuplicates}, the pre-run step {@value #STEP}).</li>
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

    /**
     * The import Pipeline's duplicate block: the bytes under any name ({@code DuplicatePolicy.Mode.CONTENT}); a name the
     * import took with other bytes is never read over the old one ({@code on_change: ignore} in the engine, and set aside
     * by {@link #refuseDuplicates}). Both are the Pipeline's own settings, so they can be changed there.
     */
    static Map<String, Object> duplicate() {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("mode", "content");
        d.put("algorithm", "SHA256");
        d.put("on_change", "ignore");
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

    /**
     * The fingerprint of the import with these bytes (sha256, hex), or null. Asked whatever the Pipeline's duplicate mode
     * (operator 2026-11-20): an import left on {@code path} still answers for the uploads Add data recorded
     * ({@link #recordUpload}) and the fingerprints seeded at start.
     */
    static LedgerEntry imported(PipelineConfig cfg, String sha256) {
        if (sha256 == null) return null;
        return ledger().findByChecksum(source(cfg), sha256).orElse(null);
    }

    /** Whether a waiting file under a name the import took with other bytes is set aside: on_change ignore, or no hashing. */
    private static boolean refusesRenamedContent(PipelineConfig cfg) {
        PipelineConfig.Duplicate d = cfg.collector().duplicate();
        return !checked(cfg) || DuplicatePolicy.OnChange.from(d.onChange()) == DuplicatePolicy.OnChange.IGNORE;
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
     * hashed the landed copy, which differs when a new column was left out of it; a Pipeline that does not hash recorded
     * nothing, so the fingerprint is recorded here.
     */
    static void recordUpload(PipelineConfig cfg, Path landed, String sha256) {
        if (landed == null || sha256 == null) return;
        String key = FileNames.safe(cfg, landed.getFileName().toString());
        LedgerEntry e = ledger().find(source(cfg), key).orElse(null);
        if (e == null)
            ledger().record(new LedgerEntry(source(cfg), key, key, 0L, sha256, null, null, 0L, System.currentTimeMillis(),
                    LedgerEntry.PROCESSED));
        else if (!sha256.equals(e.checksum()))
            ledger().record(new LedgerEntry(e.sourceId(), e.relativePath(), e.name(), e.size(), sha256,
                    e.etag(), e.version(), e.lastModified(), e.processedAt(), e.status()));
    }

    // ── the pre-run step ────────────────────────────────────────────────────────────────────────────────────────

    /**
     * The key two files' outputs collide on: the engine's name for a file's output ({@link FileNames#memberStem}: the stem
     * without folder or extension, plus a hash of the file's path when {@code processing.output_per_member} is on),
     * case-folded as its own batch guard folds it ({@link FileNames#stemKey}). So by default {@code feed.csv},
     * {@code later/Feed.csv} and {@code FEED.CSV} are one name; with {@code output_per_member} only the same path is.
     */
    static String nameKey(PipelineConfig cfg, String fileName, String pollRelativePath) {
        return FileNames.stemKey(FileNames.memberStem(cfg, fileName, pollRelativePath));
    }

    /** {@link #nameKey} of a fingerprint (its name and path are already in their safe form: not made safe twice). */
    private static String nameKey(PipelineConfig cfg, LedgerEntry e) {
        return cfg.outputPerMember() ? nameKey(cfg, e.name(), e.relativePath())
                : FileNames.stemKey(FileNames.outputStemOfSafe(e.name()));
    }

    /**
     * Step 50, before the columns are judged, against what earlier runs took: set aside in {@code refused/} every waiting
     * file (of the run's {@code only}, when narrowed) whose bytes the import already took, or whose NAME ({@link #nameKey})
     * it took with other bytes (unless the Pipeline says {@code on_change} other than {@code ignore}: then the engine reads
     * the new bytes) - recorded as a refused arrival with the reason. Also in a <i>Replace everything</i> run, for its WAITING
     * files: a failed replacement is undone for the upload's own Consignment only, so a waiting file read beside the held rows
     * would stay loaded next to the restored original. The upload itself is not judged here (it is not waiting; its
     * fingerprints are forgotten by {@link #forgetAll}, which runs AFTER this step). The files of THIS run are
     * {@link #refuseTwins}'s, once every other step has judged them.
     */
    static void refuseDuplicates(ImportPreRunSteps.Run r) throws IOException {
        PipelineConfig cfg = r.space.service().configFor(r.pipeline()).orElse(null);
        if (cfg == null) return;
        Map<String, LedgerEntry> named = new HashMap<>();
        if (refusesRenamedContent(cfg))
            for (LedgerEntry e : ledger().entries(source(cfg))) named.merge(nameKey(cfg, e), e, (x, y) -> x.processedAt() >= y.processedAt() ? x : y);
        Path dataRoot = r.space.dataRoot();
        Path in = OnboardImport.inbox(dataRoot, r.pipeline());
        List<Map<String, Object>> refused = new ArrayList<>(r.refused);
        for (Path f : waiting(r, in)) {
            String sha = DatasetArrivals.sha256(f);
            if (sha == null) continue;   // unreadable: left for the run, which sets it aside as before
            String name = f.getFileName().toString();
            LedgerEntry first = ledger().findByChecksum(source(cfg), sha).orElse(null);
            LedgerEntry took = first == null ? named.get(nameKey(cfg, name, rel(in, f))) : null;
            if (first == null && took == null) continue;
            String reason = first != null
                    ? "the same content was already imported on " + day(first) + " as '" + shown(first) + "'; its rows are in '"
                            + r.id + "', so adding it again would count them twice"
                            + (r.job.replace ? " (this Replace everything run replaces them; send it again after the replace, or rename it)" : "")
                    : "a file named '" + shown(took) + "' was already imported" + (took.processedAt() > 0 ? " on " + day(took) : "")
                            + (took.checksum() != null ? " with other content" : "")
                            + "; '" + name + "' is not read over it - rename it to import it";
            setAside(r, refused, dataRoot, f, name, reason);
        }
        r.refused = refused;
    }

    /** The pre-run step that judges the run's own files against each other, after every other step that refuses. */
    static final String TWINS_STEP = "import.refuse_twins";

    /**
     * Step 150, after the columns are judged: of the waiting files the run will take (those still in the inbox, every
     * earlier step passed), a file is set aside when an earlier one of them - or the upload being added - has its bytes,
     * or its name ({@link #nameKey}; the engine would write it over the first one's rows; not when the Pipeline says
     * {@code on_change} other than {@code ignore}, the person's choice). A file counts as taken only once it passed both,
     * so a refused file never makes another one a "twin".
     */
    static void refuseTwins(ImportPreRunSteps.Run r) throws IOException {
        PipelineConfig cfg = r.space.service().configFor(r.pipeline()).orElse(null);
        if (cfg == null) return;
        boolean byName = refusesRenamedContent(cfg);
        Path dataRoot = r.space.dataRoot();
        Path in = OnboardImport.inbox(dataRoot, r.pipeline());
        Map<String, String> bytes = new HashMap<>();   // sha256 → the file this run takes with it
        if (r.job.sha256 != null && r.upload != null) bytes.put(r.job.sha256, r.upload.name());
        Map<String, String> names = new HashMap<>();   // name key → the waiting file this run takes
        List<Map<String, Object>> refused = new ArrayList<>(r.refused);
        for (Path f : waiting(r, in)) {
            String name = f.getFileName().toString(), rel = rel(in, f);
            String sha = DatasetArrivals.sha256(f);
            String key = nameKey(cfg, name, rel);
            String twin = sha == null ? null : bytes.get(sha);
            String sameName = twin == null && byName ? names.get(key) : null;
            if (twin == null && sameName == null) {
                if (sha != null) bytes.put(sha, rel);   // taken: it passed every refusing step
                names.put(key, rel);
                continue;
            }
            String reason = twin != null ? "it has the same content as '" + twin + "', which this run takes"
                    : "'" + sameName + "' has the same file name and this run takes it; one file name holds one file's rows,"
                            + " so '" + rel + "' is not read over it - rename it to import it";
            if (setAside(r, refused, dataRoot, f, name, reason)) {
                Path abs = f.toAbsolutePath().normalize();
                r.taken.removeIf(t -> t.file().toAbsolutePath().normalize().equals(abs));   // its new columns are not kept for it
            }
        }
        r.refused = refused;
    }

    private static String rel(Path in, Path f) {
        return in.relativize(f).toString().replace(java.io.File.separatorChar, '/');
    }

    /** The run's waiting files, oldest first, narrowed to its {@code only}. */
    private static List<Path> waiting(ImportPreRunSteps.Run r, Path in) throws IOException {
        List<Path> out = new ArrayList<>();
        for (Path f : DatasetFeed.waiting(in, "." + OnboardImport.extension(r.type)))
            if (r.only == null || r.only.contains(f.toAbsolutePath().normalize())) out.add(f);
        return out;
    }

    /** Move {@code f} to {@code refused/} with {@code reason}, listed in {@code refused}; false when it cannot be moved. */
    private static boolean setAside(ImportPreRunSteps.Run r, List<Map<String, Object>> refused, Path dataRoot, Path f, String name,
                                    String reason) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("file", name);
        out.put("reason", reason);
        try {
            out.put("setAside", OnboardImport.setAsideRefused(dataRoot, r.pipeline(), f, name, reason));
            refused.add(out);
            return true;
        } catch (IOException cannotMove) {
            return false;   // left in the inbox: the engine skips a duplicate as before
        }
    }

    // ── forgetting the fingerprints of rows set aside ───────────────────────────────────────────────────────────────

    /**
     * <i>Replace everything</i>, and a new import: forget every fingerprint of the import, so the same bytes may be loaded
     * again (the upload's own, as the replacement); the job's undo records them again. Replace everything's waiting files are
     * judged against the fingerprints BEFORE this runs ({@link #refuseDuplicates}).
     */
    static void forgetAll(OnboardImport.Job job, PipelineConfig cfg) {
        forget(job, cfg, ledger().entries(source(cfg)));
    }

    /**
     * <i>Replace the period</i>: forget the fingerprints of the arrivals whose rows the Dataset no longer holds - every
     * output Parquet file of the arrival is gone (moved aside by the replacement) and no compaction merged it. An arrival
     * with rows left keeps its fingerprint: sending it again would count those rows twice. A fingerprint stands for the
     * NEWEST arrival under its name (the ledger keeps one row per name, the latest take's), so only that arrival is judged:
     * an older arrival of a reused name losing its rows never forgets a newer one that still has them.
     */
    static void forgetGone(OnboardImport.Job job, PipelineConfig cfg) {
        Path dataRoot = job.space.dataRoot();
        Path base = dataRoot.resolve(job.id).toAbsolutePath().normalize();
        Map<String, com.gamma.job.ArrivalCompactor.Segment> merged = com.gamma.job.ArrivalCompactor.merged(dataRoot, job.id);
        Map<String, Boolean> newestGone = new HashMap<>();   // the ledger's spelling of a name → its newest arrival lost its rows
        for (Map<String, String> r : job.space.service().statusStore().files(cfg)) {   // oldest first: the newest wins
            String paths = r.getOrDefault("output_paths", "");
            boolean held = paths.isBlank();   // wrote nothing: no rows to have lost
            for (String p : paths.split(";")) {
                if (p.isBlank()) continue;
                Path o = Path.of(p.trim()).toAbsolutePath().normalize();
                if (!PathJail.contains(base, o)) continue;
                String rel = base.resolve("database").relativize(o).toString().replace(java.io.File.separatorChar, '/');
                held |= Files.isRegularFile(o) || merged.containsKey(rel);
            }
            newestGone.put(FileNames.safe(cfg, String.valueOf(r.get("filename"))), !held);
        }
        List<LedgerEntry> forget = new ArrayList<>();
        for (LedgerEntry e : ledger().entries(source(cfg)))
            if (Boolean.TRUE.equals(newestGone.get(e.name()))) forget.add(e);
        forget(job, cfg, forget);
    }

    /**
     * The landed upload's fingerprint, forgotten: its rows are not in the Dataset - the run imported nothing (a 422 after
     * the engine may have committed it), or a replace or period run that is undone takes them out again.
     */
    static void forgetLanded(OnboardImport.Job job, PipelineConfig cfg) {
        if (job.landed == null) return;
        ledger().forget(source(cfg), FileNames.safe(cfg, job.landed.getFileName().toString()));
    }

    private static void forget(OnboardImport.Job job, PipelineConfig cfg, List<LedgerEntry> entries) {
        if (entries.isEmpty()) return;
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
     * logged: that import stays on {@code path}, and Add a file and the pre-run step still ask the ledger); then an import
     * the ledger holds no fingerprint of is seeded - each upload's sha256 from its arrivals sidecar whose rows no later
     * <i>Replace everything</i> set aside, and, by NAME only (no hash was ever kept), every file its old processed markers
     * name, so a file the path check held back is set aside as already imported instead of read. Idempotent: a seeded
     * import has fingerprints, so it is not seeded again.
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
            try {
                DatasetFeed.rewritePipeline(api, new DatasetFeed.Import(writeRoot, dataRoot, service, id, content, file),
                        "the import's duplicate check moves onto the fingerprint ledger", p -> {
                            @SuppressWarnings("unchecked")
                            Map<String, Object> collector = p.get("collector") instanceof Map<?, ?> m
                                    ? new LinkedHashMap<>((Map<String, Object>) m) : new LinkedHashMap<>();
                            collector.put("duplicate", duplicate());
                            p.put("collector", collector);
                        }, null);
                log.info("Dataset '{}': its import refuses the same bytes by the fingerprint ledger (B3)", id);
            } catch (IOException | RuntimeException refused) {
                log.warn("Dataset '{}': its Pipeline stays on the path check ({}); Add data still refuses by the fingerprint ledger",
                        id, refused.getMessage());
            }
            cfg = service.configFor(id).orElse(null);
            if (cfg == null) return;
        }
        if (!ledger().entries(source(cfg)).isEmpty()) return;
        Map<String, Map<String, Object>> held = new LinkedHashMap<>();   // landed name → its upload line
        long replacedBefore = 0L;   // the last Replace everything: the rows of files taken before it were set aside
        for (Map<String, Object> m : DatasetArrivals.sidecarLines(dataRoot, id)) {
            if ("system".equals(m.get("sender")) || DatasetArrivals.REFUSED.equals(m.get("status"))) continue;
            if ("replace".equals(m.get("mode"))) {
                held.clear();   // a Replace everything set the earlier rows aside
                try {
                    replacedBefore = Math.max(replacedBefore, java.time.Instant.parse(String.valueOf(m.get("at"))).toEpochMilli());
                } catch (RuntimeException noInstant) {
                    // an undated line: the markers are judged as before
                }
            }
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
        // the replace line is stamped after its run, and that run's markers were written before it: the files that run (or a
        // later one) took are not "before the replace", whatever their marker's time says
        Set<String> takenSince = new java.util.HashSet<>();
        for (Map<String, Object> m : DatasetArrivals.sidecarLines(dataRoot, id)) {
            if (DatasetArrivals.REFUSED.equals(m.get("status")) || !(m.get("landed") instanceof String landed)) continue;
            try {
                if (java.time.Instant.parse(String.valueOf(m.get("at"))).toEpochMilli() >= replacedBefore) takenSince.add(landed);
            } catch (RuntimeException noInstant) {
                // an undated line: judged by its marker's time
            }
        }
        int named = seedMarkers(cfg, held.keySet(), replacedBefore, takenSince);
        if (!held.isEmpty() || named > 0)
            log.info("Dataset '{}': {} fingerprint(s) seeded from its arrivals, {} name(s) from its markers (B3)", id, held.size(), named);
    }

    /**
     * A name-only fingerprint (no checksum: the path check never kept one) for every file the import's processed markers
     * name, but the {@code seeded} ones and those written before {@code replacedBefore} (the last Replace everything set
     * their rows aside, the markers folder is not moved with them) unless the replace's run or a later one took that file
     * ({@code takenSince}, by file name); answers how many. Recorded at the marker's time.
     */
    private static int seedMarkers(PipelineConfig cfg, Set<String> seeded, long replacedBefore, Set<String> takenSince)
            throws IOException {
        String markers = cfg.dirs().markers();
        if (markers == null || markers.isBlank()) return 0;
        Path root = Path.of(markers).toAbsolutePath().normalize();
        if (!Files.isDirectory(root)) return 0;
        String ext = cfg.processing().markerExtension();
        List<Path> found;
        try (var w = Files.walk(root)) {
            found = w.filter(Files::isRegularFile).filter(p -> {
                String n = p.getFileName().toString();
                return n.endsWith(ext) && !n.endsWith(".logical" + ext) && n.length() > ext.length();
            }).toList();
        }
        int n = 0;
        for (Path m : found) {
            String rel = root.relativize(m).toString().replace(java.io.File.separatorChar, '/');
            rel = rel.substring(0, rel.length() - ext.length());
            long at = Files.getLastModifiedTime(m).toMillis();
            String fileName = rel.substring(rel.lastIndexOf('/') + 1);
            if (seeded.contains(rel) || at < replacedBefore && !takenSince.contains(fileName)) continue;
            String key = FileNames.safe(cfg, rel);
            ledger().record(new LedgerEntry(source(cfg), key, FileNames.safe(cfg, m.getFileName().toString()
                    .substring(0, m.getFileName().toString().length() - ext.length())), 0L, null, null, null, 0L,
                    at, LedgerEntry.PROCESSED));
            n++;
        }
        return n;
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
