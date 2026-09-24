package com.gamma.control;

import com.gamma.config.io.ConfigCodec;
import com.gamma.config.io.ConfigLoader;
import com.gamma.config.safety.SchemaCompatibility;
import com.gamma.config.spec.ConfigSpec;
import com.gamma.config.spec.ConfigSpecs;
import com.gamma.config.spec.Finding;
import com.gamma.etl.SchemaMappingDrift;
import com.gamma.util.AtomicFiles;
import com.gamma.util.MappingCsv;
import com.gamma.util.StructureCsv;
import com.sun.net.httpserver.HttpExchange;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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

        // The one content gate every save path runs (SaveGate) — BEFORE any path is resolved, so an
        // invalid payload is refused 422 ahead of the subdir jail 403 (house gate order, pinned by
        // WriteGateOrderTest). It is judged from the PROSPECTIVE directory — the write root, or the
        // requested subdir when that stays inside it (an escaping one is left to the jail below) — so a
        // config-relative ref resolves as it will once written. Until G3 (2026-09-23) this gate ran from
        // the working directory and a second gate after `target` ran the checks needing the directory.
        String subdir = ApiContext.str(body, "subdir");
        Path prospective = writeRoot;
        if (subdir != null && !subdir.isBlank() && !Path.of(subdir.trim()).isAbsolute()) {
            Path candidate = writeRoot.resolve(subdir.trim()).normalize();
            if (candidate.startsWith(writeRoot.normalize())) prospective = candidate;
        }
        List<Finding> findings = SaveGate.check(api, type, draft, writeRoot, prospective,
                SaveGate.Referents.MUST_EXIST);
        if (SaveGate.refuses(findings)) {
            return ApiContext.respondJson(ex, 422, Map.of("type", type, "written", false,
                    "error", "config has ERROR-level findings; not written", "findings", findings));
        }

        // Resolve under the write root; an optional subdir must stay inside it (path jail).
        Path dir = writeRoot;
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

        // ⛔ D3 (a created Pipeline lands in config/<id>/) is SIGNED and still unlanded. Two attempts,
        // each of which taught the next one something:
        //
        // 1. 2026-09-22 — redirected the write alone. A freshly-written config became UNREACHABLE: an
        //    unregistered config was resolved at the write ROOT, so read/patch/delete answered 404.
        //    ✅ FIXED: ConfigFileSupport.resolveConfigFile now falls back to <dir>/<name>/.
        // 2. 2026-09-23 — redirected the write again on top of that fix. Better (23 failures → 15), and
        //    it surfaced the REAL coupling: 🔴 a Pipeline's bare `schema_file: <name>.toon` resolves
        //    BESIDE ITS OWN CONFIG. Nesting the Pipeline while its schema is still written flat
        //    separates the two, and the save warns that its own schema does not resolve
        //    (ControlApiConfigWriteTest.aPortableSchemaReferenceBesideTheConfigIsNotWarnedAbout).
        //
        // ⚠ So D3 needs SATELLITE writes to follow the Pipeline into its subdir — its own text says so
        // ("satellites get the subdir too"), and that is the part no row has scoped. A /config/write for
        // a schema with no `subdir:` still lands flat. Landing the Pipeline half alone orphans the
        // reference it was supposed to keep together. Row: UI-CREATED-PIPELINE-FLAT-HOME-1.

        boolean exists = Files.exists(target);
        // Optimistic concurrency (`CLIENT-HALVES-1` (a), 2026-09-11). HERE and not earlier: `target` is
        // only final after the legacy-name fallback above, and a precondition checked against the wrong
        // file is a false verdict either way.
        //
        // ⚠ This is the gate that matters, because `overwrite: true` bypasses the conflict check below —
        // and EVERY authoring caller passes it (the write is a whole-file replace). Without a
        // precondition those panes are last-write-wins: a concurrent editor's change is destroyed with
        // no signal to either party.
        //
        // Honoured, not required (the house rule ETags.requireMatch already encodes): a caller that
        // sends no If-Match writes as before, so no existing client breaks. Hashing goes through
        // ConfigFileSupport.storedContent so it is byte-for-byte what GET /config/{type}/{name} served.
        if (exists)
            ETags.requireMatch(ex, ETags.of(ContentHash.of(ConfigFileSupport.storedContent(target, type))));
        boolean overwrite = "true".equalsIgnoreCase(String.valueOf(body.get("overwrite")));
        WriteGates.conflictIf(exists && !overwrite,
                "file exists: " + writeRoot.relativize(target).toString().replace('\\', '/')
                        + " (pass overwrite:true to replace)");

        // Schema drift into the mapping (AUTHORING-REDESIGN-1 (g)): a mapping field reading a column the
        // draft's raw.fields no longer declares fails at the first row, so it is refused here. No override —
        // both halves are in this one document.
        if ("schema".equals(type)) {
            List<Finding> drift = SchemaMappingDrift.check(draft);
            if (!drift.isEmpty()) {
                findings.addAll(drift);
                return ApiContext.respondJson(ex, 422, Map.of("type", type, "written", false,
                        "error", "mapping reads columns the schema no longer declares; not written", "findings", findings));
            }
        }

        // Schema compatibility save-gate (ELT amendment §3.4.2, D-10): a schema OVERWRITE is diffed
        // old→new under the BACKWARD class; breaking edits (remove/narrow/selector-move) 422 with
        // cell-level findings. Escape hatches: copy to a new name, or the explicit override below.
        if ("schema".equals(type) && exists && !compatibilityOverridden(body)) {
            Map<String, Object> current = ConfigLoader.filesystem().decode(target.toString());
            // STRUCTURE-CSV-1: a split schema keeps its fields in the sibling CSV — without merging it the
            // gate would see NO fields on disk and wave every edit through.
            ConfigFileSupport.mergeSiblingStructure(target, current);
            List<Finding> breaking = SchemaCompatibility.check(current, draft);
            if (!breaking.isEmpty()) {
                findings.addAll(breaking);
                return ApiContext.respondJson(ex, 422, Map.of("type", type, "written", false,
                        "error", "schema edit is not BACKWARD-compatible; not written", "findings", findings));
            }
        }

        // Encode and write atomically: a partial/concurrent reader never sees a half-written file.
        Map<String, Object> toWrite = draft;
        String mappingRel = null, structureRel = null;
        if ("schema".equals(type)) {
            SchemaSplit split = splitMapping(writeRoot, target, draft);
            toWrite = split.structure();
            mappingRel = split.mappingRel();
            structureRel = split.structureRel();
        }
        byte[] bytes = ConfigCodec.toToon(toWrite).getBytes(StandardCharsets.UTF_8);
        AtomicFiles.write(target, bytes, ".cfg-");
        if ("pipeline".equals(type)) PipelineHistory.record(writeRoot, target);   // PIPELINE-CONFIG-HISTORY-1
        String rel = writeRoot.relativize(target).toString().replace('\\', '/');
        log.info("[CONFIG-WRITE] type={} wrote {} ({} bytes, overwrote={})", type, rel, bytes.length, exists);

        // The NEW concurrency handle, so an editor can save twice in a row (`CLIENT-HALVES-1` (a)).
        // ⚠ Without this a successful save leaves the caller holding the PRE-save ETag, and its next
        // write is refused as stale — a precondition that breaks the second save is worse than none.
        // Recomputed from DISK, not from `draft`: for a schema the bytes on disk are the split form plus
        // its siblings, and only storedContent reassembles exactly what a read would serve.
        ETags.set(ex, ETags.of(ContentHash.of(ConfigFileSupport.storedContent(target, type))));

        Map<String, Object> r = new LinkedHashMap<>();
        r.put("type", type);
        r.put("written", true);
        r.put("path", rel);
        if (mappingRel != null) r.put("mappingPath", mappingRel);
        if (structureRel != null) r.put("structurePath", structureRel);
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

    /** A schema draft split for persistence: the TOON remainder + the rel paths of the sibling CSVs written. */
    private record SchemaSplit(Map<String, Object> structure, String mappingRel, String structureRel) {}

    /**
     * Split-write for the Schema/Mapping separation (ELT amendment Phase 1 slice 2 + STRUCTURE-CSV-1): a
     * schema draft's {@code mapping.rules} are persisted as the sibling {@code <name>_mapping.csv} and its
     * {@code raw.fields} as {@code <name>_structure.csv} (the shapes the engine's dual-read consumes), both
     * stripped from the TOON. A draft without rules writes the TOON unchanged — an existing sibling mapping CSV
     * then remains the mapping source of truth. Fields are split only when {@link StructureCsv#splittable}
     * (every field key has a column); a list carrying {@code timezone}/{@code partitions}/… stays inline
     * and a stale structure sibling is REMOVED, so the sibling can never shadow the fields just written.
     */
    @SuppressWarnings("unchecked")
    private static SchemaSplit splitMapping(Path writeRoot, Path target, Map<String, Object> draft)
            throws IOException {
        Map<String, Object> structure = new LinkedHashMap<>(draft);
        String mappingRel = null, structureRel = null;

        if (draft.get("mapping") instanceof Map<?, ?> mapping
                && mapping.get("rules") instanceof List<?> rules && !rules.isEmpty()) {
            Path csv = MappingCsv.siblingFor(target);
            String text = MappingCsv.encode((List<? extends Map<String, ?>>) rules);
            AtomicFiles.write(csv, text.getBytes(StandardCharsets.UTF_8), ".map-");
            Map<String, Object> mappingRest = new LinkedHashMap<>(mapAt(draft, "mapping"));
            mappingRest.remove("rules");
            if (mappingRest.isEmpty()) structure.remove("mapping");
            else structure.put("mapping", mappingRest);
            mappingRel = writeRoot.relativize(csv).toString().replace('\\', '/');
        }

        Path structureCsv = StructureCsv.siblingFor(target);
        if (draft.get("raw") instanceof Map<?, ?> raw && raw.get("fields") instanceof List<?> fields
                && StructureCsv.splittable(fields)) {
            String text = StructureCsv.encode((List<? extends Map<String, ?>>) fields);
            AtomicFiles.write(structureCsv, text.getBytes(StandardCharsets.UTF_8), ".str-");
            Map<String, Object> rawRest = new LinkedHashMap<>(mapAt(draft, "raw"));
            rawRest.remove("fields");
            structure.put("raw", rawRest);
            structureRel = writeRoot.relativize(structureCsv).toString().replace('\\', '/');
        } else {
            Files.deleteIfExists(structureCsv);
        }
        return new SchemaSplit(structure, mappingRel, structureRel);
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
        // Pipeline-aware: a patch addresses a registered pipeline by name, and deactivating one is
        // the prerequisite for deleting it — resolving only against the write root made that
        // impossible for every pipeline whose file lives in a subdirectory.
        Path target = ConfigFileSupport.resolveRegisteredConfigFile(api, writeRoot, dir, type, fileName, subdir);
        String rel = writeRoot.relativize(target).toString().replace('\\', '/');
        if (!Files.isRegularFile(target))
            throw new ApiException(404, "no such config: " + rel + " (create it via /config/write first)");

        Map<String, Object> existing = ConfigLoader.filesystem().decode(target.toString());
        // Split storage (schema): patch over the CONFLATED view, so a partial draft can address
        // mapping.rules whether they live inline or in the sibling CSV.
        if ("schema".equals(type)) ConfigFileSupport.mergeSiblings(target, existing);
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

        // The one content gate (SaveGate), over the WHOLE merged draft — so a block the patch did not
        // touch is judged too — against the file's own directory.
        List<Finding> findings = SaveGate.check(api, type, merged, writeRoot, target.getParent(),
                SaveGate.Referents.MUST_EXIST);
        if (SaveGate.refuses(findings)) {
            return ApiContext.respondJson(ex, 422, Map.of("type", type, "written", false,
                    "error", "merged config has ERROR-level findings; not written", "findings", findings));
        }

        // Same drift gate as /config/write — a patch can rename a raw field out from under the mapping.
        if ("schema".equals(type)) {
            List<Finding> drift = SchemaMappingDrift.check(merged);
            if (!drift.isEmpty()) {
                findings.addAll(drift);
                return ApiContext.respondJson(ex, 422, Map.of("type", type, "written", false,
                        "error", "mapping reads columns the schema no longer declares; not written", "findings", findings));
            }
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
        String mappingRel = null, structureRel = null;
        if ("schema".equals(type)) {
            SchemaSplit split = splitMapping(writeRoot, target, merged);
            toWrite = split.structure();
            mappingRel = split.mappingRel();
            structureRel = split.structureRel();
        }
        byte[] bytes = ConfigCodec.toToon(toWrite).getBytes(StandardCharsets.UTF_8);
        AtomicFiles.write(target, bytes, ".cfg-");
        if ("pipeline".equals(type)) PipelineHistory.record(writeRoot, target);   // PIPELINE-CONFIG-HISTORY-1
        log.info("[CONFIG-PATCH] type={} patched {} ({} bytes)", type, rel, bytes.length);

        Map<String, Object> r = new LinkedHashMap<>();
        r.put("type", type);
        r.put("written", true);
        r.put("path", rel);
        if (mappingRel != null) r.put("mappingPath", mappingRel);
        if (structureRel != null) r.put("structurePath", structureRel);
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
