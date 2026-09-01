package com.gamma.control;

import com.gamma.config.io.ConfigCodec;
import com.gamma.config.io.ConfigLoader;
import com.gamma.config.safety.ConfigSafetyValidator;
import com.gamma.config.safety.SafetyPolicy;
import com.gamma.config.spec.ConfigSpecs;
import com.gamma.config.spec.Finding;
import com.gamma.config.spec.Severity;
import com.gamma.enrich.EnrichmentConfig;
import com.gamma.etl.PipelineConfig;
import com.sun.net.httpserver.HttpExchange;
import com.gamma.util.AtomicFiles;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import static com.gamma.util.Values.mapAt;

/**
 * Selective dependency-closure transfer for CANONICAL {@code *_pipeline.toon} pipelines
 * (TRANSFER-ARCH-1, {@code docs/superpower/authoring-residuals-plan.md} §R2) — the server-side
 * sibling of the client-composed stream-config bundle ({@code inspecto-ui}'s
 * {@code inspecto/transfer/stream-bundle.ts}), whose rules it mirrors: satellites travel with the
 * pipeline, secrets never do (a bound connection becomes a {@code requirements[]} entry), the
 * import retargets identity <em>inside</em> each body, satellites land <em>before</em> the
 * pipeline, an import is always an inactive draft, and the default conflict policy is refuse.
 *
 * <pre>
 *   GET  /pipelines/{name}/bundle    download one pipeline + its file closure as a zip
 *   POST /pipelines/import           import such a zip (query: ?name=&amp;conflict=refuse|overwrite|rename)
 * </pre>
 *
 * <p><b>Why this is not a {@link BundleRoutes} kind</b>: that format carries component-registry
 * artifacts addressed by id; a canonical pipeline's satellites are <em>config-namespace paths</em>
 * that must be rewritten for the target, which the id-based {@code BundleRef} cannot express — and
 * the two namespaces collide on the word <em>schema</em>. Its {@code authored-pipeline} kind keeps
 * serving grandfathered {@code *_flow.toon} flows only.
 *
 * <p><b>Closure enumeration reuses the engine's own resolution</b>: the exported satellite set is
 * {@link PipelineConfig#referencedFiles()} — every schema / per-segment schema / grammar / mapping
 * file the parser itself resolved through {@code PipelineConfigParser.resolveSchemaRef} (config-
 * relative first, W1b) when the pipeline was registered. Never a second hand-rolled resolver.
 * Satellites travel <b>byte-verbatim</b> under their bare basename (the W3 portable form), so the
 * zip is self-contained by construction; the import rewrites the pipeline's own refs to those bare
 * basenames beside it.
 *
 * <p><b>Transport</b>: the request/response body is the zip itself (raw {@code application/zip}),
 * matching {@code POST /spaces/{id}/import} — this codebase deliberately has no multipart parser,
 * and raw bytes are what the JDK {@code HttpServer} plumbing supports cleanly. The import options
 * ({@code name}, {@code conflict}) therefore travel as query parameters, not a JSON body.
 *
 * <p><b>Import gate order (fail closed)</b>: write-root 503 → manifest/spec validate 422 →
 * zip-slip path jail 403 → name conflict 409 (unless {@code conflict=overwrite|rename}) → the SAME
 * {@code ConfigSpecs.pipeline()} + {@code ConfigSafetyValidator} + three arming pre-check gate the
 * graph save runs, over the <em>retargeted</em> pipeline (ERROR ⇒ 422, nothing written; the import
 * always lands {@code active: false}, so arming findings surface as warnings) → atomic writes,
 * satellites before the pipeline.
 */
final class PipelineBundleRoutes implements RouteModule {

    private static final Logger log = LoggerFactory.getLogger(PipelineBundleRoutes.class);

    static final String FORMAT = "inspecto-pipeline-bundle";
    static final int VERSION = 1;
    static final String MANIFEST = "manifest.toon";

    /** Keys whose literal (non-{@code ${…}}) value must never leave the instance — masked at export,
     *  mirroring the client bundle's rule ({@code onboarding.md} §export: a config should only hold
     *  {@code ${ENV:…}} references; a literal is an authoring mistake, not something to ship). */
    private static final Pattern SECRET_KEY =
            Pattern.compile("(?i)(password|passphrase|secret|token|api_?key|access_key)");

    @Override
    public void register(ApiContext api) {
        // Export is a READ: like /bundle/export and /spaces/{id}/datasources/{ds}/export it carries
        // no capability gate — the closure is this instance's own config, secrets never travel.
        api.get("/pipelines/([^/]+)/bundle", (e, m) -> exportBundle(api, e, ApiContext.name(m)));
        // Import writes real config → Builder capability, then the write-root gate (BundleRoutes' order).
        api.post("/pipelines/import", ApiContext.withCapability("canAuthorWorkbench",
                (e, m) -> importBundle(api, e)));
    }

