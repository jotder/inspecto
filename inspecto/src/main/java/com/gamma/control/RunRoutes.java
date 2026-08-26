package com.gamma.control;

import com.gamma.config.io.ConfigLoader;
import com.gamma.config.safety.ConfigSafetyValidator;
import com.gamma.config.safety.SafetyPolicy;
import com.gamma.config.spec.ConfigSpecs;
import com.gamma.config.spec.Finding;
import com.gamma.config.spec.Severity;
import com.gamma.etl.PipelineConfig;
import com.gamma.inspector.ReprocessCommand;
import com.gamma.report.ReportService;
import com.gamma.service.CollectorService;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;

/**
 * Core pipeline routes ({@code /runs*}, {@code /trigger}, {@code /status}, {@code /report}):
 * the pipeline registry + lifecycle (list / register / trigger / pause / resume), the audit reads
 * backed by the {@link com.gamma.etl.StatusStore} (commits / batches / files / lineage /
 * quarantine / pending / reprocess), and the aggregated reports. Extracted verbatim from
 * {@link ControlApi}: identical routes, order, statuses and shapes.
 */
final class RunRoutes implements RouteModule {

    @Override
    public void register(ApiContext api) {
        // Optional ?limit=&offset= (ui-design-review R6a) — absent limit returns every pipeline, unchanged.
        api.get("/runs", (e, m) -> ApiContext.paged(api.service().pipelines(), e));
        // Register a new pipeline from a config on disk under the write root (control scope).
        // Registration is a workbench-authoring action (W6: canAuthorWorkbench); trigger/pause/resume/
        // reprocess below are operational (canOperateRuns) — both a no-op on Personal (rbac-groundwork.md §2).
        api.post("/runs", ApiContext.withCapability("canAuthorWorkbench", (e, m) -> createPipeline(api, e, api.body(e))));
        api.post("/runs/([^/]+)/trigger", ApiContext.withCapability("canOperateRuns", (e, m) ->
                triggerPipeline(api, e, ApiContext.name(m))));
        // W5b async: poll one manual run by id (the id returned by the 202 trigger above). Single-segment after
        // /runs/runs/, so it never collides with the /runs list or the /runs/{name}/<audit> routes.
        api.get("/runs/runs/([^/]+)", (e, m) -> pipelineRunById(api, ApiContext.name(m)));
        api.post("/runs/([^/]+)/pause", ApiContext.withCapability("canOperateRuns", (e, m) -> {
            if (!api.service().pause(ApiContext.name(m))) throw notFound(ApiContext.name(m));
            return Map.of("pipeline", ApiContext.name(m), "paused", true);
        }));
        api.post("/runs/([^/]+)/resume", ApiContext.withCapability("canOperateRuns", (e, m) -> {
            if (!api.service().resume(ApiContext.name(m))) throw notFound(ApiContext.name(m));
            return Map.of("pipeline", ApiContext.name(m), "paused", false);
        }));

        api.get("/runs/([^/]+)/commits",    (e, m) -> api.service().statusStore().committedBatches(cfg(api, m)));
        api.get("/runs/([^/]+)/batches",    (e, m) -> api.service().statusStore().batches(cfg(api, m)));
        api.get("/runs/([^/]+)/files",      (e, m) -> api.service().statusStore().files(cfg(api, m)));
        api.get("/runs/([^/]+)/lineage",    (e, m) -> api.service().statusStore().lineage(cfg(api, m), ApiContext.query(e, "batchId")));
        api.get("/runs/([^/]+)/quarantine", (e, m) -> api.service().statusStore().quarantine(cfg(api, m)));
        // The rejected ROWS behind an error_rows count (audit hole 2): the audit ledgers carry counts,
        // filenames and an error string, never row content — which existed all along in the companion
        // `<base>_errors.csv` but only on disk, so an operator without filesystem access could see
        // THAT 37 rows failed and never WHICH.
        api.get("/runs/([^/]+)/errors", (e, m) -> rejectedRows(api, e, m));
        // Inbox/processing status: files still pending (matched, not yet processed) + whether the
        // pipeline is currently ingesting. Complements the audit-backed /files (processed history).
        api.get("/runs/([^/]+)/pending",    (e, m) ->
                api.service().inboxStatus(ApiContext.name(m)).orElseThrow(() -> notFound(ApiContext.name(m))));
        // "Where is file X right now" (Phase 4 §2.4 — Stage C's per-file stage progression), backed by the
        // durable FileStages registry; empty when the registry is default-off or the file predates it —
        // never an error, since the registry is an index beside the manifest, not the record of existence.
        api.get("/runs/([^/]+)/files/stage", (e, m) -> {
                    PipelineConfig cfg = cfg(api, m);
                    String rel = ApiContext.query(e, "path");
                    if (rel == null || rel.isBlank()) throw new ApiException(400, "?path= is required");
                    return Map.of("pipeline", ApiContext.name(m), "path", rel,
                            "stages", com.gamma.consignment.FileStages.stages(cfg.collector().id(), rel));
                });

        api.post("/runs/([^/]+)/reprocess", ApiContext.withCapability("canOperateRuns", (e, m) -> {
            var path = api.service().pathFor(ApiContext.name(m)).orElseThrow(() -> notFound(ApiContext.name(m)));
            if (api.service().isTemplate(ApiContext.name(m)))   // a template has no committed batches anyway
                throw new ApiException(409, "pipeline '" + ApiContext.name(m) + "' is a template and is not runnable");
            String batchId = ApiContext.str(api.body(e), "batchId");
            if (batchId == null) throw new ApiException(400, "body must include 'batchId'");
            ReprocessCommand.run(path.toString(), batchId);
            return Map.of("pipeline", ApiContext.name(m), "batchId", batchId, "status", "reprocessed");
        }));

        api.post("/trigger", ApiContext.withCapability("canOperateRuns", (e, m) -> api.service().runAllOnce()));

        // ── v2.8.0: aggregated reports (status snapshot + batch-audit rollup) ──
        // v2.10.0: ?from=&to= scope the rollup to a date range (inclusive; date or datetime).
        api.get("/status", (e, m) -> api.service().reports().statusReport());
        // Cross-pipeline problem files (2026-08-23, operator-requested): every pipeline's WHOLE
        // failures (quarantined) and PARTIAL failures (ingested with error_rows > 0) in one bounded,
        // newest-first list — the file-grain companion to /status's pipeline-grain rollup, so an
        // operator with 100s of pipelines stops drilling into each Run Detail to find the bad ones.
        api.get("/status/problem-files", (e, m) -> problemFiles(api, e));
        api.get("/report", (e, m) -> api.service().reports().serviceReport(window(e)));
        api.get("/runs/([^/]+)/report", (e, m) -> {
            cfg(api, m);   // 404 if no such pipeline
            return api.service().reports().batchReport(ApiContext.name(m), window(e));
        });
    }

