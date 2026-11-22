package com.gamma.control;

import com.gamma.access.ComponentAccess;
import com.gamma.config.spec.Finding;
import com.gamma.config.spec.Severity;
import com.gamma.config.io.ConfigCodec;
import com.gamma.entitystore.EntityTypes;
import com.gamma.entitystore.IndustryPack;
import com.gamma.entitystore.LaLimit;
import com.gamma.entitystore.LaLimitValues;
import com.gamma.entitystore.LinkAnalysisSettings;
import com.gamma.etl.EditionFeatures;
import com.gamma.etl.PipelineConfig;
import com.gamma.etl.SchemaDrift;
import com.gamma.onboard.ImportFollowOn;
import com.gamma.pipeline.ComponentRegistry;
import com.gamma.pipeline.ComponentStore;
import com.gamma.service.CollectorService;
import com.gamma.spi.auth.ApiException;
import com.gamma.spi.auth.ErrorCodes;
import com.gamma.spi.http.ApiContext;
import com.gamma.util.AtomicFiles;
import com.gamma.util.DuckDbUtil;
import com.gamma.util.SqlIdent;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;

/**
 * The commit half of Add data ({@code LA-ONBOARD-1} OB-2, OB-3, OB-4, OB-8), plus <b>Add a file</b>, <b>Run now</b> and the
 * column classification change. See {@link OnboardRoutes} for the route family and its gates.
 *
 * <h3>What a commit writes (DT-2: no parallel ingest path)</h3>
 * <ol>
 *   <li>{@code <id>_schema.toon} and {@code <id>_pipeline.toon} under {@code imports/} in the Space's config tree, each
 *       passed through {@link SaveGate} first (any ERROR refuses with its findings, nothing written). The Pipeline is an
 *       ordinary one, marked {@code origin: import} (OB-3), with an explicit {@code trigger: {type: manual}}: it never
 *       polls on any edition (DT-17) until someone on Standard turns it into a kept-up-to-date one. Delimited files get
 *       column-INDEX selectors (OB-4); name-addressed formats (JSON, Parquet, Excel) get key names.</li>
 *   <li>The Pipeline is registered, the file is landed in its inbox and the Pipeline is run once, here.</li>
 *   <li>The Dataset is registered ({@code physicalRef: <id>/database}) with column classifications,
 *       {@code primaryEntityType} and {@code defaultLinkMapping} (OB-8), {@code origin: import}, {@code kind}.</li>
 *   <li>Inside the same user-started run (DT-16; not a signal-driven Job): the Dataset's link index is built and the
 *       Expectations that target the Pipeline are evaluated, in every edition (operator 2026-11-05).</li>
 * </ol>
 * <b>A failed commit leaves nothing behind</b>: every write above is undone in reverse (config files, registration,
 * data and inbox directories, the Dataset entry), and the uploaded file goes back to its draft so the user can change
 * a choice and commit again. Expectations, quarantine and dedupe behave as in every Pipeline (DT-15): rejected rows
 * are counted and kept aside, a quarantined file is reported with its reason.
 *
 * <p><b>Inline or followed.</b> Up to {@code import.inline_max_bytes} the run happens inside the request and the answer
 * is the run summary (200). Above it the writes, registration and landing happen inside the request, the run continues
 * in the background, and the answer is 202 with a {@code runId} followed at {@code GET /onboard/runs/{runId}}; a failure
 * there undoes everything exactly as above. The background run captures the Space's roots and service on the request
 * thread - it never re-resolves "the current Space". It also runs under the request's logging context (its Space id,
 * {@code MDC}): the readers that key on the thread's Space - the Consignment registry, the event log, the link index
 * build - would otherwise serve the default Space's ({@code LA-FULL-CYCLE-TEST-1} phase 4: a 202 import left its link
 * index out of date at once in every other Space).
 */
final class OnboardImport {

    private OnboardImport() {}

    /** Where import configs live inside the Space's config tree. */
    static final String CONFIG_DIR = "imports";
    private static final Pattern ID = Pattern.compile("[a-z][a-z0-9_]{0,62}");
    private static final Pattern COLUMN = Pattern.compile("[A-Za-z_][A-Za-z0-9_]{0,63}");
    private static final Set<String> TYPES = Set.of("VARCHAR", "BIGINT", "DOUBLE", "DATE", "TIMESTAMP", "TIME", "BOOLEAN");
    /** Pipelines that run on their own (Standard and up): <i>Keep this up to date</i> and a retention policy. */
    static final String AUTOMATION_FEATURE = EditionFeatures.PIPELINE_AUTOMATION;
    /**
     * BASIC-ONBOARD-1 P1: the default Pipeline's four steps, named in every import's Pipeline config. Read by no engine
     * code (like {@code origin}); an import written before carries none and means the same.
     */
    static final List<String> IMPORT_STEPS = List.of("read", "arrange", "write_parquet", "follow_on");

    /** Ids being imported right now in this JVM (a second commit of the same id is 409, not a race). */
    static final Set<String> IN_FLIGHT = ConcurrentHashMap.newKeySet();
    /** The claim whose Pipeline Add data is running on this thread right now ({@link Job#runChecked}); null otherwise. */
    private static final ThreadLocal<String> OWN_RUN = new ThreadLocal<>();