    // ── export ───────────────────────────────────────────────────────────────────

    /**
     * {@code GET /pipelines/{name}/bundle} — one registered canonical pipeline plus its file closure,
     * as an {@code application/zip} download: {@code manifest.toon} (format id, version, pipeline id,
     * satellite inventory with per-entry sha256, requirements, notes), the pipeline toon, every
     * {@link PipelineConfig#referencedFiles() referenced file} byte-verbatim under its bare basename,
     * and any {@code *_enrich.toon} companion whose {@code triggers.on_pipeline} names this pipeline.
     * 404 unknown pipeline; 409 when two distinct satellites collide on one basename (the zip cannot
     * hold both under the portable bare name — rename one at the source).
     */
    private Object exportBundle(ApiContext api, HttpExchange e, String name) throws IOException {
        PipelineConfig cfg = api.service().configFor(name)
                .orElseThrow(() -> new ApiException(404, "no pipeline named '" + name + "'"));
        Path file = api.service().pathFor(name)
                .orElseThrow(() -> new ApiException(404, "no config file for pipeline '" + name + "'"));
        String id = cfg.identity().pipelineName();

        List<String> notes = new ArrayList<>();
        LinkedHashMap<String, byte[]> entries = new LinkedHashMap<>();

        // The pipeline body: verbatim bytes unless a literal secret-looking value had to be masked.
        Map<String, Object> raw = ConfigLoader.filesystem().decode(file.toString());
        boolean masked = maskSecrets(raw, "", notes);
        String pipelineEntry = id + "_pipeline.toon";
        entries.put(pipelineEntry, masked
                ? ConfigCodec.toToon(raw).getBytes(StandardCharsets.UTF_8)
                : Files.readAllBytes(file));

        // The closure — the engine's OWN resolution (resolveSchemaRef, config-relative first, W1b):
        // referencedFiles() is what the parser resolved when this pipeline registered. Satellites
        // travel byte-verbatim under their bare basename (W3 portable form).
        List<Map<String, Object>> satellites = new ArrayList<>();
        Map<String, Path> byBasename = new LinkedHashMap<>();
        for (Path ref : cfg.referencedFiles()) {
            Path abs = ref.toAbsolutePath().normalize();
            if (!Files.isRegularFile(abs)) {
                notes.add("referenced file '" + ref + "' was not readable and is not in the bundle");
                continue;
            }
            String base = abs.getFileName().toString();
            Path prior = byBasename.putIfAbsent(base, abs);
            if (prior != null) {
                if (prior.equals(abs)) continue;   // the same file referenced twice — one entry
                throw new ApiException(409, "two satellites collide on the portable name '" + base
                        + "' (" + prior + " and " + abs + ") — rename one before exporting");
            }
            byte[] bytes = Files.readAllBytes(abs);
            entries.put(base, bytes);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("path", base);
            row.put("sha256", sha256(bytes));
            satellites.add(row);
        }

        // The Stage-2 companion(s): every *_enrich.toon at the write root whose triggers.on_pipeline
        // names this pipeline — the same match attachCompanionEnrichments/PipelineDependents use.
        List<String> enrichments = new ArrayList<>();
        for (Map.Entry<Path, Map<String, Object>> comp : companionsOf(api, id).entrySet()) {
            String entry = comp.getKey().getFileName().toString();
            Map<String, Object> body = comp.getValue();
            boolean m2 = maskSecrets(body, entry + ":", notes);
            entries.put(entry, m2 ? ConfigCodec.toToon(body).getBytes(StandardCharsets.UTF_8)
                    : Files.readAllBytes(comp.getKey()));
            enrichments.add(entry);
        }

        // A bound connection travels as a REQUIREMENT (profile name + connector type) — never the
        // profile, never secret material (the client bundle's rule, and BundleRoutes' D2 posture).
        List<Map<String, Object>> requirements = new ArrayList<>();
        String conn = connectionRef(raw);
        if (conn != null) {
            Map<String, Object> req = new LinkedHashMap<>();
            req.put("kind", "connection");
            req.put("profile", conn);
            api.service().connection(conn).ifPresent(p -> req.put("connector", p.connector()));
            requirements.add(req);
        }

        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("format", FORMAT);
        manifest.put("version", VERSION);
        manifest.put("pipeline", id);
        manifest.put("pipeline_file", pipelineEntry);
        manifest.put("exported_at", java.time.Instant.now().toString());
        String sourcePrefix = spacePrefix(api.writeRoot());
        if (!sourcePrefix.isEmpty()) manifest.put("source_prefix", sourcePrefix);
        if (!satellites.isEmpty()) manifest.put("satellites", satellites);
        if (!enrichments.isEmpty()) manifest.put("enrichments", enrichments);
        if (!requirements.isEmpty()) manifest.put("requirements", requirements);
        if (!notes.isEmpty()) manifest.put("notes", notes);

        byte[] zip = zip(manifest, entries);
        log.info("[PIPELINE-BUNDLE] exported '{}': {} satellite(s), {} companion(s), {} bytes",
                id, satellites.size(), enrichments.size(), zip.length);
        return download(e, zip, id + ".pipeline-bundle.zip");
    }