    /**
     * {@code POST /runs/{name}/trigger} — fire a pipeline. On the v1 surface returns {@code 202} + {@code {runId,…}}
     * + a {@code Location} to poll (async, off the ingest lock — mirrors the job trigger); the legacy surface keeps
     * its unchanged {@code 200} {@link com.gamma.inspector.MultiCollectorProcessor.RunResult} body — but the run now
     * executes on the trigger pool via {@link CollectorService#runPipelineOffThread} (blocking for the result), so
     * even the legacy path no longer holds the ingest lock on the request thread. 404 if no such pipeline.
     */
    private Object triggerPipeline(ApiContext api, HttpExchange e, String name) throws IOException {
        try {
            if (ApiContext.v1(e)) {
                String runId = api.service().triggerRunAsync(name).orElseThrow(() -> notFound(name));
                e.getResponseHeaders().set("Location", "/api/v1/runs/runs/" + runId);
                return ApiContext.respondJson(e, 202, Map.of("runId", runId, "pipeline", name, "status", "running"));
            }
            return api.service().runPipelineOffThread(name).orElseThrow(() -> notFound(name));
        } catch (IllegalStateException notRunnable) {
            throw new ApiException(409, notRunnable.getMessage());   // a `template: true` pipeline
        }
    }

    /** {@code GET /runs/runs/{runId}} — poll one manual pipeline run's status (W5b); 404 once evicted or unknown. */
    private Object pipelineRunById(ApiContext api, String runId) {
        CollectorService.PipelineRun r = api.service().pipelineRunById(runId)
                .orElseThrow(() -> new ApiException(404, "no run '" + runId + "'"));
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("runId", r.runId());
        m.put("pipeline", r.pipeline());
        m.put("trigger", r.trigger());
        m.put("status", r.status());
        m.put("startedAt", r.startedAt());
        m.put("finishedAt", r.finishedAt());
        m.put("total", r.total());
        m.put("failed", r.failed());
        m.put("message", r.message());
        return m;
    }

