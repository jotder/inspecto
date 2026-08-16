package com.gamma.control;

import com.gamma.config.io.ConfigCodec;
import com.gamma.config.io.ConfigLoader;
import com.gamma.config.safety.ConfigSafetyValidator;
import com.gamma.config.safety.SafetyPolicy;
import com.gamma.config.safety.SchemaCompatibility;
import com.gamma.config.spec.ConfigSpec;
import com.gamma.config.spec.ConfigSpecs;
import com.gamma.config.spec.Finding;
import com.gamma.config.spec.Severity;
import com.gamma.etl.ConfigValidator;
import com.gamma.etl.PipelineConfig;
import com.gamma.pipeline.exec.ComponentPreview;
import com.gamma.service.PipelineDependents;
import com.gamma.util.AtomicFiles;
import com.gamma.util.MappingCsv;
import com.sun.net.httpserver.HttpExchange;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Declarative-config routes ({@code /config/spec}, {@code /validate}, {@code /config/write},
 * {@code DELETE /config/&#123;type&#125;/&#123;name&#125;}; v3.2.0/v4.1.0/v5.1.0): describe a config
 * type's spec, validate a saved file or an unsaved draft, persist a validated draft (write-root
 * jailed, atomic), and discard one (draft delete; never an active pipeline). Extracted verbatim from
 * {@link ControlApi}: identical routes, statuses, gating order and on-disk behaviour.
 *
 * <p>{@link #schemaFileFindings} is shared with the pipeline-registration route that stays on
 * {@link ControlApi}; it lives here with the rest of the config-validation logic.
 */
final class ConfigRoutes implements RouteModule {

    private static final Logger log = LoggerFactory.getLogger(ConfigRoutes.class);

    @Override
    public void register(ApiContext api) {
        api.get("/config/spec/(.+)", (e, m) -> {
            ConfigSpec spec = ConfigSpecs.forType(ApiContext.name(m));
            if (spec == null) throw new ApiException(404, "unknown config type: " + ApiContext.name(m));
            return spec;
        });
        api.post("/validate", (e, m) -> validate(api.body(e)));
        // Parse a raw sample with a draft's parsing: settings — stateless, scratch-only (stream
        // onboarding's sample-as-thread; the raw→parsed hop).
        api.post("/config/preview/parsing", (e, m) -> previewParsing(api.body(e)));
        // TRY_CAST a draft schema's typed fields against already-parsed sample rows — stateless,
        // scratch-only (stream onboarding's Schema & Mapping stage; the parsed→typed hop).
        api.post("/config/preview/schema", (e, m) -> previewSchema(api.body(e)));
        api.post("/config/suggest/schema", (e, m) -> suggestSchema(api.body(e)));
        // Requires canAuthorWorkbench (W6; a no-op on Personal — no Subject is ever attached there).
        api.post("/config/write", ApiContext.withCapability("canAuthorWorkbench", (e, m) -> writeConfig(api, e, api.body(e))));
        // Block-level save (collector-config unification, 2026-08-04): deep-merge a patch over the
        // file's CURRENT content server-side, so a stage pane can never clobber blocks it didn't
        // edit with a stale client-held copy. Same gates and response shape as /config/write.
        api.post("/config/patch", ApiContext.withCapability("canAuthorWorkbench", (e, m) -> patchConfig(api, e, api.body(e))));
        // Draft discard (stream onboarding): delete a config file under the write root — never an
        // active pipeline. Optional ?subdir= mirrors /config/write's subdir.
        api.delete("/config/([^/]+)/([^/]+)", ApiContext.withCapability("canAuthorWorkbench",
                (e, m) -> deleteConfig(api, e, ApiContext.name(m), ApiContext.param(m, 2))));
        // Pre-delete impact read (Catalog lifecycle): what references this pipeline. Read-only and
        // ungated like the other reads; three path segments, so it cannot collide with the two-segment
        // read-back below (route patterns are anchored).
        api.get("/config/pipeline/([^/]+)/impact",
                (e, m) -> pipelineImpact(api, e, ApiContext.name(m)));
        // Draft read-back (stream onboarding resume): return a config file's decoded content.
        // Registered after /config/spec/…, which therefore keeps serving type="spec" lookups.
        api.get("/config/([^/]+)/([^/]+)",
                (e, m) -> readConfig(api, e, ApiContext.name(m), ApiContext.param(m, 2)));
    }

    private Object validate(Map<String, Object> body) throws IOException {
        String configPath = ApiContext.str(body, "configPath");
        if (configPath != null) {
            PipelineConfig cfg = PipelineConfig.load(configPath);
            List<String> warnings = ConfigValidator.validate(cfg);
            List<Finding> findings = ConfigLoader.filesystem()
                    .validate(ConfigSpecs.pipeline(), ConfigLoader.filesystem().decode(configPath));
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("pipeline", cfg.identity().pipelineName());
            r.put("warnings", warnings);     // legacy string form (back-compat)
            r.put("findings", findings);     // structured form (v3.2.0)
            r.put("clean", warnings.isEmpty());
            return r;
        }
        String type = ApiContext.str(body, "type");
        Object cfgObj = body.get("config");
        if (type == null || !(cfgObj instanceof Map<?, ?>)) {
            throw new ApiException(400,
                    "body must include 'configPath', or 'type' + 'config' (a draft config map)");
        }
        ConfigSpec spec = ConfigSpecs.forType(type);
        if (spec == null) throw new ApiException(404, "unknown config type: " + type);
        @SuppressWarnings("unchecked")
        Map<String, Object> draft = (Map<String, Object>) cfgObj;
        List<Finding> findings = new ArrayList<>(ConfigLoader.filesystem().validate(spec, draft));
        // Pre-flight: warn when a pipeline draft's schema_file won't resolve on this server —
        // registration would otherwise fail later with an opaque error (v4.1.0).
        findings.addAll(schemaFileFindings(type, draft, Severity.WARNING));
        // Opt-in hard-fail safety gate (R6): merged in only when the caller asks, so the default
        // /validate response is byte-for-byte unchanged for existing callers.
        boolean safety = "true".equalsIgnoreCase(String.valueOf(body.get("safety")));
        if (safety) {
            findings.addAll(ConfigSafetyValidator.check(type, draft, SafetyPolicy.defaultPolicy()));
        }
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("type", type);
        r.put("findings", findings);
        r.put("safetyChecked", safety);
        r.put("clean", findings.isEmpty());
        return r;
    }

    private Object writeConfig(ApiContext api, HttpExchange ex, Map<String, Object> body) throws IOException {
        Path writeRoot = WriteGates.requireWriteRoot(api, "config write");

        String type = ApiContext.str(body, "type");
        Object cfgObj = body.get("config");
        if (type == null || !(cfgObj instanceof Map<?, ?>))
            throw new ApiException(400, "body must include 'type' and 'config' (a draft config map)");
        ConfigSpec spec = ConfigSpecs.forType(type);
        if (spec == null) throw new ApiException(404, "unknown config type: " + type);
        @SuppressWarnings("unchecked")
        Map<String, Object> draft = (Map<String, Object>) cfgObj;

        // Gate: spec validation + the hard-fail safety check (R6). Block on ERRORs; warnings pass.
        List<Finding> findings = new ArrayList<>(ConfigLoader.filesystem().validate(spec, draft));
        findings.addAll(ConfigSafetyValidator.check(type, draft, SafetyPolicy.defaultPolicy()));
        // Warning only: the save still succeeds (the schema file may be created afterwards), but
        // the operator learns now that Register would fail on this host.
        findings.addAll(schemaFileFindings(type, draft, Severity.WARNING));
        // ERROR: an armed pipeline with no schema source parses nowhere. Without this the write
        // returns written:true and the config is then silently dropped from the index forever.
        findings.addAll(armedWithoutSchemaFindings(type, draft));
        // ERROR: a collector bound to a connection this space does not have cannot acquire anything —
        // it throws once per poll cycle instead. Bundle import already refuses it; a save now agrees.
        findings.addAll(unknownConnectionFindings(type, draft, api));
        if (findings.stream().anyMatch(f -> f.severity() == Severity.ERROR)) {
            return ApiContext.respondJson(ex, 422, Map.of("type", type, "written", false,
                    "error", "config has ERROR-level findings; not written", "findings", findings));
        }

        // Filename from the config's own identity field — no caller-controlled path component.
        String idField = identityField(type);
        String rawName = dottedString(draft, idField);
        if (rawName == null || rawName.isBlank())
            throw new ApiException(422, "config is missing its identity field '" + idField + "'");
        String safeIdentity = WriteGates.safeName(rawName, "config name");
        String fileName = fileBase(type, safeIdentity);

        // Resolve under the write root; an optional subdir must stay inside it (path jail).
        Path dir = writeRoot;
        String subdir = ApiContext.str(body, "subdir");
        if (subdir != null && !subdir.isBlank()) {
            Path sub = Path.of(subdir.trim());
            if (sub.isAbsolute()) throw new ApiException(400, "subdir must be relative");
            dir = WriteGates.jail(writeRoot, writeRoot.resolve(sub), "subdir");
        }
        Path target = WriteGates.jail(writeRoot, dir.resolve(fileName + ".toon"), "resolved path");

        boolean exists = Files.exists(target);
        boolean overwrite = "true".equalsIgnoreCase(String.valueOf(body.get("overwrite")));
        WriteGates.conflictIf(exists && !overwrite,
                "file exists: " + writeRoot.relativize(target).toString().replace('\\', '/')
                        + " (pass overwrite:true to replace)");

        // Schema compatibility save-gate (ELT amendment §3.4.2, D-10): a schema OVERWRITE is diffed
        // old→new under the BACKWARD class; breaking edits (remove/narrow/selector-move) 422 with
        // cell-level findings. Escape hatches: copy to a new name, or the explicit override below.
        if ("schema".equals(type) && exists && !compatibilityOverridden(body)) {
            Map<String, Object> current = ConfigLoader.filesystem().decode(target.toString());
            List<Finding> breaking = SchemaCompatibility.check(current, draft);
            if (!breaking.isEmpty()) {
                findings.addAll(breaking);
                return ApiContext.respondJson(ex, 422, Map.of("type", type, "written", false,
                        "error", "schema edit is not BACKWARD-compatible; not written", "findings", findings));
            }
        }

        // Encode and write atomically: a partial/concurrent reader never sees a half-written file.
        Map<String, Object> toWrite = draft;
        String mappingRel = null;
        if ("schema".equals(type)) {
            SchemaSplit split = splitMapping(writeRoot, target, draft);
            toWrite = split.structure();
            mappingRel = split.mappingRel();
        }
        byte[] bytes = ConfigCodec.toToon(toWrite).getBytes(StandardCharsets.UTF_8);
        AtomicFiles.write(target, bytes, ".cfg-");
        String rel = writeRoot.relativize(target).toString().replace('\\', '/');
        log.info("[CONFIG-WRITE] type={} wrote {} ({} bytes, overwrote={})", type, rel, bytes.length, exists);

        Map<String, Object> r = new LinkedHashMap<>();
        r.put("type", type);
        r.put("written", true);
        r.put("path", rel);
        if (mappingRel != null) r.put("mappingPath", mappingRel);
        r.put("name", safeIdentity);
        r.put("bytes", bytes.length);
        r.put("overwritten", exists);
        r.put("findings", findings);   // warnings only at this point (errors would have 422'd)
        return r;
    }

    /** The explicit D-10 escape hatch: {@code compatibility: "none"} in the request body. */
    private static boolean compatibilityOverridden(Map<String, Object> body) {
        return "none".equalsIgnoreCase(String.valueOf(body.get("compatibility")));
    }

    /** A schema draft split for persistence: the structure map (no {@code mapping.rules}) + the CSV's rel path. */
    private record SchemaSplit(Map<String, Object> structure, String mappingRel) {}

    /**
     * Split-write for the Schema/Mapping separation (ELT amendment Phase 1 slice 2): a schema draft's
     * {@code mapping.rules} are persisted as the sibling {@code <name>_mapping.csv} (the shape the
     * engine's slice-1 dual-read consumes) and stripped from the TOON. A draft without rules writes
     * the TOON unchanged — an existing sibling CSV then remains the mapping source of truth.
     */
    @SuppressWarnings("unchecked")
    private static SchemaSplit splitMapping(Path writeRoot, Path target, Map<String, Object> draft)
            throws IOException {
        Object mappingObj = draft.get("mapping");
        if (!(mappingObj instanceof Map<?, ?> mapping)) return new SchemaSplit(draft, null);
        Object rulesObj = mapping.get("rules");
        if (!(rulesObj instanceof List<?> rules) || rules.isEmpty()) return new SchemaSplit(draft, null);

        Path csv = MappingCsv.siblingFor(target);
        String text = MappingCsv.encode((List<? extends Map<String, ?>>) rules);
        AtomicFiles.write(csv, text.getBytes(StandardCharsets.UTF_8), ".map-");

        Map<String, Object> structure = new LinkedHashMap<>(draft);
        Map<String, Object> mappingRest = new LinkedHashMap<>((Map<String, Object>) mapping);
        mappingRest.remove("rules");
        if (mappingRest.isEmpty()) structure.remove("mapping");
        else structure.put("mapping", mappingRest);
        return new SchemaSplit(structure, writeRoot.relativize(csv).toString().replace('\\', '/'));
    }

    /**
     * The read-side of the split: merge a schema file's sibling {@code _mapping.csv} (if any) into
     * its decoded map, so clients always see the conflated shape they authored — the same dual-read
     * the engine's {@code PipelineConfigParser} performs.
     */
    private static void mergeSiblingMapping(Path schemaFile, Map<String, Object> config) throws IOException {
        Path csv = MappingCsv.siblingFor(schemaFile);
        if (!Files.exists(csv)) return;
        List<Map<String, String>> rules =
                MappingCsv.parse(Files.readString(csv, StandardCharsets.UTF_8), csv.toString());
        @SuppressWarnings("unchecked")
        Map<String, Object> mapping = config.get("mapping") instanceof Map<?, ?> m
                ? (Map<String, Object>) m : null;
        if (mapping == null) {
            mapping = new LinkedHashMap<>();
            config.put("mapping", mapping);
        }
        mapping.put("rules", rules);
    }

    /**
     * {@code POST /config/patch} — deep-merge a partial draft over a config file's <em>current</em>
     * on-disk content and rewrite it atomically (collector-config unification, 2026-08-04). The
     * merge happens server-side, against the file as it is NOW — not against whatever the client
     * last read — which is what kills the stale-read clobber {@code /config/write overwrite:true}
     * invites when two surfaces author the same file (an onboarding stage pane vs. the pipeline
     * editor's graph save).
     *
     * <p>Body {@code {type, name, patch, subdir?}}. Merge semantics: maps merge recursively,
     * scalars/lists replace, an explicit JSON {@code null} deletes its key. The whole merged draft
     * is validated through the same gates as {@code /config/write} and the response has the same
     * shape, so callers route findings identically.
     *
     * <p>Fail-closed gate order: write-root 503 → unknown type 404 → bad body 400 → unsafe name
     * 422 → subdir jail 403 → missing target 404 (patch needs an existing file — create via
     * {@code /config/write}) → identity change 409 → merged-draft ERROR findings 422
     * ({@code written:false}) → atomic write.
     */
    private Object patchConfig(ApiContext api, HttpExchange ex, Map<String, Object> body) throws IOException {
        Path writeRoot = WriteGates.requireWriteRoot(api, "config patch");

        String type = ApiContext.str(body, "type");
        String name = ApiContext.str(body, "name");
        Object patchObj = body.get("patch");
        if (type == null || name == null || name.isBlank() || !(patchObj instanceof Map<?, ?>))
            throw new ApiException(400, "body must include 'type', 'name' and 'patch' (a partial config map)");
        ConfigSpec spec = ConfigSpecs.forType(type);
        if (spec == null) throw new ApiException(404, "unknown config type: " + type);
        @SuppressWarnings("unchecked")
        Map<String, Object> patch = (Map<String, Object>) patchObj;
        String fileName = WriteGates.safeName(name, "config name");

        Path dir = writeRoot;
        String subdir = ApiContext.str(body, "subdir");
        if (subdir != null && !subdir.isBlank()) {
            Path sub = Path.of(subdir.trim());
            if (sub.isAbsolute()) throw new ApiException(400, "subdir must be relative");
            dir = WriteGates.jail(writeRoot, writeRoot.resolve(sub), "subdir");
        }
        Path target = resolveConfigFile(writeRoot, dir, type, fileName);
        String rel = writeRoot.relativize(target).toString().replace('\\', '/');
        if (!Files.isRegularFile(target))
            throw new ApiException(404, "no such config: " + rel + " (create it via /config/write first)");

        Map<String, Object> existing = ConfigLoader.filesystem().decode(target.toString());
        // Split storage (schema): patch over the CONFLATED view, so a partial draft can address
        // mapping.rules whether they live inline or in the sibling CSV.
        if ("schema".equals(type)) mergeSiblingMapping(target, existing);
        Map<String, Object> merged = deepMerge(existing, patch);

        // The filename derives from the identity field, so a patch may not move it — a renamed
        // identity under the old filename would silently split the config from its index entry.
        String idField = identityField(type);
        String before = dottedString(existing, idField);
        String after = dottedString(merged, idField);
        WriteGates.conflictIf(before != null && !before.equals(after),
                "patch changes the identity field '" + idField + "' (" + before + " → " + after
                        + "); rename via /config/write");

        // Same gate as /config/write, over the WHOLE merged draft: spec + hard-fail safety check;
        // schema references resolve config-relative here because the file has a home directory.
        List<Finding> findings = new ArrayList<>(ConfigLoader.filesystem().validate(spec, merged));
        findings.addAll(ConfigSafetyValidator.check(type, merged, SafetyPolicy.defaultPolicy()));
        findings.addAll(schemaFileFindings(type, merged, Severity.WARNING, target.getParent()));
        findings.addAll(armedWithoutSchemaFindings(type, merged));
        findings.addAll(unknownConnectionFindings(type, merged, api));   // a patch can introduce one too
        if (findings.stream().anyMatch(f -> f.severity() == Severity.ERROR)) {
            return ApiContext.respondJson(ex, 422, Map.of("type", type, "written", false,
                    "error", "merged config has ERROR-level findings; not written", "findings", findings));
        }

        // Same BACKWARD save-gate as /config/write — a patch is an edit of an existing schema.
        if ("schema".equals(type) && !compatibilityOverridden(body)) {
            List<Finding> breaking = SchemaCompatibility.check(existing, merged);
            if (!breaking.isEmpty()) {
                findings.addAll(breaking);
                return ApiContext.respondJson(ex, 422, Map.of("type", type, "written", false,
                        "error", "schema edit is not BACKWARD-compatible; not written", "findings", findings));
            }
        }

        Map<String, Object> toWrite = merged;
        String mappingRel = null;
        if ("schema".equals(type)) {
            SchemaSplit split = splitMapping(writeRoot, target, merged);
            toWrite = split.structure();
            mappingRel = split.mappingRel();
        }
        byte[] bytes = ConfigCodec.toToon(toWrite).getBytes(StandardCharsets.UTF_8);
        AtomicFiles.write(target, bytes, ".cfg-");
        log.info("[CONFIG-PATCH] type={} patched {} ({} bytes)", type, rel, bytes.length);

        Map<String, Object> r = new LinkedHashMap<>();
        r.put("type", type);
        r.put("written", true);
        r.put("path", rel);
        if (mappingRel != null) r.put("mappingPath", mappingRel);
        r.put("name", fileName);
        r.put("bytes", bytes.length);
        r.put("overwritten", true);
        r.put("findings", findings);   // warnings only at this point (errors would have 422'd)
        return r;
    }

    /**
     * Recursive merge for {@link #patchConfig}: maps merge key-by-key, anything else replaces, an
     * explicit {@code null} patch value deletes its key. Copies — never mutates either argument.
     */
    private static Map<String, Object> deepMerge(Map<String, Object> base, Map<String, Object> patch) {
        Map<String, Object> out = new LinkedHashMap<>(base);
        for (Map.Entry<String, Object> e : patch.entrySet()) {
            Object pv = e.getValue();
            if (pv == null) {
                out.remove(e.getKey());
            } else if (pv instanceof Map<?, ?> pm && out.get(e.getKey()) instanceof Map<?, ?> bm) {
                @SuppressWarnings("unchecked")
                Map<String, Object> basePart = (Map<String, Object>) bm;
                @SuppressWarnings("unchecked")
                Map<String, Object> patchPart = (Map<String, Object>) pm;
                out.put(e.getKey(), deepMerge(basePart, patchPart));
            } else {
                out.put(e.getKey(), pv);
            }
        }
        return out;
    }

    /**
     * {@code DELETE /config/{type}/{name}} — discard a config file under the write root (the
     * onboarding draft-discard path, v5.1.0). Fail-closed gate order: write-root 503 → unknown type
     * 404 → unsafe name 422 → path jail 403 → missing file 404 → active pipeline 409 →
     * <b>dependents 409</b> → single atomic delete. An {@code active: true} pipeline is never
     * deleted — deactivate it first.
     *
     * <p><b>Dependents gate (Catalog lifecycle).</b> Deleting a pipeline that something still
     * references used to succeed silently and leave enrichment bindings, dataset {@code physicalRef}s,
     * widgets and dashboard tiles dangling — detected only later, by the read-only
     * {@code metadata_validate} task. It now 409s with the dependent list, mirroring
     * {@code ComponentRoutes.deleteComponent}. {@code ?force=true} deletes anyway: the dependents may
     * legitimately be the stale half, and refusing with no escape would leave an operator editing
     * every dependent first. The forced path is logged with the count it overrode.
     */
    private Object deleteConfig(ApiContext api, HttpExchange ex, String type, String name) throws IOException {
        Path writeRoot = WriteGates.requireWriteRoot(api, "config delete");
        if (ConfigSpecs.forType(type) == null) throw new ApiException(404, "unknown config type: " + type);
        String fileName = WriteGates.safeName(name, "config name");

        Path dir = writeRoot;
        String subdir = ApiContext.query(ex, "subdir");
        if (subdir != null && !subdir.isBlank()) {
            Path sub = Path.of(subdir.trim());
            if (sub.isAbsolute()) throw new ApiException(400, "subdir must be relative");
            dir = WriteGates.jail(writeRoot, writeRoot.resolve(sub), "subdir");
        }
        Path target = resolveConfigFile(writeRoot, dir, type, fileName);
        String rel = writeRoot.relativize(target).toString().replace('\\', '/');
        if (!Files.isRegularFile(target)) throw new ApiException(404, "no such config: " + rel);

        boolean force = "true".equalsIgnoreCase(String.valueOf(ApiContext.query(ex, "force")));
        if ("pipeline".equals(type)) {
            Map<String, Object> raw = ConfigLoader.filesystem().decode(target.toString());
            WriteGates.conflictIf(
                    Boolean.parseBoolean(String.valueOf(raw.getOrDefault("active", "false"))),
                    "pipeline '" + fileName + "' is active; deactivate (active: false) before deleting");

            PipelineDependents.Report impact = PipelineDependents.scan(writeRoot, pipelineIdOf(raw, fileName));
            WriteGates.conflictIf(!impact.isEmpty() && !force,
                    "pipeline '" + impact.pipeline() + "' is referenced by " + impact.total()
                            + " config(s): " + impact.summary()
                            + " — repoint or remove them, or re-send with ?force=true");
            if (!impact.isEmpty()) {
                log.warn("[CONFIG-DELETE] forced delete of '{}' over {} dependent(s): {}",
                        impact.pipeline(), impact.total(), impact.summary());
            }
        }

        Files.delete(target);
        // Split storage (schema): the sibling _mapping.csv is part of the component — discard it too.
        if ("schema".equals(type)) Files.deleteIfExists(MappingCsv.siblingFor(target));
        if ("pipeline".equals(type)) {
            api.service().unregisterPipeline(target);   // drop the ghost row instead of waiting for the next poll cycle
        } else if ("enrichment".equals(type)) {
            api.service().unregisterEnrichment(fileName);   // stop its schedule timer immediately, not at restart
        }
        log.info("[CONFIG-DELETE] type={} deleted {}", type, rel);
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("type", type);
        r.put("name", fileName);
        r.put("deleted", true);
        r.put("path", rel);
        return r;
    }

    /**
     * {@code GET /config/pipeline/{name}/impact} — what still references this pipeline, so a caller can
     * see what a delete would break <em>before</em> issuing it (the {@code /import/preview} shape:
     * report, write nothing). Gate order is the read side's: write-root 503 → unsafe name 422 → path
     * jail 403 → missing file 404. Optional {@code ?subdir=} mirrors the delete route's;
     * {@code ?limit=} bounds the list, which is hard-capped regardless and reports the TRUE total.
     */
    private Object pipelineImpact(ApiContext api, HttpExchange ex, String name) throws IOException {
        Path writeRoot = WriteGates.requireWriteRoot(api, "config impact");
        String fileName = WriteGates.safeName(name, "config name");

        Path dir = writeRoot;
        String subdir = ApiContext.query(ex, "subdir");
        if (subdir != null && !subdir.isBlank()) {
            Path sub = Path.of(subdir.trim());
            if (sub.isAbsolute()) throw new ApiException(400, "subdir must be relative");
            dir = WriteGates.jail(writeRoot, writeRoot.resolve(sub), "subdir");
        }
        Path target = resolveConfigFile(writeRoot, dir, "pipeline", fileName);
        if (!Files.isRegularFile(target)) {
            throw new ApiException(404, "no such config: "
                    + writeRoot.relativize(target).toString().replace('\\', '/'));
        }

        Map<String, Object> raw = ConfigLoader.filesystem().decode(target.toString());
        int limit = PipelineDependents.MAX_DEPENDENTS;
        String limitParam = ApiContext.query(ex, "limit");
        if (limitParam != null && !limitParam.isBlank()) {
            try {
                limit = Integer.parseInt(limitParam.trim());
            } catch (NumberFormatException nfe) {
                throw new ApiException(400, "limit must be an integer");
            }
            if (limit < 1) throw new ApiException(400, "limit must be positive");
        }
        return PipelineDependents.toJson(
                PipelineDependents.scan(writeRoot, pipelineIdOf(raw, fileName), limit));
    }

    /**
     * A pipeline config's registered id — the explicit top-level {@code id:} when present and
     * non-blank, else {@code name} lower-cased with spaces underscored. Mirrors
     * {@code PipelineConfigParser}'s derivation, which is what every by-name binding keys on; falls
     * back to the file name only when the config carries neither.
     */
    private static String pipelineIdOf(Map<String, Object> raw, String fileName) {
        Object explicit = raw.get("id");
        String id = explicit == null ? "" : String.valueOf(explicit).trim();
        if (!id.isEmpty()) return id;
        Object nm = raw.get("name");
        String derived = nm == null ? "" : String.valueOf(nm).trim();
        return derived.isEmpty() ? fileName : derived.toLowerCase().replace(' ', '_');
    }

    /**
     * {@code GET /config/{type}/{name}} — read a config file back as its decoded map (the
     * onboarding resume path, v5.1.0). Same resolution and gate order as {@code DELETE}:
     * write-root 503 → unknown type 404 → unsafe name 422 → path jail 403 → missing file 404.
     * Ungated like the other reads; the write/delete mutations stay capability-gated.
     */
    private Object readConfig(ApiContext api, HttpExchange ex, String type, String name) throws IOException {
        Path writeRoot = WriteGates.requireWriteRoot(api, "config read");
        if (ConfigSpecs.forType(type) == null) throw new ApiException(404, "unknown config type: " + type);
        String fileName = WriteGates.safeName(name, "config name");

        Path dir = writeRoot;
        String subdir = ApiContext.query(ex, "subdir");
        if (subdir != null && !subdir.isBlank()) {
            Path sub = Path.of(subdir.trim());
            if (sub.isAbsolute()) throw new ApiException(400, "subdir must be relative");
            dir = WriteGates.jail(writeRoot, writeRoot.resolve(sub), "subdir");
        }
        Path target = resolveConfigFile(writeRoot, dir, type, fileName);
        String rel = writeRoot.relativize(target).toString().replace('\\', '/');
        if (!Files.isRegularFile(target)) throw new ApiException(404, "no such config: " + rel);

        Map<String, Object> config = ConfigLoader.filesystem().decode(target.toString());
        // Split storage (schema): serve the conflated view — sibling _mapping.csv rules merged in.
        if ("schema".equals(type)) mergeSiblingMapping(target, config);
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("type", type);
        r.put("name", fileName);
        r.put("path", rel);
        r.put("config", config);
        return r;
    }

    /** Character cap on {@code sample_text} — a preview sample, not a data upload. */
    private static final int MAX_SAMPLE_CHARS = 1_000_000;

    /**
     * {@code POST /config/preview/parsing} — parse a raw sample with a pipeline draft's
     * {@code parsing:} settings and return the produced columns/rows (stream onboarding,
     * v5.1.0). Stateless and scratch-only: body {@code {config:{…}, sample_text}} where
     * {@code config} is a full pipeline draft map (the same shape {@code /validate} takes).
     * The draft is interpreted by the real config parser and the sample is read with the same
     * DuckDB idioms the ingest engine uses ({@link ComponentPreview#parsing}), so what the
     * builder sees is what the engine would parse. Config/parse problems are the caller's
     * (422 with the reason), never a server error.
     */
    private Object previewParsing(Map<String, Object> body) {
        Object cfgObj = body.get("config");
        String sample = ApiContext.str(body, "sample_text");
        if (!(cfgObj instanceof Map<?, ?>) || sample == null || sample.isBlank())
            throw new ApiException(400, "body must include 'config' (a pipeline draft map) and 'sample_text'");
        if (sample.length() > MAX_SAMPLE_CHARS)
            throw new ApiException(400, "sample_text too large (max " + MAX_SAMPLE_CHARS + " chars)");
        @SuppressWarnings("unchecked")
        Map<String, Object> draft = (Map<String, Object>) cfgObj;
        PipelineConfig cfg;
        try {
            cfg = PipelineConfig.fromMap(draft);
        } catch (Exception invalid) {
            throw new ApiException(422, "config is not a valid pipeline draft: " + invalid.getMessage());
        }
        try {
            ComponentPreview.GrammarResult r = ComponentPreview.parsing(cfg, sample);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("frontend", frontendOf(cfg));
            out.put("columns", r.columns());
            out.put("rowCount", r.rowCount());
            out.put("rows", r.rows());
            out.put("rejectedRows", r.rejectedRows());
            return out;
        } catch (IllegalArgumentException unsupported) {
            throw new ApiException(422, unsupported.getMessage());
        } catch (Exception parseFail) {
            throw new ApiException(422, "sample does not parse with these settings: " + parseFail.getMessage());
        }
    }

    /**
     * {@code POST /config/preview/schema} — {@code TRY_CAST} already-parsed {@code sampleRows}
     * against a draft schema's typed fields, splitting ok/rejected (stream onboarding, v5.2.0).
     * Body {@code {config:{raw:{fields:[{name,type}]}}, sampleRows:[{...}]}}. Reuses
     * {@link ComponentPreview#schema} — the same scratch-only cast check the Studio schema
     * component's own preview runs — so the Schema & Mapping stage's "Validate types" sees exactly
     * what that engine path would do. Config/cast problems are the caller's (422), never a server
     * error.
     *
     * <p><b>B1 (definition-surface unification P4):</b> when the posted draft also carries
     * {@code mapping.rules}, the response gains {@code mappedColumns} / {@code mappedCount} /
     * {@code mappedRows} — the rules compiled over the rows that passed the cast, i.e. the Load drawer's
     * "mapped output" in TARGET columns. Additive: a draft without rules gets byte-identical output to
     * before, which is what the onboarding pane still posts.
     */
    private Object previewSchema(Map<String, Object> body) {
        Object cfgObj = body.get("config");
        List<Map<String, Object>> sampleRows = ApiContext.sampleRows(body);
        if (!(cfgObj instanceof Map<?, ?>) || sampleRows.isEmpty())
            throw new ApiException(400, "body must include 'config' (a schema draft map) and non-empty 'sampleRows'");
        @SuppressWarnings("unchecked")
        Map<String, Object> content = (Map<String, Object>) cfgObj;
        try {
            ComponentPreview.Result r = ComponentPreview.schema(content, sampleRows);
            int okCount = 0, rejectedCount = 0;
            List<Map<String, Object>> rejectedRows = List.of();
            ComponentPreview.RelationPreview mapped = null;
            for (ComponentPreview.RelationPreview rel : r.relations()) {
                if ("data".equals(rel.rel())) okCount = rel.rowCount();
                else if ("rejected".equals(rel.rel())) {
                    rejectedCount = rel.rowCount();
                    rejectedRows = rel.rows();
                } else if ("mapped".equals(rel.rel())) mapped = rel;
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("columns", r.inputColumns());
            out.put("okCount", okCount);
            out.put("rejectedCount", rejectedCount);
            out.put("rejectedRows", rejectedRows);
            // B1: present only when the draft declared mapping rules. The column list comes from the rules
            // themselves, not from the returned rows, so an empty mapped set still renders its grid header.
            if (mapped != null) {
                out.put("mappedColumns", targetColumns(content));
                out.put("mappedCount", mapped.rowCount());
                out.put("mappedRows", mapped.rows());
            }
            return out;
        } catch (IllegalArgumentException badSchema) {
            throw new ApiException(422, badSchema.getMessage());
        } catch (Exception castFail) {
            throw new ApiException(422, "schema preview failed: " + castFail.getMessage());
        }
    }

    /** The mapped relation's column names, in rule order — the draft's own {@code mapping.rules} targets. */
    private static List<String> targetColumns(Map<String, Object> content) {
        if (!(content.get("mapping") instanceof Map<?, ?> mapping)
                || !(mapping.get("rules") instanceof List<?> rules)) return List.of();
        List<String> out = new ArrayList<>();
        for (Object r : rules)
            if (r instanceof Map<?, ?> rule && rule.get("targetColumn") != null)
                out.add(rule.get("targetColumn").toString());
        return List.copyOf(out);
    }

    /**
     * {@code POST /config/suggest/schema} — draft-schema inference over already-parsed
     * {@code sampleRows} (G1, {@code consignment-chain-plan.md} S4). {@code SchemaSuggest} runs
     * TRY_CAST voting per column on a scratch DuckDB and this handler shapes the winners into a
     * DRAFT: a {@code raw.fields} list ({@code selector} = the sample's own column key) plus
     * identity {@code mapping} rules, for the schema editor to seed a HUMAN edit — never
     * auto-applied (the {@code ParserPlugin.suggest} posture), and real ingest keeps
     * {@code auto_detect=false}. Body {@code {sampleRows:[{...}]}} — the parsing preview's output
     * shape, so the two routes chain: parse the sample, then suggest from what parsed.
     */
    private Object suggestSchema(Map<String, Object> body) {
        List<Map<String, Object>> sampleRows = ApiContext.sampleRows(body);
        if (sampleRows.isEmpty())
            throw new ApiException(400, "body must include non-empty 'sampleRows'");
        try {
            List<Map<String, Object>> fields = new ArrayList<>();
            List<Map<String, Object>> rules = new ArrayList<>();
            for (com.gamma.pipeline.exec.SchemaSuggest.Field f
                    : com.gamma.pipeline.exec.SchemaSuggest.infer(sampleRows)) {
                Map<String, Object> field = new LinkedHashMap<>();
                field.put("name", f.name());
                field.put("selector", f.name());
                field.put("type", f.type());
                fields.add(field);
                Map<String, Object> rule = new LinkedHashMap<>();
                rule.put("targetColumn", f.name());
                rule.put("sourceExpression", f.name());
                rule.put("transformType", "DIRECT");
                rules.add(rule);
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("fields", fields);
            out.put("mapping", Map.of("rules", rules));
            return out;
        } catch (IllegalArgumentException badSample) {
            throw new ApiException(422, badSample.getMessage());
        } catch (Exception inferFail) {
            throw new ApiException(422, "schema suggestion failed: " + inferFail.getMessage());
        }
    }

    /** The parsing frontend a config resolves to (the same precedence the ingester applies). */
    private static String frontendOf(PipelineConfig cfg) {
        if (cfg.fixedWidth() != null) return "fixedwidth";
        if (cfg.json() != null) return "json";
        if (cfg.textRegex() != null) return "text_regex";
        if (cfg.schemas().ingesterClass() != null) return "plugin";
        return "delimited";
    }

    /**
     * Pre-flight check that a pipeline draft's schema reference(s) resolve on <em>this server's</em>
     * filesystem (v4.1.0). A draft that validates clean can otherwise still fail at registration
     * with an opaque 422 — this surfaces it early, as a structured finding anchored to the field.
     * Checks both the legacy {@code processing.schema_file} and the multi-schema
     * {@code processing.schemas[].schema_file}. No-op for non-pipeline types.
     *
     * <p>⚠ This must resolve references <b>exactly</b> the way {@link PipelineConfig#load} does, or it
     * becomes a gate that rejects configs the engine would happily run. Since W1b that means
     * config-relative first, working-directory second — hence {@code configDir}.
     *
     * @param severity  WARNING at validate/save time (the file may be created later, or the config
     *                  may be destined for another host); ERROR at register time (it will fail)
     * @param configDir directory the config lives in, so a config-relative reference resolves; {@code null}
     *                  for a draft with no home yet, which checks the working-directory form only
     */
    static List<Finding> schemaFileFindings(String type, Map<String, Object> draft, Severity severity,
                                            Path configDir) {
        if (!"pipeline".equals(type)) return List.of();
        Object procObj = draft.get("processing");
        if (!(procObj instanceof Map<?, ?> proc)) return List.of();
        List<Finding> out = new ArrayList<>();
        if (proc.get("schema_file") instanceof String s && !s.isBlank() && !resolves(s, configDir))
            out.add(new Finding(severity, "processing.schema_file", unresolvable(s)));
        if (proc.get("schemas") instanceof List<?> defs) {
            for (int i = 0; i < defs.size(); i++) {
                if (defs.get(i) instanceof Map<?, ?> def
                        && def.get("schema_file") instanceof String s && !s.isBlank()
                        && !resolves(s, configDir))
                    out.add(new Finding(severity, "processing.schemas[" + i + "].schema_file",
                            unresolvable(s)));
            }
        }
        return out;
    }

    /**
     * {@code active: true} with no schema source at all — the one draft shape that {@link
     * PipelineConfig#load} hard-throws on but spec validation accepts. Left unchecked, the write
     * succeeds, {@code ConfigRegistry.rebuild} logs a single WARN and omits the pipeline, and the
     * scheduler then skips it every cycle forever: no run, no failure, no operator signal.
     *
     * <p>Deliberately narrower than a full {@code PipelineConfig.fromMap} gate — parsing the draft
     * here would also hard-fail an <em>unresolvable</em> schema reference, which
     * {@link #schemaFileFindings} intentionally keeps a WARNING (the file may be created after the
     * save, or belong to another host). Mirrors {@code PipelineConfigParser}'s three schema sources.
     */
    static List<Finding> armedWithoutSchemaFindings(String type, Map<String, Object> draft) {
        if (!"pipeline".equals(type)) return List.of();
        if (!Boolean.parseBoolean(String.valueOf(draft.getOrDefault("active", "false"))))
            return List.of();
        Object procObj = draft.get("processing");
        Map<?, ?> proc = procObj instanceof Map<?, ?> m ? m : Map.of();
        Object parsingObj = draft.get("parsing");
        Map<?, ?> parsing = parsingObj instanceof Map<?, ?> m ? m : Map.of();
        Object plugin = parsing.get("plugin") instanceof Map<?, ?> pm ? pm.get("ingester") : null;
        boolean hasSchema =
                (proc.get("schema_file") instanceof String s && !s.isBlank())
                || (proc.get("schemas") instanceof List<?> l && !l.isEmpty())
                || (proc.get("ingester") instanceof String i && !i.isBlank())
                || (plugin instanceof String p && !p.isBlank());
        if (hasSchema) return List.of();
        return List.of(new Finding(Severity.ERROR, "active",
                "active: true but no schema is configured (processing.schema_file, "
                        + "processing.schemas[], or a plugin ingester) — keep the draft inactive "
                        + "until its schema is attached"));
    }

    /**
     * A <b>remote</b> collector whose {@code connection} names a profile this space does not have. Left
     * unchecked the dangling id reaches the poll cycle, where {@code CollectorConnectors.forConfig} resolves
     * it to {@code null} and the connector factory throws — on <em>every</em> cycle, never once, and never
     * at the moment the operator could fix it. Bundle import has always refused this
     * ({@code DataSourceRoutes.referentialFindings}, same field path); a plain save did not.
     *
     * <p><b>Only when the connector is remote</b>, because that is the only case that resolves the binding:
     * {@code CollectorConnectors.forConfig} short-circuits to the local connector first and never looks the
     * id up, so a {@code connection} left behind on a {@code local} collector is inert, not broken. Refusing
     * it would reject configs that run fine today — and does: it fails five {@code /config/patch} fixtures
     * whose seed is exactly that shape. A blank/absent connector is the legacy no-{@code collector:}-block
     * case and counts as local, matching {@code CollectorConnectors.isRemote}. Flipping such a config to a
     * remote connector goes through this same gate (write or patch), which is where it starts to matter.
     *
     * <p>Checked against the live {@code ConnectionProfileRegistry} — the same source of truth the import
     * path uses, updated in the same request by every connection write. One blind spot: a
     * {@code *_connection.toon} copied straight onto disk with no restart since. There is no rescan route,
     * so it is invisible here exactly as it is to the import check and to the run itself.
     */
    static List<Finding> unknownConnectionFindings(String type, Map<String, Object> draft, ApiContext api) {
        if (!"pipeline".equals(type) || api == null) return List.of();
        if (!(draft.get("collector") instanceof Map<?, ?> collector)) return List.of();
        Object scheme = collector.get("connector");
        String connector = scheme == null ? "" : String.valueOf(scheme).trim();
        if (connector.isEmpty() || "local".equalsIgnoreCase(connector)) return List.of();
        Object id = collector.get("connection");
        if (id == null) return List.of();
        String conn = String.valueOf(id).trim();
        if (conn.isEmpty() || api.service().connections().containsKey(conn)) return List.of();
        return List.of(new Finding(Severity.ERROR, "collector.connection",
                "unknown connection '" + conn + "' — no such connection profile in this space;"
                        + " create the connection first, or clear collector.connection"));
    }

    /**
     * For a draft that has no directory yet (validate / pre-write), where a config-relative reference
     * cannot be checked because there is nothing to be relative to.
     */
    static List<Finding> schemaFileFindings(String type, Map<String, Object> draft, Severity severity) {
        return schemaFileFindings(type, draft, severity, null);
    }

    /** Mirrors {@code PipelineConfigParser.resolveSchemaRef}: config-relative first, then the CWD. */
    private static boolean resolves(String ref, Path configDir) {
        Path asAuthored = Path.of(ref);
        if (configDir != null && !asAuthored.isAbsolute()) {
            Path base      = configDir.toAbsolutePath().normalize();
            Path candidate = base.resolve(asAuthored).normalize();
            if (candidate.startsWith(base) && Files.isRegularFile(candidate)) return true;
        }
        return Files.isRegularFile(asAuthored);
    }

    private static String unresolvable(String schemaPath) {
        return "schema file does not resolve on the server: '" + schemaPath
                + "' (relative paths resolve against the server's working directory: "
                + Path.of("").toAbsolutePath() + ")";
    }

    /**
     * The on-disk base name for a config. The service bootstrap scan indexes pipelines by the
     * {@code *_pipeline.toon} suffix ({@code MultiCollectorProcessor.resolveConfigs}) and Stage-2
     * enrichments by {@code *_enrich.toon} ({@code ServiceBootstrap.resolveBySuffix}), so a
     * guided write MUST follow them — otherwise a draft silently drops out on the next service
     * restart (found by the P2 live walk for pipelines; same trap for enrichments). Other types
     * keep the bare name (their identity fields, e.g. {@code raw.name}, already carry the scan
     * suffix by convention).
     */
    private static String fileBase(String type, String safeName) {
        if ("pipeline".equals(type) && !safeName.endsWith("_pipeline")) return safeName + "_pipeline";
        if ("enrichment".equals(type) && !safeName.endsWith("_enrich")) return safeName + "_enrich";
        return safeName;
    }

    /** Resolve a config's file: the suffixed convention first, then the bare name (back-compat). */
    private static Path resolveConfigFile(Path writeRoot, Path dir, String type, String safeName) {
        Path suffixed = WriteGates.jail(writeRoot, dir.resolve(fileBase(type, safeName) + ".toon"), "resolved path");
        if (Files.isRegularFile(suffixed)) return suffixed;
        return WriteGates.jail(writeRoot, dir.resolve(safeName + ".toon"), "resolved path");
    }

    /** Dotted path into the config map that holds a config's stable identity (its filename source). */
    private static String identityField(String type) {
        return switch (type) {
            case "job"    -> "job.name";
            case "schema" -> "raw.name";
            default       -> "name";   // pipeline, enrichment, meta
        };
    }

    /** Read a dotted key (e.g. {@code job.name}) from a nested config map, or {@code null} if absent. */
    private static String dottedString(Map<String, Object> map, String dotted) {
        Object cur = map;
        for (String seg : dotted.split("\\.")) {
            if (!(cur instanceof Map<?, ?> m)) return null;
            cur = m.get(seg);
        }
        return cur == null ? null : String.valueOf(cur);
    }
}
