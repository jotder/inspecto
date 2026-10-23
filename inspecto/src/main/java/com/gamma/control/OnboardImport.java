package com.gamma.control;

import com.gamma.access.ComponentAccess;
import com.gamma.config.spec.Finding;
import com.gamma.config.spec.Severity;
import com.gamma.config.io.ConfigCodec;
import com.gamma.entitystore.EntityTypes;
import com.gamma.entitystore.LaLimit;
import com.gamma.entitystore.LaLimitValues;
import com.gamma.entitystore.LinkAnalysisSettings;
import com.gamma.etl.EditionFeatures;
import com.gamma.etl.PipelineConfig;
import com.gamma.expectation.ExpectationRunner;
import com.gamma.linkindex.LinkIndexAccess;
import com.gamma.linkindex.LinkIndexBuilder;
import com.gamma.pipeline.ComponentRegistry;
import com.gamma.pipeline.ComponentStore;
import com.gamma.service.CollectorService;
import com.gamma.spi.auth.ApiException;
import com.gamma.spi.auth.ErrorCodes;
import com.gamma.spi.http.ApiContext;
import com.gamma.util.AtomicFiles;
import com.gamma.util.DuckDbUtil;
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
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
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
 *       Expectations that target the Pipeline are evaluated. Skipped where {@code pipeline.automation} is present (a
 *       Space whose Jobs do it), and reported either way.</li>
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
 * thread - it never re-resolves "the current Space".
 */
final class OnboardImport {

    private OnboardImport() {}

    /** Where import configs live inside the Space's config tree. */
    static final String CONFIG_DIR = "imports";
    private static final Pattern ID = Pattern.compile("[a-z][a-z0-9_]{0,62}");
    private static final Pattern COLUMN = Pattern.compile("[A-Za-z_][A-Za-z0-9_]{0,63}");
    private static final Set<String> TYPES = Set.of("VARCHAR", "BIGINT", "DOUBLE", "DATE", "TIMESTAMP", "BOOLEAN");
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmssSSS").withZone(ZoneOffset.UTC);
    /** The feature whose presence means a Space's Jobs build indexes and evaluate Expectations (DATA-TIERS-1 §6). */
    static final String AUTOMATION_FEATURE = "pipeline.automation";

    /** Ids being imported right now in this JVM (a second commit of the same id is 409, not a race). */
    private static final Set<String> IN_FLIGHT = ConcurrentHashMap.newKeySet();
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
        String id = datasetId(body.get("name"), fileName);