    /** Rejected-row detail is a diagnostic sample, not an export — a 4M-reject file must not be a response. */
    private static final int MAX_REJECT_ROWS = 500;

    /**
     * {@code GET /runs/{name}/errors?file=<name>} — the rejected ROWS behind a file's
     * {@code error_rows} count, from its companion {@code <base>_errors.csv}
     * ({@code line_number,column,reason,raw_line}, written by {@code DuckDbCsvIngester.writeRejects}).
     *
     * <p><b>Why a bare file NAME is the key.</b> Both surfaces that need this — the Files tab (a file
     * accepted with rejects, whose errors file stays in {@code dirs.errors()}) and the Quarantine tab
     * (a file rejected outright, whose errors file was moved next to it) — already hold the file name,
     * and the two locations differ. Resolving by name in both places serves one key from either tab,
     * and a name with no separator cannot traverse: the errors dir is searched first, then the
     * quarantine tree. Both results are jailed to their configured root regardless, so a symlink or an
     * odd config cannot reach outside.
     *
     * <p>Gates: unknown pipeline → 404; missing {@code ?file=} → 400; a name carrying a path separator
     * or {@code ..} → 403 (never resolved at all); no errors file → 404, which is the honest answer for
     * "this file had no rejected rows recorded" and distinguishes it from an empty list.
     */
    private Object rejectedRows(ApiContext api, HttpExchange e, Matcher m) throws IOException {
        PipelineConfig cfg = cfg(api, m);                       // 404 — unknown pipeline
        String file = ApiContext.query(e, "file");
        if (file == null || file.isBlank())
            throw new ApiException(400, "?file= is required (the input file's name)");
        if (file.contains("/") || file.contains("\\") || file.contains(".."))
            throw new ApiException(403, "?file= must be a bare file name, not a path");

        String wanted = com.gamma.etl.CsvIngester.stripExtensions(file) + "_errors.csv";
        Path found = locateErrorsFile(cfg, wanted);
        if (found == null)
            throw new ApiException(404, "no rejected-row detail recorded for '" + file + "'");

        List<Map<String, String>> rows = new ArrayList<>();
        try {
            com.gamma.util.Csv.readInto(found, rows);
        } catch (Exception bad) {
            throw new ApiException(422, "could not read the rejected-row detail: " + bad.getMessage());
        }
        boolean truncated = rows.size() > MAX_REJECT_ROWS;
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("pipeline", ApiContext.name(m));
        out.put("file", file);
        out.put("errorsFile", found.getFileName().toString());
        out.put("rowCount", rows.size());
        out.put("truncated", truncated);
        out.put("rows", truncated ? rows.subList(0, MAX_REJECT_ROWS) : rows);
        return out;
    }