    /**
     * Whether the run asking is Add data's own run of the import it has claimed ({@code claim}, {@code writeRoot|id}): the
     * engine runs a Pipeline's pre-run steps on the thread that asked for the run, so only Add data's own call is on the
     * thread that set it. Any other run of a claimed import is refused (O7, {@link ImportPreRunSteps}).
     */
    static boolean runsOwn(String claim) {
        return claim != null && claim.equals(OWN_RUN.get());
    }
    /** Followed runs (202), newest last; bounded. */
    private static final Map<String, Map<String, Object>> RUNS = Collections.synchronizedMap(new LinkedHashMap<>() {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Map<String, Object>> eldest) {
            return size() > 200;
        }
    });
    private static final ExecutorService BACKGROUND = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "onboard-import");
        t.setDaemon(true);
        return t;
    });

    /** Everything a run needs, captured on the request thread. */
    record Space(Path writeRoot, Path dataRoot, CollectorService service, String actor) {}

    // ── POST /onboard/files/{draftId}/commit ────────────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    static Object commit(ApiContext api, HttpExchange ex, String draftId, Map<String, Object> body) throws IOException {
        Path writeRoot = OnboardRoutes.requireSelfImport(api, "Add data");
        Path dataRoot = OnboardRoutes.requireDataRoot(api);
        Map<String, Object> draft = OnboardRoutes.readDraft(dataRoot, draftId, ex);
        Path draftDir = dataRoot.resolve(OnboardRoutes.DRAFTS).resolve(draftId);
        Path draftFile = draftDir.resolve(String.valueOf(draft.get("stored")));
        if (!Files.isRegularFile(draftFile))
            throw new ApiException(404, ErrorCodes.NOT_FOUND, "the draft's file is gone; add the file again");
        LaLimitValues lim = OnboardRoutes.limits(writeRoot);
        String kind = String.valueOf(draft.get("kind"));
        String fileName = String.valueOf(((Map<String, Object>) draft.get("file")).get("name"));
        String id = datasetId(body.get("name"), proposedStem(draft, fileName));

        // claim the id BEFORE the existence checks: a commit of the same id finishing between check and write would
        // otherwise be undone by this one's rollback
        String key = writeRoot + "|" + id;
        if (!IN_FLIGHT.add(key))
            throw new ApiException(409, ErrorCodes.CONFLICT, "'" + id + "' is being imported right now");
        boolean handedOff = false;
        try {
            // ── decide everything before writing anything ─────────────────────────────────────────────────────
            List<EntityTypes.EntityType> types = LinkAnalysisSettings.forRoot(writeRoot).effectiveEntityTypes();
            body = withFoundFormats(draft, body, draftDir, draftFile, fileName);
            Plan plan = Plan.of(draft, body, types, kind);
            PendingChanges.holdRefusing(api, List.of("dataset", "pipeline", "schema"), "Add data writes a Pipeline, its schema and a Dataset");
            ComponentStore registry = new ComponentStore(writeRoot.resolve("registry"));
            if (registry.exists("dataset", id))
                throw new ApiException(409, ErrorCodes.CONFLICT, "a Dataset '" + id + "' already exists; choose another name or use Add a file on it");
            CollectorService service = HostContext.of(api).service();
            if (service.configFor(id).isPresent())
                throw new ApiException(409, ErrorCodes.CONFLICT, "a Pipeline '" + id + "' already exists; choose another name");
            Path configDir = writeRoot.resolve(CONFIG_DIR);
            Path pipelineFile = configDir.resolve(id + "_pipeline.toon"), schemaFile = configDir.resolve(id + "_schema.toon");
            Path snapshotFile = PostgresSnapshot.configFile(writeRoot, id);
            if (Files.exists(pipelineFile) || Files.exists(schemaFile) || Files.exists(snapshotFile))
                throw new ApiException(409, ErrorCodes.CONFLICT, "config files for '" + id + "' already exist; choose another name");
            // BASIC-ONBOARD-1 P3: a draft copied from Postgres keeps its connection and table, so Run now can copy it again;
            // BASIC-ELT-PROFILE-1 BE-4 (O4): as a saved postgres Connection
            PostgresSnapshot.Saved snapshot = draft.get("snapshot") instanceof Map<?, ?> sm
                    ? PostgresSnapshot.Saved.plan(api, id, (Map<String, Object>) sm) : null;
            if (Files.exists(dataRoot.resolve(id)) || Files.exists(inbox(dataRoot, id)))
                throw new ApiException(409, ErrorCodes.CONFLICT, "the data directory already holds '" + id + "'; choose another name");
            long max = lim.get(LaLimit.IMPORT_MAX_DATASETS);
            if (OnboardRoutes.importCount(writeRoot) >= max)
                throw new ApiException(409, ErrorCodes.CONFLICT, "this Space already holds " + max
                        + " imported Datasets (import.max_datasets); remove one or ask an administrator to raise the limit");

            Map<String, Object> schema = plan.schema(id);
            String type = String.valueOf(draft.get("type"));
            Map<String, Object> pipeline = plan.pipeline(id, dataRoot, kind, fileName, type,
                    ("csv".equals(type) || "tsv".equals(type)) && holdsDoubleQuote(draftFile));
            Map<String, Object> dataset = plan.dataset(id, kind, fileName, String.valueOf(draft.get("type")));
            String recordType = commitRecordType(draft, body, LinkAnalysisSettings.forRoot(writeRoot));
            if (recordType != null) dataset.put(DatasetEntryRules.RECORD_TYPE, recordType);
            if (snapshot != null) {
                dataset.put("importSource", PostgresSnapshot.SOURCE);
                if (!(body.get("description") instanceof String d && !d.isBlank()))
                    dataset.put("description", "Copied from Postgres with Add data");
            }
            // Shared with the Space like every other Dataset in it (LA-ONBOARD-DEFECTS-1): no `shares` key, so `owner` (the
            // importer, stamped here) is provenance, not a restriction. It was `shares: []`, private to the importer, and no
            // screen could share it, so colleagues saw "No Dataset in this Space has a link yet".
            dataset = ComponentAccess.onCreate(ex, dataset);
            try {
                ComponentRoutes.validateKind(api, "dataset", id, dataset);
            } catch (IllegalArgumentException bad) {
                throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, bad.getMessage());
            }
            refuseFindings("schema", SaveGate.check(api, "schema", schema, writeRoot, configDir, SaveGate.Referents.MAY_ARRIVE_LATER));

            Space space = new Space(writeRoot, dataRoot, service, ApiContext.actor(ex));
            Job job = new Job(space, id, kind, draftFile, fileName, dataset, false);
            job.fromDraft = true;
            try {
                // ── write: schema, then the pipeline (gated against the schema now on disk), register, land ──────
                Files.createDirectories(configDir);
                AtomicFiles.write(schemaFile, ConfigCodec.toToon(schema).getBytes(StandardCharsets.UTF_8), ".cfg-");
                job.undo.push(() -> Files.deleteIfExists(schemaFile));
                List<Finding> pf = SaveGate.check(api, "pipeline", pipeline, writeRoot, configDir, SaveGate.Referents.MUST_EXIST);
                if (SaveGate.refuses(pf)) {
                    job.rollback();
                    refuseFindings("pipeline", pf);
                }
                AtomicFiles.write(pipelineFile, ConfigCodec.toToon(pipeline).getBytes(StandardCharsets.UTF_8), ".cfg-");
                job.undo.push(() -> Files.deleteIfExists(pipelineFile));
                if (snapshot != null) {
                    job.undo.push(() -> snapshot.undo(snapshotFile));
                    snapshot.write(api, snapshotFile);
                }
                job.undo.push(() -> {
                    service.unregisterPipeline(pipelineFile);
                    OnboardRoutes.deleteTree(dataRoot.resolve(id));
                    OnboardRoutes.deleteTree(inbox(dataRoot, id));
                });
                try {
                    service.registerPipeline(pipelineFile);
                } catch (IllegalArgumentException | IllegalStateException refused) {
                    job.rollback();
                    throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "the import's Pipeline did not load: " + refused.getMessage());
                }
                job.landFromDraft(String.valueOf(draft.get("type")));

                // O7: a snapshot's first run imports the draft's copy as every commit does; then its Pipeline reads Postgres
                // itself (PostgresSnapshot.switchPipeline), still under this commit's claim
                if (Files.size(job.landed) > lim.get(LaLimit.IMPORT_INLINE_MAX_BYTES)) {
                    Map<String, Object> rec = follow(job, "commit", () -> {
                        Map<String, Object> summary = job.finishCommit(draftDir);
                        if (snapshot != null) PostgresSnapshot.switchAfterCommit(api, space, id);
                        return summary;
                    });
                    handedOff = true;
                    return ApiContext.respondJson(ex, 202, rec);
                }
                Map<String, Object> summary = job.finishCommit(draftDir);
                if (snapshot != null) PostgresSnapshot.switchAfterCommit(api, space, id);
                return summary;
            } catch (IOException | RuntimeException fault) {
                if (!handedOff) job.rollback();
                throw fault;
            }
        } finally {
            if (!handedOff) IN_FLIGHT.remove(key);
        }
    }

    /**
     * {@code LA-FULL-CYCLE-TEST-1} finding 5: a date type stated only here, with no format, gets the format most of the
     * column's values are written in (the draft's file is read once more, as a redraft reads it). Without it the import
     * reads such a column with the Pipeline's one default format and leaves every other value blank - a whole column of
     * ISO date-times imported as nothing. The screen states types through a redraft, so this is for every other caller.
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> withFoundFormats(Map<String, Object> draft, Map<String, Object> body, Path dir, Path file,
                                                        String fileName) throws IOException {
        if (!(body.get("columns") instanceof List<?> columns) || !(draft.get("plan") instanceof List<?> plan)) return body;
        Map<String, Object> planned = new LinkedHashMap<>();
        for (Object p : plan) if (p instanceof Map<?, ?> m) planned.put(String.valueOf(m.get("name")), m.get("type"));
        Map<String, String> stated = new LinkedHashMap<>();
        for (Object o : columns) {
            if (!(o instanceof Map<?, ?> m) || !(m.get("name") instanceof String n) || !(m.get("type") instanceof String t)
                    || m.containsKey("format") || Boolean.TRUE.equals(m.get("drop")) || !planned.containsKey(n)) continue;
            String type = t.trim().toUpperCase(Locale.ROOT);
            if ((type.equals("DATE") || type.equals("TIMESTAMP")) && !type.equals(planned.get(n))) stated.put(n, type);
        }
        if (stated.isEmpty()) return body;
        Map<String, String> found = new LinkedHashMap<>();
        for (OnboardDraft.Col c : OnboardDraft.analyse(dir, file, fileName, String.valueOf(draft.get("type")),
                OnboardRoutes.formatOverrides(draft), stated).cols())
            if (stated.containsKey(c.name()) && c.format() != null) found.put(c.name(), c.format());
        if (found.isEmpty()) return body;
        List<Object> withFormats = new ArrayList<>();
        for (Object o : columns) {
            if (o instanceof Map<?, ?> m && found.containsKey(m.get("name"))) {
                Map<String, Object> c = new LinkedHashMap<>((Map<String, Object>) m);
                c.put("format", found.get(m.get("name")));
                withFormats.add(c);
            } else withFormats.add(o);
        }
        Map<String, Object> out = new LinkedHashMap<>(body);
        out.put("columns", withFormats);
        return out;
    }

    private static void refuseFindings(String what, List<Finding> findings) {
        if (!SaveGate.refuses(findings)) return;
        StringBuilder sb = new StringBuilder();
        for (Finding f : findings)
            if (f.severity() == Severity.ERROR) {
                if (!sb.isEmpty()) sb.append("; ");
                sb.append(f.fieldPath()).append(": ").append(f.message());
            }
        throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "the import's " + what + " was refused: " + sb);
    }

    /**
     * The name a Dataset takes when the commit states none: the file name without the date it holds
     * ({@code format.fileDate.name}; {@code sales_2026-10.csv} → {@code sales}, C1), else the file name.
     */
    @SuppressWarnings("unchecked")
    static String proposedStem(Map<String, Object> draft, String fileName) {
        if (draft.get("format") instanceof Map<?, ?> f && f.get("fileDate") instanceof Map<?, ?> d && d.get("name") instanceof String n && !n.isBlank())
            return n;
        return fileName;
    }

    /** {@code name} (or the file name's stem) as a Dataset and Pipeline id: lower-case letters, digits and {@code _}. */
    static String datasetId(Object requested, String fileName) {
        String raw = requested instanceof String s && !s.isBlank() ? s.trim() : fileName.replaceFirst("\\.[A-Za-z0-9]{1,8}$", "");
        String id = raw.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_]+", "_").replaceAll("^_+|_+$", "");
        if (id.isEmpty() || !Character.isLetter(id.charAt(0))) id = "data_" + id;
        if (id.length() > 63) id = id.substring(0, 63);
        id = id.replaceAll("_+$", "");
        if (!ID.matcher(id).matches())
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "'" + raw + "' cannot name a Dataset");
        if (requested instanceof String s && !s.isBlank() && !id.equals(s.trim()))
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "name must be lower-case letters, digits and '_', "
                    + "starting with a letter (for example '" + id + "')");
        return id;
    }

    static Path inbox(Path dataRoot, String id) {
        return dataRoot.resolve("inbox").resolve(id);
    }

    // ── the plan: the user's confirmed choices over the draft's proposals ───────────────────────────────────

    /**
     * One column as it will be written. {@code source} is the draft name the body refers to it by (and the schema's raw
     * field name); {@code name} the Dataset's. A dropped column stays a raw field (delimited files are read by position)
     * and is left out of the mapping, so it never reaches the Dataset.
     */
    record PlanCol(String source, String name, String selector, String type, String format, String classification, String entityType,
                   boolean keep, String role, String decimal) {}

    record Plan(List<PlanCol> cols, Map<String, Object> format, String primaryEntityType, Map<String, Object> link,
                String eventDate, String identifying, String description, String frontendType, ImportRowFilter.Result filter) {

        @SuppressWarnings("unchecked")
        static Plan of(Map<String, Object> draft, Map<String, Object> body, List<EntityTypes.EntityType> types, String kind) {
            Map<String, Object> draftFormat = (Map<String, Object>) draft.getOrDefault("format", Map.of());
            if (body.get("format") instanceof Map<?, ?> f)
                for (var e : f.entrySet()) {
                    Object proposed = draftFormat.get(String.valueOf(e.getKey())) instanceof Map<?, ?> c ? c.get("value") : null;
                    if (!java.util.Objects.equals(String.valueOf(proposed), String.valueOf(e.getValue())))
                        throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "format." + e.getKey()
                                + " differs from the draft: add the file again with ?" + e.getKey() + "=… so the columns are re-read");
                }
            // proposals per draft column
            Map<String, Map<String, Object>> entityByColumn = new LinkedHashMap<>();
            Map<String, String> roleByColumn = new LinkedHashMap<>();   // LA-COLUMN-ROLES-1 P1: the proposed role
            Map<String, Object> et = (Map<String, Object>) draft.getOrDefault("entityTypes", Map.of());
            for (Object o : (List<Object>) et.getOrDefault("columns", List.of()))
                if (o instanceof Map<?, ?> m) {
                    entityByColumn.put(String.valueOf(m.get("column")), (Map<String, Object>) m.get("entityType"));
                    String r = proposed(m.get("role"));
                    if (r != null) roleByColumn.put(String.valueOf(m.get("column")), r);
                }
            Map<String, Map<String, Object>> overrides = new LinkedHashMap<>();
            if (body.get("columns") != null) {
                if (!(body.get("columns") instanceof List<?> l))
                    throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "columns must be a list of {name, type?, format?, entityType?, role?, rename?, drop?}");
                for (Object o : l) {
                    if (!(o instanceof Map<?, ?> m) || !(m.get("name") instanceof String n))
                        throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "every columns[] entry needs the draft column's name");
                    overrides.put(n, (Map<String, Object>) m);
                }
            }
            List<Map<String, Object>> planned = (List<Map<String, Object>>) draft.get("plan");
            Set<String> known = new HashSet<>();
            for (Map<String, Object> p : planned) known.add(String.valueOf(p.get("name")));
            for (String n : overrides.keySet())
                if (!known.contains(n)) throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "the draft has no column '" + n + "'");

            List<PlanCol> cols = new ArrayList<>();
            Set<String> outNames = new HashSet<>();
            Map<String, String> renamed = new LinkedHashMap<>();   // draft name -> output name, kept columns only
            for (Map<String, Object> p : planned) {
                String n = String.valueOf(p.get("name"));
                Map<String, Object> o = overrides.getOrDefault(n, Map.of());
                if (Boolean.TRUE.equals(o.get("drop"))) {
                    cols.add(new PlanCol(n, n, String.valueOf(p.get("selector")), "VARCHAR", null, null, null, false, null, null));
                    continue;
                }
                String out = o.get("rename") instanceof String r && !r.isBlank() ? r.trim() : n;
                if (!COLUMN.matcher(out).matches())
                    throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "'" + out + "' cannot name a column: letters, digits and '_'");
                if (!outNames.add(out.toLowerCase(Locale.ROOT)))
                    throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "two columns would be named '" + out + "'");
                String type = o.get("type") instanceof String t ? t.replace(" ", "").toUpperCase(Locale.ROOT) : String.valueOf(p.get("type"));
                if (!TYPES.contains(type) && !OnboardDraft.isDecimal(type))
                    throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "type of '" + n + "' must be one of " + TYPES + " or DECIMAL(p,s)");
                String fmt = o.containsKey("format") ? (o.get("format") == null ? null : String.valueOf(o.get("format")))
                        : o.containsKey("type") && !type.equals(p.get("type")) ? null : (String) p.get("format");
                if (fmt != null && !fmt.isBlank() && !type.equals("DATE") && !type.equals("TIMESTAMP"))
                    throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "format applies to DATE and TIMESTAMP columns only ('" + n + "')");
                if (fmt != null && (fmt.contains("%z") || fmt.contains("%Z")))
                    throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "a time-zone directive is not accepted in '" + n + "''s format");
                String typeId;
                if (o.containsKey("entityType")) typeId = o.get("entityType") == null ? null : String.valueOf(o.get("entityType")).trim();
                else {
                    Map<String, Object> prop = entityByColumn.get(n);
                    typeId = prop == null || prop.get("value") == null ? null : String.valueOf(prop.get("value"));
                }
                String classification = null;
                if (typeId != null && !typeId.isEmpty()) {
                    EntityTypes.EntityType t = types.stream().filter(x -> x.id().equals(typeId)).findFirst().orElseThrow(() ->
                            new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "this Space has no Entity Type '" + typeId + "'"));
                    Map<String, Object> prop = entityByColumn.get(n);
                    Object proposed = prop == null ? null : prop.get("classification");
                    classification = proposed != null && typeId.equals(prop.get("value")) ? String.valueOf(proposed)
                            : t.classifications().isEmpty() ? null : t.classifications().get(0);
                }
                boolean holds = typeId != null && !typeId.isEmpty();
                // C1: a number written with a decimal mark the cast cannot read is read through that mark, while it stays a number
                String decimal = p.get("decimal") instanceof String dm && (type.equals("DOUBLE") || type.equals("BIGINT")) ? dm : null;
                cols.add(new PlanCol(n, out, String.valueOf(p.get("selector")), type, fmt == null || fmt.isBlank() ? null : fmt,
                        classification, holds ? typeId : null, true, role(n, o, roleByColumn.get(n), holds, type), decimal));
                renamed.put(n, out);
            }
            if (renamed.isEmpty()) throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "every column was dropped");

            // Dataset level
            String primary = body.containsKey("primaryEntityType") ? stringOrNull(body, "primaryEntityType")
                    : proposed(et.get("primary"));
            if (primary != null && cols.stream().noneMatch(c -> c.keep() && primary.equals(c.entityType())))
                throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "primaryEntityType '" + primary + "' is held by no column");
            Map<String, Object> link = null;
            String eventDate = null;
            String identifying = null;
            if ("reference".equals(kind)) {
                String idc = body.containsKey("identifyingColumn") ? stringOrNull(body, "identifyingColumn") : proposed(et.get("identifying"));
                if (idc != null) {
                    identifying = renamed.get(idc);
                    if (identifying == null) throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "identifyingColumn '" + idc + "' is not a kept column");
                }
            } else {
                Map<String, Object> proposedLink = (Map<String, Object>) et.getOrDefault("link", Map.of());
                Map<String, Object> l = new LinkedHashMap<>();
                boolean stated = body.containsKey("link");
                if (stated && body.get("link") != null && !(body.get("link") instanceof Map))
                    throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED,
                            "link must be {from, to, kind?, primaryTime?, primaryMeasure?, details?} or null");
                Map<String, Object> given = stated ? (Map<String, Object>) body.get("link") : null;
                if (!stated || given != null) {
                    for (String k : List.of("from", "to", "kind", DatasetEntryRules.PRIMARY_TIME, DatasetEntryRules.PRIMARY_MEASURE)) {
                        // a body may still say time / amount, the primaries' old names (one or the other, as stored entries)
                        String v = given != null ? givenLink(given, k) : proposed(proposedLink.get(k));
                        if (v == null) continue;
                        String out = renamed.get(v);
                        if (out == null) throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "link." + k + " '" + v + "' is not a kept column");
                        l.put(k, out);
                    }
                    Object details = given != null ? given.get("details") : proposedValue(proposedLink.get("details"));
                    if (details instanceof List<?> dl) {
                        List<String> d = new ArrayList<>();
                        for (Object x : dl) if (renamed.containsKey(String.valueOf(x))) d.add(renamed.get(String.valueOf(x)));
                        l.put("details", d);
                    }
                    if (l.containsKey("from") && l.containsKey("to")) link = l;
                    else if (given != null) throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "link needs a from and a to column");
                }
                // ADD-DATA-STREAM-TIME-1: the Time is the Stream's own (what an arrival covers), kept with no link too - the
                // stated eventDate, else the link's primaryTime, else (nothing stated) the draft's proposed Time
                eventDate = link == null ? null : (String) link.get(DatasetEntryRules.PRIMARY_TIME);
                String ed = body.containsKey("eventDate") ? stringOrNull(body, "eventDate") : null;
                if (ed != null) {
                    String out = renamed.get(ed);
                    if (out == null) throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "eventDate '" + ed + "' is not a kept column");
                    if (eventDate != null && !eventDate.equals(out))
                        throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "eventDate '" + ed + "' differs from link.primaryTime '" + eventDate + "'");
                    eventDate = out;
                } else if (eventDate == null && !stated && !body.containsKey("eventDate"))
                    eventDate = renamed.get(proposed(proposedLink.get(DatasetEntryRules.PRIMARY_TIME)));
            }
            if (link != null) cols = linkRoles(cols, overrides, link);
            else if (eventDate != null) cols = linkRoles(cols, overrides, Map.of(DatasetEntryRules.PRIMARY_TIME, eventDate));
            String description = body.get("description") instanceof String s && !s.isBlank() ? s.trim() : null;
            // ADD-DATA-ROW-FILTER-1: the rows to keep, over the kept columns as they will be typed and named
            List<ImportRowFilter.Column> filterable = new ArrayList<>();
            Set<String> left = new HashSet<>();
            for (PlanCol c : cols)
                if (c.keep()) filterable.add(new ImportRowFilter.Column(c.source(), c.name(), c.type()));
                else left.add(c.source());
            ImportRowFilter.Result filter = ImportRowFilter.of(body.get("filter"), filterable, left);
            return new Plan(cols, draftFormat, primary, link, eventDate, identifying, description, String.valueOf(draft.get("type")), filter);
        }

        /**
         * LA-COLUMN-ROLES-1 P1: a kept column's role. A stated {@code role} is taken as given (the Dataset rules check it);
         * a column that holds an Entity Type is an Entity; else the draft's proposal while it still fits the column's
         * (possibly changed) type, else a Detail.
         */
        private static String role(String column, Map<String, Object> o, String proposed, boolean holds, String type) {
            if (o.containsKey("role") && o.get("role") != null) {
                String r = String.valueOf(o.get("role")).trim().toLowerCase(Locale.ROOT);
                if (!DatasetEntryRules.ROLES.contains(r))
                    throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "role of '" + column + "' must be one of " + DatasetEntryRules.ROLES);
                if ("entity".equals(r) != holds)
                    throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, holds
                            ? "'" + column + "' holds an Entity Type, so its role is entity"
                            : "'" + column + "' is an Entity column only when it holds an Entity Type (entityType)");
                return r;
            }
            if (holds) return "entity";
            if (proposed == null || "entity".equals(proposed)) return "detail";
            boolean fits = switch (proposed) {
                case "temporal" -> type.equals("DATE") || type.equals("TIMESTAMP");
                case "measure" -> type.equals("DOUBLE") || type.equals("BIGINT") || OnboardDraft.isDecimal(type);
                default -> true;
            };
            return fits ? proposed : "detail";
        }

        /**
         * The link's kind is a Category, its primary Time a Time and its primary Measure a Measure, unless the body stated
         * the column's role itself (then the Dataset rules refuse a contradiction) or the column holds an Entity Type.
         */
        private static List<PlanCol> linkRoles(List<PlanCol> cols, Map<String, Map<String, Object>> overrides, Map<String, Object> link) {
            Map<String, String> want = new LinkedHashMap<>();
            for (var e : Map.of("kind", "dimension", DatasetEntryRules.PRIMARY_TIME, "temporal", DatasetEntryRules.PRIMARY_MEASURE, "measure")
                    .entrySet())
                if (link.get(e.getKey()) instanceof String c) want.put(c, e.getValue());
            List<PlanCol> out = new ArrayList<>();
            for (PlanCol c : cols) {
                String r = want.get(c.name());
                Object stated = overrides.getOrDefault(c.source(), Map.of()).get("role");
                out.add(c.keep() && r != null && stated == null && c.entityType() == null
                        ? new PlanCol(c.source(), c.name(), c.selector(), c.type(), c.format(), c.classification(), c.entityType(), true, r, c.decimal())
                        : c);
            }
            return out;
        }

        private static String givenLink(Map<String, Object> given, String key) {
            String old = DatasetEntryRules.PRIMARY_TIME.equals(key) ? "time" : DatasetEntryRules.PRIMARY_MEASURE.equals(key) ? "amount" : null;
            try {
                return old == null ? (given.get(key) == null ? null : String.valueOf(given.get(key))) : com.gamma.linkindex.LinkMapping.primary(given, key, old);
            } catch (IllegalArgumentException twoNames) {
                throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "link: " + twoNames.getMessage());
            }
        }

        /** A body string, or null; anything else is 422 (never a ClassCastException 500). */
        private static String stringOrNull(Map<String, Object> body, String key) {
            Object v = body.get(key);
            if (v == null || v instanceof String) return (String) v;
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, key + " must be a string or null");
        }

        private static String proposed(Object choice) {
            Object v = proposedValue(choice);
            return v == null ? null : String.valueOf(v);
        }

        private static Object proposedValue(Object choice) {
            return choice instanceof Map<?, ?> m ? m.get("value") : null;
        }

        private Object formatValue(String key) {
            return format.get(key) instanceof Map<?, ?> m ? m.get("value") : null;
        }

        Map<String, Object> schema(String id) {
            Map<String, Object> raw = new LinkedHashMap<>();
            raw.put("name", id);
            raw.put("format", "parquet".equals(frontendType) ? "PARQUET" : "CSV");
            List<Map<String, Object>> fields = new ArrayList<>();
            List<Map<String, Object>> mapping = new ArrayList<>();
            for (PlanCol c : cols) {
                Map<String, Object> f = new LinkedHashMap<>();
                f.put("name", c.source());
                f.put("selector", c.selector());
                f.put("type", c.decimal() != null ? "VARCHAR" : c.type());
                if (c.format() != null) f.put("format", c.format());
                fields.add(f);
                if (!c.keep()) continue;
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("name", c.name());
                m.put("from", c.source());
                if (c.decimal() != null) {
                    // the raw text is read through its decimal mark (a plain cast reads '1.234,50' as nothing)
                    m.put("fn", "custom");
                    m.put("args", Map.of("expression", DecimalMark.sql(SqlIdent.q(c.source()), c.type(), c.decimal())));
                } else {
                    m.put("fn", "keep");
                }
                mapping.add(m);
            }
            raw.put("fields", fields);
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("canonicalName", id);
            map.put("rawName", id);
            map.put("fields", mapping);
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("raw", raw);
            s.put("mapping", map);
            return s;
        }

        /**
         * The import's Pipeline. {@code holdsQuote}: the file holds a double quote anywhere - only then does a draft that
         * read it with no quote write {@code quote: ""}; otherwise the engine's default quote reads the file alike and
         * keeps a later file's quoted fields whole.
         */
        Map<String, Object> pipeline(String id, Path dataRoot, String kind, String fileName, String type, boolean holdsQuote) {
            String d = dataRoot.resolve(id).toString().replace('\\', '/');
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("name", id);
            p.put("active", true);
            p.put("origin", "import");
            p.put("import_steps", IMPORT_STEPS);
            p.put("description", description != null ? description : "Imported from " + fileName + " with Add data");
            if ("reference".equals(kind)) p.put("produces", "reference");
            p.put("trigger", Map.of("type", "manual"));
            Map<String, Object> dirs = new LinkedHashMap<>();
            dirs.put("poll", inbox(dataRoot, id).toString().replace('\\', '/'));
            dirs.put("database", d + "/database");
            dirs.put("backup", d + "/backup");
            dirs.put("temp", d + "/temp");
            dirs.put("errors", d + "/errors");
            dirs.put("quarantine", d + "/quarantine");
            dirs.put("markers", d + "/markers");
            dirs.put("status_dir", d + "/status");
            dirs.put("log_dir", d + "/logs");
            p.put("dirs", dirs);
            p.put("output", Map.of("format", "PARQUET", "compression", "snappy"));
            Map<String, Object> proc = new LinkedHashMap<>();
            proc.put("threads", 1);
            proc.put("file_pattern", "glob:**/*." + extension(type));
            proc.put("duplicate_check", Map.of("enabled", true, "marker_extension", ".processed"));
            proc.put("schema_file", id + "_schema.toon");
            Map<String, Object> csv = new LinkedHashMap<>();
            if ("csv".equals(type) || "tsv".equals(type)) {
                csv.put("delimiter", String.valueOf(formatValue("delimiter")));
                csv.put("has_header", Boolean.parseBoolean(String.valueOf(formatValue("header"))));
                // the draft's quote as it read the file (BASIC-ELT-PROFILE-1 BE-3): "" is no quote, written when the file
                // holds a '"' the default quote would read differently; absent, the engine's '"' reads a quote-free file alike
                Object q = formatValue("quote");
                if (q != null && (holdsQuote || !String.valueOf(q).isEmpty())) csv.put("quote", String.valueOf(q));
                Object skip = formatValue("skipLines");   // the lines the draft's sniff skipped before the header
                if (skip != null) csv.put("skip_header_lines", Integer.parseInt(String.valueOf(skip)));
                Object enc = formatValue("encoding");   // C2: the encoding fix
                if (enc != null && !"utf-8".equals(enc)) csv.put("encoding", String.valueOf(enc));
            }
            csv.put("date_formats", List.of("%Y-%m-%d"));
            csv.put("timestamp_formats", List.of("%Y-%m-%d %H:%M:%S"));
            // ADD-DATA-ROW-FILTER-1: the post-parse row predicate over the Dataset's typed columns (every frontend); every
            // later run of this Pipeline applies it - Add a file, Run now, the inbox poll, a snapshot refresh
            if (filter.where() != null) csv.put("where", filter.where());
            proc.put("csv_settings", csv);
            p.put("processing", proc);
            Map<String, Object> parsing = new LinkedHashMap<>();
            switch (type) {
                case "json" -> {
                    parsing.put("frontend", "json");
                    // C1: a document holding its records in a list names that list (the engine's json.records_path)
                    Object path = formatValue("recordPath");
                    parsing.put("json", path == null || "$".equals(path) ? Map.of("format", "array")
                            : Map.of("format", "auto", "records_path", String.valueOf(path)));
                }
                case "ndjson" -> {
                    parsing.put("frontend", "json");
                    parsing.put("json", Map.of("format", "newline"));
                }
                case "parquet" -> parsing.put("frontend", "parquet");
                case "excel" -> {
                    parsing.put("frontend", "xlsx");
                    parsing.put("xlsx", Map.of("header", true));
                }
                default -> parsing.put("frontend", "delimited");
            }
            p.put("parsing", parsing);
            Map<String, Object> collector = new LinkedHashMap<>();
            collector.put("discovery", "poll");
            collector.put("connector", "local");
            collector.put("accepts_upload", true);
            collector.put("duplicate", ImportFingerprints.duplicate());   // B3 part 2: the same bytes under any name
            p.put("collector", collector);
            return p;
        }

        Map<String, Object> dataset(String id, String kind, String fileName, String type) {
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("physicalRef", id + "/database");
            d.put("description", description != null ? description : "Imported from " + fileName + " with Add data");
            d.put("origin", "import");
            d.put("kind", kind);
            d.put("importPipeline", id);
            d.put("importFormat", type);
            if (eventDate != null) d.put("eventDate", eventDate);
            List<Map<String, Object>> columns = new ArrayList<>();
            for (PlanCol c : cols) {
                if (!c.keep()) continue;
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("name", c.name());
                m.put("type", datasetType(c.type()));
                if (c.classification() != null) m.put("classification", c.classification());
                if (c.role() != null) m.put("role", c.role());
                columns.add(m);
            }
            d.put("columns", columns);
            if (primaryEntityType != null) d.put(DatasetEntryRules.PRIMARY_ENTITY_TYPE, primaryEntityType);
            if (link != null) d.put(DatasetEntryRules.DEFAULT_LINK_MAPPING, link);
            if (identifying != null) d.put("identifyingColumn", identifying);
            // the conditions in words for the card ("Keeps rows where …"); the Pipeline's where is what runs
            if (!filter.stored().isEmpty()) d.put(IMPORT_FILTER, filter.stored());
            return d;
        }
    }

    static String extension(String type) {
        return switch (type) {
            case "tsv" -> "tsv";
            case "parquet" -> "parquet";
            case "json" -> "json";
            case "ndjson" -> "ndjson";
            case "excel" -> "xlsx";
            default -> "csv";
        };
    }

    /**
     * The Dataset entry's column type for an engine type: {@code number}, {@code date}, {@code timestamp},
     * {@code boolean}, else what a Dataset read calls it ({@link com.gamma.query.ResultSetDescriptor#columnType(String)}):
     * a time of day ({@code TIME}) is {@code date}, the temporal type (U3, 2026-11-20; it was {@code string}), text is
     * {@code string}.
     */
    static String datasetType(String engine) {
        return switch (engine) {
            case "BIGINT", "DOUBLE" -> "number";
            case "DATE" -> "date";
            case "TIMESTAMP" -> "timestamp";
            case "BOOLEAN" -> "boolean";
            default -> com.gamma.query.ResultSetDescriptor.columnType(engine);
        };
    }

    // ── one import run ──────────────────────────────────────────────────────────────────────────────────────

    /** Undo step that may throw. */
    @FunctionalInterface
    interface Undo {
        void run() throws Exception;
    }

    /** The Dataset entry's key for the row conditions an import keeps rows by ({@code ADD-DATA-ROW-FILTER-1}; display only). */
    static final String IMPORT_FILTER = "importFilter";
    /** The import formats whose rows a filtered run counts apart, so it can say how many the filter left out. */
    static final Set<String> COUNTED_FORMATS = Set.of("csv", "tsv", "parquet");
    /** The registry entry's key for {@link Job#lastImport}. */
    static final String LAST_IMPORT = "lastImport";
    /** Add a file's third mode (LA-ARRIVAL-MODES-1): the file replaces the days it covers. */
    static final String PERIOD = "period";

    /** One run of an import Pipeline: commit, Add a file or Run now. */
    static final class Job {
        final Space space;
        final String id, kind, fileName;
        final Path source;
        final Map<String, Object> dataset;
        final boolean replace;
        final Deque<Undo> undo = new ArrayDeque<>();
        Path landed;
        /** C2: this run is a commit of a draft, so a set-aside file is explained with fixes that edit that draft. */
        boolean fromDraft;
        /** The source is a complete export outside the inbox (a Postgres snapshot's Run now): moved in, not copied. */
        boolean moveSource;
        /** The ledger's {@code consignment_id} of the last batch {@link #runChecked} saw this run add; null if none. */
        String runBatch;
        /** sha256 of the uploaded file (LA-ARRIVAL-LEDGER-1), read as it lands; null for Run now. */
        String sha256;
        /** The per-file ledger rows {@link #runChecked} saw this run add: the files it took (LA-ARRIVAL-LEDGER-1). */
        List<Map<String, String>> taken = List.of();
        /** ADD-DATA-ROW-FILTER-1: rows the import's row filter left out, by the ledger's file name; empty when it has none. */
        Map<String, Long> filteredOut = Map.of();
        /** LA-ARRIVAL-MODES-1: the upload replaces the days it covers (its rows are added, then those days' older rows go). */
        boolean period;
        /** Columns the upload carries that the Dataset does not read: accepted, not kept, named in the result. */
        List<String> newColumns = List.of();
        /** The same for each waiting file Run now judged, by inbox file name. */
        Map<String, List<String>> inboxNewColumns = Map.of();
        /** LA-ARRIVAL-NEW-COLUMNS-1: the new columns this run kept as details (every file it took carries them). */
        List<String> keptColumns = List.of();
        /** Why a new column was left out; null when none was. */
        String newColumnsReason;
        /** Add a file's run-scoped arrival mode (O6; B13), open from the run until {@link #dropAside} or a rollback. */
        private com.gamma.job.ArrivalLoadStep.RunLoad runLoad;

        String mode() {
            return replace ? "replace" : period ? PERIOD : "append";
        }

        /**
         * Add a file's choice, as the run's own arrival mode (BASIC-ELT-PROFILE-1 O6, B13): the engine's {@code output.load}
         * commit step, at the upload's Consignment, removes every other Parquet file of the Dataset (Replace everything) or
         * the rows of every older file on a day the upload covers (Replace the period; {@link com.gamma.job.ArrivalReplacer},
         * B4), through the replacer's journal and heal; the run's other files append. An append is a choice too: it wins
         * over the {@code output.load} a Postgres snapshot's Pipeline holds (O7). Undone by {@link #rollback} (the originals
         * come back, then the upload's own rows go); made final by {@link #dropAside}. Open before the run, once landed.
         */
        void runLoad(String eventDate) {
            PipelineConfig.ArrivalLoad load = replace ? new PipelineConfig.ArrivalLoad(PipelineConfig.ArrivalLoad.Mode.REPLACE, null)
                    : period ? new PipelineConfig.ArrivalLoad(PipelineConfig.ArrivalLoad.Mode.PERIOD, eventDate)
                    : PipelineConfig.ArrivalLoad.APPEND;
            runLoad = com.gamma.job.ArrivalLoadStep.override(database(), load, landed.getFileName().toString());
            com.gamma.job.ArrivalLoadStep.RunLoad open = runLoad;
            undo.push(() -> {   // a no-op restore once dropAside made it final
                try {
                    open.restore();
                } finally {
                    open.close();
                }
            });
        }

        /** The rows the run's replacement removed (0 without one): {@link #runChecked} judges the rows the run added. */
        long rowsReplaced() {
            com.gamma.job.ArrivalReplacer.Result r = runLoad == null ? null : runLoad.result();
            return r == null ? 0 : r.rowsRemoved();
        }

        /** After the run: the run's replacement failed, so the caller undoes the run ({@link #rollback}). */
        void requireLoaded() throws IOException {
            Exception failed = runLoad == null ? null : runLoad.failure();
            if (failed instanceof IOException io) throw io;
            if (failed instanceof RuntimeException re) throw re;
        }

        /** What Replace the period did, after the run: {@code {days, from, to, rowsReplaced, filesRewritten}}. */
        Map<String, Object> replacedPeriod() throws IOException {
            requireLoaded();
            com.gamma.job.ArrivalReplacer.Result r = runLoad.result();
            if (r == null) r = new com.gamma.job.ArrivalReplacer.Result(List.of(), 0, 0);   // the upload wrote no file
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("days", r.days().size());
            m.put("from", r.days().isEmpty() ? null : r.days().get(0));
            m.put("to", r.days().isEmpty() ? null : r.days().get(r.days().size() - 1));
            m.put("rowsReplaced", r.rowsRemoved());
            m.put("filesRewritten", r.filesTouched());
            return m;
        }

        Job(Space space, String id, String kind, Path source, String fileName, Map<String, Object> dataset, boolean replace) {
            this.space = space;
            this.id = id;
            this.kind = kind;
            this.source = source;
            this.fileName = fileName;
            this.dataset = dataset;
            this.replace = replace;
        }

        Path database() {
            return space.dataRoot().resolve(id).resolve("database");
        }

        /**
         * Copy the draft's file into the Pipeline's inbox under a unique name that ends in the import's canonical,
         * lower-case extension ({@link #extension}) - the one its {@code file_pattern} globs - whatever the file was called
         * ({@code .txt}, {@code .tab}, {@code .jsonl}, {@code .xlsm}, {@code T.CSV}); undo deletes it.
         */
        void landFromDraft(String type) throws IOException {
            Path in = inbox(space.dataRoot(), id);
            Files.createDirectories(in);
            String stem = source.getFileName().toString().replaceFirst("\\.[^.]*$", "");
            Path dest = in.resolve("add_" + stamp() + "_" + stem + "." + extension(type));
            if (sha256 == null) sha256 = DatasetArrivals.sha256(source);   // Add a file hashed the bytes as uploaded
            if (moveSource) {
                // a Postgres snapshot's export (BASIC-ONBOARD-1 redesign, operator 2026-11-05): the complete file is MOVED in
                Files.move(source, dest, StandardCopyOption.ATOMIC_MOVE);
            } else {
                // a COPY: the draft keeps its file until the commit succeeds, so a failed commit can be retried as it stands.
                // The copy is made outside the inbox (under the drafts folder of the data root) and moved in whole, so
                // neither the 30 s poll nor a Run now can see a half-written file.
                Path tmp = space.dataRoot().resolve(OnboardRoutes.DRAFTS).resolve(".landing-" + UUID.randomUUID() + "-" + dest.getFileName());
                Files.createDirectories(tmp.getParent());
                try {
                    Files.copy(source, tmp, StandardCopyOption.REPLACE_EXISTING);
                    Files.move(tmp, dest, StandardCopyOption.ATOMIC_MOVE);
                } finally {
                    Files.deleteIfExists(tmp);
                }
            }
            landed = dest;
            undo.push(() -> Files.deleteIfExists(dest));
        }

        void rollback() {
            while (!undo.isEmpty()) {
                try {
                    undo.pop().run();
                } catch (Exception ignored) {
                    // keep undoing; a leftover is better than stopping half way
                }
            }
        }

        /** Run, check, register the Dataset, then build the index and evaluate Expectations; undo everything on failure. */
        Map<String, Object> finishCommit(Path draftDir) throws IOException {
            // B3 part 2: a new Dataset holds no rows, so no fingerprint an earlier Dataset of this id left refuses its file
            space.service().configFor(id).ifPresent(cfg -> ImportFingerprints.forgetAll(this, cfg));
            Map<String, Object> summary = runChecked(0);
            ComponentStore registry = new ComponentStore(space.writeRoot().resolve("registry"));
            dataset.put(LAST_IMPORT, lastImport(summary));
            registry.write("dataset", id, dataset);
            undo.push(() -> registry.delete("dataset", id));
            followOn(summary);
            recordArrival(summary);
            OnboardRoutes.deleteTree(draftDir);
            undo.clear();
            com.gamma.util.OnboardingCounters.record(com.gamma.util.OnboardingCounters.Event.FIRST_DATASET);   // OB-13
            return summary;
        }

        /** Run the Pipeline and judge it: a run that loaded nothing, or quarantined the file, is a failure (422). */
        Map<String, Object> runChecked(long before) {
            PipelineConfig cfg = space.service().configFor(id)
                    .orElseThrow(() -> new ApiException(409, ErrorCodes.CONFLICT, "the import's Pipeline '" + id + "' is not registered"));
            // what this run adds to the ledgers: the batches and quarantine entries that were not there before it
            Set<String> batchesBefore = new HashSet<>();
            for (Map<String, String> b : space.service().statusStore().batches(cfg)) batchesBefore.add(String.valueOf(b.get("consignment_id")));
            Set<String> quarantineBefore = new HashSet<>();
            for (Map<String, String> q : space.service().statusStore().quarantine(cfg)) quarantineBefore.add(String.valueOf(q.get("path")));
            Set<String> filesBefore = new HashSet<>();
            for (Map<String, String> f : space.service().statusStore().files(cfg)) filesBefore.add(f.get("filename") + "|" + f.get("consignment_id"));
            // ADD-DATA-ROW-FILTER-1: the native CSV lane counts a file's rows AFTER the row filter (parsed = rows written), so
            // the rows its reader yields are counted here, before the run, with the engine's own reader view
            boolean filters = cfg.csv().hasRowPredicate();
            String format = String.valueOf(dataset.get("importFormat"));
            Map<String, Long> read = filters && COUNTED_FORMATS.contains(format) ? new LinkedHashMap<>() : null;
            // finding 5: in the same pass, the values each typed column cannot read (blank in the Dataset, the row kept)
            List<Map<String, Object>> blanked = prescan(cfg, format, read);
            // O7: a Postgres snapshot's Pipeline fetches the table itself (collector.connector: postgres); Add data's own run
            // takes what it put in the inbox - the export it checked - and never a second copy fetched by the run
            Path inbox = inbox(space.dataRoot(), id);
            OWN_RUN.set(space.writeRoot() + "|" + id);   // the pre-run step lets this run, and only this one, through the claim
            try (com.gamma.inspector.RunFiles.Scope own = PostgresSnapshot.isSnapshot(space.writeRoot(), id)
                    ? com.gamma.inspector.RunFiles.noFetch(inbox) : null) {
                space.service().runPipeline(id).orElseThrow(() -> new ApiException(409, ErrorCodes.CONFLICT, "the import's Pipeline '" + id + "' is not registered"));
            } catch (IllegalStateException notRunnable) {
                throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "the import could not run: " + notRunnable.getMessage());
            } finally {
                OWN_RUN.remove();
            }
            List<Map<String, String>> took = new ArrayList<>();
            for (Map<String, String> f : space.service().statusStore().files(cfg))
                if (!filesBefore.contains(f.get("filename") + "|" + f.get("consignment_id"))) took.add(f);
            taken = took;
            long after = countRows(database());
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("dataset", id);
            s.put("pipeline", id);
            s.put("kind", kind);
            if (fileName != null) s.put("file", fileName);
            long parsed = 0, rejected = 0, written = 0;
            Map<String, Long> byFile = new LinkedHashMap<>();
            Map<String, Long> keptByFile = new LinkedHashMap<>();
            List<Map<String, Object>> quarantined = new ArrayList<>();
            for (Map<String, String> b : space.service().statusStore().batches(cfg)) {
                if (batchesBefore.contains(String.valueOf(b.get("consignment_id")))) continue;
                runBatch = String.valueOf(b.get("consignment_id"));
                parsed += parseLong(b.get("total_input_rows"));
                rejected += parseLong(b.get("rejected_rows"));
                written += parseLong(b.get("total_output_rows"));
                if (read != null) filteredPerFile(cfg, runBatch, parseLong(b.get("total_output_rows")), took, read, byFile, keptByFile);
            }
            filteredOut = byFile;
            long filtered = byFile.values().stream().mapToLong(Long::longValue).sum();
            String landedName = landed == null ? null : landed.getFileName().toString();
            // the native lane sets a file the filter kept nothing of aside as `empty`: say what really happened
            boolean filteredAway = landedName != null && byFile.getOrDefault(landedName, 0L) > 0 && keptByFile.getOrDefault(landedName, -1L) == 0;
            for (Map<String, String> q : space.service().statusStore().quarantine(cfg)) {
                if (quarantineBefore.contains(String.valueOf(q.get("path")))) continue;
                Map<String, Object> m = new LinkedHashMap<>();
                String qf = String.valueOf(q.get("file"));
                m.put("file", landedName != null && qf.equals(landedName) ? fileName : q.get("file"));
                boolean all = "empty".equals(q.get("reason")) && byFile.getOrDefault(qf, 0L) > 0;
                m.put("reason", all ? "the row filter kept none of its " + byFile.get(qf) + " rows" : q.get("reason"));
                quarantined.add(m);
            }
            // rows read = written + rejected + left out, whichever lane counted them (see `read` above)
            if (read != null) parsed = written + rejected + filtered;
            // C2: a commit whose run set the file aside or rejected rows says why with the catalogue, from the draft's file
            List<Map<String, Object>> findings = fromDraft && (rejected > 0 || !quarantined.isEmpty()) ? explain(cfg) : List.of();
            if (!findings.isEmpty())
                for (Map<String, Object> m : quarantined) m.put("findings", findings);
            s.put("rowsIn", parsed);
            s.put("rowsRejected", rejected);
            s.put("blanked", blanked);
            if (filters) {
                s.put("rowsFilteredOut", read == null ? null : filtered);   // null: this format's rows are not counted apart
                if (dataset.get(IMPORT_FILTER) instanceof List<?> conditions) s.put("filter", conditions);
            }
            if (rejected > 0)
                s.put("rejectedDetail", "GET /runs/" + id + "/errors?file=" + landed.getFileName());
            s.put("quarantined", quarantined);
            if (!findings.isEmpty()) s.put("findings", findings);
            s.put("rowsBefore", before);
            s.put("rowsAfter", after);
            s.put("rowsChanged", after - before);
            s.put("replaced", replace);
            // a replacement removed rows inside the run (O6, B13): the rows the run added are judged without that
            if (landed != null && (!quarantined.isEmpty() || after + rowsReplaced() <= before)) {
                String why = filteredAway ? "the row filter kept none of its " + byFile.get(landedName) + " rows"
                        : !quarantined.isEmpty() ? "the file was set aside (" + quarantined.get(0).get("reason") + ")"
                        : filtered > 0 && filtered + rejected >= parsed ? "the row filter kept none of its " + parsed + " rows"
                        : rejected > 0 ? "all " + rejected + " rows were rejected" : "no row could be read";
                String msg = "nothing was imported from '" + fileName + "': " + why + "; nothing was kept - check the column types and formats and try again";
                if (!findings.isEmpty()) throw new OnboardFixes.Refusal(422, msg, findings);
                throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, msg);
            }
            describeOutput(s);
            return s;
        }

        /**
         * The rows the filter left out of each file of one batch: the rows the file yields ({@code read}, counted before the
         * run) less the rows it wrote (the batch's output when it took one file, else the lineage ledger's rows for that
         * file). The ledger's {@code parsed_rows} cannot serve: the native CSV and the Parquet lanes count it after the filter.
         */
        private void filteredPerFile(PipelineConfig cfg, String batch, long batchWritten, List<Map<String, String>> took,
                                     Map<String, Long> read, Map<String, Long> byFile, Map<String, Long> keptByFile) {
            List<Map<String, String>> files = new ArrayList<>();
            for (Map<String, String> f : took) if (batch.equals(f.get("consignment_id"))) files.add(f);
            Map<String, Long> wrote = new LinkedHashMap<>();
            if (files.size() == 1) wrote.put(String.valueOf(files.get(0).get("filename")), batchWritten);
            else
                for (Map<String, String> l : space.service().statusStore().lineage(cfg, batch)) {
                    String in = l.get("input_file");
                    if (in == null || in.isBlank()) continue;
                    wrote.merge(Path.of(in).getFileName().toString(), parseLong(l.get("row_count")), Long::sum);
                }
            for (Map<String, String> f : files) {
                String name = String.valueOf(f.get("filename"));
                long w = wrote.getOrDefault(Path.of(name).getFileName().toString(), 0L);
                Long yielded = read.get(name);
                keptByFile.put(name, w);
                if (yielded != null) byFile.put(name, Math.max(0, yielded - w));   // not counted (unreadable): left unsaid
            }
        }

        /** The reader view {@link #prescan} reads each waiting file through. */
        private static final String PRESCAN_VIEW = "import_prescan";

        /** One typed column of {@link #prescan}: the SQL of its raw value, and the predicate "present, but blank once typed". */
        private record Check(String column, String source, String failed) {}

        /**
         * One pass over each file waiting in the import's inbox, before the run, with the engine's own reader view
         * ({@code DuckDbCsvIngester.createRawInputView}: the same read, rejected rows excluded).
         * <ul>
         *   <li>{@code rows} (non-null for an import that filters rows): the rows each file yields, by the name the run ledger
         *       gives it - delimited text through the view, Parquet by its row count.</li>
         *   <li>Answered ({@code LA-FULL-CYCLE-TEST-1} finding 5): the values each typed column cannot read - present in the
         *       file, left blank in the Dataset, the row kept - as {@code [{column, type, values, example}]}, counted with the
         *       engine's own cast expressions ({@link com.gamma.etl.DataTransformer#castChecks}), so the count is what the
         *       run does, not a second opinion.</li>
         * </ul>
         * A file it cannot read is left out (the run judges it). One extra read of each file.
         */
        private List<Map<String, Object>> prescan(PipelineConfig cfg, String format, Map<String, Long> rows) {
            Path in = inbox(space.dataRoot(), id);
            Map<String, Object> schema = cfg.schemas().single();
            if (schema == null || !Files.isDirectory(in)) return List.of();
            List<Path> files;
            try (java.util.stream.Stream<Path> w = Files.list(in)) {
                files = w.filter(Files::isRegularFile).filter(p -> !p.getFileName().toString().startsWith(".")).toList();
            } catch (IOException unreadable) {
                return List.of();
            }
            List<Check> checks = checks(schema, cfg);
            if (files.isEmpty() || (rows == null && checks.isEmpty())) return List.of();
            long[] values = new long[checks.size()];
            String[] examples = new String[checks.size()];
            StringBuilder select = new StringBuilder("SELECT count(*)");
            for (Check c : checks)
                select.append(", count(*) FILTER (WHERE ").append(c.failed()).append("), first(CAST(").append(c.source())
                        .append(" AS VARCHAR)) FILTER (WHERE ").append(c.failed()).append(")");
            boolean parquet = "parquet".equals(format);
            try {
                DuckDbUtil.loadDriver();
                try (Connection conn = DuckDbUtil.openInMemory(null, List.of(in.toAbsolutePath().normalize()), null)) {
                    for (Path f : files) {
                        String name = com.gamma.etl.FileNames.safe(cfg, f.getFileName().toString());
                        if (rows != null && parquet)
                            try (Statement st = conn.createStatement();
                                 ResultSet rs = st.executeQuery("SELECT count(*) FROM " + com.gamma.sql.SqlViews.parquetFile(f))) {
                                if (rs.next()) rows.put(name, rs.getLong(1));
                            } catch (Exception unreadable) {
                                // left out: the run quarantines or rejects it and the ledger says so
                            }
                        if (checks.isEmpty() && (rows == null || parquet)) continue;
                        try (Statement st = conn.createStatement()) {
                            com.gamma.etl.DuckDbCsvIngester.createRawInputView(f.toFile(), conn, schema, cfg, PRESCAN_VIEW, 0);
                            try (ResultSet rs = st.executeQuery(select + " FROM " + PRESCAN_VIEW)) {
                                if (rs.next()) {
                                    if (rows != null && !parquet) rows.put(name, rs.getLong(1));
                                    for (int i = 0; i < checks.size(); i++) {
                                        values[i] += rs.getLong(2 + 2 * i);
                                        if (examples[i] == null) examples[i] = rs.getString(3 + 2 * i);
                                    }
                                }
                            }
                            st.execute("DROP VIEW IF EXISTS " + PRESCAN_VIEW);
                        } catch (Exception unreadable) {
                            // left out: the run quarantines or rejects it and the ledger says so
                        }
                    }
                }
            } catch (Exception noDuckDb) {
                // no count: the ledger's own figures are used
            }
            Map<String, String> types = new LinkedHashMap<>();
            if (dataset.get("columns") instanceof List<?> cols)
                for (Object o : cols) if (o instanceof Map<?, ?> m) types.put(String.valueOf(m.get("name")), String.valueOf(m.get("type")));
            List<Map<String, Object>> blanked = new ArrayList<>();
            for (int i = 0; i < checks.size(); i++) {
                if (values[i] == 0) continue;
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("column", checks.get(i).column());
                m.put("type", types.getOrDefault(checks.get(i).column(), "string"));
                m.put("values", values[i]);
                m.put("example", examples[i]);
                blanked.add(m);
            }
            return blanked;
        }

        /**
         * The typed columns {@link #prescan} audits: every mapping field the engine's own cast audit covers, and the numbers
         * this import reads through a decimal mark (C1) - its own expression, which the engine's audit leaves out as
         * author-owned SQL. Empty when the schema does not compile (the run then says so).
         */
        private static List<Check> checks(Map<String, Object> schema, PipelineConfig cfg) {
            List<Check> out = new ArrayList<>();
            String view = SqlIdent.q(PRESCAN_VIEW);
            try {
                for (com.gamma.etl.DataTransformer.CastCheck c : com.gamma.etl.DataTransformer.castChecks(schema, cfg.csv(), PRESCAN_VIEW))
                    out.add(new Check(c.column(), view + "." + SqlIdent.q(c.source()), c.failed()));
            } catch (RuntimeException uncompilable) {
                return List.of();
            }
            if (schema.get("mapping") instanceof Map<?, ?> mapping && mapping.get("fields") instanceof List<?> fields)
                for (Object o : fields) {
                    if (!(o instanceof Map<?, ?> f) || !"custom".equals(f.get("fn")) || !(f.get("from") instanceof String from)
                            || !(f.get("args") instanceof Map<?, ?> args) || !(args.get("expression") instanceof String expr)) continue;
                    boolean ours = false;
                    for (String type : List.of("DOUBLE", "BIGINT"))
                        for (String mark : List.of(".", ",")) ours |= expr.equals(DecimalMark.sql(SqlIdent.q(from), type, mark));
                    if (!ours) continue;
                    String source = view + "." + SqlIdent.q(from);
                    out.add(new Check(String.valueOf(f.get("name")), source,
                            "NULLIF(TRIM(CAST(" + source + " AS VARCHAR)), '') IS NOT NULL AND (" + expr + ") IS NULL"));
                }
            return out;
        }

        /**
         * Finding 5, over the Parquet this run wrote (before any compaction): the rows that cannot be a link because an end
         * is blank ({@code unlinked}, only for a Dataset with a link), and for each column holding an Entity Type the
         * spellings that differ from another only by spaces or letter case ({@code respelled}, with the key the type's
         * normaliser gives one of them: Entity profiles, entity counts and lookups read that key, while the link index keeps
         * each spelling as its own node id). Best effort: unreadable output leaves both unsaid.
         */
        private void describeOutput(Map<String, Object> s) {
            Path base = space.dataRoot().resolve(id).toAbsolutePath().normalize();
            List<Path> outputs = new ArrayList<>();
            for (Map<String, String> r : taken)
                for (Path p : ledgerOutputs(r)) {
                    Path abs = p.toAbsolutePath().normalize();
                    if (com.gamma.config.safety.PathJail.contains(base, abs) && Files.isRegularFile(abs)) outputs.add(abs);
                }
            if (outputs.isEmpty()) return;
            String relation = com.gamma.sql.SqlViews.parquet(com.gamma.sql.SqlViews.pathList(outputs.stream().map(Path::toString).toList()),
                    true, com.gamma.sql.SqlViews.Hive.OFF);
            try {
                DuckDbUtil.loadDriver();
                try (Connection conn = DuckDbUtil.openInMemory(null, List.of(base)); Statement st = conn.createStatement()) {
                    if (dataset.get(DatasetEntryRules.DEFAULT_LINK_MAPPING) instanceof Map<?, ?> link && link.get("from") instanceof String from
                            && link.get("to") instanceof String to) {
                        String noFrom = "NULLIF(trim(CAST(" + SqlIdent.q(from) + " AS VARCHAR)), '') IS NULL";
                        String noTo = "NULLIF(trim(CAST(" + SqlIdent.q(to) + " AS VARCHAR)), '') IS NULL";
                        try (ResultSet rs = st.executeQuery("SELECT count(*) FILTER (WHERE " + noFrom + " OR " + noTo + "), count(*) FILTER (WHERE "
                                + noFrom + "), count(*) FILTER (WHERE " + noTo + ") FROM " + relation)) {
                            if (rs.next()) {
                                Map<String, Object> u = new LinkedHashMap<>();
                                u.put("rows", rs.getLong(1));
                                u.put("noFrom", rs.getLong(2));
                                u.put("noTo", rs.getLong(3));
                                u.put("from", from);
                                u.put("to", to);
                                s.put("unlinked", u);
                            }
                        }
                    }
                    List<Map<String, Object>> respelled = new ArrayList<>();
                    List<EntityTypes.EntityType> types = LinkAnalysisSettings.forRoot(space.writeRoot()).effectiveEntityTypes();
                    if (dataset.get("columns") instanceof List<?> cols)
                        for (Object o : cols) {
                            if (!(o instanceof Map<?, ?> col) || !"entity".equals(col.get("role")) || col.get("classification") == null) continue;
                            String c = "CAST(" + SqlIdent.q(String.valueOf(col.get("name"))) + " AS VARCHAR)";
                            String spellings = "(SELECT v, upper(trim(v)) AS folded FROM (SELECT DISTINCT " + c + " AS v FROM " + relation
                                    + " WHERE trim(" + c + ") <> ''))";
                            try (ResultSet rs = st.executeQuery("SELECT (SELECT count(*) - count(DISTINCT folded) FROM " + spellings + "), (SELECT v FROM "
                                    + spellings + " WHERE folded IN (SELECT folded FROM " + spellings + " GROUP BY folded HAVING count(*) > 1)"
                                    + " ORDER BY (v = folded), v LIMIT 1)")) {
                                if (!rs.next() || rs.getLong(1) == 0) continue;
                                Map<String, Object> m = new LinkedHashMap<>();
                                m.put("column", col.get("name"));
                                m.put("values", rs.getLong(1));
                                m.put("example", rs.getString(2));
                                EntityTypes.EntityType type = EntityTypes.typeNamedBy(types, col.get("classification"));
                                m.put("readAs", type == null || rs.getString(2) == null ? null : EntityTypes.normalise(type.normaliser(), rs.getString(2)));
                                respelled.add(m);
                            }
                        }
                    s.put("respelled", respelled);
                }
            } catch (Exception unreadable) {
                // left unsaid: the import itself succeeded
            }
        }

        /**
         * C2: the catalogue's reading of the draft's file when its run set it aside or rejected rows (delimited text only;
         * empty when nothing explains it). The draft keeps its file until the commit succeeds, so it is read there.
         */
        private List<Map<String, Object>> explain(PipelineConfig cfg) {
            String type = String.valueOf(dataset.get("importFormat"));
            if (!"csv".equals(type) && !"tsv".equals(type) || !Files.isRegularFile(source)) return List.of();
            PipelineConfig.CsvSettings c = cfg.csv();
            try {
                return OnboardDraft.diagnose(source, fileName, c.delimiter() == null || c.delimiter().isEmpty() ? "," : c.delimiter(),
                        c.quote() == null ? "" : c.quote(), c.hasHeader(), c.skipHeaderLines(), c.encoding());
            } catch (IOException unreadable) {
                return List.of();
            }
        }

        /**
         * LA-ONBOARD-RESIDUALS-1 (7): what this import did, kept on the Dataset's registry entry so the home page's
         * <i>What changed in your data</i> can say replaced or appended and the net change. Run now records none. The
         * card matches {@code batch} to the ledger's last run (no clock comparison: the ledger's times carry no zone).
         */
        Map<String, Object> lastImport(Map<String, Object> summary) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("at", Instant.now().toString());
            m.put("replaced", replace);
            m.put("mode", mode());
            m.put("rowsBefore", summary.get("rowsBefore"));
            m.put("rowsAfter", summary.get("rowsAfter"));
            if (runBatch != null) m.put("batch", runBatch);
            if (fileName != null) m.put("file", fileName);
            return m;
        }

        /**
         * LA-ARRIVAL-LEDGER-1: what the run ledger cannot know about the files this run took (the upload's name, mode and
         * sha256; every file's index outcome), for {@code GET /datasets/{id}/arrivals}. Run now records its files too.
         */
        void recordArrival(Map<String, Object> summary) {
            Map<String, List<String>> fresh = new LinkedHashMap<>(inboxNewColumns);
            if (landed != null && !newColumns.isEmpty()) fresh.put(landed.getFileName().toString(), newColumns);
            DatasetArrivals.recordRun(space.dataRoot(), id, taken, landed, fileName, mode(), sha256, fresh, keptColumns, filteredOut, summary);
        }

        /**
         * Add a file: write {@link #lastImport} onto the entry as it stands now (it may have been edited since). A result
         * stamp, so not archived as a version (it would push real edits out of the history).
         */
        void recordLastImport(Map<String, Object> summary) throws IOException {
            ComponentStore registry = new ComponentStore(space.writeRoot().resolve("registry"));
            Map<String, Object> current = new LinkedHashMap<>(registry.get("dataset", id)
                    .map(ComponentRegistry.Component::content).orElse(dataset));
            current.put(LAST_IMPORT, lastImport(summary));
            registry.write("dataset", id, current, false);
        }

        /**
         * The default Pipeline's fourth step (BASIC-ONBOARD-1 P1, DT-16): the import's follow-on work, inside the user's
         * run - compaction, each application's registered {@link ImportFollowOn} under its key, then the Expectations
         * (DT-15: every edition). No follow-on registered: the import ends at its Parquet. All of it runs in every edition
         * (operator 2026-11-05). The same steps a commit of the Pipeline outside Add data runs ({@link ImportCommitSteps},
         * BASIC-ELT-PROFILE-1 BE-1a); this run holds the Dataset's claim, so they do not run there for it.
         */
        void followOn(Map<String, Object> summary) {
            Map<String, Object> current = new ComponentStore(space.writeRoot().resolve("registry")).get("dataset", id)
                    .map(ComponentRegistry.Component::content).orElse(dataset);
            ImportCommitSteps.runAll(space, id, current, summary);
        }

        /**
         * The run succeeded: its replacement ({@link #runLoad}) is final - the files it replaced are marked {@code REPLACED}
         * (B4: a reprocess of their Consignments must not bring them back) and the originals it set aside go.
         */
        void dropAside() {
            if (runLoad != null) runLoad.close();
            runLoad = null;
        }
    }

    /**
     * A unique, time-ordered landing prefix in letters only: a long digit run in a file name is fingerprinted in the run
     * ledgers ({@code <fp:…>}), so a digit stamp would make the landed file unrecognisable there.
     */
    static String stamp() {
        long v = System.currentTimeMillis() * 1000 + (System.nanoTime() % 1000 + 1000) % 1000;
        StringBuilder sb = new StringBuilder();
        while (v > 0) {
            sb.append((char) ('a' + v % 26));
            v /= 26;
        }
        return sb.reverse().toString();
    }

    private static volatile List<ImportFollowOn> followOnsForTest;

    /** Test seam: a stand-in follow-on list for a class path that carries no application; {@code null} restores the real one. */
    static void followOnsForTest(List<ImportFollowOn> list) {
        followOnsForTest = list;
    }

    /** The follow-ons the installed applications registered (BASIC-ONBOARD-1 P1), in class-path order. */
    static List<ImportFollowOn> followOns() {
        List<ImportFollowOn> t = followOnsForTest;
        return t != null ? t : com.gamma.spi.OptionalSpi.all(ImportFollowOn.class);
    }

    /** The registered follow-ons as the draft lists them for the review sentence: {@code [{key, does, needs}]}. */
    static List<Map<String, Object>> describeFollowOns() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (ImportFollowOn f : followOns()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("key", f.key());
            m.put("does", f.does());
            m.put("needs", List.copyOf(new java.util.TreeSet<>(f.needs())));
            out.add(m);
        }
        return out;
    }

    private static long parseLong(String s) {
        try {
            return s == null || s.isBlank() ? 0 : Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** The output files a per-file ledger row names ({@code output_paths}, {@code ;}-separated). */
    private static List<Path> ledgerOutputs(Map<String, String> ledgerRow) {
        List<Path> out = new ArrayList<>();
        for (String p : ledgerRow.getOrDefault("output_paths", "").split(";"))
            if (!p.isBlank()) out.add(Path.of(p.trim()));
        return out;
    }

    /** Rows in a Parquet store directory; 0 when it holds none. */
    static long countRows(Path db) {
        if (!Files.isDirectory(db)) return 0;
        try {
            boolean any;
            try (var w = Files.walk(db)) {
                any = w.anyMatch(p -> p.getFileName().toString().endsWith(".parquet"));
            }
            if (!any) return 0;
            DuckDbUtil.loadDriver();
            try (Connection c = DuckDbUtil.openInMemory(null, List.of(db));
                 Statement st = c.createStatement();
                 ResultSet rs = st.executeQuery("SELECT count(*) FROM " + com.gamma.sql.SqlViews.parquet(
                         com.gamma.util.SqlIdent.pathStr(db, "/**/*.parquet"), true, com.gamma.sql.SqlViews.Hive.OFF))) {
                rs.next();
                return rs.getLong(1);
            }
        } catch (Exception unreadable) {
            return 0;
        }
    }

    // ── followed runs (202) ─────────────────────────────────────────────────────────────────────────────────

    @FunctionalInterface
    interface Work {
        Map<String, Object> run() throws Exception;
    }

    /** Hand {@code job}'s remaining work to the background; answer the 202 record. */
    private static Map<String, Object> follow(Job job, String what, Work work) {
        String runId = "r" + UUID.randomUUID().toString().replace("-", "");
        Map<String, Object> rec = new LinkedHashMap<>();
        rec.put("runId", runId);
        rec.put("dataset", job.id);
        rec.put("action", what);
        rec.put("state", "running");
        rec.put("actor", job.space.actor());
        rec.put("startedAt", Instant.now().toString());
        RUNS.put(runId, rec);
        String key = job.space.writeRoot() + "|" + job.id;
        Map<String, String> context = org.slf4j.MDC.getCopyOfContextMap();   // the request's Space: see the class doc
        BACKGROUND.submit(() -> {
            if (context != null) org.slf4j.MDC.setContextMap(context);
            else org.slf4j.MDC.clear();
            try {
                Map<String, Object> summary = work.run();
                synchronized (rec) {
                    rec.put("state", "succeeded");
                    rec.put("summary", summary);
                }
            } catch (Exception failed) {
                job.rollback();
                synchronized (rec) {
                    rec.put("state", "failed");
                    rec.put("message", failed.getMessage());
                    if (failed instanceof OnboardFixes.Refusal r) rec.put("findings", r.findings);   // C2: as the inline refusal
                }
            } finally {
                synchronized (rec) {
                    rec.put("finishedAt", Instant.now().toString());
                }
                IN_FLIGHT.remove(key);
                org.slf4j.MDC.clear();                                         // a pooled thread keeps no Space for the next run
            }
        });
        Map<String, Object> out = new LinkedHashMap<>(rec);
        out.remove("actor");
        out.put("poll", "/onboard/runs/" + runId);
        return out;
    }

    /** {@code GET /onboard/runs/{runId}}: a followed run's state; 404 when unknown, evicted, or another person's. */
    static Object run(HttpExchange ex, String runId) {
        Map<String, Object> rec = RUNS.get(runId);
        if (rec == null || (OnboardRoutes.signedIn(ex) && !OnboardRoutes.isAdmin(ex)
                && !String.valueOf(rec.get("actor")).equals(ApiContext.actor(ex))))
            throw new ApiException(404, ErrorCodes.NOT_FOUND, "no import run '" + (runId.length() > 40 ? runId.substring(0, 40) : runId) + "'");
        synchronized (rec) {
            Map<String, Object> out = new LinkedHashMap<>(rec);
            out.remove("actor");
            return out;
        }
    }

    // ── Add a file, Run now ─────────────────────────────────────────────────────────────────────────────────

    /**
     * The import Dataset {@code id} the caller may edit: 404 unknown or unseen, 409 when not an import. ⚠ The registry
     * fields ({@code origin}, {@code importPipeline}) are editable through {@code PUT /components/dataset/{id}}, so they are
     * never trusted alone: the Pipeline must be the one Add data wrote for THIS id - same id, registered from
     * {@code imports/<id>_pipeline.toon} under this Space's config root - or a forged entry could run (or, with replace,
     * set aside the data of) any other Pipeline.
     */
    static Map<String, Object> importDataset(Path writeRoot, HttpExchange ex, String id, CollectorService service) {
        Map<String, Object> content = new ComponentStore(writeRoot.resolve("registry")).get("dataset", id)
                .map(ComponentRegistry.Component::content)
                .orElseThrow(() -> new ApiException(404, ErrorCodes.NOT_FOUND, "no dataset component '" + id + "'"));
        OnboardRoutes.requireEdit(ex, id, content);
        requireOwnImport(writeRoot, id, content, service);
        return content;
    }

    /** {@link #importDataset}'s 409s without a request: {@code content} is an import Add data wrote for THIS id. */
    static void requireOwnImport(Path writeRoot, String id, Map<String, Object> content, CollectorService service) {
        boolean ours = isOurs(writeRoot, id, content, service);
        if (!ours && DatasetEntryRules.ORIGIN_MAPPING.equals(String.valueOf(content.get("origin"))))   // LA-DATA-PAGE-1
            throw new ApiException(409, ErrorCodes.CONFLICT, "'" + id + "' is a Mapping of '" + content.get(DatasetEntryRules.BASED_ON)
                    + "': it reads that Dataset's data, so add files to '" + content.get(DatasetEntryRules.BASED_ON) + "'");
        if (!ours)
            throw new ApiException(409, ErrorCodes.CONFLICT, "'" + id + "' is kept up to date by a Pipeline, not by Add data; add files to that Pipeline's Collector");
    }

    /** Whether {@code content} is the entry Add data wrote for {@code id} and its Pipeline is the registered {@code imports/<id>_pipeline.toon}. */
    static boolean isOurs(Path writeRoot, String id, Map<String, Object> content, CollectorService service) {
        Path expected = writeRoot.resolve(CONFIG_DIR).resolve(id + "_pipeline.toon").toAbsolutePath().normalize();
        return "import".equals(String.valueOf(content.get("origin"))) && id.equals(content.get("importPipeline"))
                && service.pathFor(id).map(p -> p.toAbsolutePath().normalize().equals(expected)).orElse(false)
                && service.configFor(id).isPresent();
    }

    static Object addFile(ApiContext api, HttpExchange ex, String id) throws IOException {
        Path writeRoot = OnboardRoutes.requireSelfImport(api, "Add a file");
        Path dataRoot = OnboardRoutes.requireDataRoot(api);
        Map<String, Object> content = importDataset(writeRoot, ex, id, HostContext.of(api).service());
        String pipeline = String.valueOf(content.get("importPipeline"));
        String name = OnboardRoutes.fileName(ex);
        String mode = ApiContext.query(ex, "mode");
        // UPLOAD-DEFAULTS-1 (operator 2026-11-21): no silent default - replace removes the Dataset's old rows, so the caller says so
        boolean replace = "replace".equals(mode);
        boolean period = PERIOD.equals(mode);
        if (!replace && !period && !"append".equals(mode))
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "mode is required and must be replace (the Dataset's old rows are removed), append (the rows are added) or period (the days the file covers are replaced)"
                    + (mode == null || mode.isBlank() ? "" : "; got '" + mode + "'"));
        // LA-ARRIVAL-NEW-COLUMNS-1 (operator 2026-11-06): a later file's new columns are kept unless the person leaves them out
        String newCols = ApiContext.query(ex, "newColumns");
        boolean keepNew = newCols == null || newCols.isBlank() || newCols.equals("keep");
        if (!keepNew && !"leave".equals(newCols))
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "newColumns must be keep (the default) or leave");
        if (!replace && "reference".equals(content.get("kind")))
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "a Reference is replaced, never appended to");
        // LA-ARRIVAL-MODES-1: Replace the period it covers needs the Dataset's When column to say which days a file covers
        String eventDate = content.get("eventDate") instanceof String e && !e.isBlank() ? e.trim() : null;
        if (period && eventDate == null)
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "'" + id + "' has no When column, so a file cannot replace "
                    + "the days it covers; add its rows or replace everything");
        LaLimitValues lim = OnboardRoutes.limits(writeRoot);
        CollectorService service = HostContext.of(api).service();
        service.configFor(pipeline)
                .orElseThrow(() -> new ApiException(409, ErrorCodes.CONFLICT, "the import's Pipeline '" + pipeline + "' is not registered"));

        String key = writeRoot + "|" + pipeline;
        if (!IN_FLIGHT.add(key)) throw new ApiException(409, ErrorCodes.CONFLICT, "'" + id + "' is being imported right now");
        Path stage = dataRoot.resolve(OnboardRoutes.DRAFTS).resolve("a" + UUID.randomUUID().toString().replace("-", ""));
        Path file = stage.resolve(OnboardRoutes.storedName(name));
        try {
            OnboardRoutes.receive(api, ex, file, lim.get(LaLimit.IMPORT_MAX_BYTES));
        } catch (IOException | RuntimeException fault) {
            IN_FLIGHT.remove(key);
            OnboardRoutes.deleteTree(stage);
            throw fault;
        }
        return addStaged(new Space(writeRoot, dataRoot, service, ApiContext.actor(ex)), content, id, key, stage, file, name,
                replace ? "replace" : period ? PERIOD : "append", ex, false, keepNew);
    }

    /**
     * Add the file staged at {@code file} (in its own {@code stage} directory) to the import {@code id} and run it - the part
     * of Add a file after the body is received, shared with a Postgres snapshot's refresh ({@link PostgresSnapshot#refresh}).
     * The caller has claimed {@code key} in {@link #IN_FLIGHT}; this releases it and removes {@code stage}, success or not
     * (a followed run, when it ends). {@code ex == null} always runs inline.
     */
    static Object addStaged(Space space, Map<String, Object> content, String id, String key, Path stage, Path file, String name,
                            String mode, HttpExchange ex) throws IOException {
        return addStaged(space, content, id, key, stage, file, name, mode, ex, false);
    }

    /**
     * {@link #addStaged(Space, Map, String, String, Path, Path, String, String, HttpExchange)}; {@code moveIntoInbox}: the
     * staged file is a complete export (a Postgres snapshot's Run now) and is MOVED into the inbox whole instead of copied.
     */
    static Object addStaged(Space space, Map<String, Object> content, String id, String key, Path stage, Path file, String name,
                            String mode, HttpExchange ex, boolean moveIntoInbox) throws IOException {
        return addStaged(space, content, id, key, stage, file, name, mode, ex, moveIntoInbox, true);
    }

    /**
     * {@link #addStaged(Space, Map, String, String, Path, Path, String, String, HttpExchange, boolean)}; {@code keepNewColumns}
     * false: the person chose, on Add a file, to leave a new column out (LA-ARRIVAL-NEW-COLUMNS-1; every other run keeps).
     */
    static Object addStaged(Space space, Map<String, Object> content, String id, String key, Path stage, Path file, String name,
                            String mode, HttpExchange ex, boolean moveIntoInbox, boolean keepNewColumns) throws IOException {
        Path writeRoot = space.writeRoot(), dataRoot = space.dataRoot();
        String pipeline = String.valueOf(content.get("importPipeline"));
        boolean replace = "replace".equals(mode), period = PERIOD.equals(mode);
        String eventDate = content.get("eventDate") instanceof String e && !e.isBlank() ? e.trim() : null;
        LaLimitValues lim = OnboardRoutes.limits(writeRoot);
        boolean handedOff = false;
        Job job = null;
        try {
            String type = OnboardDraft.fileType(file, name);
            String expected = String.valueOf(content.get("importFormat"));
            if (!type.equals(expected))   // the import's parser and file_pattern are fixed: a TSV is not a CSV import's file
                throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "'" + name + "' is " + type.toUpperCase(Locale.ROOT)
                        + "; '" + id + "' was imported from " + expected.toUpperCase(Locale.ROOT));
            String sha = DatasetArrivals.sha256(file);   // the bytes as uploaded, before any column is left out
            ColumnCheck uploadCheck = requireSameColumns(stage, file, name, type,
                    writeRoot.resolve(CONFIG_DIR).resolve(pipeline + "_schema.toon"), id, content);
            // D-3: the same bytes added again would count their rows twice; Replace everything may always reload a file. B3 part 2:
            // the engine's fingerprint ledger answers, the import's Pipeline hashing what it takes (ImportFingerprints)
            PipelineConfig cfg = space.service().configFor(pipeline)
                    .orElseThrow(() -> new ApiException(409, ErrorCodes.CONFLICT, "the import's Pipeline '" + pipeline + "' is not registered"));
            com.gamma.acquire.LedgerEntry first = replace ? null : ImportFingerprints.imported(cfg, sha);
            if (first != null)
                throw new ApiException(409, ErrorCodes.CONFLICT, "'" + name + "' was already imported on " + ImportFingerprints.day(first)
                        + (!name.equals(ImportFingerprints.shown(first)) ? " as '" + ImportFingerprints.shown(first) + "'" : "")
                        + " and its rows are in '" + id + "'; adding it again would count them twice. To load it again, replace everything");
            // the run also takes any file waiting in the inbox: judged by the same columns policy as Run now, then the new
            // columns of every file it takes, the upload's too (BE-1b: the pre-run steps, ImportPreRunSteps)
            job = new Job(space, pipeline, String.valueOf(content.get("kind")), file, name, content, replace);
            job.period = period;
            ImportPreRunSteps.Run pre = new ImportPreRunSteps.Run(space, id, content, job, null, keepNewColumns);
            pre.upload = new DatasetNewColumns.Taken(stage, file, name, uploadCheck);
            job.sha256 = sha;   // before the pre-run steps: a waiting file with the upload's bytes is set aside
            ImportPreRunSteps.runAll(pre);
            List<Map<String, Object>> refused = pre.refused;
            List<String> newColumns = job.newColumns;
            job.moveSource = moveIntoInbox;
            long before = countRows(job.database());
            final Job j = job;
            // B3 part 2: Replace everything replaces the old rows INSIDE the run (B13), but the engine judges the upload against
            // the ledger first: the fingerprints of the rows about to be replaced go now, so the same bytes may be loaded again.
            // A run that is undone (the replacement restored) records them again (ImportFingerprints.forget's undo).
            if (replace) ImportFingerprints.forgetAll(job, cfg);
            job.landFromDraft(type);
            Work rest = () -> {
                try {
                    Map<String, Object> s;
                    // ADD-FILE-FEED-1: the upload is whole (landFromDraft moves it in complete), so a feed's stability window does
                    // not hold it back from the person's own run; every other waiting file is gated as before
                    j.runLoad(eventDate);   // B13: Replace everything replaces at the upload's commit, journalled and healed
                    try (com.gamma.inspector.RunFiles.Scope whole = com.gamma.inspector.RunFiles.complete(Set.of(j.landed))) {
                        s = j.runChecked(before);
                    } catch (RuntimeException notImported) {
                        ImportFingerprints.forgetLanded(j, cfg);   // B3 part 2: no rows of it are kept
                        throw notImported;
                    }
                    // undone (the replacement restored, the upload's own rows gone): its fingerprint goes too - pushed before
                    // requireLoaded, whose failure is such an undo
                    if (replace || period) j.undo.push(() -> ImportFingerprints.forgetLanded(j, cfg));
                    j.requireLoaded();
                    ImportFingerprints.recordUpload(cfg, j.landed, sha);
                    if (period) {
                        s.put("period", j.replacedPeriod());
                        ImportFingerprints.forgetGone(j, cfg);   // B3 part 2: an arrival whose days were all replaced may come back
                        s.put("rowsAfter", countRows(j.database()));
                    }
                    if (!newColumns.isEmpty()) s.put("newColumns", newColumns);
                    putNewColumns(j, s);
                    if (!refused.isEmpty()) s.put("refused", refused);
                    s.put("rowsBefore", before);
                    s.put("rowsChanged", ((Number) s.get("rowsAfter")).longValue() - before);
                    s.put("dataset", id);
                    j.recordLastImport(s);   // before the old rows are dropped: a failed write still restores them
                    j.dropAside();
                    j.undo.clear();   // final: a failure below must not undo what is kept (the upload's fingerprint, the replaced rows' absence)
                    j.followOn(s);
                    j.recordArrival(s);
                    return s;
                } finally {
                    OnboardRoutes.deleteTree(stage);   // the staged copy, success or not (a followed run too)
                }
            };
            if (ex != null && Files.size(job.landed) > lim.get(LaLimit.IMPORT_INLINE_MAX_BYTES)) {
                Map<String, Object> rec = follow(job, "add-file", rest);
                handedOff = true;
                return ApiContext.respondJson(ex, 202, rec);
            }
            return rest.run();
        } catch (IOException | RuntimeException fault) {
            if (job != null && !handedOff) job.rollback();
            throw fault;
        } catch (Exception other) {
            if (job != null) job.rollback();
            throw new IOException(other);
        } finally {
            if (!handedOff) {
                IN_FLIGHT.remove(key);
                OnboardRoutes.deleteTree(stage);
            }
        }
    }

    /**
     * The added file must carry the columns the import reads (by index for delimited text, by name otherwise); answers the
     * check, whose new columns {@link DatasetNewColumns#settle} keeps or leaves out. 422 naming every missing or moved column.
     */
    private static ColumnCheck requireSameColumns(Path dir, Path file, String name, String type, Path schema, String id,
                                                  Map<String, Object> dataset) throws IOException {
        ColumnCheck c = checkColumns(dir, file, name, type, schema, id, dataset);
        if (c.refusal() != null && !c.missing().isEmpty()) throw new OnboardFixes.Refusal(422, c.refusal(), missingFindings(c, name, id));
        if (c.refusal() != null) throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, c.refusal());
        return c;
    }

    /**
     * LA-ARRIVAL-NEW-COLUMNS-1: keep the new columns every file of this run carries ({@link DatasetNewColumns#settle}),
     * onto {@code job}: what was kept, what each file left out, and why. Undone with the job.
     */
    static void settleNewColumns(Job job, String id, String type, List<DatasetNewColumns.Taken> files, boolean keep)
            throws IOException {
        Map<String, List<String>> leftOut = new LinkedHashMap<>();
        DatasetNewColumns.Outcome o = DatasetNewColumns.settle(job.space, id, job.id, "csv".equals(type) || "tsv".equals(type),
                files, keep, job.undo, leftOut);
        job.keptColumns = o.kept();
        job.newColumnsReason = o.reason();
        job.inboxNewColumns = leftOut;
        String upload = job.fileName == null ? null : files.stream().filter(t -> t.file().equals(job.source))
                .map(DatasetNewColumns.Taken::name).findFirst().orElse(null);
        job.newColumns = upload == null ? List.of() : leftOut.getOrDefault(upload, List.of());
        if (upload != null) leftOut.remove(upload);
    }

    /** The summary keys of {@link #settleNewColumns}: {@code keptColumns}, and {@code newColumnsReason} when one was left out. */
    private static void putNewColumns(Job job, Map<String, Object> s) {
        if (!job.keptColumns.isEmpty()) s.put("keptColumns", job.keptColumns);
        if (job.newColumnsReason != null) s.put("newColumnsReason", job.newColumnsReason);
    }

    /**
     * A later file judged against the Dataset's columns: why it is refused (null = accepted), its new columns, and - for
     * delimited text with new columns - the columns to keep ({@code keep}, in order), the file's delimiter and quote, and
     * the lines before its header line ({@code skip}: as many as the import skips, or the file was refused).
     */
    record ColumnCheck(String refusal, List<String> newColumns, List<String> keep, String delimiter, String quote, List<String> missing,
                       List<OnboardDraft.Col> freshCols, int skip) {
        ColumnCheck(String refusal, List<String> newColumns, List<String> missing, List<OnboardDraft.Col> freshCols) {
            this(refusal, newColumns, List.of(), null, null, missing, freshCols, 0);
        }

        /** The same check keeping {@code keep} (the read columns plus the new ones a run kept, LA-ARRIVAL-NEW-COLUMNS-1). */
        ColumnCheck withKeep(List<String> keep) {
            return new ColumnCheck(refusal, newColumns, keep, delimiter, quote, missing, freshCols, skip);
        }
    }

    /** The schema's fields ({@code {selector, name, type}}) as the engine's drift comparison reads them. */
    private static List<SchemaDrift.Declared> declared(List<String[]> fields) {
        List<SchemaDrift.Declared> out = new ArrayList<>();
        for (String[] f : fields) out.add(new SchemaDrift.Declared(f[0], f[1], f[2]));
        return out;
    }

    /** A file's columns as the engine's drift comparison reads them: the cleaned name, the selector, the profiler's type. */
    private static List<SchemaDrift.Observed> observed(List<OnboardDraft.Col> cols) {
        List<SchemaDrift.Observed> out = new ArrayList<>();
        for (OnboardDraft.Col c : cols) out.add(observed(c));
        return out;
    }

    static SchemaDrift.Observed observed(OnboardDraft.Col c) {
        return new SchemaDrift.Observed(c.name(), c.selector(), c.type(), c.format());
    }

    /** The columns of {@code cols} that {@code picked} names, in file order. */
    private static List<OnboardDraft.Col> colsOf(List<SchemaDrift.Observed> picked, List<OnboardDraft.Col> cols) {
        List<OnboardDraft.Col> out = new ArrayList<>();
        for (OnboardDraft.Col c : cols) if (picked.contains(observed(c))) out.add(c);
        return out;
    }

    /** C2: the missing-column entry, once per column the Dataset reads that the file lacks. */
    static List<Map<String, Object>> missingFindings(ColumnCheck c, String file, String id) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (String m : c.missing()) out.add(OnboardFixes.missingColumn(file, id, m));
        return out;
    }

    /** The encoding an import's Pipeline reads with ({@code csv_settings.encoding}), else {@code null} (UTF-8). */
    private static String importEncoding(Path schema) {
        String e = importSetting(schema, "processing", "csv_settings", "encoding");
        return e != null && OnboardDraft.ENCODINGS.contains(e) ? e : null;   // List.of(..).contains(null) throws
    }

    /** One {@code <block>.<sub>.<key>} value of the Pipeline Add data wrote beside {@code schema}, else {@code null}. */
    private static String importSetting(Path schema, String block, String sub, String key) {
        String n = schema.getFileName().toString();
        if (!n.endsWith("_schema.toon")) return null;
        try {
            Map<String, Object> p = com.gamma.util.ToonHelper.load(schema.resolveSibling(n.replace("_schema.toon", "_pipeline.toon")).toString());
            return p.get(block) instanceof Map<?, ?> b && b.get(sub) instanceof Map<?, ?> s && s.get(key) != null ? String.valueOf(s.get(key)) : null;
        } catch (Exception unreadable) {
            return null;
        }
    }

    /** A {@code skip_header_lines} / {@code skipLines} value as a number; {@code 0} when absent or not a count. */
    private static int skipLines(Object v) {
        String s = v == null ? "" : String.valueOf(v).trim();
        return s.matches("\\d{1,9}") ? Integer.parseInt(s) : 0;
    }

    /** Whether {@code file} holds a {@code "} byte anywhere (BE-3: the engine's default quote would read it otherwise than no quote). */
    static boolean holdsDoubleQuote(Path file) throws IOException {
        try (java.io.InputStream in = new java.io.BufferedInputStream(Files.newInputStream(file))) {
            for (int b; (b = in.read()) != -1; ) if (b == '"') return true;
        }
        return false;
    }

    /**
     * New columns of delimited text are not kept: the engine reads by position and sets aside a file whose rows have more
     * fields than the import reads, so the file is rewritten in place with the read columns only (they lead the line: a
     * read column at another position is refused). DuckDB reads and writes it, so quoting is kept right; values stay text.
     * The lines before the header line ({@code skip_header_lines}, BE-3) are read past and written back as they were:
     * the import skips them in every file it reads.
     */
    static void keepReadColumns(Path dir, Path file, ColumnCheck c) throws IOException {
        if (c.keep().isEmpty() || c.freshCols().isEmpty()) return;
        Path tmp = file.resolveSibling(".keep-" + file.getFileName());
        Path whole = file.resolveSibling(".kept-" + file.getFileName());
        String delim = SqlIdent.sqlStr(c.delimiter() == null ? "," : c.delimiter());
        String quote = SqlIdent.sqlStr(c.quote() == null ? "\"" : c.quote());
        String cols = String.join(", ", c.keep().stream().map(SqlIdent::q).toList());
        try {
            DuckDbUtil.loadDriver();
            try (Connection conn = DuckDbUtil.openInMemory(null, List.of(dir));
                 Statement st = conn.createStatement()) {
                st.execute("COPY (SELECT " + cols + " FROM read_csv(" + SqlIdent.pathStr(file) + ", header = true, skip = " + c.skip()
                        + ", all_varchar = true, delim = " + delim + ", quote = " + quote + ")) TO " + SqlIdent.pathStr(tmp)
                        + " (FORMAT CSV, HEADER, DELIMITER " + delim + ")");
            }
            if (c.skip() > 0) {
                try (java.io.OutputStream out = Files.newOutputStream(whole)) {
                    out.write(leadingLines(file, c.skip()));
                    Files.copy(tmp, out);
                }
                Files.move(whole, tmp, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            }
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (ClassNotFoundException | java.sql.SQLException failed) {
            throw new IOException("the new columns of '" + file.getFileName() + "' could not be left out: " + failed.getMessage(), failed);
        } finally {
            Files.deleteIfExists(tmp);
            Files.deleteIfExists(whole);
        }
    }

    /** The first {@code n} lines of {@code file}, byte for byte, line ends included. */
    private static byte[] leadingLines(Path file, int n) throws IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        try (java.io.InputStream in = new java.io.BufferedInputStream(Files.newInputStream(file))) {
            for (int b, lines = 0; lines < n && (b = in.read()) != -1; ) {
                out.write(b);
                if (b == '\n') lines++;
            }
        }
        return out.toByteArray();
    }

    /**
     * LA-ARRIVAL-MODES-1, the schema-drift policy (operator 2026-11-05, plan §6 D-6): a later file is judged against the
     * columns the import reads BY NAME before anything lands. A missing column, or - for delimited text read by position - a
     * column at another position, refuses the file and names each one (a reordered header no longer loads into the wrong
     * columns). A column the Dataset does not read is accepted, not kept, and named. An import with no header line can be
     * judged by its column count only.
     */
    static ColumnCheck checkColumns(Path dir, Path file, String name, String type, Path schema, String id,
                                   Map<String, Object> dataset) throws IOException {
        // LA-ARRIVAL-NEW-COLUMNS-1: a missing column a later file added says so (operator 2026-11-06: refused, as any other)
        Map<String, String> kept = DatasetNewColumns.keptNotes(dataset);
        List<String[]> fields = new ArrayList<>();   // {selector, name, type}
        try {
            Map<String, Object> s = com.gamma.util.ToonHelper.load(schema.toString());
            if (s.get("raw") instanceof Map<?, ?> raw && raw.get("fields") instanceof List<?> fs)
                for (Object f : fs)
                    if (f instanceof Map<?, ?> fm)
                        fields.add(new String[] {String.valueOf(fm.get("selector")), String.valueOf(fm.get("name")), String.valueOf(fm.get("type"))});
        } catch (Exception unreadable) {
            fields.clear();
        }
        if (fields.isEmpty())   // fail closed: no file lands against a schema nobody can read
            throw new ApiException(409, ErrorCodes.CONFLICT, "the schema of '" + id + "' cannot be read, so no file can be added to it");
        List<String> problems = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        List<String> fresh = new ArrayList<>();
        if ("csv".equals(type) || "tsv".equals(type)) {
            int max = -1;
            boolean headerless = true;
            try {
                for (String[] f : fields) {
                    int sel = Integer.parseInt(f[0]);
                    max = Math.max(max, sel);
                    headerless &= ("col_" + (sel + 1)).equals(f[1]);
                }
            } catch (NumberFormatException notAnIndex) {
                throw new ApiException(409, ErrorCodes.CONFLICT, "the schema of '" + id + "' does not read columns by position");
            }
            Map<String, String> how = new LinkedHashMap<>();
            // a headerless import reads line 1 as data: so must the check (the sniff would take a lone '12.25' line 1 as a header)
            how.put("header", headerless ? "false" : "true");
            String enc = importEncoding(schema);
            if (enc != null) how.put("encoding", enc);
            OnboardDraft.Analysis a;
            try {
                a = OnboardDraft.analyse(dir, file, name, type, how);
            } catch (OnboardFixes.Refusal notReadable) {   // its fix edits a draft: a later file has none
                throw new ApiException(notReadable.status, ErrorCodes.CONFIG_VALIDATION_FAILED, notReadable.getMessage());
            }
            // BASIC-ELT-PROFILE-1 BE-3: the import skips the lines its first file's sniff skipped (skip_header_lines); a
            // later file with other leading lines would be read wrongly by the run (a row lost, or its header read as one)
            int skips = skipLines(importSetting(schema, "processing", "csv_settings", "skip_header_lines"));
            int has = a.format().get("skipLines") instanceof Map<?, ?> sl ? skipLines(sl.get("value")) : 0;
            if (has != skips)
                problems.add("it has " + Words.count(has, "line", "lines") + " before the " + (headerless ? "first record" : "header line")
                        + " and '" + id + "' skips " + skips);
            // BASIC-ELT-PROFILE-1 B5: the engine's one comparison (SchemaDrift.diff); the refusal words stay Add data's
            List<OnboardDraft.Col> cols = a.cols();
            if (headerless) {
                SchemaDrift.Diff d = SchemaDrift.diff(declared(fields), observed(cols), SchemaDrift.By.WIDTH);
                if (!d.missing().isEmpty())
                    problems.add("it has " + d.observedWidth() + " columns and '" + id + "' reads " + d.declaredWidth());
                else {
                    String cut = cutRefusal(name, id, fields, f -> cols.get(Integer.parseInt(f[0])));
                    if (cut != null) return new ColumnCheck(cut, List.of(), List.of(), List.of());
                }
            } else {
                SchemaDrift.Diff d = SchemaDrift.diff(declared(fields), observed(cols), SchemaDrift.By.POSITION);
                for (SchemaDrift.Placed p : d.fields()) {
                    String n = p.field().name();
                    if (p.missing()) {
                        missing.add(n);
                        problems.add("'" + n + "' is missing" + (kept.containsKey(n) ? " (" + kept.get(n) + ")" : ""));
                    } else if (p.moved()) {
                        problems.add("'" + n + "' is column " + (p.at() + 1) + " here and column "
                                + (Integer.parseInt(p.field().selector()) + 1) + " in '" + id + "'");
                    }
                }
                for (SchemaDrift.Observed o : d.added()) fresh.add(o.name());
                if (problems.isEmpty()) {
                    String cut = cutRefusal(name, id, fields, f -> cols.get(Integer.parseInt(f[0])));
                    if (cut != null) return new ColumnCheck(cut, List.copyOf(fresh), List.of(), List.of());
                }
                if (problems.isEmpty() && !fresh.isEmpty()) {
                    // the file's OWN header text: read_csv names its columns by it, not by the cleaned-up name
                    List<String> keep = cols.subList(0, max + 1).stream().map(OnboardDraft.Col::header).toList();
                    // the new columns a run can keep: by position, those right after the read ones, in a row
                    List<OnboardDraft.Col> freshCols = colsOf(d.keepable(), cols);
                    return new ColumnCheck(null, List.copyOf(fresh), keep, choiceValue(a.format(), "delimiter"),
                            choiceValue(a.format(), "quote"), List.of(), List.copyOf(freshCols), has);
                }
            }
        } else {
            // the later file is read where the import reads its records (a chosen json.records_path), not the first list
            Map<String, String> how = new LinkedHashMap<>();
            String recordPath = importSetting(schema, "parsing", "json", "records_path");
            if (recordPath != null && !"$".equals(recordPath)) how.put("recordPath", recordPath);
            OnboardDraft.Analysis a = OnboardDraft.analyse(dir, file, name, type, how);
            List<SchemaDrift.Observed> seen = observed(a.cols());   // a flattened JSON field by its dotted path
            Set<String> selectors = new HashSet<>();
            for (String[] f : fields) selectors.add(f[0]);
            for (String[] f : fields) {
                // an import written before C1 reads a nested object whole by its root key, which the file still has: its
                // flattened fields stand for that one column
                if (seen.stream().noneMatch(o -> o.selector().equals(f[0])) && seen.stream().anyMatch(o -> o.selector().startsWith(f[0] + "."))) {
                    seen.removeIf(o -> o.selector().startsWith(f[0] + ".") && !selectors.contains(o.selector()));
                    seen.add(new SchemaDrift.Observed(f[0], f[0], null, null));
                }
            }
            SchemaDrift.Diff d = SchemaDrift.diff(declared(fields), seen, SchemaDrift.By.NAME);
            for (SchemaDrift.Placed p : d.fields())
                if (p.missing()) {
                    String n = p.field().name();
                    problems.add("'" + p.field().selector() + "' is missing" + (kept.containsKey(n) ? " (" + kept.get(n) + ")" : ""));
                    missing.add(p.field().selector());
                }
            for (SchemaDrift.Observed o : d.added()) fresh.add(o.selector());
            List<OnboardDraft.Col> freshCols = colsOf(d.keepable(), a.cols());
            String refusal = problems.isEmpty()
                    ? cutRefusal(name, id, fields, f -> a.cols().stream().filter(c -> f[0].equals(c.selector())).findFirst().orElse(null))
                    : refusal(name, id, problems);
            return new ColumnCheck(refusal, List.copyOf(fresh), List.copyOf(missing), refusal == null ? freshCols : List.of());
        }
        String refusal = problems.isEmpty() ? null : refusal(name, id, problems);
        // delimited text reaching here has no new column it could keep (a headerless import is judged by its count)
        return new ColumnCheck(refusal, List.copyOf(fresh), List.copyOf(missing), List.of());
    }

    /**
     * DECIMAL-TEXT-NOT-BIGINT-1 (2026-11-06): a whole-number ({@code BIGINT}) column of the Dataset would cut a value with a
     * fraction ({@code 12.25} stored as 12, the engine's cast rounds), so the file is refused, naming each such column with
     * one value from anywhere in the file; {@code null} when nothing would be cut. {@code colOf} finds a read field's column.
     */
    private static String cutRefusal(String name, String id, List<String[]> fields,
                                     java.util.function.Function<String[], OnboardDraft.Col> colOf) {
        List<String> cut = new ArrayList<>();
        for (String[] f : fields) {
            if (!"BIGINT".equalsIgnoreCase(f[2])) continue;
            OnboardDraft.Col c = colOf.apply(f);
            if (c != null && c.fraction() != null)
                cut.add("'" + f[1] + "' holds whole numbers and this file has '" + c.fraction() + "'");
        }
        return cut.isEmpty() ? null : "'" + name + "' has values '" + id + "' would cut: " + String.join("; ", cut)
                + ". Nothing was imported; a whole-number column keeps no fraction, so add the data as a new Dataset "
                + "with the column as a decimal number (DOUBLE)";
    }

    private static String refusal(String name, String id, List<String> problems) {
        return "'" + name + "' does not have the columns of '" + id + "': "
                + String.join("; ", problems) + ". Nothing was imported; fix the file's columns and add it again";
    }

    static Object runNow(ApiContext api, HttpExchange ex, String id) throws IOException {
        Path writeRoot = OnboardRoutes.requireSelfImport(api, "Run now");
        Path dataRoot = OnboardRoutes.requireDataRoot(api);
        CollectorService service = HostContext.of(api).service();
        Map<String, Object> content = importDataset(writeRoot, ex, id, service);
        // BASIC-ONBOARD-1 P3: a Postgres snapshot's Run now copies the table again, replacing everything (O7: and switches
        // its Pipeline to read Postgres itself, migrating an old snapshot)
        if (PostgresSnapshot.read(writeRoot, id) != null)
            return PostgresSnapshot.refresh(api, new Space(writeRoot, dataRoot, service, ApiContext.actor(ex)), content, id, ex);
        return importWaiting(new Space(writeRoot, dataRoot, service, ApiContext.actor(ex)), id, content, null);
    }

    /**
     * <b>Import waiting files</b>: the files in the import's inbox, each judged by the columns policy first (a refused one
     * set aside with its reason), then the run, the follow-on and the arrivals. The ONE path for Run now and the inbox
     * poll ({@link InboxWatch}, {@code BASIC-ONBOARD-1} P2); the caller has passed the family's gates. 409 while the same
     * import is running. {@code only}, when not null, is the set of inbox files the caller checked (the poll's settled
     * files): nothing else is judged or taken, so a file created after the check waits for a later run
     * (BASIC-ONBOARD-SETTLE-1). Run now passes null: the whole inbox.
     */
    static Map<String, Object> importWaiting(Space space, String id, Map<String, Object> content, Set<Path> only) {
        Path writeRoot = space.writeRoot(), dataRoot = space.dataRoot();
        String pipeline = String.valueOf(content.get("importPipeline"));
        String key = writeRoot + "|" + pipeline;
        if (!IN_FLIGHT.add(key)) throw new ApiException(409, ErrorCodes.CONFLICT, "'" + id + "' is being imported right now");
        try {
            Job job = new Job(space, pipeline, String.valueOf(content.get("kind")), null, null, content, false);
            long before = countRows(job.database());
            // LA-ARRIVAL-MODES-1: a waiting file is judged by the columns policy first; a refused one is set aside, named.
            // LA-ARRIVAL-NEW-COLUMNS-1: an unattended run keeps the new columns every waiting file carries. BE-1b: the same
            // pre-run steps a run outside Add data runs (ImportPreRunSteps), in the same order
            ImportPreRunSteps.Run pre = new ImportPreRunSteps.Run(space, id, content, job, only, true);
            Map<String, Object> s;
            try {
                ImportPreRunSteps.runAll(pre);
                if (only == null) {
                    s = job.runChecked(before);
                } else {
                    try (com.gamma.inspector.RunFiles.Scope scope = com.gamma.inspector.RunFiles.only(inbox(dataRoot, pipeline), only)) {
                        s = job.runChecked(before);
                    }
                }
            } catch (IOException | RuntimeException failed) {
                job.rollback();   // a column kept for a run that failed is not kept
                if (failed instanceof RuntimeException r) throw r;
                throw new java.io.UncheckedIOException((IOException) failed);
            }
            putNewColumns(job, s);
            s.put("dataset", id);
            if (!pre.refused.isEmpty()) s.put("refused", pre.refused);
            job.followOn(s);
            job.recordArrival(s);   // LA-ARRIVAL-LEDGER-1: each file Run now took, with the index outcome
            return s;
        } finally {
            IN_FLIGHT.remove(key);
        }
    }

    private static String choiceValue(Map<String, Object> format, String key) {
        return format != null && format.get(key) instanceof Map<?, ?> m && m.get("value") != null ? String.valueOf(m.get("value")) : null;
    }

    /** Where Run now sets a refused waiting file aside: {@code <dataRoot>/<id>/refused/}. */
    static final String REFUSED_DIR = "refused";

    /**
     * Judge every file waiting in the import inbox by {@link #checkColumns}: a refused one is moved to
     * {@code <dataRoot>/<id>/refused/} and recorded as a refused arrival (with the reason); an accepted one's new columns go
     * into {@code fresh} by file name. Answers {@code [{file, reason, setAside}]}. A file that cannot be read at all is left
     * for the run, which quarantines it as before.
     */
    static List<Map<String, Object>> judgeInbox(Path dataRoot, String pipeline, String id, String type, Path schema,
                                                Map<String, Object> dataset, List<DatasetNewColumns.Taken> accepted,
                                                Set<Path> only) {
        List<Map<String, Object>> out = new ArrayList<>();
        Path in = inbox(dataRoot, pipeline);
        if (!Files.isDirectory(in)) return out;
        String ext = "." + extension(type);
        List<Path> waiting;
        try (var w = Files.walk(in)) {   // the import's file_pattern is glob:**/*.<ext>: sub-folders too
            waiting = w.filter(Files::isRegularFile)
                    .filter(p -> !p.getFileName().toString().startsWith(".")
                            && p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(ext))
                    .filter(p -> only == null || only.contains(p.toAbsolutePath().normalize()))
                    .sorted().toList();
        } catch (IOException unreadable) {
            return out;
        }
        for (Path f : waiting) {
            String name = f.getFileName().toString();
            ColumnCheck c;
            try {
                c = checkColumns(in, f, name, type, schema, id, dataset);
            } catch (IOException | RuntimeException unreadableFile) {
                if (unreadableFile instanceof ApiException a && a.status == 409) throw a;   // the schema itself: the run cannot go on
                continue;
            }
            if (c.refusal() == null) {
                accepted.add(new DatasetNewColumns.Taken(in, f, name, c));   // its new columns: DatasetNewColumns.settle
                continue;
            }
            try {
                String setAside = setAsideRefused(dataRoot, pipeline, f, name, c.refusal());
                Map<String, Object> r = new LinkedHashMap<>();
                r.put("file", name);
                r.put("reason", c.refusal());
                if (!c.missing().isEmpty()) r.put("findings", missingFindings(c, name, id));   // C2
                r.put("setAside", setAside);
                out.add(r);
            } catch (IOException cannotMove) {
                // left in the inbox: the run reads it as before
            }
        }
        return out;
    }

    /**
     * Move the waiting file {@code f} to {@code <dataRoot>/<pipeline>/refused/} (a stamped name when that name is taken) and
     * record it as a refused arrival with {@code reason}; answers where it went, relative to the Dataset's directory.
     */
    static String setAsideRefused(Path dataRoot, String pipeline, Path f, String name, String reason) throws IOException {
        Path dir = dataRoot.resolve(pipeline).resolve(REFUSED_DIR);
        Files.createDirectories(dir);
        Path to = dir.resolve(name);
        if (Files.exists(to)) to = dir.resolve(stamp() + "_" + name);
        Files.move(f, to, StandardCopyOption.ATOMIC_MOVE);
        String setAside = REFUSED_DIR + "/" + to.getFileName();
        DatasetArrivals.recordRefused(dataRoot, pipeline, name, reason, setAside);
        return setAside;
    }

    // ── deleting an import Dataset (LA-ONBOARD-ORPHAN-PIPELINE-1) ──────────────────────────────────────────

    /**
     * The import Pipeline that the Dataset entry {@code id} being deleted owns, claimed against a concurrent import of
     * the same id; {@code null} when the entry is not an Add data import or its Pipeline is already gone. Deleting the
     * entry without retiring the Pipeline left {@code imports/<id>_pipeline.toon} registered with no Dataset over it, so
     * an entry re-created under the same id (the generic component route) could drive it with Add a file and Run now.
     * The entry's {@code origin} / {@code importPipeline} are editable, so only the file Add data writes for THIS id is
     * retired, never another Pipeline the entry names. Under an approval policy over Pipelines or schemas the delete is
     * refused (409): retiring writes around that policy otherwise. The Dataset's data stays on disk.
     */
    static Retirement claimRetirement(ApiContext api, String id, Map<String, Object> content) {
        Path writeRoot = api.writeRoot();
        if (writeRoot == null || id == null || !ID.matcher(id).matches()
                || !"import".equals(String.valueOf(content.get("origin"))) || !id.equals(content.get("importPipeline")))
            return null;
        Path dir = writeRoot.resolve(CONFIG_DIR);
        Path pipelineFile = dir.resolve(id + "_pipeline.toon");
        if (!Files.isRegularFile(pipelineFile)) return null;
        PendingChanges.holdRefusing(api, List.of("pipeline", "schema"),
                "deleting an Add data Dataset retires its Pipeline and schema");
        String key = writeRoot + "|" + id;
        if (!IN_FLIGHT.add(key))
            throw new ApiException(409, ErrorCodes.CONFLICT, "'" + id + "' is being imported right now; delete it when the run has finished");
        return new Retirement(writeRoot, id, pipelineFile, dir.resolve(id + "_schema.toon"), HostContext.of(api).service(), key);
    }

    /** A claimed import Pipeline: {@link #retire} once the Dataset entry is gone, {@link #release} always. */
    record Retirement(Path writeRoot, String id, Path pipelineFile, Path schemaFile, CollectorService service, String key) {

        /**
         * Unregister the Pipeline and remove its config files and history; the data directory is kept. Idempotent, and the
         * Pipeline file goes LAST: it is what {@link #claimRetirement} finds a retry by, so a step that fails leaves it to retry.
         */
        void retire() throws IOException {
            service.unregisterPipeline(pipelineFile);
            Files.deleteIfExists(schemaFile);
            PostgresSnapshot.retire(writeRoot, id, service);   // BASIC-ONBOARD-1 P3: a snapshot's settings (and its Connection) go too
            PipelineHistory.purge(writeRoot, id);
            Files.deleteIfExists(pipelineFile);
        }

        void release() {
            IN_FLIGHT.remove(key);
        }
    }

    // ── PUT /datasets/{id}/columns/{column} ─────────────────────────────────────────────────────────────────

    /**
     * The record type a commit writes (ENTITY-TYPE-CATALOG-1 P2): the body's {@code recordType} when stated ({@code null} =
     * none), else the draft's proposal. One the Space's packs do not define is refused (422).
     */
    @SuppressWarnings("unchecked")
    static String commitRecordType(Map<String, Object> draft, Map<String, Object> body, LinkAnalysisSettings settings) {
        Map<String, Object> et = (Map<String, Object>) draft.getOrDefault("entityTypes", Map.of());
        String rt = body.containsKey("recordType") ? (body.get("recordType") == null ? null : String.valueOf(body.get("recordType")).trim())
                : Plan.proposed(et.get("recordType"));
        if (rt == null || rt.isEmpty()) return null;
        String want = rt;
        if (settings.effectiveRecordTypes().stream().noneMatch(r -> r.id().equals(want)))
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "recordType '" + rt + "' is not a record type of this Space"
                    + "; in force: " + settings.effectiveRecordTypes().stream().map(IndustryPack.RecordType::id).toList());
        return rt;
    }

    /**
     * {@code PUT /datasets/{id}/record-type} {@code {recordType: "<id>" | null}} (ENTITY-TYPE-CATALOG-1 P2): what one row of the
     * Dataset is. It only orders the "what does this column hold" choices. Gates as {@link #setColumn}: no write root 503,
     * unknown or unseen Dataset 404, view-only 403, an approval policy 409; 422 for a missing key or a record type the Space's
     * packs do not define. Answers {@code {dataset, recordType}}.
     */
    @SuppressWarnings("unchecked")
    static Object setRecordType(ApiContext api, HttpExchange ex, String id, Map<String, Object> body) throws IOException {
        Path writeRoot = com.gamma.access.WriteGates.requireWriteRoot(api, "dataset record type change");
        ComponentStore store = new ComponentStore(writeRoot.resolve("registry"));
        Map<String, Object> current = store.get("dataset", id).map(ComponentRegistry.Component::content)
                .orElseThrow(() -> new ApiException(404, ErrorCodes.NOT_FOUND, "no dataset component '" + id + "'"));
        OnboardRoutes.requireEdit(ex, id, current);
        PendingChanges.holdRefusing(api, List.of("dataset"), "what a row is is a Dataset change");
        if (body == null || !body.containsKey("recordType"))
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "recordType is required: a record type id, or null to clear it");
        if (body.get("recordType") != null && !(body.get("recordType") instanceof String))
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "recordType must be a string or null");
        String rt = commitRecordType(Map.of(), body, LinkAnalysisSettings.forRoot(writeRoot));
        Map<String, Object> content = new LinkedHashMap<>(current);
        content.remove("name");
        if (rt == null) content.remove(DatasetEntryRules.RECORD_TYPE);
        else content.put(DatasetEntryRules.RECORD_TYPE, rt);
        try {
            ComponentRoutes.validateKind(api, "dataset", id, content);
        } catch (IllegalArgumentException bad) {
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, bad.getMessage());
        }
        store.write("dataset", id, content);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("dataset", id);
        out.put("recordType", rt);
        return out;
    }

    /**
     * Change one column's Entity Type: body {@code {entityType: "<id>" | null}} or {@code {classification: "<X>" | null}};
     * and/or its role (LA-COLUMN-ROLES-1: {@code role: entity|temporal|measure|dimension|detail | null}) and a measure's
     * {@code unit} ({@code null} clears either; the Dataset rules judge the result); and whether a Time, Measure or
     * Category column rides on the links ({@code carried: false}; {@code true} or {@code null} clears it, P2).
     * The Dataset is re-assessed on the next profile read (its binding signature changes). Gates: capability → write root
     * 503 → unknown or unseen Dataset 404 → view-only 403 → approval policy over Datasets 409 → unknown column or Entity
     * Type 422 → the entry still passes the Dataset rules (a column in the link mapping keeps working) → write.
     */
    @SuppressWarnings("unchecked")
    static Object setColumn(ApiContext api, HttpExchange ex, String id, String column, Map<String, Object> body) throws IOException {
        Path writeRoot = com.gamma.access.WriteGates.requireWriteRoot(api, "dataset column change");
        ComponentStore store = new ComponentStore(writeRoot.resolve("registry"));
        Map<String, Object> current = store.get("dataset", id).map(ComponentRegistry.Component::content)
                .orElseThrow(() -> new ApiException(404, ErrorCodes.NOT_FOUND, "no dataset component '" + id + "'"));
        OnboardRoutes.requireEdit(ex, id, current);
        PendingChanges.holdRefusing(api, List.of("dataset"), "a column's role or Entity Type is a Dataset change");
        boolean byType = body.containsKey("entityType"), byClass = body.containsKey("classification");
        boolean byRole = body.containsKey("role"), byUnit = body.containsKey("unit"), byCarried = body.containsKey(DatasetEntryRules.CARRIED);
        if (byType && byClass)
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "state one of entityType or classification, not both");
        if (!byType && !byClass && !byRole && !byUnit && !byCarried)
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED,
                    "state entityType or classification, role, unit or carried (null clears it)");
        Object carried = body.get(DatasetEntryRules.CARRIED);
        if (carried != null && !(carried instanceof Boolean))
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "carried is true, false or null");
        // stored lower-case: Studio and Explore compare the role as written
        String role = byRole && body.get("role") != null ? String.valueOf(body.get("role")).trim().toLowerCase(Locale.ROOT) : null;
        String unit = byUnit && body.get("unit") != null ? String.valueOf(body.get("unit")).trim() : null;
        String classification = null;
        String typeId = null;
        if (!byType && !byClass) {
            // the column keeps what it holds
        } else if (byType) {
            Object v = body.get("entityType");
            if (v == null) classification = null;
            else {
                typeId = String.valueOf(v).trim();
                String tid = typeId;
                EntityTypes.EntityType t = LinkAnalysisSettings.forRoot(writeRoot).effectiveEntityTypes().stream()
                        .filter(x -> x.id().equals(tid)).findFirst()
                        .orElseThrow(() -> new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "this Space has no Entity Type '" + tid + "'"));
                if (t.classifications().isEmpty())
                    throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "Entity Type '" + tid + "' claims no classification");
                classification = t.classifications().get(0);
            }
        } else {
            Object v = body.get("classification");
            classification = v == null || String.valueOf(v).isBlank() ? null : String.valueOf(v).trim();
        }
        if (!(current.get("columns") instanceof List<?> cols))
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "'" + id + "' lists no columns");
        List<Object> next = new ArrayList<>();
        boolean found = false;
        for (Object o : cols) {
            if (o instanceof Map<?, ?> c && column.equalsIgnoreCase(String.valueOf(c.get("name")))) {
                Map<String, Object> m = new LinkedHashMap<>((Map<String, Object>) c);
                if (byType || byClass) {
                    if (classification == null) m.remove("classification");
                    else m.put("classification", classification);
                    // LA-COLUMN-ROLES-1: clearing what an Entity column holds, without a new role, makes it a detail again
                    if (classification == null && !byRole && "entity".equalsIgnoreCase(String.valueOf(m.get("role"))))
                        m.remove("role");
                }
                if (byRole) {
                    if (role == null || role.isEmpty()) m.remove("role");
                    else m.put("role", role);
                    // a unit is a measure's: a column that stops being one drops it unless the body states one
                    if (!byUnit && !"measure".equalsIgnoreCase(role)) m.remove("unit");
                }
                if (byUnit) {
                    if (unit == null || unit.isEmpty()) m.remove("unit");
                    else m.put("unit", unit);
                }
                // only "not carried" is stored: carrying is the default (plan Q-5)
                if (byCarried) {
                    if (Boolean.FALSE.equals(carried)) m.put(DatasetEntryRules.CARRIED, false);
                    else m.remove(DatasetEntryRules.CARRIED);
                }
                next.add(m);
                found = true;
            } else next.add(o);
        }
        if (!found) throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "'" + id + "' has no column '" + column + "'");
        Map<String, Object> content = new LinkedHashMap<>(current);
        content.remove("name");
        content.put("columns", next);
        try {
            ComponentRoutes.validateKind(api, "dataset", id, content);
        } catch (IllegalArgumentException bad) {
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, bad.getMessage());
        }
        ComponentRegistry.Component written = store.write("dataset", id, content);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("dataset", id);
        out.put("column", column);
        if (byType || byClass) out.put("classification", classification);
        if (typeId != null) out.put("entityType", typeId);
        out.put("columns", written.content().get("columns"));
        return out;
    }
}