    // ── import ───────────────────────────────────────────────────────────────────

    /**
     * {@code POST /pipelines/import?name=&conflict=refuse|overwrite|rename} — body is the bundle zip
     * (raw {@code application/zip}; see the class doc for why not multipart). Gate order per the
     * class doc. The pipeline and its satellites land in {@code <write-root>/<id>/} (the sample-space
     * layout), the pipeline's refs rewritten to the bare basenames beside it; a companion enrichment
     * is retargeted on {@code name}, {@code triggers.on_pipeline}, {@code input.database} and
     * {@code output.database} and registered. The import ALWAYS lands {@code active: false}.
     */
    private Object importBundle(ApiContext api, HttpExchange e) throws IOException {
        // Gate 1 — writes disabled → 503.
        Path writeRoot = WriteGates.requireWriteRoot(api, "pipeline import");

        // Gate 2 — manifest / spec validation → 422.
        LinkedHashMap<String, byte[]> entries = unzip(e.getRequestBody().readAllBytes());
        Map<String, Object> manifest = manifestOf(entries);
        String sourceId = ApiContext.str(manifest, "pipeline");
        String pipelineEntry = ApiContext.str(manifest, "pipeline_file");
        byte[] pipelineBytes = pipelineEntry == null ? null : entries.get(pipelineEntry);
        if (sourceId == null || pipelineBytes == null)
            throw new ApiException(422, "bundle names no pipeline entry");
        List<Map<String, Object>> satellites = asMapList(manifest.get("satellites"));
        for (Map<String, Object> s : satellites) {
            String path = ApiContext.str(s, "path");
            byte[] bytes = path == null ? null : entries.get(path);
            if (bytes == null)
                throw new ApiException(422, "manifest names satellite '" + path + "' but the zip has no such entry");
            String expected = ApiContext.str(s, "sha256");
            if (expected != null && !expected.equals(sha256(bytes)))
                throw new ApiException(422, "satellite '" + path + "' does not match its manifest sha256");
        }
        Map<String, Object> sourceMap;
        try {
            sourceMap = ConfigCodec.toMap(new String(pipelineBytes, StandardCharsets.UTF_8));
        } catch (RuntimeException bad) {
            throw new ApiException(422, "bundle pipeline does not parse: " + bad.getMessage());
        }

        // The target identity: caller's ?name= wins, else the bundle's own id. 422 on an unsafe name.
        String requested = ApiContext.query(e, "name");
        String newId = WriteGates.safeName(
                (requested == null || requested.isBlank() ? sourceId : requested).trim().toLowerCase(),
                "pipeline name");
        String conflict = String.valueOf(
                java.util.Objects.requireNonNullElse(ApiContext.query(e, "conflict"), "refuse")).toLowerCase();
        if (!Set.of("refuse", "overwrite", "rename").contains(conflict))
            throw new ApiException(422, "conflict must be refuse, overwrite or rename");

        // Gate 3 — zip-slip path jail → 403 (BundleImporter precedent, WriteGates.jail = PathJail):
        // every entry the manifest tells us to write must resolve inside the destination directory.
        // Jailed here against the requested destination — BEFORE the conflict gate, per the specced
        // order — and again below against the FINAL destination once rename/overwrite resolved it.
        Path provisionalDir = writeRoot.resolve(newId);
        for (Map<String, Object> s : satellites) {
            String path = ApiContext.str(s, "path");
            WriteGates.jail(provisionalDir, provisionalDir.resolve(path), "bundle entry '" + path + "'");
        }
        for (Object en : asStringList(manifest.get("enrichments")))
            WriteGates.jail(provisionalDir, provisionalDir.resolve(String.valueOf(en)), "bundle entry '" + en + "'");

        // Gate 4 — name conflict → 409 unless the policy says otherwise.
        String renamedFrom = null;
        if (taken(api, writeRoot, newId)) {
            switch (conflict) {
                case "overwrite" -> { /* proceed over the existing file */ }
                case "rename" -> {
                    String base = newId;
                    newId = freeName(api, writeRoot, base);
                    renamedFrom = base;
                }
                default -> throw new ApiException(409, "pipeline '" + newId
                        + "' already exists; re-send with ?conflict=overwrite or ?conflict=rename");
            }
        }
        // Overwrite lands on the pipeline's REGISTERED file (the saveGraph rule — never a second,
        // shadow file); anything else gets the sample-space layout <write-root>/<id>/<id>_pipeline.toon.
        Path registered = api.service().pathFor(newId).map(Path::normalize)
                .filter(p -> p.startsWith(writeRoot)).orElse(null);
        Path target = WriteGates.jail(writeRoot,
                registered != null ? registered
                        : writeRoot.resolve(newId).resolve(newId + "_pipeline.toon"),
                "resolved path");
        Path destDir = target.getParent();
        Set<String> satelliteNames = new java.util.LinkedHashSet<>();
        for (Map<String, Object> s : satellites) {
            String path = ApiContext.str(s, "path");
            WriteGates.jail(destDir, destDir.resolve(path), "bundle entry '" + path + "'");
            satelliteNames.add(path);
        }
        for (Object en : asStringList(manifest.get("enrichments")))
            WriteGates.jail(destDir, destDir.resolve(String.valueOf(en)), "bundle entry '" + en + "'");

        // Retarget INSIDE the pipeline body, then run the FULL saveGraph gate over the result.
        String prefix = spacePrefix(writeRoot);
        Map<String, Object> retargeted = retargetPipeline(sourceMap, sourceId, newId, prefix, satelliteNames);

        // Satellites land FIRST (the client bundle's ordering rule — the pipeline never names a file
        // that does not exist yet). They must also land BEFORE the safety gate: a config ref resolves
        // config-relative only when the candidate EXISTS (resolveSchemaRef / ConfigSafetyValidator,
        // both by design), so validating the rewritten bare basenames against an empty directory
        // would false-422 every bundle. On an ERROR verdict the satellites are removed again, so a
        // refused import still leaves nothing written.
        boolean createdDir = !Files.isDirectory(destDir);
        Files.createDirectories(destDir);
        List<Path> satellitePaths = new ArrayList<>();
        List<String> written = new ArrayList<>();
        for (String s : satelliteNames) {
            Path st = destDir.resolve(s).normalize();
            AtomicFiles.write(st, entries.get(s), ".import-");
            satellitePaths.add(st);
            written.add(writeRoot.relativize(st).toString().replace('\\', '/'));
        }

        List<Finding> findings = new ArrayList<>(
                ConfigLoader.filesystem().validate(ConfigSpecs.pipeline(), retargeted));
        findings.addAll(ConfigSafetyValidator.check("pipeline", retargeted, SafetyPolicy.defaultPolicy(), destDir));
        findings.addAll(ConfigRoutes.armedWithoutSchemaFindings("pipeline", retargeted));
        findings.addAll(ConfigRoutes.routeArmingFindings("pipeline", retargeted));
        findings.addAll(ConfigRoutes.stepDisableFindings("pipeline", retargeted));
        if (findings.stream().anyMatch(f -> f.severity() == Severity.ERROR)) {
            for (Path st : satellitePaths) Files.deleteIfExists(st);
            if (createdDir) {
                try {
                    Files.deleteIfExists(destDir);   // only when empty — never a recursive delete
                } catch (IOException notEmpty) {
                    // someone else's file appeared — leave the directory rather than risk their data
                }
            }
            return ApiContext.respondJson(e, 422, Map.of("written", false,
                    "error", "config has ERROR-level findings; not written", "findings", findings));
        }

        // Companion enrichment(s): retargeted inside the body, written before the pipeline too.
        List<String> notes = new ArrayList<>();
        List<Path> enrichTargets = new ArrayList<>();
        String sourcePrefix = ApiContext.str(manifest, "source_prefix");
        Object sourceDb = sourceMap.get("dirs") instanceof Map<?, ?> d ? d.get("database") : null;
        Object targetDb = retargeted.get("dirs") instanceof Map<?, ?> d ? d.get("database") : null;
        for (Object en : asStringList(manifest.get("enrichments"))) {
            byte[] bytes = entries.get(String.valueOf(en));
            if (bytes == null) throw new ApiException(422, "manifest names companion '" + en
                    + "' but the zip has no such entry");
            Map<String, Object> enrich;
            try {
                enrich = ConfigCodec.toMap(new String(bytes, StandardCharsets.UTF_8));
            } catch (RuntimeException bad) {
                throw new ApiException(422, "bundle companion '" + en + "' does not parse: " + bad.getMessage());
            }
            String newName = retargetEnrichment(enrich, sourceId, newId, sourcePrefix, prefix,
                    sourceDb == null ? null : String.valueOf(sourceDb),
                    targetDb == null ? null : String.valueOf(targetDb));
            Path et = WriteGates.jail(destDir, destDir.resolve(
                    ConfigFileSupport.fileBase("enrichment", newName.toLowerCase()) + ".toon"), "enrichment name");
            WriteGates.conflictIf(!"overwrite".equals(conflict) && Files.exists(et),
                    "companion file exists: " + et.getFileName());
            AtomicFiles.write(et, ConfigCodec.toToon(enrich).getBytes(StandardCharsets.UTF_8), ".import-");
            written.add(writeRoot.relativize(et).toString().replace('\\', '/'));
            enrichTargets.add(et);
        }

        AtomicFiles.write(target, ConfigCodec.toToon(retargeted).getBytes(StandardCharsets.UTF_8), ".import-");
        written.add(writeRoot.relativize(target).toString().replace('\\', '/'));

        // Make it live: register the (inactive) pipeline, then its companions — the same "a write
        // registers, it does not merely persist" rule the metadata bundle's enrichment kind follows.
        String registeredName;
        try {
            registeredName = api.service().registerPipeline(target);
        } catch (IllegalArgumentException invalid) {
            throw new ApiException(422, "imported pipeline did not register: " + invalid.getMessage());
        } catch (IllegalStateException clash) {
            throw new ApiException(409, clash.getMessage());
        }
        for (Path et : enrichTargets) {
            try {
                api.service().registerEnrichment(EnrichmentConfig.load(et.toString()));
            } catch (RuntimeException invalid) {
                notes.add("companion " + et.getFileName() + " was written but did not register: "
                        + invalid.getMessage());
            }
        }

        log.info("[PIPELINE-BUNDLE] imported '{}' as '{}' ({} file(s))", sourceId, newId, written.size());
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("written", true);
        r.put("pipeline", registeredName);
        r.put("path", writeRoot.relativize(target).toString().replace('\\', '/'));
        r.put("files", written);
        r.put("active", false);
        if (renamedFrom != null) r.put("renamedFrom", renamedFrom);
        Object requirements = manifest.get("requirements");
        if (requirements instanceof List<?> l && !l.isEmpty()) r.put("requirements", requirements);
        if (!notes.isEmpty()) r.put("notes", notes);
        r.put("findings", findings);
        return r;
    }