    /**
     * The errors file for one input, or {@code null}. {@code dirs.errors()} holds it while the input was
     * accepted-with-rejects; {@code QuarantineManager} moves it beside the file when the input itself is
     * quarantined, so the quarantine tree is searched by name (bounded depth) as the fallback.
     */
    private static Path locateErrorsFile(PipelineConfig cfg, String wanted) throws IOException {
        if (cfg.dirs().errors() != null) {
            Path root = Path.of(cfg.dirs().errors()).toAbsolutePath().normalize();
            Path direct = WriteGates.jail(root, root.resolve(wanted), "errors file");
            if (Files.isRegularFile(direct)) return direct;
        }
        if (cfg.dirs().quarantine() == null) return null;
        Path qRoot = Path.of(cfg.dirs().quarantine()).toAbsolutePath().normalize();
        if (!Files.isDirectory(qRoot)) return null;
        try (var walk = Files.walk(qRoot, 6)) {          // <poll-subpath>/<reason>/<file> plus headroom
            return walk.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().equals(wanted))
                    .map(p -> WriteGates.jail(qRoot, p, "errors file"))
                    .findFirst().orElse(null);
        }
    }

    // ── GET /status/problem-files ─────────────────────────────────────────────

    /** A diagnostic read, not an export — the default page and the cap it may be raised to. */
    private static final int PROBLEM_FILES_DEFAULT_LIMIT = 1000;
    private static final int PROBLEM_FILES_MAX_LIMIT = 5000;

    /**
     * {@code GET /status/problem-files?limit=&since=} — one row per problem file across EVERY loaded
     * pipeline, newest first:
     *
     * <ul>
     *   <li><b>FULL</b> — the file never landed: a {@code QUARANTINED_*} status-ledger row, or a
     *       quarantine-tree entry for a file rejected before any batch existed (corrupt download,
     *       empty). The two overlap for most quarantined files, so tree entries are added only for
     *       files the status ledger does not already carry — one row per problem, not two.</li>
     *   <li><b>PARTIAL</b> — the file ingested with rejected rows ({@code SUCCESS} +
     *       {@code error_rows > 0}). Drill-down to the rows themselves is the existing
     *       {@code GET /runs/{name}/errors?file=}.</li>
     * </ul>
     *
     * <p>Aggregation walks the loaded configs through the {@link com.gamma.etl.StatusStore} seam —
     * the same per-poll iteration {@code AlertService} already does, so the cost is precedented; the
     * response is bounded ({@code ?limit=}, capped) with {@code truncated} reporting the TRUE total.
     * The summary counts are computed over EVERYTHING, pre-limit, so the cards stay honest when the
     * list is cut. ⚠ A pipeline whose ledger cannot be read surfaces as a {@code WARNING} row —
     * silence looking like health is the failure mode this route exists to kill.
     *
     * <p>{@code ?since=} is an inclusive lower bound compared lexicographically against the ledger's
     * {@code end_time} ({@code yyyy-MM-dd HH:mm:ss}), so both {@code 2026-08-20} and
     * {@code 2026-08-20 06:00:00} work. Quarantine-tree rows carry no timestamp and are always
     * included — absence of evidence must not hide a quarantined file from a windowed view.
     */
    private Object problemFiles(ApiContext api, HttpExchange e) {
        int limit = clampLimit(ApiContext.query(e, "limit"));
        String since = ApiContext.query(e, "since");

        List<Map<String, Object>> rows = new ArrayList<>();
        int full = 0, partial = 0, warnings = 0;
        java.util.Set<String> pipelinesWithProblems = new java.util.TreeSet<>();

        for (PipelineConfig cfg : api.service().loadedPipelines()) {
            String pipeline = cfg.identity().pipelineName();
            try {
                java.util.Set<String> seen = new java.util.HashSet<>();
                for (Map<String, String> f : api.service().statusStore().files(cfg)) {
                    String status = f.getOrDefault("status", "");
                    long errorRows = parseLongOr(f.get("error_rows"), 0);
                    boolean fullFail = !"SUCCESS".equals(status);
                    if (!fullFail && errorRows <= 0) continue;
                    String time = f.getOrDefault("end_time", "");
                    if (since != null && !since.isBlank() && !time.isBlank() && time.compareTo(since) < 0)
                        continue;
                    seen.add(f.getOrDefault("filename", ""));
                    if (fullFail) full++; else partial++;
                    pipelinesWithProblems.add(pipeline);
                    rows.add(problemRow(pipeline, f.getOrDefault("filename", ""),
                            fullFail ? "FULL" : "PARTIAL", status,
                            f.get("parsed_rows"), f.get("error_rows"),
                            f.getOrDefault("error", ""), f.get("consignment_id"), time,
                            // The archive/compressed original this member came out of; blank for an
                            // ordinary file, and absent entirely from ledgers written before the
                            // column existed (readers parse by header NAME, so that is a blank too).
                            f.getOrDefault("origin", ""), f.getOrDefault("logical_name", "")));
                }
                for (Map<String, String> q : api.service().statusStore().quarantine(cfg)) {
                    String file = q.getOrDefault("file", "");
                    if (seen.contains(file)) continue;   // the status ledger's row is richer
                    full++;
                    pipelinesWithProblems.add(pipeline);
                    rows.add(problemRow(pipeline, file, "FULL", "QUARANTINED",
                            null, null, q.getOrDefault("reason", ""), null, "", "", ""));
                }
            } catch (Exception ledgerUnreadable) {
                // Honesty rule: an unreadable ledger is a WARNING row, never a silent absence.
                warnings++;
                pipelinesWithProblems.add(pipeline);
                rows.add(problemRow(pipeline, "", "WARNING", "LEDGER_UNREADABLE", null, null,
                        errMsg(ledgerUnreadable), null, "", "", ""));
            }
        }

        // Newest first; the untimed quarantine-tree rows sink to the end rather than faking recency.
        rows.sort((a, b) -> String.valueOf(b.get("time")).compareTo(String.valueOf(a.get("time"))));
        int total = rows.size();
        boolean truncated = total > limit;
        if (truncated) rows = new ArrayList<>(rows.subList(0, limit));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("rows", rows);
        out.put("total", total);
        out.put("truncated", truncated);
        out.put("fullCount", full);
        out.put("partialCount", partial);
        out.put("warningCount", warnings);
        out.put("pipelinesWithProblems", pipelinesWithProblems.size());
        return out;
    }

    private static Map<String, Object> problemRow(String pipeline, String filename, String verdict,
                                                  String status, String parsedRows, String errorRows,
                                                  String error, String consignmentId, String time,
                                                  String origin, String logicalName) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("pipeline", pipeline);
        r.put("filename", filename);
        r.put("verdict", verdict);
        r.put("status", status);
        r.put("parsedRows", parseLongOr(parsedRows, -1));   // -1 = not carried, rendered blank
        r.put("errorRows", parseLongOr(errorRows, -1));
        r.put("error", error);
        r.put("consignmentId", consignmentId == null ? "" : consignmentId);
        r.put("time", time);
        r.put("origin", origin == null ? "" : origin);
        // The inbox file's extension-insensitive IDENTITY: cdr.csv.gz, cdr.Z and bare cdr are ONE
        // logical file, so a re-delivery groups to its earlier spelling instead of reading the alias
        // hit out of the dedup log. Unlike `origin` (a display basename) it is poll-relative and IS a
        // key. Blank on ledgers written before the column existed — readers parse by header name.
        r.put("logicalName", logicalName == null ? "" : logicalName);
        return r;
    }

    private static int clampLimit(String raw) {
        if (raw == null || raw.isBlank()) return PROBLEM_FILES_DEFAULT_LIMIT;
        try {
            int v = Integer.parseInt(raw.trim());
            if (v < 1) throw new ApiException(400, "?limit= must be >= 1");
            return Math.min(v, PROBLEM_FILES_MAX_LIMIT);
        } catch (NumberFormatException nfe) {
            throw new ApiException(400, "?limit= must be an integer");
        }
    }

    private static long parseLongOr(String s, long fallback) {
        if (s == null || s.isBlank()) return fallback;
        try {
            return Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static String errMsg(Exception e) {
        String m = e.getMessage();
        return m == null || m.isBlank() ? e.getClass().getSimpleName() : m;
    }

    private PipelineConfig cfg(ApiContext api, Matcher m) {
        return api.service().configFor(ApiContext.name(m)).orElseThrow(() -> notFound(ApiContext.name(m)));
    }

    private static ApiException notFound(String name) {
        return new ApiException(404, "no pipeline named '" + name + "'");
    }

    /** Build a report {@link ReportService.Window} from {@code ?from=&to=}. */
    private static ReportService.Window window(HttpExchange ex) {
        return ReportService.Window.of(ApiContext.query(ex, "from"), ApiContext.query(ex, "to"));
    }

    /**
     * Register a new pipeline from a config already on disk under the write root (v4.1.0, scope
     * {@code control}). Pairs with {@code POST /config/write}: author + persist a {@code .toon}
     * there, then register it so the running service processes it on the next poll cycle — no
     * restart. (Registration is in-memory; a registered pipeline survives a restart only if its
     * file also lies under a config dir the service is launched with — keep {@code assist.write.root}
     * inside the launched config tree to get both.)
     *
     * <p>Body {@code {"configPath":"…"}} — absolute, or relative to {@code -Dassist.write.root}.
     * Gated fail-closed: registration disabled unless the write root is set → 503; missing
     * {@code configPath} → 400; a path resolving outside the root → 403; no file there → 404; a
     * config that fails spec / hard-fail safety (R6) validation → 422 (findings returned); an id
     * colliding with a <em>different</em> registered pipeline → 409. On success the new pipeline's
     * {@link CollectorService.PipelineView} is returned.
     */
    private Object createPipeline(ApiContext api, HttpExchange ex, Map<String, Object> body) throws IOException {
        Path writeRoot = WriteGates.requireWriteRoot(api, "pipeline registration");
        String configPath = ApiContext.str(body, "configPath");
        if (configPath == null || configPath.isBlank())
            throw new ApiException(400, "body must include 'configPath'");

        Path candidate = Path.of(configPath.trim());
        Path resolved = WriteGates.jail(writeRoot,
                candidate.isAbsolute() ? candidate : writeRoot.resolve(candidate), "configPath");
        if (!Files.isRegularFile(resolved))
            throw new ApiException(404, "no config file at "
                    + writeRoot.relativize(resolved).toString().replace('\\', '/'));

        // Validate before registering: spec + the hard-fail safety gate (R6). Block on ERRORs —
        // the file may have been placed here without going through POST /config/write.
        Map<String, Object> raw;
        try {
            raw = ConfigLoader.filesystem().decode(resolved.toString());
        } catch (RuntimeException parse) {
            throw new ApiException(422, "config does not parse: " + parse.getMessage());
        }
        List<Finding> findings = new ArrayList<>(ConfigLoader.filesystem().validate(ConfigSpecs.pipeline(), raw));
        findings.addAll(ConfigSafetyValidator.check("pipeline", raw, SafetyPolicy.defaultPolicy()));
        // ERROR here: registration loads the config for real, so an unresolvable schema_file is a
        // guaranteed failure — block with a structured, field-anchored finding instead of letting
        // PipelineConfig.load() surface it as an opaque "config is not a valid pipeline" 422.
        // `resolved.getParent()` so a config-relative schema reference resolves here the same way
        // PipelineConfig.load will resolve it (W1b) — otherwise this gate 422s a config that would run.
        findings.addAll(ConfigRoutes.schemaFileFindings("pipeline", raw, Severity.ERROR, resolved.getParent()));
        if (findings.stream().anyMatch(f -> f.severity() == Severity.ERROR)) {
            return ApiContext.respondJson(ex, 422, Map.of("registered", false,
                    "error", "config has ERROR-level findings; not registered", "findings", findings));
        }

        String id;
        try {
            id = api.service().registerPipeline(resolved);
        } catch (IllegalStateException collision) {
            throw new ApiException(409, collision.getMessage());
        } catch (RuntimeException invalid) {
            throw new ApiException(422, "config is not a valid pipeline: " + invalid.getMessage());
        }

        CollectorService.PipelineView view = api.service().pipelines().stream()
                .filter(p -> p.name().equals(id)).findFirst().orElse(null);
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("registered", true);
        r.put("id", id);
        r.put("path", writeRoot.relativize(resolved).toString().replace('\\', '/'));
        r.put("pipeline", view);
        r.put("findings", findings);   // warnings only at this point
        return r;
    }
}
