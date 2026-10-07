package com.gamma.la.api;

import com.gamma.control.ApiContext;
import com.gamma.control.ApiException;
import com.gamma.access.ComponentAccess;
import com.gamma.control.ErrorCodes;
import com.gamma.control.LinkAnalysisSettings;
import com.gamma.access.Roles;
import com.gamma.control.RouteModule;
import com.gamma.control.Subject;
import com.gamma.access.WriteGates;
import com.gamma.audit.Event;
import com.gamma.audit.EventLog;
import com.gamma.la.core.DatasetProvider;
import com.gamma.la.core.DatasetProviders;
import com.gamma.la.core.FingerprintBudget;
import com.gamma.la.core.InputFingerprint;
import com.gamma.la.core.LinkEventTypes;
import com.gamma.la.storage.BucketFunction;
import com.gamma.la.storage.IndexBuildService;
import com.gamma.la.storage.IndexReader;
import com.gamma.la.storage.IndexBuildService.Refused;
import com.gamma.la.storage.IndexBuildService.RunView;
import com.gamma.la.storage.IndexBuildService.Status;
import com.gamma.la.storage.IndexBuilder;
import com.gamma.la.storage.IndexManifest;
import com.gamma.la.storage.IndexMapping;
import com.gamma.la.storage.IndexPlan;
import com.gamma.la.storage.IndexStore;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.nio.file.Path;
import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * The edge/node index of a Dataset (LA separation D-3 step 4, design 3.1): build it, watch the build, list what exists.
 *
 * <ul>
 *   <li>{@code POST /inv/index/builds} - start a build over a Dataset and an edge mapping {@code {dataset, sourceCol,
 *       targetCol, kindCol?, timeCol?, timeColZone?, weightCol?, attrCols?}}. Gated by {@code canBuildLinkIndex} (a build
 *       spends compute and disk). {@code 202} + {@code Location}; a build is never answered inline.</li>
 *   <li>{@code GET /inv/index} - the indexes of the Datasets the caller may view: the current version's manifest summary
 *       and a {@code stale} flag with its {@code reason}.</li>
 *   <li>{@code GET /inv/index/builds/{id}} - one build; a build another caller started is absent (404).</li>
 *   <li>{@code POST /inv/index/builds/{id}/cancel} - the build's starter or an administrator; anyone else gets the 404
 *       of an unknown build.</li>
 * </ul>
 *
 * <p><b>The Dataset gate (design Decision 2).</b> The index carries no row scope, so EVERY route first stands behind
 * the base Dataset's view gate, exactly as {@link InvRoutes#relationFor} does: unknown Dataset, 404; a Dataset the
 * caller may not view, the SAME 404; only then is the relation SQL read. A Dataset shared away reads as absent, and so
 * does its index and every build over it. Build reads and cancels re-run the gate (a starter who lost the Dataset sees
 * the 404 of an unknown build). <b>Valid only while a Dataset has no per-Subject row filter</b> (Decision 2): if the
 * Dataset gains one, this gate no longer covers the index.
 *
 * <p><b>Where.</b> {@code <Space write root>/la-index} ({@link WriteGates#requireWriteRoot}: 503 when none). One
 * {@link IndexBuildService} per Space, created on first use with that Space's {@code index.threads} / {@code index.queue}
 * (applied when it is built), closed with the API or when idle ({@link IndexBuildServices}). The disk budget
 * ({@code index.max_disk_bytes}) and {@code index.keep_versions} are read per request.
 *
 * <p><b>Nothing reads the index yet.</b> {@code index.enabled} (default false) lands now and is echoed by {@code GET
 * /inv/index}; the read paths arrive in later steps (design 5).
 *
 * <p><b>The base fingerprint is grounded in files</b> (design 5.3a): {@code DatasetProvider#inputFingerprint} lists the files
 * the relation reads (the engine's own resolution, superseded files subtracted) and the build records the fingerprint plus, up
 * to {@code IndexManifest.MAX_INPUT_FILES}, the file list. {@link IndexStaleness} compares it with the files now. A Dataset
 * with nothing to list reports {@code fingerprint: unknown} - no currency is claimed.
 */
public final class IndexRoutes implements RouteModule {

    /** The directory under a Space's write root that holds every index. */
    static final String INDEX_DIR = "la-index";
    private static final int MAX_ATTR_COLS = 50;
    private static final Set<String> BODY_KEYS = Set.of("dataset", "sourceCol", "targetCol", "kindCol", "timeCol", "timeColZone",
            "weightCol", "attrCols", "mode");

    /** Test seam (the {@code GraphRunRoutes.forTest} idiom): a builder for services created AFTER this call. */
    private static volatile Function<IndexBuilder.Request, IndexBuilder.Result> builderOverride;

    /** @param builder null = the real {@link IndexBuilder#build} */
    public static void forTest(Function<IndexBuilder.Request, IndexBuilder.Result> builder) {
        builderOverride = builder;
    }

    /** The registered routes of this JVM, so a scheduled build uses the same per-Space {@link IndexBuildService} as {@code POST /inv/index/builds}; null = no API is up. */
    private static volatile IndexRoutes active;

    /** The per-Space build service the HTTP routes use, or null when no control API is registered (a scheduled build then refuses). */
    static IndexBuildService scheduledService(Path writeRoot) {
        IndexRoutes r = active;
        return r == null ? null : r.services.get(writeRoot);
    }

    private final IndexBuildServices services = new IndexBuildServices(IndexRoutes::newService, System::currentTimeMillis,
            IndexBuildServices.IDLE_TTL_MS);

    @Override
    public void register(ApiContext api) {
        api.onClose(services::close);
        active = this;                                                                        // the scheduled build (la.index.build) shares THIS service: one queue, one duplicate guard
        api.onClose(() -> { if (active == this) active = null; });
        api.onClose(IndexReader::evictAll);                                                   // pooled sealed readers die with the API
        // ⚠ String LITERAL on purpose - CapabilityManifestTest's scanner matches only a literal argument.
        api.post("/inv/index/builds", ApiContext.withCapability("canBuildLinkIndex", (e, m) -> start(api, e, api.body(e))));
        api.get("/inv/index", (e, m) -> list(api, e));
        api.get("/inv/index/builds/([^/]+)", (e, m) -> get(api, e, m.group(1)));
        api.post("/inv/index/builds/([^/]+)/cancel", (e, m) -> cancel(api, e, m.group(1)));
    }

    private static IndexBuildService newService(Path writeRoot) {
        IndexBuildService.Limits std = IndexBuildService.Limits.standard();
        LinkAnalysisSettings.Index ix = LinkAnalysisSettings.forRoot(writeRoot).effectiveIndex();
        IndexBuildService.Limits limits = new IndexBuildService.Limits(ix.threadsInForce(), ix.queueInForce(), std.runTtlMs(), std.maxRuns());
        Function<IndexBuilder.Request, IndexBuilder.Result> b = builderOverride != null ? builderOverride : IndexBuilder::build;
        return new IndexBuildService(indexRoot(writeRoot), limits, System::currentTimeMillis, IndexRoutes::auditTerminal,
                IndexBuildService.WORKER_KEEPALIVE_MS, b);
    }

    static Path indexRoot(Path writeRoot) {
        return writeRoot.resolve(INDEX_DIR);
    }

    // ── POST /inv/index/builds ────────────────────────────────────────────────────────────────────────────

    private Object start(ApiContext api, HttpExchange ex, Map<String, Object> body) throws IOException {
        Path writeRoot = WriteGates.requireWriteRoot(api, "link index build");                // 503
        for (String k : body.keySet())
            if (!BODY_KEYS.contains(k))
                throw bad("unknown field '" + k + "' (a build takes: " + String.join(", ", new TreeSet<>(BODY_KEYS)) + ")");
        String dataset = required(body, "dataset");
        String source = required(body, "sourceCol");
        String target = required(body, "targetCol");
        String kind = optional(body, "kindCol");
        String time = optional(body, "timeCol");
        String zone = optional(body, "timeColZone");
        String weight = optional(body, "weightCol");
        List<String> attrs = attrCols(body);
        IndexBuilder.Mode mode = mode(body);
        if (zone != null) {
            if (time == null) throw bad("'timeColZone' needs a 'timeCol' to read in that zone");
            try {
                ZoneId.of(zone);
            } catch (DateTimeException notAZone) {
                throw bad("'timeColZone' is not a time zone id: '" + zone + "'");
            }
        }

        String relationSql = InvRoutes.relationFor(api, ex, writeRoot, dataset);              // 404 · 404 · 422 - the Decision 2 gate
        List<String> columns = InvRoutes.relationColumns(dataset, relationSql);              // 422
        List<String> mapped = new ArrayList<>(List.of(source, target));
        for (String c : new String[] {kind, time, weight}) if (c != null) mapped.add(c);
        mapped.addAll(attrs);
        for (String c : mapped)
            if (columns.stream().noneMatch(x -> x.equalsIgnoreCase(c)))
                throw bad("'" + c + "' is not a column of dataset '" + dataset + "'");

        IndexMapping mapping = new IndexMapping(source, target, kind, time, zone, weight, attrs);
        LinkAnalysisSettings.Index ix = LinkAnalysisSettings.forRoot(writeRoot).effectiveIndex();
        RunView v;
        try {
            v = service(writeRoot).submit(new IndexBuildService.Request(callerId(ex), dataset, mapping,
                    ds -> relation(api.dataRoot(), writeRoot, dataset, relationSql),
                    ix.maxDiskBytesInForce(), ix.keepVersionsInForce(), mode));
        } catch (Refused refused) {
            throw map(refused);
        } catch (IllegalArgumentException unsafe) {
            throw bad("dataset '" + dataset + "' cannot name an index directory");
        }
        emitStarted(ex, v);
        ex.getResponseHeaders().set("Location", "/api/v1/inv/index/builds/" + v.id());
        return ApiContext.respondJson(ex, 202, view(v));
    }

    /** The relation to build over with its input fingerprint (and file list, when small enough to record). Runs on the submitting thread. */
    static IndexBuildService.Relation relation(Path dataRoot, Path writeRoot, String dataset, String relationSql) {
        InputFingerprint fp = currentInput(dataRoot, writeRoot, dataset);
        List<IndexManifest.InputFile> files = null;
        if (fp != null && fp.known() && fp.files().size() <= IndexManifest.MAX_INPUT_FILES)
            files = fp.files().stream().map(f -> new IndexManifest.InputFile(f.path(), f.size(), f.mtimeMillis())).toList();
        // APPEND reads only the added files: the provider renders the relation over them (null = not row-wise, never appendable)
        Function<List<String>, String> deltaSql = added -> DatasetProviders.require().dataset(writeRoot, dataset)
                .map(c -> DatasetProviders.require().relationSqlOverFiles(c, dataRoot, writeRoot, added)).orElse(null);
        List<Path> roots = DatasetProviders.require().dataset(writeRoot, dataset)
                .map(c -> DatasetProviders.require().readRoots(c, dataRoot)).orElse(List.of());
        return new IndexBuildService.Relation(relationSql, fp == null ? "unknown" : fp.value(), files, deltaSql, roots);
    }

    /** The body's {@code mode}: {@code full} (default), {@code append} (only the files added since the live version) or {@code compact} (merge the deltas). */
    private static IndexBuilder.Mode mode(Map<String, Object> body) {
        String m = optional(body, "mode");
        if (m == null) return IndexBuilder.Mode.FULL;
        return switch (m.toLowerCase(Locale.ROOT)) {
            case "full" -> IndexBuilder.Mode.FULL;
            case "append" -> IndexBuilder.Mode.APPEND;
            case "compact" -> IndexBuilder.Mode.COMPACT;
            default -> throw bad("'mode' must be full, append or compact");
        };
    }

    private static InputFingerprint currentInput(ApiContext api, Path writeRoot, String dataset) {
        return currentInput(api.dataRoot(), writeRoot, dataset);
    }

    /** The Dataset's input fingerprint now, or null when it cannot be taken - the ONE path the build, GET /inv/index and the traversal gate share. */
    static InputFingerprint currentInput(Path dataRoot, Path writeRoot, String dataset) {
        DatasetProvider p = DatasetProviders.require();
        return p.dataset(writeRoot, dataset).map(c -> currentInput(dataRoot, writeRoot, c)).orElse(null);
    }

    /** The Dataset's input fingerprint now, or null when it cannot be taken. */
    private static InputFingerprint currentInput(ApiContext api, Path writeRoot, Map<String, Object> content) {
        return currentInput(api.dataRoot(), writeRoot, content);
    }

    private static InputFingerprint currentInput(Path dataRoot, Path writeRoot, Map<String, Object> content) {
        try {
            return DatasetProviders.require().inputFingerprint(content, dataRoot, writeRoot);
        } catch (RuntimeException unresolvable) {
            return null;
        }
    }

    /** Test seam: the per-request listing budget of {@code GET /inv/index}; null = {@link FingerprintBudget#forRequest()}. */
    private static volatile Supplier<FingerprintBudget> requestBudgetOverride;

    public static void forTestRequestBudget(Supplier<FingerprintBudget> budget) {
        requestBudgetOverride = budget;
    }

    /** As the unbudgeted form but listing under the request's shared {@code budget}: spent = not listed at all ({@code unknown:budget}). */
    private static InputFingerprint currentInput(ApiContext api, Path writeRoot, Map<String, Object> content, FingerprintBudget budget) {
        if (budget.exhausted()) return InputFingerprint.unknown(InputFingerprint.BUDGET);
        try {
            return DatasetProviders.require().inputFingerprint(content, api.dataRoot(), writeRoot, budget);
        } catch (RuntimeException unresolvable) {
            return null;
        }
    }

    private static String required(Map<String, Object> body, String key) {
        String v = optional(body, key);
        if (v == null) throw bad("body must include '" + key + "'");
        return v;
    }

    /** A stated string, trimmed; absent or JSON null = null; a non-string or a blank one is refused. */
    private static String optional(Map<String, Object> body, String key) {
        Object raw = body.get(key);
        if (raw == null) return null;
        if (!(raw instanceof String s) || s.isBlank()) throw bad("'" + key + "' must be a non-blank string");
        return s.trim();
    }

    private static List<String> attrCols(Map<String, Object> body) {
        Object raw = body.get("attrCols");
        if (raw == null) return List.of();
        if (!(raw instanceof List<?> l) || l.size() > MAX_ATTR_COLS || l.stream().anyMatch(o -> !(o instanceof String s) || s.isBlank()))
            throw bad("'attrCols' must be a list of at most " + MAX_ATTR_COLS + " non-blank column names");
        return l.stream().map(o -> ((String) o).trim()).toList();
    }

    // ── GET /inv/index ────────────────────────────────────────────────────────────────────────────────────────

    private Object list(ApiContext api, HttpExchange ex) throws IOException {
        Path writeRoot = WriteGates.requireWriteRoot(api, "link index");
        Path root = indexRoot(writeRoot);
        List<Object> items = new ArrayList<>();
        Supplier<FingerprintBudget> override = requestBudgetOverride;
        FingerprintBudget budget = override == null ? FingerprintBudget.forRequest() : override.get();   // ONE budget for every Dataset listed
        for (DatasetProvider.Entry c : DatasetProviders.require().datasets(writeRoot)) {
            if (!ComponentAccess.canView(ex, c.content())) continue;           // R3: a Dataset the caller may not view has no index, as far as they can tell
            List<String> hashes;
            try {
                hashes = IndexStore.mappingHashes(root, c.name());
            } catch (IllegalArgumentException cannotNameADirectory) {
                continue;                                                      // such a Dataset can have no index
            }
            if (hashes.isEmpty()) continue;
            String currentSqlHash = currentRelationHash(api, c, writeRoot);
            InputFingerprint input = currentInput(api, writeRoot, c.content(), budget);
            for (String hash : hashes) {
                Map<String, Object> item = item(root, c.name(), hash, currentSqlHash, input);
                if (item != null) items.add(item);
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("enabled", LinkAnalysisSettings.forRoot(writeRoot).effectiveIndex().enabledInForce());
        out.put("indexes", items);
        out.put("total", items.size());
        return out;
    }

    /** The hash of the Dataset's relation SQL now, or null when it cannot be resolved. */
    private static String currentRelationHash(ApiContext api, DatasetProvider.Entry c, Path writeRoot) {
        try {
            return IndexBuilder.relationSqlHash(DatasetProviders.require().relationSql(c.content(), api.dataRoot(), writeRoot));
        } catch (RuntimeException unresolvable) {
            return null;
        }
    }

    /** One index's summary, or null when it has no published version, or its manifest is unreadable, or it belongs to another Dataset. */
    private static Map<String, Object> item(Path root, String dataset, String hash, String currentSqlHash, InputFingerprint input) {
        Optional<Path> current = new IndexStore(root, dataset, hash).current();
        if (current.isEmpty()) return null;
        IndexManifest m;
        try {
            m = IndexManifest.read(current.get());
        } catch (IOException | IllegalArgumentException unreadable) {
            return null;
        }
        if (!dataset.equals(m.dataset())) return null;           // the directory is case-folded: another Dataset's index is not this one's
        String duck;
        try {
            duck = IndexBuilder.duckdbVersion();
        } catch (RuntimeException unknown) {
            duck = null;                                          // the server's own DuckDB version could not be read: say nothing rather than guess
        }
        IndexStaleness.Result st = IndexStaleness.compute(m, currentSqlHash, input, BucketFunction.NAME, duck);
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("dataset", m.dataset());
        o.put("mappingHash", m.mappingHash());
        Map<String, Object> mapping = new LinkedHashMap<>();
        mapping.put("sourceCol", m.mapping().srcColumn());
        mapping.put("targetCol", m.mapping().dstColumn());
        mapping.put("kindCol", m.mapping().kindColumn());
        mapping.put("timeCol", m.mapping().timeColumn());
        mapping.put("timeColZone", m.mapping().timeColZone());
        mapping.put("weightCol", m.mapping().weightColumn());
        mapping.put("attrCols", m.mapping().attributeColumns());
        o.put("mapping", mapping);
        o.put("version", m.version());
        o.put("builtAt", m.builtAt());
        o.put("builder", m.builder().name().toLowerCase(Locale.ROOT));
        o.put("rows", m.tables().containsKey("out") ? m.tables().get("out").rows() : 0L);
        o.put("nodes", m.tables().containsKey("nodes") ? m.tables().get("nodes").rows() : 0L);
        o.put("bytes", m.tables().values().stream().mapToLong(IndexManifest.TableStats::bytes).sum());
        o.put("droppedNull", m.droppedNull());
        o.put("buckets", m.buckets());
        o.put("stale", st.stale());
        o.put("reason", st.stale() ? String.join("; ", st.details()) : null);
        o.put("reasons", st.reasons());
        o.put("removedInput", st.removedInput());
        o.put("deltas", m.deltas().size());
        o.put("plan", plan(m, currentSqlHash, input, duck));
        o.put("fingerprint", st.fingerprintKnown() ? "known" : "unknown");
        o.put("fingerprintReason", st.fingerprintKnown() ? null : InputFingerprint.unknownReason(input));   // no-files, too-many-files, timeout, budget, unavailable
        if (input != null && input.known()) o.put("inputFiles", input.files().size());
        return o;
    }

    /**
     * The staleness probe's advice (D-3 step 8): what differs between the live version and the Dataset now, and which build would
     * close the gap - {@code append} (only new files), {@code full} (anything removed, rewritten or redefined), {@code compact}
     * (the delta cap), {@code none}. It only REPORTS; nothing is ever built or switched by looking.
     */
    private static Map<String, Object> plan(IndexManifest m, String currentSqlHash, InputFingerprint input, String duck) {
        List<IndexManifest.InputFile> now = input != null && input.known()
                ? input.files().stream().map(f -> new IndexManifest.InputFile(f.path(), f.size(), f.mtimeMillis())).toList() : null;
        IndexPlan.Plan p = IndexPlan.classify(m, now, currentSqlHash, BucketFunction.NAME, duck);
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("recommended", p.recommended().name().toLowerCase(Locale.ROOT));
        o.put("appendable", p.appendable());
        o.put("reasons", p.reasons());
        o.put("added", p.added().size());
        o.put("removed", p.removed().size());
        o.put("changed", p.changed().size());
        o.put("addedSample", p.added().stream().limit(PLAN_SAMPLE).toList());
        o.put("removedSample", p.removed().stream().limit(PLAN_SAMPLE).toList());
        o.put("changedSample", p.changed().stream().limit(PLAN_SAMPLE).toList());
        return o;
    }

    /** How many file paths of each kind the probe lists (the counts are exact). */
    private static final int PLAN_SAMPLE = 20;

    // ── GET /inv/index/builds/{id} ────────────────────────────────────────────────────────────────────────────

    private Object get(ApiContext api, HttpExchange ex, String id) throws IOException {
        Path writeRoot = WriteGates.requireWriteRoot(api, "link index build");
        return view(visible(service(writeRoot), api, ex, writeRoot, id));
    }

    /**
     * The build if it exists, the caller started it AND the caller may still view its Dataset; otherwise ONE 404 (status and
     * message) whichever of the three failed - the caller cannot tell an unknown build from another's, or from a Dataset
     * shared away.
     */
    private RunView visible(IndexBuildService svc, ApiContext api, HttpExchange ex, Path writeRoot, String id) {
        RunView v;
        try {
            v = svc.get(id);
        } catch (Refused e) {
            throw e.kind() == Refused.Kind.NOT_FOUND ? absent(id) : map(e);
        }
        Optional<Subject> subject = ApiContext.subject(ex);
        if (subject.isPresent() && !subject.get().id().equals(v.owner())) throw absent(id);
        if (!viewable(ex, writeRoot, v.datasetId())) throw absent(id);
        return v;
    }

    /** The Dataset exists and the caller may view it - the gate of {@link InvRoutes#relationFor} without reading its SQL. */
    private static boolean viewable(HttpExchange ex, Path writeRoot, String datasetId) {
        Optional<Map<String, Object>> dataset = DatasetProviders.require().dataset(writeRoot, datasetId);
        return dataset.isPresent() && ComponentAccess.canView(ex, dataset.get());
    }

    /** The one answer for a build that is unknown, not this caller's, or over a Dataset the caller can no longer view. */
    private static ApiException absent(String id) {
        return new ApiException(404, ErrorCodes.NOT_FOUND, "no index build '" + id + "'");
    }

    // ── POST /inv/index/builds/{id}/cancel ───────────────────────────────────────────────────────────────────

    private Object cancel(ApiContext api, HttpExchange ex, String id) throws IOException {
        Path writeRoot = WriteGates.requireWriteRoot(api, "link index build");
        Optional<Subject> subject = ApiContext.subject(ex);
        boolean admin = subject.isEmpty() || subject.get().capabilities().contains(Roles.CAN_ADMINISTER);
        IndexBuildService svc = service(writeRoot);
        // an administrator may stop any build (not the Dataset's reader by right); anyone else only their own, over a Dataset still viewable
        RunView known = admin ? knownTo(svc, id) : visible(svc, api, ex, writeRoot, id);
        RunView v;
        try {
            v = svc.cancel(known.id(), callerId(ex), admin);
        } catch (Refused e) {
            throw map(e);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("buildId", v.id());
        out.put("status", v.status().name());
        out.put("cancelRequested", v.cancelRequested());
        return ApiContext.respondJson(ex, 202, out);
    }

    private static RunView knownTo(IndexBuildService svc, String id) {
        try {
            return svc.get(id);
        } catch (Refused e) {
            throw e.kind() == Refused.Kind.NOT_FOUND ? absent(id) : map(e);
        }
    }

    // ── the wire shape of one build ──────────────────────────────────────────────────────────────────────────

    private static Map<String, Object> view(RunView v) {
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("buildId", v.id());
        o.put("status", v.status().name());
        o.put("dataset", v.datasetId());
        o.put("mappingHash", v.mappingHash());
        o.put("mode", v.mode().name().toLowerCase(Locale.ROOT));
        Map<String, Object> progress = new LinkedHashMap<>();
        progress.put("phase", v.progress().phase());
        progress.put("step", v.progress().step());
        progress.put("steps", v.progress().steps());
        o.put("progress", progress);
        o.put("cancelRequested", v.cancelRequested());
        if (v.failure() != null) o.put("failure", v.failure());
        o.put("createdAt", v.createdAt());
        if (v.status().terminal()) {
            o.put("finishedAt", v.finishedAt());
            o.put("elapsedMs", v.elapsedMs());
        }
        if (v.status() == Status.COMPLETED && v.result() != null) {
            IndexBuilder.Result r = v.result();
            Map<String, Object> res = new LinkedHashMap<>();
            res.put("version", r.version());
            res.put("rows", r.rowsInRelation());
            res.put("edges", r.edges());
            res.put("droppedNull", r.droppedNull());
            res.put("nodes", r.nodes());
            res.put("buckets", r.buckets());
            res.put("totalMs", r.totalMs());
            if (r.manifest() != null) {
                res.put("builder", r.manifest().builder().name().toLowerCase(Locale.ROOT));
                res.put("indexEdges", r.manifest().tables().containsKey("out") ? r.manifest().tables().get("out").rows() : 0L);
                res.put("deltas", r.manifest().deltas().size());
            }
            if (r.manifest() != null) res.put("bytes", r.manifest().tables().values().stream().mapToLong(IndexManifest.TableStats::bytes).sum());
            o.put("result", res);
        }
        return o;
    }

    // ── helpers ──────────────────────────────────────────────────────────────────────────────────────────────

    private IndexBuildService service(Path writeRoot) {
        return services.get(writeRoot);
    }

    private static ApiException bad(String message) {
        return new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, message);
    }

    private static ApiException map(Refused e) {
        return switch (e.kind()) {
            case NOT_FOUND -> new ApiException(404, ErrorCodes.NOT_FOUND, e.getMessage());
            case FORBIDDEN -> new ApiException(403, ErrorCodes.PERMISSION_DENIED, e.getMessage());
            case TERMINAL, DUPLICATE, NOT_APPLICABLE -> new ApiException(409, ErrorCodes.CONFLICT, e.getMessage());
            case OVER_BUDGET, ESTIMATE_TIMEOUT -> new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, e.getMessage());
            case REJECTED, ESTIMATE_BUSY -> new ApiException(503, ErrorCodes.STORE_BUSY, e.getMessage());
        };
    }

    /** The caller as the build table knows them: the Subject's id, else the request's actor (nothing is enforced without a Subject). */
    private static String callerId(HttpExchange ex) {
        return ApiContext.subject(ex).map(Subject::id).orElseGet(() -> ApiContext.actor(ex));
    }

    // ── audit (best effort: an audit failure never fails the caller's request or a build) ────────────────────

    private static void emitStarted(HttpExchange ex, RunView v) {
        try {
            EventLog.current().emit(base(LinkEventTypes.LINK_INDEX_BUILD_STARTED, v, ApiContext.actor(ex), ApiContext.actorType(ex)));
        } catch (RuntimeException ignored) {
            // best effort
        }
    }

    /** The service's terminal hook: the END of a build, from whichever thread ended it. Never column names - they can embed data. */
    private static void auditTerminal(RunView v) {
        try {
            String type = switch (v.status()) {
                case COMPLETED -> LinkEventTypes.LINK_INDEX_BUILD_COMPLETED;
                case CANCELLED -> LinkEventTypes.LINK_INDEX_BUILD_CANCELLED;
                case FAILED -> LinkEventTypes.LINK_INDEX_BUILD_FAILED;
                case QUEUED, RUNNING -> null;
            };
            if (type == null) return;
            if (type.equals(LinkEventTypes.LINK_INDEX_BUILD_COMPLETED)) {
                InputFingerprintCache.invalidate(v.datasetId(), v.mappingHash());
                IndexReader.evictAll();                                                       // a new version is current: close the old one's idle readers
            }
            Event.Builder b = base(type, v, v.owner(), v.owner().startsWith(ScheduledIndexBuild.PRINCIPAL_PREFIX) ? "service" : "user")
                    .attr("elapsedMs", v.elapsedMs());
            if (v.result() != null) {
                IndexBuilder.Result r = v.result();
                b = b.attr("version", r.version()).attr("rows", r.rowsInRelation()).attr("edges", r.edges()).attr("buckets", r.buckets());
            }
            b = b.attr("mode", v.mode().name().toLowerCase(Locale.ROOT));
            if (v.failure() != null) b = b.attr("failure", v.failure());
            EventLog.current().emit(b);
        } catch (RuntimeException ignored) {
            // best effort
        }
    }

    private static Event.Builder base(String type, RunView v, String actor, String actorType) {
        String action = "link.index.build." + type.substring("LINK_INDEX_BUILD_".length()).toLowerCase(Locale.ROOT);
        return Event.builder(type).source("inv").message(action + " - " + v.datasetId())
                .actor(actor).actorType(actorType).action(action).actionCategory("analysis")
                .attr("dataset", v.datasetId()).attr("mappingHash", v.mappingHash()).attr("runId", v.id());
    }
}
