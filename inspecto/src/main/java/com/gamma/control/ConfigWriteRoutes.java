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
import static com.gamma.util.Values.mapAt;

/**
 * Declarative-config mutation routes ({@code /config/write}, {@code /config/patch};
 * v3.2.0/v4.1.0/v5.1.0): persist a validated draft (write-root jailed, atomic) and block-level
 * patch an existing file. Extracted verbatim from {@code ConfigRoutes}: identical routes,
 * statuses, gating order and on-disk behaviour. The shared findings helpers live on
 * {@link ConfigRoutes}; file resolution lives on {@link ConfigFileSupport}.
 */
final class ConfigWriteRoutes implements RouteModule {

    private static final Logger log = LoggerFactory.getLogger(ConfigWriteRoutes.class);

    @Override
    public void register(ApiContext api) {
        // Requires canAuthorWorkbench (W6; a no-op on Personal — no Subject is ever attached there).
        api.post("/config/write", ApiContext.withCapability("canAuthorWorkbench", (e, m) -> writeConfig(api, e, api.body(e))));
        // Block-level save (collector-config unification, 2026-08-04): deep-merge a patch over the
        // file's CURRENT content server-side, so a stage pane can never clobber blocks it didn't
        // edit with a stale client-held copy. Same gates and response shape as /config/write.
        api.post("/config/patch", ApiContext.withCapability("canAuthorWorkbench", (e, m) -> patchConfig(api, e, api.body(e))));
    }

