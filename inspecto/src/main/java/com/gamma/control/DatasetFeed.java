package com.gamma.control;

import com.gamma.config.io.ConfigCodec;
import com.gamma.config.spec.Finding;
import com.gamma.config.spec.Severity;
import com.gamma.etl.EditionFeatures;
import com.gamma.etl.MarkerManager;
import com.gamma.etl.PipelineConfig;
import com.gamma.pipeline.PipelineTrigger;
import com.gamma.service.CollectorService;
import com.gamma.spi.auth.ApiException;
import com.gamma.spi.auth.ErrorCodes;
import com.gamma.spi.http.ApiContext;
import com.gamma.util.AtomicFiles;
import com.gamma.util.ToonHelper;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * <b>How files reach an import Dataset</b> ({@code LA-ARRIVAL-FEEDS-1}, plan {@code la-data-arrival-plan.md} P3, D-1):
 * <ul>
 *   <li>{@code GET /datasets/{id}/feed} - the Dataset's inbox (its absolute path on the server, the files waiting there,
 *       and which of them the import already took under the same name, so Run now skips them)
 *       and its feed: {@code uploads} (a person adds files; the Pipeline runs only when asked), {@code folder} (the inbox
 *       is watched) or {@code sftp} (a Connection is read). Every edition: on Basic the files waiting are imported with
 *       <i>Run now</i>, or by <i>Watch this folder</i> ({@link InboxWatch}, {@code BASIC-ONBOARD-1} P2: {@code watch},
 *       {@code watchError}).</li>
 *   <li>{@code PUT /datasets/{id}/feed} {@code {kind: folder|sftp, connection?, every?, readyMarker?}} - <b>Keep this up to
 *       date</b> (Standard and above, {@code pipeline.automation}): rewrites the import's own Pipeline to a scheduled poll
 *       with the safe defaults - a stability gate, an optional ready marker, a checksum duplicate policy that skips a
 *       re-sent file, the quarantine it already has. The answer carries {@code indexJob}, the {@code la.index.build} Job
 *       that keeps the graph up to date, for the client to save through {@code POST /jobs} (its own gates).</li>
 *   <li>{@code DELETE /datasets/{id}/feed} - back to uploads (manual) in every edition.</li>
 * </ul>
 * Gates as Add a file: {@code canUploadData}, write root 503, {@code data.self_import} 403, the entry must be an import
 * Add data wrote for THIS id ({@link OnboardImport#importDataset}: 404 / 409). The feed is read from the Pipeline itself,
 * never stored twice.
 */
final class DatasetFeed {

    private DatasetFeed() {}

    /** The poll interval when the body names none, and its bounds. */
    static final String DEFAULT_EVERY = "15m";
    static final long MIN_EVERY_MS = 60_000L, MAX_EVERY_MS = 86_400_000L;
    /** At most this many waiting files are named; {@code waiting} still counts all of them. */
    static final int LISTED = 50;
    private static final Pattern EVERY = Pattern.compile("[1-9][0-9]{0,5}[smhd]");
    private static final Pattern MARKER = Pattern.compile("[A-Za-z0-9._{}-]{1,40}");
    private static final Pattern CONNECTION = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_.-]{0,99}");

    static void register(ApiContext api) {
        // ⚠ String LITERALS on purpose (CapabilityManifestTest's scanner).
        api.get("/datasets/([^/]+)/feed", ApiContext.withCapability("canUploadData", (e, m) -> read(api, e, ApiContext.name(m))));
        api.put("/datasets/([^/]+)/feed", ApiContext.withCapability("canUploadData",
                (e, m) -> keepUpToDate(api, e, ApiContext.name(m), api.body(e))));
        api.delete("/datasets/([^/]+)/feed", ApiContext.withCapability("canUploadData", (e, m) -> stop(api, e, ApiContext.name(m))));
    }

    /** The import behind {@code id}, after the family's gates. */
    record Import(Path writeRoot, Path dataRoot, CollectorService service, String id, Map<String, Object> content,
                          Path pipelineFile) {}

    static Import gate(ApiContext api, HttpExchange ex, String id, String what) {
        Path writeRoot = OnboardRoutes.requireSelfImport(api, what);
        Path dataRoot = OnboardRoutes.requireDataRoot(api);
        CollectorService service = HostContext.of(api).service();
        Map<String, Object> content = OnboardImport.importDataset(writeRoot, ex, id, service);
        Path file = writeRoot.resolve(OnboardImport.CONFIG_DIR).resolve(id + "_pipeline.toon").toAbsolutePath().normalize();
        return new Import(writeRoot, dataRoot, service, id, content, file);
    }

    // ── GET ───────────────────────────────────────────────────────────────────────────────────────────────────

    static Object read(ApiContext api, HttpExchange ex, String id) throws IOException {
        Import imp = gate(api, ex, id, "Data feed");
        return describe(imp);
    }

    private static Map<String, Object> describe(Import imp) throws IOException {
        PipelineConfig cfg = imp.service().configFor(imp.id())
                .orElseThrow(() -> new ApiException(409, ErrorCodes.CONFLICT, "the import's Pipeline '" + imp.id() + "' is not registered"));
        Path inbox = OnboardImport.inbox(imp.dataRoot(), imp.id()).toAbsolutePath().normalize();
        String ext = "." + OnboardImport.extension(String.valueOf(imp.content().get("importFormat")));
        List<Path> waiting = waiting(inbox, ext);
        List<Map<String, Object>> files = new ArrayList<>();
        int alreadyImported = 0;
        for (int i = 0; i < waiting.size(); i++) {
            Path p = waiting.get(i);
            // the import's duplicate check skips a file whose name it took before: it would wait here for ever
            boolean taken = MarkerManager.isAlreadyProcessed(p.toFile(), cfg);
            if (taken) alreadyImported++;
            if (i >= LISTED) continue;
            Map<String, Object> f = new LinkedHashMap<>();
            f.put("name", inbox.relativize(p).toString().replace('\\', '/'));
            f.put("size", Files.size(p));
            f.put("modified", Files.getLastModifiedTime(p).toInstant().toString());
            f.put("alreadyImported", taken);
            files.add(f);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("dataset", imp.id());
        out.put("inbox", inbox.toString());
        out.put("waiting", waiting.size());
        out.put("alreadyImported", alreadyImported);
        out.put("files", files);
        out.put("truncated", waiting.size() > LISTED);
        out.put("automatic", EditionFeatures.present(OnboardImport.AUTOMATION_FEATURE));
        out.put("feed", feedOf(cfg));
        watch(out, cfg, imp.dataRoot(), imp.id(), imp.content());   // BASIC-ONBOARD-1 P2
        // the toggle is offered: not a Reference (it is replaced, a watched inbox adds rows), no schedule runs the Pipeline
        out.put("watchable", (InboxWatch.snapshot(imp.content()) || !"reference".equals(imp.content().get("kind")))
                && PipelineTrigger.of(cfg.triggerConfig()).scheduler() == PipelineTrigger.Scheduler.MANUAL);
        return out;
    }

    /**
     * The feed for the arrivals answer (one read for every Data status line): how files come and how many wait - never
     * the inbox path, which {@code GET /datasets/{id}/feed} shows under {@code canUploadData}.
     */
    static Map<String, Object> summary(PipelineConfig cfg, Path dataRoot, String id, Map<String, Object> content) {
        Map<String, Object> f = new LinkedHashMap<>(feedOf(cfg));
        watch(f, cfg, dataRoot, id, content);
        try {
            Path inbox = OnboardImport.inbox(dataRoot, id).toAbsolutePath().normalize();
            List<Path> waiting = waiting(inbox, "." + OnboardImport.extension(String.valueOf(content.get("importFormat"))));
            int taken = 0;
            for (Path p : waiting) if (MarkerManager.isAlreadyProcessed(p.toFile(), cfg)) taken++;
            f.put("waiting", waiting.size());
            f.put("alreadyImported", taken);
        } catch (IOException unreadable) {
            f.put("waiting", null);
        }
        return f;
    }

    /** {@code watch} (the inbox is polled every 30 s) and, after a failed import by the poll, {@code watchError}. */
    private static void watch(Map<String, Object> out, PipelineConfig cfg, Path dataRoot, String id, Map<String, Object> content) {
        boolean on = InboxWatch.watched(content, cfg);
        out.put("watch", on);
        if (InboxWatch.snapshot(content)) out.put("snapshot", true);   // the poll refreshes the table, not an inbox
        String error = on ? InboxWatch.lastError(dataRoot, id) : null;
        if (error != null) out.put("watchError", error);
    }

    /** The import's own files in its inbox, oldest first: never a hidden file (a landing upload, a marker). */
    static List<Path> waiting(Path inbox, String ext) throws IOException {
        if (!Files.isDirectory(inbox)) return List.of();
        String lower = ext.toLowerCase(Locale.ROOT);
        try (Stream<Path> s = Files.walk(inbox, 4)) {
            return s.filter(p -> Files.isRegularFile(p, java.nio.file.LinkOption.NOFOLLOW_LINKS))
                    .filter(p -> !p.getFileName().toString().startsWith("."))
                    .filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(lower))
                    .sorted(Comparator.comparing((Path p) -> {
                        try {
                            return Files.getLastModifiedTime(p).toMillis();
                        } catch (IOException gone) {
                            return Long.MAX_VALUE;
                        }
                    }).thenComparing(Path::toString))
                    .toList();
        }
    }

    /** {@code {kind, every?, connection?, readyMarker?}} as the Pipeline says now. */
    static Map<String, Object> feedOf(PipelineConfig cfg) {
        PipelineTrigger t = PipelineTrigger.of(cfg.triggerConfig());
        PipelineConfig.Collector c = cfg.collector();
        Map<String, Object> f = new LinkedHashMap<>();
        if (t.scheduler() == PipelineTrigger.Scheduler.MANUAL) {
            f.put("kind", "uploads");
            return f;
        }
        f.put("kind", "local".equals(c.connector()) ? "folder" : c.connector());
        if (t.kind() == PipelineTrigger.Kind.SCHEDULE_INTERVAL) f.put("every", every(t.everyMs()));
        if (c.hasConnection()) f.put("connection", c.connection());
        if (c.stability().readyMarker() != null) f.put("readyMarker", c.stability().readyMarker());
        return f;
    }

    private static String every(long ms) {
        if (ms % 86_400_000L == 0) return ms / 86_400_000L + "d";
        if (ms % 3_600_000L == 0) return ms / 3_600_000L + "h";
        if (ms % 60_000L == 0) return ms / 60_000L + "m";
        return ms / 1000L + "s";
    }

    // ── PUT: Keep this up to date ───────────────────────────────────────────────────────────────────────────

    static Object keepUpToDate(ApiContext api, HttpExchange ex, String id, Map<String, Object> body) throws IOException {
        Import imp = gate(api, ex, id, "Keep this up to date");
        if (!EditionFeatures.present(OnboardImport.AUTOMATION_FEATURE))   // D-1: Basic is manual (DT-5)
            throw new ApiException(503, ErrorCodes.CAPABILITY_UNAVAILABLE, "Keep this up to date: "
                    + EditionFeatures.refusal(OnboardImport.AUTOMATION_FEATURE) + "; import the files waiting with Run now");
        if ("reference".equals(imp.content().get("kind")))
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED,
                    "a Reference is replaced with Add a file; a feed only adds rows, so it keeps Events up to date");
        Map<String, Object> b = body == null ? Map.of() : body;
        String kind = text(b, "kind");
        if (!"folder".equals(kind) && !"sftp".equals(kind))
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "kind must be folder (the Dataset's inbox is watched) or sftp");
        String every = text(b, "every");
        if (every == null) every = DEFAULT_EVERY;
        if (!EVERY.matcher(every).matches())
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "every must be a number and a unit, for example 15m, 1h or 1d");
        long everyMs = PipelineTrigger.of(Map.of("every", every)).everyMs();
        if (everyMs < MIN_EVERY_MS || everyMs > MAX_EVERY_MS)
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "every must be between 1m and 1d");
        String marker = text(b, "readyMarker");
        if (marker != null && !MARKER.matcher(marker).matches())
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED,
                    "readyMarker is a file-name suffix such as .done, or a name with {name} such as {name}.ok");
        String connection = text(b, "connection");
        String connector = "local";
        if ("sftp".equals(kind)) {
            if (connection == null || !CONNECTION.matcher(connection).matches())
                throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "an sftp feed names its Connection (connection)");
            connector = imp.service().connection(connection)
                    .map(p -> p.connector() == null ? "" : p.connector().toLowerCase(Locale.ROOT))
                    .orElseThrow(() -> new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "no Connection '" + connection + "'"));
            if (!"sftp".equals(connector))
                throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "Connection '" + connection + "' is " + connector + ", not sftp");
        } else if (connection != null)
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "a folder feed watches the Dataset's inbox; it takes no connection");

        Map<String, Object> stability = new LinkedHashMap<>();   // a half-written file is never read
        stability.put("window", "30s");
        stability.put("size_checks", 2);
        if (marker != null) stability.put("ready_marker", marker);
        Map<String, Object> duplicate = new LinkedHashMap<>();   // the same file sent again is skipped (D-3)
        duplicate.put("mode", "checksum");
        duplicate.put("algorithm", "SHA256");
        duplicate.put("on_change", "ignore");
        Map<String, Object> trigger = new LinkedHashMap<>();
        trigger.put("type", "schedule");
        trigger.put("every", every);
        final String conn = connection, kindOf = connector;
        rewrite(api, imp, "Keep this up to date rewrites the import's Pipeline", collector -> {
            collector.put("discovery", "poll");
            collector.put("connector", kindOf);
            if (conn != null) collector.put("connection", conn);
            else collector.remove("connection");
            collector.put("accepts_upload", true);
            collector.put("stability", stability);
            collector.put("duplicate", duplicate);
            return trigger;
        });

        Map<String, Object> out = describe(imp);
        out.put("indexJob", indexJob(imp, ApiContext.actor(ex)));
        return out;
    }

    // ── DELETE: back to uploads ─────────────────────────────────────────────────────────────────────────────

    static Object stop(ApiContext api, HttpExchange ex, String id) throws IOException {
        Import imp = gate(api, ex, id, "Stop the feed");
        rewrite(api, imp, "stopping the feed rewrites the import's Pipeline", collector -> {
            // the feed's own keys go; anything set in the guided editor (post_action, include, ...) stays
            collector.remove("connection");
            collector.remove("stability");
            collector.remove("duplicate");
            collector.put("discovery", "poll");
            collector.put("connector", "local");
            collector.put("accepts_upload", true);
            return Map.of("type", "manual");
        });
        return describe(imp);
    }

    /**
     * Rewrite the import Pipeline's {@code collector} (edited in place by {@code edit}, other keys kept) and its
     * {@code trigger} (what {@code edit} answers), as every Pipeline writer does: an approval policy over Pipelines refuses
     * (409, like Add data's own writes), the save gate blocks only the findings the edit introduces, the import is claimed
     * against a run or a delete of the same Dataset, the write is audited and kept in the Pipeline's history, and the file
     * is put back when the new one does not load.
     */
    @SuppressWarnings("unchecked")
    private static void rewrite(ApiContext api, Import imp, String why,
                                java.util.function.Function<Map<String, Object>, Map<String, Object>> edit) throws IOException {
        PendingChanges.holdRefusing(api, List.of("pipeline"), why);
        String key = imp.writeRoot() + "|" + imp.id();
        if (!OnboardImport.IN_FLIGHT.add(key))
            throw new ApiException(409, ErrorCodes.CONFLICT, "'" + imp.id() + "' is being imported or deleted right now; try again when it has finished");
        try {
            if (!Files.isRegularFile(imp.pipelineFile()))
                throw new ApiException(409, ErrorCodes.CONFLICT, "the import's Pipeline '" + imp.id() + "' is gone");
            byte[] before = Files.readAllBytes(imp.pipelineFile());
            Map<String, Object> src = ToonHelper.load(imp.pipelineFile().toString());
            Map<String, Object> p = new LinkedHashMap<>(src);
            Map<String, Object> collector = p.get("collector") instanceof Map<?, ?> c
                    ? new LinkedHashMap<>((Map<String, Object>) c) : new LinkedHashMap<>();
            p.put("trigger", edit.apply(collector));
            p.put("collector", collector);
            Path dir = imp.pipelineFile().getParent();
            List<Finding> introduced = SaveGate.introduced(
                    SaveGate.check(api, "pipeline", p, imp.writeRoot(), dir, SaveGate.Referents.MUST_EXIST),
                    SaveGate.check(api, "pipeline", src, imp.writeRoot(), dir, SaveGate.Referents.MUST_EXIST));
            if (!introduced.isEmpty()) {
                StringBuilder sb = new StringBuilder();
                for (Finding f : introduced)
                    if (f.severity() == Severity.ERROR) sb.append(sb.isEmpty() ? "" : "; ").append(f.fieldPath()).append(": ").append(f.message());
                throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "the import's Pipeline was refused: " + sb);
            }
            RefusalConfigAudit.write(imp.pipelineFile(), ConfigCodec.toToon(p).getBytes(StandardCharsets.UTF_8), ".cfg-");
            try {
                PipelineConfig.load(imp.pipelineFile().toString());
            } catch (IOException | RuntimeException refused) {
                AtomicFiles.write(imp.pipelineFile(), before, ".cfg-");
                imp.service().refreshConfigs();
                throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "the import's Pipeline did not load: " + refused.getMessage());
            }
            PipelineHistory.record(imp.writeRoot(), imp.pipelineFile());   // PIPELINE-CONFIG-HISTORY-1
            imp.service().refreshConfigs();
        } finally {
            OnboardImport.IN_FLIGHT.remove(key);
        }
    }

    /**
     * The {@code la.index.build} Job that appends each new arrival to the link index (the first build full, then the append
     * the server advises), or null when the Dataset has no link mapping. Saved by the client through {@code POST /jobs}, so
     * the Job's own gates (rights, approvals, owner stamping) decide; this route never writes a Job.
     */
    static Map<String, Object> indexJob(Import imp, String actor) {
        if (!(imp.content().get(DatasetEntryRules.DEFAULT_LINK_MAPPING) instanceof Map<?, ?> m)) return null;
        Object from = m.get("from"), to = m.get("to");
        if (from == null || to == null) return null;
        Map<String, Object> j = new LinkedHashMap<>();
        j.put("name", imp.id() + "_index");
        j.put("type", "la.index.build");
        j.put("description", "Keeps the link index of '" + imp.id() + "' up to date: appends each file its feed brings.");
        j.put("on_signal", "pipeline.commit");
        j.put("when", "$signal.pipeline == " + imp.id() + " && $signal.status == SUCCESS");
        j.put("dataset", imp.id());
        j.put("source_col", String.valueOf(from));
        j.put("target_col", String.valueOf(to));
        if (m.get("kind") != null) j.put("kind_col", String.valueOf(m.get("kind")));
        String time = DatasetEntryRules.primary(m, DatasetEntryRules.PRIMARY_TIME, "time");
        if (time != null) j.put("time_col", time);
        String weight = DatasetEntryRules.primary(m, DatasetEntryRules.PRIMARY_MEASURE, "amount");
        if (weight != null) j.put("weight_col", weight);
        List<String> carried = DatasetEntryRules.carriedColumns(imp.content());
        if (!carried.isEmpty()) j.put("attr_cols", String.join(",", carried));
        j.put("owner", actor);
        j.put("allow_full", "true");
        j.put("timeout_seconds", "600");
        return j;
    }

    private static String text(Map<String, Object> body, String key) {
        Object v = body.get(key);
        if (v == null) return null;
        if (!(v instanceof String s)) throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, key + " must be a string");
        s = s.trim();
        return s.isEmpty() ? null : s;
    }
}