    // ── retargeting (identity travels INSIDE each body) ───────────────────────────

    /**
     * The imported pipeline map: identity, stream, collector id and every {@code dirs.*} path
     * re-derived from the target space's convention ({@code <prefix>data/inbox/<id>} /
     * {@code <prefix>data/<id>/<leaf>} — the layout the sample spaces and the create surface write),
     * satellite refs rewritten to the bare basenames landing beside the file, and ALWAYS
     * {@code active: false} — importing as live would start processing on someone else's server.
     */
    private static Map<String, Object> retargetPipeline(Map<String, Object> src, String oldId, String newId,
                                                        String prefix, Set<String> satelliteNames) {
        Map<String, Object> t = new LinkedHashMap<>(src);
        t.put("name", newId);
        t.put("id", newId);            // explicit identity — never re-derived from a changed name
        t.put("active", false);
        if (t.containsKey("stream")) t.put("stream", newId);   // never inherit the source's Stream

        String home = prefix + "data/" + newId;
        Map<String, Object> dirs = new LinkedHashMap<>();
        if (src.get("dirs") instanceof Map<?, ?> sd) {
            Map<String, String> leaf = Map.of("status_dir", "status", "log_dir", "logs");
            for (Map.Entry<?, ?> en : sd.entrySet()) {
                String k = String.valueOf(en.getKey());
                dirs.put(k, switch (k) {
                    case "poll" -> prefix + "data/inbox/" + newId;
                    case "status_file" -> home + "/status/" + newId + "_status.csv";
                    default -> home + "/" + leaf.getOrDefault(k, k);
                });
            }
        }
        dirs.putIfAbsent("poll", prefix + "data/inbox/" + newId);   // spec-required
        dirs.putIfAbsent("database", home + "/database");           // spec-required
        t.put("dirs", dirs);

        // The collector's id is the acquisition ledger's source_id; `source:` is the legacy spelling.
        String colKey = src.containsKey("collector") || !src.containsKey("source") ? "collector" : "source";
        if (src.get(colKey) instanceof Map<?, ?>) {
            Map<String, Object> col = new LinkedHashMap<>(mapAt(src, colKey));
            if (col.containsKey("id")) col.put("id", newId);
            t.put(colKey, col);
        }

        if (src.get("output") instanceof Map<?, ?>) {
            Map<String, Object> out = new LinkedHashMap<>(mapAt(src, "output"));
            if (out.get("ducklake") instanceof Map<?, ?>) {
                Map<String, Object> lake = new LinkedHashMap<>(mapAt(out, "ducklake"));
                if (lake.containsKey("data_path")) lake.put("data_path", home + "/ducklake");
                out.put("ducklake", lake);
            }
            t.put("output", out);
        }

        // A trigger naming the pipeline ITSELF follows the rename; one naming an upstream pipeline
        // is a target-environment concern and travels verbatim.
        if (t.get("triggers") instanceof Map<?, ?> trig
                && oldId.equalsIgnoreCase(String.valueOf(trig.get("on_pipeline")))) {
            Map<String, Object> tr = new LinkedHashMap<>(mapAt(t, "triggers"));
            tr.put("on_pipeline", newId);
            t.put("triggers", tr);
        }

        rewriteSatelliteRefs(t, satelliteNames);
        return t;
    }