    private Object writeConfig(ApiContext api, HttpExchange ex, Map<String, Object> body) throws IOException {
        Path writeRoot = WriteGates.requireWriteRoot(api, "config write");

        String type = ApiContext.str(body, "type");
        Object cfgObj = body.get("config");
        if (type == null || !(cfgObj instanceof Map<?, ?>))
            throw new ApiException(400, "body must include 'type' and 'config' (a draft config map)");
        ConfigSpec spec = ConfigSpecs.forType(type);
        if (spec == null) throw new ApiException(404, "unknown config type: " + type);
        Map<String, Object> draft = mapAt(body, "config");

        // Gate: spec validation + the hard-fail safety check (R6). Block on ERRORs; warnings pass.
        // ⚠ No config dir is passed here, unlike the patch route below and PUT /pipelines/{n}/graph:
        // this gate runs BEFORE the target path is derived, so a config ref can only be resolved
        // working-directory-relative. A pipeline written through THIS route carrying the portable bare
        // `<name>.toon` would still be refused. Every UI path that authors one goes through the graph
        // route, which is why it is not reordered here — moving a write gate is a bigger change than
        // the defect warrants, and doing it blind risks the ordering the gate depends on.
        List<Finding> findings = new ArrayList<>(ConfigLoader.filesystem().validate(spec, draft));
        findings.addAll(ConfigSafetyValidator.check(type, draft, SafetyPolicy.defaultPolicy()));
        // ERROR: an armed pipeline with no schema source parses nowhere. Without this the write
        // returns written:true and the config is then silently dropped from the index forever.
        findings.addAll(ConfigRoutes.armedWithoutSchemaFindings(type, draft));
        // ERROR (when active): an armed route: that cannot arm registers and then throws on every
        // run. Same reasoning as the row above — the save is the last moment the author is present.
        findings.addAll(ConfigRoutes.routeArmingFindings(type, draft));
        // ERROR (when active): disabled_steps cannot arm until park/drain ships (S4a gate).
        findings.addAll(ConfigRoutes.stepDisableFindings(type, draft));
        // ERROR: a collector bound to a connection this space does not have cannot acquire anything —
        // it throws once per poll cycle instead. Bundle import already refuses it; a save now agrees.
        findings.addAll(ConfigRoutes.unknownConnectionFindings(type, draft, api));
        if (findings.stream().anyMatch(f -> f.severity() == Severity.ERROR)) {
            return ApiContext.respondJson(ex, 422, Map.of("type", type, "written", false,
                    "error", "config has ERROR-level findings; not written", "findings", findings));
        }

        // Resolve under the write root; an optional subdir must stay inside it (path jail).
        Path dir = writeRoot;
        String subdir = ApiContext.str(body, "subdir");
        if (subdir != null && !subdir.isBlank()) {
            Path sub = Path.of(subdir.trim());
            if (sub.isAbsolute()) throw new ApiException(400, "subdir must be relative");
            dir = WriteGates.jail(writeRoot, writeRoot.resolve(sub), "subdir");
        }

        // Filename from the config's own identity field — no caller-controlled path component.
        List<String> idFields = identityFields(type);
        String identity = firstPresentValue(draft, idFields);
        if (identity == null)
            throw new ApiException(422, "config is missing its identity field '" + idFields.getFirst() + "'");
        String safeIdentity = WriteGates.safeName(identity, "config name");
        Path target = WriteGates.jail(writeRoot,
                dir.resolve(ConfigFileSupport.fileBase(type, safeIdentity) + ".toon"), "resolved path");

        // A pipeline written before its `id` was stamped at birth lives under a name-derived filename.
        // Keep editing THAT file rather than forking a second config beside it under the id.
        //
        // ⚠ A fallback candidate is PROBED, never enforced: a display `name` has no pattern of its own, so
        // `safeName` here would 422 the very writes the id-keyed filename exists to make possible.
        //
        // ⛔ And it is only ADOPTED when the file on disk agrees it is the same config — see
        // {@link #adoptable}. Two pipelines may legitimately share a display name; retargeting on the
        // label alone would let a write to one silently replace the other.
        //
        // ⛔ WRITE-1: a candidate that declares NO id of its own is genuinely ambiguous — "this pipeline
        // gaining an id" and "a different pipeline that happens to share the display label" are the same
        // request on the wire. It stays ADOPTED, and that is not a shortcut: adoption only ever runs when
        // the id-named target does NOT exist, so an adopting write is necessarily an `overwrite:true` of
        // the legacy file — refusing the ambiguous case would refuse the in-place edit this fallback
        // exists to perform (pinned by `anIdStampedOntoALegacyConfigKeepsEditingTheExistingFile`). What a
        // caller gets instead is `legacyName`: name the file you mean and the probe takes it verbatim,
        // rather than a display label the server has to guess from.
        String legacyName = ApiContext.str(body, "legacyName");
        boolean namedLegacy = legacyName != null && !legacyName.isBlank();
        if (idFields.size() > 1 && !Files.exists(target)) {
            List<String> probes = namedLegacy
                    ? List.of(legacyName)
                    : idFields.subList(1, idFields.size()).stream().map(f -> dottedString(draft, f)).toList();
            for (String raw : probes) {
                if (!WriteGates.isSafeName(raw)) continue;
                // resolveConfigFile, not a hand-rolled join: a legacy config may sit under the bare
                // `<name>.toon` back-compat form, which /config/read and /config/patch both honour.
                Path legacy = ConfigFileSupport.resolveConfigFile(writeRoot, dir, type, raw.trim());
                if (!Files.isRegularFile(legacy)) continue;
                String declared = declaredIdentity(legacy, idFields.getFirst());
                boolean sameId = declared != null && declared.equals(identity.trim());
                if (!sameId && declared != null) continue;      // a DIFFERENT id — never this config's file
                target = legacy;
                break;
            }
        }

        // Warning only: the save still succeeds (the schema file may be created afterwards), but
        // the operator learns now that Register would fail on this host.
        //
        // W3: deliberately AFTER `target`, so the check knows the directory the config is landing in.
        // A reference resolves config-relative first, so the portable bare `<name>.toon` the UI now
        // writes is only checkable against that parent; run earlier it warned on every single write.
        findings.addAll(ConfigRoutes.schemaFileFindings(type, draft, Severity.WARNING, target.getParent()));

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
        Map<String, Object> mappingRest = new LinkedHashMap<>(mapAt(draft, "mapping"));
        mappingRest.remove("rules");
        if (mappingRest.isEmpty()) structure.remove("mapping");
        else structure.put("mapping", mappingRest);
        return new SchemaSplit(structure, writeRoot.relativize(csv).toString().replace('\\', '/'));
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
        Map<String, Object> patch = mapAt(body, "patch");
        String fileName = WriteGates.safeName(name, "config name");

        Path dir = writeRoot;
        String subdir = ApiContext.str(body, "subdir");
        if (subdir != null && !subdir.isBlank()) {
            Path sub = Path.of(subdir.trim());
            if (sub.isAbsolute()) throw new ApiException(400, "subdir must be relative");
            dir = WriteGates.jail(writeRoot, writeRoot.resolve(sub), "subdir");
        }
        Path target = ConfigFileSupport.resolveConfigFile(writeRoot, dir, type, fileName);
        String rel = writeRoot.relativize(target).toString().replace('\\', '/');
        if (!Files.isRegularFile(target))
            throw new ApiException(404, "no such config: " + rel + " (create it via /config/write first)");

        Map<String, Object> existing = ConfigLoader.filesystem().decode(target.toString());
        // Split storage (schema): patch over the CONFLATED view, so a partial draft can address
        // mapping.rules whether they live inline or in the sibling CSV.
        if ("schema".equals(type)) ConfigFileSupport.mergeSiblingMapping(target, existing);
        Map<String, Object> merged = deepMerge(existing, patch);

        // The filename derives from the identity field, so a patch may not move it — a renamed
        // identity under the old filename would silently split the config from its index entry.
        for (String idField : identityFields(type)) {
            String before = dottedString(existing, idField);
            String after = dottedString(merged, idField);
            WriteGates.conflictIf(before != null && !before.equals(after),
                    "patch changes the identity field '" + idField + "' (" + before + " → " + after
                            + "); rename via /config/write");
        }

        // Same gate as /config/write, over the WHOLE merged draft: spec + hard-fail safety check;
        // schema references resolve config-relative here because the file has a home directory.
        List<Finding> findings = new ArrayList<>(ConfigLoader.filesystem().validate(spec, merged));
        findings.addAll(ConfigSafetyValidator.check(type, merged, SafetyPolicy.defaultPolicy(),
                target.getParent()));
        findings.addAll(ConfigRoutes.schemaFileFindings(type, merged, Severity.WARNING, target.getParent()));
        findings.addAll(ConfigRoutes.armedWithoutSchemaFindings(type, merged));
        findings.addAll(ConfigRoutes.routeArmingFindings(type, merged));              // a patch can break arming too
        findings.addAll(ConfigRoutes.stepDisableFindings(type, merged));                // and can add disabled_steps too
        findings.addAll(ConfigRoutes.unknownConnectionFindings(type, merged, api));   // a patch can introduce one too
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
            } else if (pv instanceof Map<?, ?> && out.get(e.getKey()) instanceof Map<?, ?>) {
                out.put(e.getKey(), deepMerge(mapAt(out, e.getKey()), mapAt(patch, e.getKey())));
            } else {
                out.put(e.getKey(), pv);
            }
        }
        return out;
    }

    /**
     * Dotted paths into the config map that hold a config's stable identity (its filename source),
     * best first. A pipeline's is its {@code id} — the field {@code PipelineRoutes.rename} already
     * names the file from, so create and rename now agree on one filename; {@code name} stays as the
     * fallback for a config written before the id was stamped at birth.
     */
    private static List<String> identityFields(String type) {
        return switch (type) {
            case "job"      -> List.of("job.name");
            case "schema"   -> List.of("raw.name");
            case "pipeline" -> List.of("id", "name");
            default         -> List.of("name");   // enrichment, meta
        };
    }

    /** The value of the first of {@code fields} the draft carries a non-blank value for, else {@code null}. */
    private static String firstPresentValue(Map<String, Object> draft, List<String> fields) {
        for (String f : fields) {
            String v = dottedString(draft, f);
            if (v != null && !v.isBlank()) return v;
        }
        return null;
    }

    /**
     * The identity a fallback candidate declares for itself, or {@code null} when it declares none (a
     * genuine pre-{@code id} config). The caller decides what to do with each case.
     *
     * <p>⛔ A display label is not unique, so a candidate declaring a DIFFERENT identity is never this
     * draft's file: writing {@code {name: "Orders", id: "orders_v2"}} beside a legacy {@code Orders} must
     * not retarget onto it and, with {@code overwrite:true}, destroy it.
     *
     * <p>⚠ A candidate declaring NO identity stays indistinguishable from "the same config gaining one"
     * — WRITE-1's residual ambiguity, which the caller resolves with {@code legacyName}.
     */
    private static String declaredIdentity(Path candidate, String idField) throws IOException {
        String declared = dottedString(ConfigLoader.filesystem().decode(candidate.toString()), idField);
        return declared == null || declared.isBlank() ? null : declared.trim();
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