        // claim the id BEFORE the existence checks: a commit of the same id finishing between check and write would
        // otherwise be undone by this one's rollback
        String key = writeRoot + "|" + id;
        if (!IN_FLIGHT.add(key))
            throw new ApiException(409, ErrorCodes.CONFLICT, "'" + id + "' is being imported right now");
        boolean handedOff = false;
        try {
            // ── decide everything before writing anything ─────────────────────────────────────────────────────
            List<EntityTypes.EntityType> types = LinkAnalysisSettings.forRoot(writeRoot).effectiveEntityTypes();
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
            if (Files.exists(pipelineFile) || Files.exists(schemaFile))
                throw new ApiException(409, ErrorCodes.CONFLICT, "config files for '" + id + "' already exist; choose another name");
            if (Files.exists(dataRoot.resolve(id)) || Files.exists(inbox(dataRoot, id)))
                throw new ApiException(409, ErrorCodes.CONFLICT, "the data directory already holds '" + id + "'; choose another name");
            long max = lim.get(LaLimit.IMPORT_MAX_DATASETS);
            if (OnboardRoutes.importCount(writeRoot) >= max)
                throw new ApiException(409, ErrorCodes.CONFLICT, "this Space already holds " + max
                        + " imported Datasets (import.max_datasets); remove one or ask an administrator to raise the limit");

            Map<String, Object> schema = plan.schema(id);
            Map<String, Object> pipeline = plan.pipeline(id, dataRoot, kind, fileName, String.valueOf(draft.get("type")));
            Map<String, Object> dataset = plan.dataset(id, kind, fileName, String.valueOf(draft.get("type")));
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

                if (Files.size(job.landed) > lim.get(LaLimit.IMPORT_INLINE_MAX_BYTES)) {
                    Map<String, Object> rec = follow(job, "commit", () -> job.finishCommit(draftDir));
                    handedOff = true;
                    return ApiContext.respondJson(ex, 202, rec);
                }
                Map<String, Object> summary = job.finishCommit(draftDir);
                return summary;
            } catch (IOException | RuntimeException fault) {
                if (!handedOff) job.rollback();
                throw fault;
            }
        } finally {
            if (!handedOff) IN_FLIGHT.remove(key);
        }
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
                   boolean keep) {}

    record Plan(List<PlanCol> cols, Map<String, Object> format, String primaryEntityType, Map<String, Object> link,
                String identifying, String description, String frontendType) {

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
            Map<String, Object> et = (Map<String, Object>) draft.getOrDefault("entityTypes", Map.of());
            for (Object o : (List<Object>) et.getOrDefault("columns", List.of()))
                if (o instanceof Map<?, ?> m) entityByColumn.put(String.valueOf(m.get("column")), (Map<String, Object>) m.get("entityType"));
            Map<String, Map<String, Object>> overrides = new LinkedHashMap<>();
            if (body.get("columns") != null) {
                if (!(body.get("columns") instanceof List<?> l))
                    throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "columns must be a list of {name, type?, format?, entityType?, rename?, drop?}");
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
                    cols.add(new PlanCol(n, n, String.valueOf(p.get("selector")), "VARCHAR", null, null, null, false));
                    continue;
                }
                String out = o.get("rename") instanceof String r && !r.isBlank() ? r.trim() : n;
                if (!COLUMN.matcher(out).matches())
                    throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "'" + out + "' cannot name a column: letters, digits and '_'");
                if (!outNames.add(out.toLowerCase(Locale.ROOT)))
                    throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "two columns would be named '" + out + "'");
                String type = o.get("type") instanceof String t ? t.trim().toUpperCase(Locale.ROOT) : String.valueOf(p.get("type"));
                if (!TYPES.contains(type))
                    throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "type of '" + n + "' must be one of " + TYPES);
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
                cols.add(new PlanCol(n, out, String.valueOf(p.get("selector")), type, fmt == null || fmt.isBlank() ? null : fmt,
                        classification, typeId == null || typeId.isEmpty() ? null : typeId, true));
                renamed.put(n, out);
            }
            if (renamed.isEmpty()) throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "every column was dropped");

            // Dataset level
            String primary = body.containsKey("primaryEntityType") ? stringOrNull(body, "primaryEntityType")
                    : proposed(et.get("primary"));
            if (primary != null && cols.stream().noneMatch(c -> c.keep() && primary.equals(c.entityType())))
                throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "primaryEntityType '" + primary + "' is held by no column");
            Map<String, Object> link = null;
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
                    throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "link must be {from, to, kind?, time?, amount?, details?} or null");
                Map<String, Object> given = stated ? (Map<String, Object>) body.get("link") : null;
                if (!stated || given != null) {
                    for (String k : List.of("from", "to", "kind", "time", "amount")) {
                        String v = given != null ? (given.get(k) == null ? null : String.valueOf(given.get(k))) : proposed(proposedLink.get(k));
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
            }
            String description = body.get("description") instanceof String s && !s.isBlank() ? s.trim() : null;
            return new Plan(cols, draftFormat, primary, link, identifying, description, String.valueOf(draft.get("type")));
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
                f.put("type", c.type());
                if (c.format() != null) f.put("format", c.format());
                fields.add(f);
                if (!c.keep()) continue;
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("name", c.name());
                m.put("from", c.source());
                m.put("fn", "keep");
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

        Map<String, Object> pipeline(String id, Path dataRoot, String kind, String fileName, String type) {
            String d = dataRoot.resolve(id).toString().replace('\\', '/');
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("name", id);
            p.put("active", true);
            p.put("origin", "import");
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
                Object q = formatValue("quote");
                if (q != null && !String.valueOf(q).isEmpty()) csv.put("quote", String.valueOf(q));
            }
            csv.put("date_formats", List.of("%Y-%m-%d"));
            csv.put("timestamp_formats", List.of("%Y-%m-%d %H:%M:%S"));
            proc.put("csv_settings", csv);
            p.put("processing", proc);
            Map<String, Object> parsing = new LinkedHashMap<>();
            switch (type) {
                case "json" -> {
                    parsing.put("frontend", "json");
                    parsing.put("json", Map.of("format", "array"));
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
            String time = link == null ? null : (String) link.get("time");
            if (time != null) d.put("eventDate", time);
            List<Map<String, Object>> columns = new ArrayList<>();
            for (PlanCol c : cols) {
                if (!c.keep()) continue;
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("name", c.name());
                m.put("type", datasetType(c.type()));
                if (c.classification() != null) m.put("classification", c.classification());
                columns.add(m);
            }
            d.put("columns", columns);
            if (primaryEntityType != null) d.put(DatasetEntryRules.PRIMARY_ENTITY_TYPE, primaryEntityType);
            if (link != null) d.put(DatasetEntryRules.DEFAULT_LINK_MAPPING, link);
            if (identifying != null) d.put("identifyingColumn", identifying);
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

    static String datasetType(String engine) {
        return switch (engine) {
            case "BIGINT", "DOUBLE" -> "number";
            case "DATE" -> "date";
            case "TIMESTAMP" -> "timestamp";
            case "BOOLEAN" -> "boolean";
            default -> "string";
        };
    }

    // ── one import run ──────────────────────────────────────────────────────────────────────────────────────

    /** Undo step that may throw. */
    @FunctionalInterface
    interface Undo {
        void run() throws Exception;
    }

    /** One run of an import Pipeline: commit, Add a file or Run now. */
    static final class Job {
        final Space space;
        final String id, kind, fileName;
        final Path source;
        final Map<String, Object> dataset;
        final boolean replace;
        final Deque<Undo> undo = new ArrayDeque<>();
        Path landed;
        Path aside;

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
            // a COPY: the draft keeps its file until the commit succeeds, so a failed commit can be retried as it stands
            String stem = source.getFileName().toString().replaceFirst("\\.[^.]*$", "");
            Path dest = in.resolve("add_" + stamp() + "_" + stem + "." + extension(type));
            Path tmp = in.resolve(".landing-" + dest.getFileName());
            Files.copy(source, tmp, StandardCopyOption.REPLACE_EXISTING);
            Files.move(tmp, dest, StandardCopyOption.ATOMIC_MOVE);   // the poll never sees a half-written file
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
            Map<String, Object> summary = runChecked(0);
            ComponentStore registry = new ComponentStore(space.writeRoot().resolve("registry"));
            registry.write("dataset", id, dataset);
            undo.push(() -> registry.delete("dataset", id));
            followOn(summary);
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
            try {
                space.service().runPipeline(id).orElseThrow(() -> new ApiException(409, ErrorCodes.CONFLICT, "the import's Pipeline '" + id + "' is not registered"));
            } catch (IllegalStateException notRunnable) {
                throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "the import could not run: " + notRunnable.getMessage());
            }
            long after = countRows(database());
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("dataset", id);
            s.put("pipeline", id);
            s.put("kind", kind);
            if (fileName != null) s.put("file", fileName);
            long parsed = 0, rejected = 0;
            List<Map<String, Object>> quarantined = new ArrayList<>();
            for (Map<String, String> b : space.service().statusStore().batches(cfg)) {
                if (batchesBefore.contains(String.valueOf(b.get("consignment_id")))) continue;
                parsed += parseLong(b.get("total_input_rows"));
                rejected += parseLong(b.get("rejected_rows"));
            }
            for (Map<String, String> q : space.service().statusStore().quarantine(cfg)) {
                if (quarantineBefore.contains(String.valueOf(q.get("path")))) continue;
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("file", landed != null && String.valueOf(q.get("file")).equals(landed.getFileName().toString()) ? fileName : q.get("file"));
                m.put("reason", q.get("reason"));
                quarantined.add(m);
            }
            s.put("rowsIn", parsed);
            s.put("rowsRejected", rejected);
            if (rejected > 0)
                s.put("rejectedDetail", "GET /runs/" + id + "/errors?file=" + landed.getFileName());
            s.put("quarantined", quarantined);
            s.put("rowsBefore", before);
            s.put("rowsAfter", after);
            s.put("rowsChanged", after - before);
            s.put("replaced", replace);
            if (landed != null && (!quarantined.isEmpty() || after <= (replace ? 0 : before))) {
                String why = !quarantined.isEmpty() ? "the file was set aside (" + quarantined.get(0).get("reason") + ")"
                        : rejected > 0 ? "all " + rejected + " rows were rejected" : "no row could be read";
                throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "nothing was imported from '" + fileName + "': " + why
                        + "; nothing was kept - check the column types and formats and try again");
            }
            return s;
        }

        /** DT-16: the import's own follow-on work, inside the user's run (unless the Space's Jobs do it). */
        void followOn(Map<String, Object> summary) {
            Map<String, Object> current = new ComponentStore(space.writeRoot().resolve("registry")).get("dataset", id)
                    .map(ComponentRegistry.Component::content).orElse(dataset);
            boolean inRun = !EditionFeatures.present(AUTOMATION_FEATURE);
            summary.put("followOnInRun", inRun);
            summary.put("index", inRun ? buildIndex(current) : outcome("DEFERRED", "the Space's Jobs build the index"));
            summary.put("expectations", inRun ? evaluateExpectations() : List.of());
        }

        @SuppressWarnings("unchecked")
        private Map<String, Object> buildIndex(Map<String, Object> content) {
            if (!(content.get(DatasetEntryRules.DEFAULT_LINK_MAPPING) instanceof Map<?, ?> raw))
                return outcome("SKIPPED", "the Dataset has no link mapping");
            Map<String, Object> m = (Map<String, Object>) raw;
            LinkIndexBuilder builder = com.gamma.spi.OptionalSpi.first(LinkIndexBuilder.class).orElse(null);
            if (builder == null) return outcome("NOT_AVAILABLE", "Link Analysis is not installed, so there is no index to build");
            try {
                LinkIndexAccess.Outcome o = builder.build(space.writeRoot(), space.dataRoot(), new LinkIndexAccess.Request(
                        "onboard:" + id, id, (String) m.get("from"), (String) m.get("to"), (String) m.get("kind"), (String) m.get("time"),
                        null, (String) m.get("amount"), List.of(), space.actor(), true, 300_000L, 0L));
                Map<String, Object> r = outcome(o.result(), o.message());
                r.put("code", o.code());
                r.put("edges", o.edges());
                r.put("nodes", o.nodes());
                return r;
            } catch (RuntimeException failed) {
                return outcome("FAILED", "the index could not be built: " + failed.getMessage());
            }
        }

        private List<Map<String, Object>> evaluateExpectations() {
            List<Map<String, Object>> out = new ArrayList<>();
            ComponentStore store = new ComponentStore(space.writeRoot().resolve("registry"));
            ExpectationRunner runner = new ExpectationRunner(space.writeRoot(), space.dataRoot(),
                    space.service().objects().orElse(null), msg -> msg);
            for (ComponentRegistry.Component c : store.list(ExpectationRunner.TYPE)) {
                Map<String, Object> e = c.content();
                if (!"pipeline".equalsIgnoreCase(String.valueOf(e.get("targetType"))) || !id.equalsIgnoreCase(String.valueOf(e.get("target")))
                        || "false".equalsIgnoreCase(String.valueOf(e.getOrDefault("enabled", true)))) continue;
                Map<String, Object> r = new LinkedHashMap<>();
                r.put("name", e.get("name"));
                try {
                    Map<String, Object> next = runner.evaluateOne(e);
                    Object last = next.get("lastResult");
                    r.put("status", last instanceof Map<?, ?> lm ? lm.get("status") : null);
                    r.put("violations", last instanceof Map<?, ?> lm ? lm.get("violations") : null);
                } catch (IOException | RuntimeException failed) {
                    r.put("status", "ERROR");
                    r.put("message", failed.getMessage());
                }
                out.add(r);
            }
            return out;
        }

        /** Replace: set the current data aside; {@link #restore} puts it back, {@link #dropAside} discards it. */
        void setAside() throws IOException {
            Path db = database();
            if (!Files.isDirectory(db)) return;
            aside = db.resolveSibling("database.prev-" + STAMP.format(Instant.now()));
            Files.move(db, aside, StandardCopyOption.ATOMIC_MOVE);
            undo.push(this::restore);
        }

        void restore() throws IOException {
            if (aside == null) return;
            OnboardRoutes.deleteTree(database());
            Files.move(aside, database(), StandardCopyOption.ATOMIC_MOVE);
            aside = null;
        }

        void dropAside() {
            if (aside != null) OnboardRoutes.deleteTree(aside);
            aside = null;
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

    private static Map<String, Object> outcome(String result, String message) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("result", result);
        m.put("message", message);
        return m;
    }

    private static long parseLong(String s) {
        try {
            return s == null || s.isBlank() ? 0 : Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
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
        BACKGROUND.submit(() -> {
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
                }
            } finally {
                synchronized (rec) {
                    rec.put("finishedAt", Instant.now().toString());
                }
                IN_FLIGHT.remove(key);
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
    private static Map<String, Object> importDataset(Path writeRoot, HttpExchange ex, String id, CollectorService service) {
        Map<String, Object> content = new ComponentStore(writeRoot.resolve("registry")).get("dataset", id)
                .map(ComponentRegistry.Component::content)
                .orElseThrow(() -> new ApiException(404, ErrorCodes.NOT_FOUND, "no dataset component '" + id + "'"));
        OnboardRoutes.requireEdit(ex, id, content);
        Path expected = writeRoot.resolve(CONFIG_DIR).resolve(id + "_pipeline.toon").toAbsolutePath().normalize();
        boolean ours = "import".equals(String.valueOf(content.get("origin"))) && id.equals(content.get("importPipeline"))
                && service.pathFor(id).map(p -> p.toAbsolutePath().normalize().equals(expected)).orElse(false)
                && service.configFor(id).isPresent();
        if (!ours)
            throw new ApiException(409, ErrorCodes.CONFLICT, "'" + id + "' is kept up to date by a Pipeline, not by Add data; add files to that Pipeline's Collector");
        return content;
    }

    static Object addFile(ApiContext api, HttpExchange ex, String id) throws IOException {
        Path writeRoot = OnboardRoutes.requireSelfImport(api, "Add a file");
        Path dataRoot = OnboardRoutes.requireDataRoot(api);
        Map<String, Object> content = importDataset(writeRoot, ex, id, HostContext.of(api).service());
        String pipeline = String.valueOf(content.get("importPipeline"));
        String name = OnboardRoutes.fileName(ex);
        String mode = ApiContext.query(ex, "mode");
        boolean replace = mode == null || mode.isBlank() || mode.equals("replace");
        if (!replace && !"append".equals(mode))
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "mode must be replace (the default) or append");
        if (!replace && "reference".equals(content.get("kind")))
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "a Reference is replaced, never appended to");
        LaLimitValues lim = OnboardRoutes.limits(writeRoot);
        CollectorService service = HostContext.of(api).service();
        PipelineConfig cfg = service.configFor(pipeline)
                .orElseThrow(() -> new ApiException(409, ErrorCodes.CONFLICT, "the import's Pipeline '" + pipeline + "' is not registered"));

        String key = writeRoot + "|" + pipeline;
        if (!IN_FLIGHT.add(key)) throw new ApiException(409, ErrorCodes.CONFLICT, "'" + id + "' is being imported right now");
        Path stage = dataRoot.resolve(OnboardRoutes.DRAFTS).resolve("a" + UUID.randomUUID().toString().replace("-", ""));
        boolean handedOff = false;
        Job job = null;
        try {
            Path file = stage.resolve(OnboardRoutes.storedName(name));
            OnboardRoutes.receive(api, ex, file, lim.get(LaLimit.IMPORT_MAX_BYTES));
            String type = OnboardDraft.fileType(file, name);
            String expected = String.valueOf(content.get("importFormat"));
            if (!type.equals(expected))   // the import's parser and file_pattern are fixed: a TSV is not a CSV import's file
                throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "'" + name + "' is " + type.toUpperCase(Locale.ROOT)
                        + "; '" + id + "' was imported from " + expected.toUpperCase(Locale.ROOT));
            requireSameColumns(stage, file, name, type, writeRoot.resolve(CONFIG_DIR).resolve(pipeline + "_schema.toon"), id);
            Space space = new Space(writeRoot, dataRoot, service, ApiContext.actor(ex));
            job = new Job(space, pipeline, String.valueOf(content.get("kind")), file, name, content, replace);
            long before = countRows(job.database());
            final Job j = job;
            if (replace) job.setAside();
            job.landFromDraft(type);
            Work rest = () -> {
                try {
                    Map<String, Object> s = j.runChecked(replace ? 0 : before);
                    s.put("rowsBefore", before);
                    s.put("rowsChanged", ((Number) s.get("rowsAfter")).longValue() - before);
                    s.put("dataset", id);
                    j.dropAside();
                    j.followOn(s);
                    j.undo.clear();
                    return s;
                } finally {
                    OnboardRoutes.deleteTree(stage);   // the staged copy, success or not (a followed run too)
                }
            };
            if (Files.size(job.landed) > lim.get(LaLimit.IMPORT_INLINE_MAX_BYTES)) {
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

    /** The added file must carry the columns the import reads (by index for delimited text, by name otherwise). */
    private static void requireSameColumns(Path dir, Path file, String name, String type, Path schema, String id) throws IOException {
        OnboardDraft.Analysis a = OnboardDraft.analyse(dir, file, name, type, Map.of());
        List<String> selectors = new ArrayList<>();
        try {
            Map<String, Object> s = com.gamma.util.ToonHelper.load(schema.toString());
            if (s.get("raw") instanceof Map<?, ?> raw && raw.get("fields") instanceof List<?> fs)
                for (Object f : fs) if (f instanceof Map<?, ?> fm) selectors.add(String.valueOf(fm.get("selector")));
        } catch (Exception unreadable) {
            selectors.clear();
        }
        if (selectors.isEmpty())   // fail closed: no file lands against a schema nobody can read
            throw new ApiException(409, ErrorCodes.CONFLICT, "the schema of '" + id + "' cannot be read, so no file can be added to it");
        if ("csv".equals(type) || "tsv".equals(type)) {
            int max = -1;
            try {
                for (String sel : selectors) max = Math.max(max, Integer.parseInt(sel));
            } catch (NumberFormatException notAnIndex) {
                throw new ApiException(409, ErrorCodes.CONFLICT, "the schema of '" + id + "' does not read columns by position");
            }
            if (a.cols().size() <= max)
                throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "'" + name + "' has " + a.cols().size()
                        + " columns; '" + id + "' reads " + (max + 1));
        } else {
            Set<String> headers = new LinkedHashSet<>();
            for (OnboardDraft.Col c : a.cols()) headers.add(c.header());
            for (String sel : selectors)
                if (!headers.contains(sel))
                    throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "'" + name + "' has no '" + sel + "' field, which '" + id + "' reads");
        }
    }

    static Object runNow(ApiContext api, HttpExchange ex, String id) {
        Path writeRoot = OnboardRoutes.requireSelfImport(api, "Run now");
        Path dataRoot = OnboardRoutes.requireDataRoot(api);
        Map<String, Object> content = importDataset(writeRoot, ex, id, HostContext.of(api).service());
        String pipeline = String.valueOf(content.get("importPipeline"));
        String key = writeRoot + "|" + pipeline;
        if (!IN_FLIGHT.add(key)) throw new ApiException(409, ErrorCodes.CONFLICT, "'" + id + "' is being imported right now");
        try {
            Job job = new Job(new Space(writeRoot, dataRoot, HostContext.of(api).service(), ApiContext.actor(ex)), pipeline,
                    String.valueOf(content.get("kind")), null, null, content, false);
            long before = countRows(job.database());
            Map<String, Object> s = job.runChecked(before);
            s.put("dataset", id);
            job.followOn(s);
            return s;
        } finally {
            IN_FLIGHT.remove(key);
        }
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
            PipelineHistory.purge(writeRoot, id);
            Files.deleteIfExists(pipelineFile);
        }

        void release() {
            IN_FLIGHT.remove(key);
        }
    }

    // ── PUT /datasets/{id}/columns/{column} ─────────────────────────────────────────────────────────────────

    /**
     * Change one column's Entity Type: body {@code {entityType: "<id>" | null}} or {@code {classification: "<X>" | null}}.
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
        PendingChanges.holdRefusing(api, List.of("dataset"), "a column's Entity Type is a Dataset change");
        boolean byType = body.containsKey("entityType"), byClass = body.containsKey("classification");
        if (byType == byClass)
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "state exactly one of entityType or classification (null clears it)");
        String classification;
        String typeId = null;
        if (byType) {
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
                if (classification == null) m.remove("classification");
                else m.put("classification", classification);
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
        out.put("classification", classification);
        if (typeId != null) out.put("entityType", typeId);
        out.put("columns", written.content().get("columns"));
        return out;
    }
}