    /** Rewrite every satellite-bearing ref to the bare basename now sitting beside the pipeline file
     *  (the W3 portable form) — only when that basename is actually in the bundle. */
    @SuppressWarnings("unchecked")
    private static void rewriteSatelliteRefs(Map<String, Object> pipeline, Set<String> names) {
        if (pipeline.get("processing") instanceof Map<?, ?>) {
            Map<String, Object> proc = new LinkedHashMap<>(mapAt(pipeline, "processing"));
            for (String k : List.of("schema_file", "grammar", "mapping_file")) rewriteRef(proc, k, names);
            if (proc.get("schemas") instanceof List<?> list) proc.put("schemas", rewriteSchemaList(list, names));
            if (proc.get("segments") instanceof Map<?, ?> seg) proc.put("segments", rewriteRefMap(seg, names));
            pipeline.put("processing", proc);
        }
        if (pipeline.get("schemas") instanceof List<?> list)
            pipeline.put("schemas", rewriteSchemaList(list, names));
        if (pipeline.get("parsing") instanceof Map<?, ?>) {
            Map<String, Object> parsing = new LinkedHashMap<>(mapAt(pipeline, "parsing"));
            rewriteRef(parsing, "grammar", names);
            for (String frontend : List.of("asn1", "plugin")) {
                if (parsing.get(frontend) instanceof Map<?, ?>) {
                    Map<String, Object> f = new LinkedHashMap<>(mapAt(parsing, frontend));
                    if (f.get("segments") instanceof Map<?, ?> seg) f.put("segments", rewriteRefMap(seg, names));
                    parsing.put(frontend, f);
                }
            }
            pipeline.put("parsing", parsing);
        }
    }

    private static List<Object> rewriteSchemaList(List<?> list, Set<String> names) {
        List<Object> out = new ArrayList<>();
        for (Object o : list) {
            if (o instanceof Map<?, ?> m) {
                Map<String, Object> row = new LinkedHashMap<>();
                m.forEach((k, v) -> row.put(String.valueOf(k), v));
                rewriteRef(row, "schema_file", names);
                out.add(row);
            } else {
                out.add(o);
            }
        }
        return out;
    }

    private static Map<String, Object> rewriteRefMap(Map<?, ?> seg, Set<String> names) {
        Map<String, Object> out = new LinkedHashMap<>();
        seg.forEach((k, v) -> out.put(String.valueOf(k),
                v instanceof String s ? rewritten(s, names) : v));
        return out;
    }

    private static void rewriteRef(Map<String, Object> map, String key, Set<String> names) {
        if (map.get(key) instanceof String s && !s.isBlank()) map.put(key, rewritten(s, names));
    }

    /** The bare basename when the ref's leaf is a bundled satellite; the authored value otherwise.
     *  A {@code schema/<id>} / {@code grammar/<id>} registry ref leafs to {@code <id>.toon} — the
     *  file its resolution lands on ({@code registry/<dir>/<id>.toon}). */
    private static String rewritten(String ref, Set<String> names) {
        String leaf;
        if (ref.startsWith("schema/") || ref.startsWith("grammar/"))
            leaf = ref.substring(ref.indexOf('/') + 1) + ".toon";
        else {
            int cut = Math.max(ref.lastIndexOf('/'), ref.lastIndexOf('\\'));
            leaf = cut < 0 ? ref : ref.substring(cut + 1);
        }
        return names.contains(leaf) ? leaf : ref;
    }

    /**
     * Retarget a companion enrichment in place — on exactly the leaves the client bundle learned
     * (the hard way) it must rewrite: {@code name} (a config's own identity field decides its file),
     * {@code triggers.on_pipeline}, {@code input.database} (the imported pipeline's own database) and
     * {@code output.database} (source space prefix and enrichment name re-pointed, the author's
     * intermediate layout preserved). Returns the new enrichment name.
     */
    private static String retargetEnrichment(Map<String, Object> enrich, String oldId, String newId,
                                             String sourcePrefix, String targetPrefix,
                                             String sourceDb, String targetDb) {
        String oldName = String.valueOf(enrich.getOrDefault("name", oldId + "_enrich"));
        String swapped = Pattern.compile(Pattern.quote(oldId), Pattern.CASE_INSENSITIVE)
                .matcher(oldName).replaceAll(java.util.regex.Matcher.quoteReplacement(newId));
        String newName = swapped.equals(oldName) ? newId + "_" + oldName : swapped;
        enrich.put("name", newName);

        if (enrich.get("triggers") instanceof Map<?, ?>) {
            Map<String, Object> tr = new LinkedHashMap<>(mapAt(enrich, "triggers"));
            tr.put("on_pipeline", newId);
            enrich.put("triggers", tr);
        }
        if (enrich.get("input") instanceof Map<?, ?>) {
            Map<String, Object> in = new LinkedHashMap<>(mapAt(enrich, "input"));
            Object db = in.get("database");
            if (db != null && targetDb != null
                    && (sourceDb == null || String.valueOf(db).equals(sourceDb) || sourceDb.isBlank()))
                in.put("database", targetDb);
            else if (db != null) in.put("database", repointPath(String.valueOf(db),
                    sourcePrefix, targetPrefix, oldName, newName, oldId, newId));
            enrich.put("input", in);
        }
        if (enrich.get("output") instanceof Map<?, ?>) {
            Map<String, Object> out = new LinkedHashMap<>(mapAt(enrich, "output"));
            if (out.get("database") != null)
                out.put("database", repointPath(String.valueOf(out.get("database")),
                        sourcePrefix, targetPrefix, oldName, newName, oldId, newId));
            enrich.put("output", out);
        }
        return newName;
    }

    /** Re-point a data path: source space prefix → target's, and the old identity leaves (enrichment
     *  name / pipeline id) → the new ones. Intermediate layout stays the author's. */
    private static String repointPath(String path, String sourcePrefix, String targetPrefix,
                                      String oldName, String newName, String oldId, String newId) {
        String p = path;
        if (sourcePrefix != null && !sourcePrefix.isEmpty() && p.startsWith(sourcePrefix))
            p = targetPrefix + p.substring(sourcePrefix.length());
        p = Pattern.compile(Pattern.quote(oldName), Pattern.CASE_INSENSITIVE)
                .matcher(p).replaceAll(java.util.regex.Matcher.quoteReplacement(newName.toLowerCase()));
        p = Pattern.compile(Pattern.quote(oldId), Pattern.CASE_INSENSITIVE)
                .matcher(p).replaceAll(java.util.regex.Matcher.quoteReplacement(newId));
        return p;
    }

    // ── helpers ────────────────────────────────────────────────────────────────────

    /** Is {@code id} taken — registered live, or already a file at either canonical location? */
    private static boolean taken(ApiContext api, Path writeRoot, String id) {
        return api.service().pathFor(id).isPresent()
                || Files.exists(writeRoot.resolve(id).resolve(id + "_pipeline.toon"))
                || Files.exists(writeRoot.resolve(id + "_pipeline.toon"));
    }

    /** First free {@code <base>_2}, {@code <base>_3}, … for {@code conflict=rename}. */
    private static String freeName(ApiContext api, Path writeRoot, String base) {
        for (int i = 2; i < 100; i++) {
            String candidate = base + "_" + i;
            if (!taken(api, writeRoot, candidate)) return candidate;
        }
        throw new ApiException(409, "no free name near '" + base + "'");
    }

    /** The {@code *_enrich.toon} companions of one pipeline at the write root, decoded (read-only). */
    private static Map<Path, Map<String, Object>> companionsOf(ApiContext api, String pipeline) {
        Map<Path, Map<String, Object>> out = new LinkedHashMap<>();
        Path root = api.writeRoot();
        if (root == null || !Files.isDirectory(root)) return out;
        try (var files = Files.list(root)) {
            for (Path p : files.filter(f -> f.getFileName().toString().endsWith("_enrich.toon")).toList()) {
                try {
                    Map<String, Object> enrich = ConfigLoader.filesystem().decode(p.toString());
                    if (enrich.get("triggers") instanceof Map<?, ?> t
                            && pipeline.equalsIgnoreCase(String.valueOf(t.get("on_pipeline"))))
                        out.put(p, enrich);
                } catch (RuntimeException | IOException unreadable) {
                    // an unreadable companion never breaks the pipeline's own export
                }
            }
        } catch (IOException ignored) {
            // listing failed — export the pipeline without companions
        }
        return out;
    }

    /**
     * Mask literal secret-looking values in place (mirrors the client bundle: a config should only
     * hold {@code ${ENV:…}} references; a literal is an authoring mistake, not something to ship).
     * Returns whether anything changed; each mask is reported in {@code notes}.
     */
    @SuppressWarnings("unchecked")
    private static boolean maskSecrets(Map<String, Object> map, String path, List<String> notes) {
        boolean changed = false;
        for (Map.Entry<String, Object> en : map.entrySet()) {
            String at = path.isEmpty() ? en.getKey() : path + "." + en.getKey();
            Object v = en.getValue();
            if (v instanceof Map<?, ?> m) {
                changed |= maskSecrets((Map<String, Object>) m, at, notes);
            } else if (v instanceof String s && SECRET_KEY.matcher(en.getKey()).matches()
                    && !s.isBlank() && !s.startsWith("${")) {
                en.setValue("***");
                notes.add("masked a literal secret-looking value at '" + at
                        + "' — use a ${ENV:…} reference instead");
                changed = true;
            }
        }
        return changed;
    }

    /** A pipeline's bound connection id ({@code collector.connection}; {@code source:} legacy), or null. */
    private static String connectionRef(Map<String, Object> pipeline) {
        for (String key : List.of("collector", "source")) {
            if (pipeline.get(key) instanceof Map<?, ?> block && block.get("connection") != null) {
                String s = String.valueOf(block.get("connection")).trim();
                if (!s.isEmpty()) return s;
            }
        }
        return null;
    }

    /**
     * The space's own path prefix as a config spells it ({@code spaces/<id>/}, or absolute when the
     * space lives outside the working directory; empty for a single-tenant root) — the write root's
     * parent relative to the working directory, the same derivation {@code BundleImporter.targetPrefix}
     * uses on the whole-tree zip path.
     */
    private static String spacePrefix(Path configRoot) {
        if (configRoot == null) return "";
        Path base = configRoot.toAbsolutePath().normalize().getParent();
        if (base == null) return "";
        Path cwd = Path.of("").toAbsolutePath().normalize();
        String s = (base.startsWith(cwd) ? cwd.relativize(base) : base).toString().replace('\\', '/');
        return s.isEmpty() ? "" : s + "/";
    }

    private static byte[] zip(Map<String, Object> manifest, Map<String, byte[]> entries) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(bos)) {
            put(zos, MANIFEST, ConfigCodec.toToon(manifest).getBytes(StandardCharsets.UTF_8));
            for (Map.Entry<String, byte[]> e : entries.entrySet()) put(zos, e.getKey(), e.getValue());
        }
        return bos.toByteArray();
    }

    private static void put(ZipOutputStream zos, String name, byte[] bytes) throws IOException {
        zos.putNextEntry(new ZipEntry(name));
        zos.write(bytes);
        zos.closeEntry();
    }

    /** Parse the zip → entries; unreadable zip → 422 (the manifest/spec gate, not a malformed-JSON 400). */
    private static LinkedHashMap<String, byte[]> unzip(byte[] zip) {
        LinkedHashMap<String, byte[]> all = new LinkedHashMap<>();
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(zip))) {
            for (var en = zis.getNextEntry(); en != null; en = zis.getNextEntry())
                if (!en.isDirectory()) all.put(en.getName(), zis.readAllBytes());
        } catch (IOException bad) {
            throw new ApiException(422, "body is not a readable zip: " + bad.getMessage());
        }
        if (all.isEmpty()) throw new ApiException(422, "body is not a pipeline bundle (empty zip)");
        return all;
    }

    /** Validate + decode {@code manifest.toon} → 422 on anything not a v1 pipeline bundle. */
    private static Map<String, Object> manifestOf(LinkedHashMap<String, byte[]> entries) {
        byte[] mf = entries.remove(MANIFEST);
        if (mf == null) throw new ApiException(422, "not a pipeline bundle: missing " + MANIFEST);
        Map<String, Object> manifest;
        try {
            manifest = ConfigCodec.toMap(new String(mf, StandardCharsets.UTF_8));
        } catch (RuntimeException bad) {
            throw new ApiException(422, "invalid " + MANIFEST + ": " + bad.getMessage());
        }
        if (!FORMAT.equals(ApiContext.str(manifest, "format")))
            throw new ApiException(422, "not a pipeline bundle (format must be '" + FORMAT + "')");
        Object v = manifest.get("version");
        if (!(v instanceof Number n) || n.intValue() != VERSION)
            throw new ApiException(422, "unsupported bundle version (expected " + VERSION + ")");
        return manifest;
    }

    private static String sha256(byte[] bytes) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder sb = new StringBuilder("sha256:");
            for (byte b : d) sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);   // SHA-256 is mandatory in every JRE
        }
    }

    /** Write {@code zip} as an {@code application/zip} attachment download (DataSourceRoutes idiom). */
    private static Object download(HttpExchange e, byte[] zip, String filename) throws IOException {
        e.getResponseHeaders().set("Content-Type", "application/zip");
        e.getResponseHeaders().set("Content-Disposition", "attachment; filename=\"" + filename + "\"");
        e.sendResponseHeaders(200, zip.length);
        e.getResponseBody().write(zip);
        return ApiContext.HANDLED;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> asMapList(Object o) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (o instanceof List<?> list)
            for (Object e : list) if (e instanceof Map<?, ?> m) out.add((Map<String, Object>) m);
        return out;
    }

    private static List<String> asStringList(Object o) {
        List<String> out = new ArrayList<>();
        if (o instanceof List<?> list) for (Object e : list) if (e != null) out.add(String.valueOf(e));
        return out;
    }
}
