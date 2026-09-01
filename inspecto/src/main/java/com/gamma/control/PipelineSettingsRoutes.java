package com.gamma.control;

import com.gamma.config.io.ConfigCodec;
import com.gamma.config.io.ConfigLoader;
import com.gamma.config.spec.ConfigSpecs;
import com.gamma.config.spec.Finding;
import com.gamma.config.spec.Severity;
import com.gamma.config.safety.ConfigSafetyValidator;
import com.gamma.config.safety.SafetyPolicy;
import com.gamma.etl.PipelineConfig;
import com.gamma.util.AtomicFiles;
import com.sun.net.httpserver.HttpExchange;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import static com.gamma.util.Values.mapAt;

/**
 * Pipeline authoring routes over an existing config file: save-as-template (v5.4.0), display-name
 * relabel (v5.4.0), and the pipeline-level {@code produces}/{@code reference} settings pair (D8,
 * pipeline-graph backlog). Extracted verbatim from {@code PipelineRoutes}: identical routes, order,
 * HTTP statuses and validation.
 */
final class PipelineSettingsRoutes implements RouteModule {

    private static final Logger log = LoggerFactory.getLogger(PipelineSettingsRoutes.class);

    @Override
    public void register(ApiContext api) {
        // Authoring, not operating: copying a pipeline into a template writes config and runs nothing.
        api.post("/pipelines/([^/]+)/save-as-template", ApiContext.withCapability("canAuthorWorkbench",
                (e, m) -> saveAsTemplate(api, e, ApiContext.name(m), api.body(e))));
        api.post("/pipelines/([^/]+)/label", ApiContext.withCapability("canAuthorWorkbench",
                (e, m) -> relabel(api, e, ApiContext.name(m), api.body(e))));
        // D8 (pipeline-graph backlog): the pipeline-level produces/reference block is opaque
        // passthrough to the graph editor (PipelineEditable never models it) — this pair of
        // routes is its own dedicated authoring surface, independent of PUT .../graph.
        api.get("/pipelines/([^/]+)/settings", (e, m) -> pipelineSettings(api, ApiContext.name(m)));
        api.post("/pipelines/([^/]+)/settings", ApiContext.withCapability("canAuthorWorkbench",
                (e, m) -> savePipelineSettings(api, e, ApiContext.name(m), api.body(e))));
    }

    /**
     * {@code POST /pipelines/{name}/label} — change a pipeline's <b>display name</b> while its identity
     * stays exactly where it is (v5.4.0). The cheap, safe half of "rename".
     *
     * <p>{@code ConfigSpecs.pipeline()} splits {@code name} (display) from {@code id} (stable identity), but
     * every config written so far omits {@code id}, so identity is <em>derived</em> from the name — and that
     * derived string is embedded in the config filename, the {@code <id>_commits.log}, the run-timestamped
     * audit CSVs {@code FileStatusStore.readRuns} globs by id prefix, the acquisition ledger's
     * {@code source_id} and the Catalog Stream. Editing {@code name} on such a config would silently move
     * all of it — which is why the parser warns never to re-derive identity from a changed name.
     *
     * <p>So this route <b>stamps {@code id} with today's derived value first</b>, pinning the identity, and
     * only then sets the new {@code name}. Nothing observable changes except the label: the file keeps its
     * {@code <id>_pipeline.toon} name, the dirs, audit trail, ledger keys and Stream are untouched, and no
     * dependent config needs rewriting. Stamping is idempotent — a config that already declares {@code id}
     * is relabelled without touching it.
     *
     * <p>Moving the identity itself (renaming the file, the audit trail and the ledger keys, and rewriting
     * every dependent reference) is the separate migration tracked in
     * {@code docs/superpower/pipeline-rename-and-template-plan.md} §3, deliberately not done here.
     *
     * <p>Fail-closed gate order: write-root 503 → unknown pipeline 404 → path jail 403 → missing
     * {@code name} 400 → spec + safety findings 422 → write atomically in place.
     */
    private Object relabel(ApiContext api, HttpExchange e, String source, Map<String, Object> body)
            throws IOException {
        Path writeRoot = WriteGates.requireWriteRoot(api, "pipeline write");
        Path srcPath = api.service().pathFor(source)
                .orElseThrow(() -> new ApiException(404, "no pipeline named '" + source + "'"));
        WriteGates.jail(writeRoot, srcPath, "config path");   // refuse a config outside the write root
        PipelineConfig live = api.service().configFor(source)
                .orElseThrow(() -> new ApiException(404, "no pipeline named '" + source + "'"));

        String raw = ApiContext.str(body, "name");
        if (raw == null || raw.isBlank())
            throw new ApiException(400, "body must include 'name' (the new display name)");
        String label = raw.trim();

        Map<String, Object> src = ConfigLoader.filesystem().decode(srcPath.toString());
        String id = live.identity().pipelineName();
        boolean stampedId = !(src.get("id") instanceof String s && !s.isBlank());

        // Rebuild with name + id first so the stamped identity reads as a header rather than a trailing key.
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("name", label);
        out.put("id", id);
        src.forEach((k, v) -> {
            if (!"name".equals(k) && !"id".equals(k)) out.put(k, v);
        });

        // The full gate still runs, but a relabel may only be blocked by findings it INTRODUCES. This route
        // rewrites no paths, and a config already on disk was never subjected to the write-time safety policy
        // — so re-punishing it here would make any pipeline whose data lives outside the default allowed
        // roots impossible to rename, which is most of them in a real deployment.
        List<Finding> findings = new ArrayList<>(ConfigLoader.filesystem().validate(ConfigSpecs.pipeline(), out));
        findings.addAll(ConfigSafetyValidator.check("pipeline", out, SafetyPolicy.defaultPolicy()));
        Set<String> preExisting = new HashSet<>();
        ConfigLoader.filesystem().validate(ConfigSpecs.pipeline(), src).forEach(f -> preExisting.add(PipelineSupport.findingKey(f)));
        ConfigSafetyValidator.check("pipeline", src, SafetyPolicy.defaultPolicy())
                .forEach(f -> preExisting.add(PipelineSupport.findingKey(f)));
        List<Finding> introduced = findings.stream()
                .filter(f -> f.severity() == Severity.ERROR)
                .filter(f -> !preExisting.contains(PipelineSupport.findingKey(f)))
                .toList();
        if (!introduced.isEmpty())
            return ApiContext.respondJson(e, 422, Map.of("written", false,
                    "error", "the new name introduces ERROR-level findings; not written",
                    "findings", introduced));

        byte[] bytes = ConfigCodec.toToon(out).getBytes(StandardCharsets.UTF_8);
        AtomicFiles.write(srcPath, bytes, ".cfg-");
        log.info("[PIPELINE-LABEL] pipeline '{}' relabelled to '{}'{}",
                id, label, stampedId ? " (identity stamped as id: " + id + ")" : "");

        Map<String, Object> r = new LinkedHashMap<>();
        r.put("written", true);
        r.put("path", writeRoot.relativize(srcPath).toString().replace('\\', '/'));
        r.put("id", id);
        r.put("name", label);
        r.put("stampedId", stampedId);
        r.put("findings", findings);
        return r;
    }

    /**
     * {@code GET /pipelines/{name}/settings} — the pipeline-level {@code produces}/{@code reference}
     * block, read straight off the config file rather than through {@link com.gamma.pipeline.PipelineEditable}, which
     * never models it (D8, pipeline-graph backlog). {@code produces} defaults to {@code "stream"}
     * (the parser's own default) so a config that omits the key round-trips as an explicit choice.
     */
    private Object pipelineSettings(ApiContext api, String name) throws IOException {
        Path srcPath = api.service().pathFor(name)
                .orElseThrow(() -> new ApiException(404, "no pipeline named '" + name + "'"));
        Map<String, Object> raw = ConfigLoader.filesystem().decode(srcPath.toString());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("produces", raw.getOrDefault("produces", "stream"));
        out.put("reference", raw.get("reference"));
        out.put("description", raw.get("description"));
        return out;
    }

    /**
     * {@code POST /pipelines/{name}/settings} — write the {@code produces}/{@code reference} block.
     * Mirrors {@link #relabel}'s gate order and "only ERROR findings the write itself introduces
     * block it" policy, since this route also edits an on-disk config that was never subjected to the
     * write-time safety gate. {@code reference}, if given, REPLACES the block wholesale — there is no
     * partial-field patch, so a caller wanting to keep {@code refresh_seconds} must resend it.
     */
    private Object savePipelineSettings(ApiContext api, HttpExchange e, String name, Map<String, Object> body)
            throws IOException {
        Path writeRoot = WriteGates.requireWriteRoot(api, "pipeline write");
        Path srcPath = api.service().pathFor(name)
                .orElseThrow(() -> new ApiException(404, "no pipeline named '" + name + "'"));
        WriteGates.jail(writeRoot, srcPath, "config path");

        Map<String, Object> src = ConfigLoader.filesystem().decode(srcPath.toString());
        Map<String, Object> out = new LinkedHashMap<>(src);
        if (body.containsKey("produces")) out.put("produces", body.get("produces"));
        if (body.containsKey("reference")) out.put("reference", body.get("reference"));
        // description was settable only at creation (the create dialog) — the settings surface is its
        // one post-creation home (display-only key; nothing in the engine reads it). Blank clears.
        if (body.containsKey("description")) {
            Object d = body.get("description");
            if (d == null || String.valueOf(d).isBlank()) out.remove("description");
            else out.put("description", String.valueOf(d).trim());
        }

        List<Finding> findings = new ArrayList<>(ConfigLoader.filesystem().validate(ConfigSpecs.pipeline(), out));
        findings.addAll(ConfigSafetyValidator.check("pipeline", out, SafetyPolicy.defaultPolicy()));
        Set<String> preExisting = new HashSet<>();
        ConfigLoader.filesystem().validate(ConfigSpecs.pipeline(), src).forEach(f -> preExisting.add(PipelineSupport.findingKey(f)));
        ConfigSafetyValidator.check("pipeline", src, SafetyPolicy.defaultPolicy())
                .forEach(f -> preExisting.add(PipelineSupport.findingKey(f)));
        List<Finding> introduced = findings.stream()
                .filter(f -> f.severity() == Severity.ERROR)
                .filter(f -> !preExisting.contains(PipelineSupport.findingKey(f)))
                .toList();
        if (!introduced.isEmpty())
            return ApiContext.respondJson(e, 422, Map.of("written", false,
                    "error", "the new settings introduce ERROR-level findings; not written",
                    "findings", introduced));

        byte[] bytes = ConfigCodec.toToon(out).getBytes(StandardCharsets.UTF_8);
        AtomicFiles.write(srcPath, bytes, ".cfg-");
        log.info("[PIPELINE-SETTINGS] pipeline '{}' produces/reference block updated", name);

        Map<String, Object> r = new LinkedHashMap<>();
        r.put("written", true);
        r.put("path", writeRoot.relativize(srcPath).toString().replace('\\', '/'));
        r.put("produces", out.getOrDefault("produces", "stream"));
        r.put("reference", out.get("reference"));
        r.put("findings", findings);
        return r;
    }

    /**
     * {@code POST /pipelines/{name}/save-as-template} — copy a pipeline into a non-runnable authoring
     * <b>template</b> (v5.4.0): a starting point for standing up a <em>similar</em> stream that cannot
     * touch the pipeline it was copied from, even if someone tries to run it.
     *
     * <p>Two halves make that true. The written config carries {@code template: true}, which
     * {@link com.gamma.service.CollectorService} refuses to run and {@code PipelineScheduler} skips; and
     * every <em>environment binding</em> is repointed by {@link #neutralizeForTemplate} into a
     * {@code templates/<id>/} sandbox, so even after the flag is cleared the copy reads its own inbox and
     * writes its own output, audit trail and ledger keys. Belt (the flag) and braces (the bindings) —
     * because the flag is what the operator clears when promoting, and at that moment the bindings are all
     * that stands between a fresh pipeline and the original's data.
     *
     * <p>Companion configs ({@code *_enrich.toon}, {@code *_job.toon}) and anything targeting the source
     * (expectations, alert rules, datasets) are deliberately <b>not</b> copied: a template describes one
     * pipeline, and wiring it up is an explicit act, not a side effect of copying.
     *
     * <p>Fail-closed gate order: write-root 503 → unknown source 404 → missing/invalid {@code id} 400/422
     * → id taken 409 → path jail 403 → file exists 409 → spec + safety findings 422 → write atomically.
     */
    private Object saveAsTemplate(ApiContext api, HttpExchange e, String source, Map<String, Object> body)
            throws IOException {
        Path writeRoot = WriteGates.requireWriteRoot(api, "pipeline write");
        Path srcPath = api.service().pathFor(source)
                .orElseThrow(() -> new ApiException(404, "no pipeline named '" + source + "'"));

        String rawId = ApiContext.str(body, "id");
        if (rawId == null || rawId.isBlank())
            throw new ApiException(400, "body must include 'id' (the new template's pipeline id)");
        String id = rawId.trim().toLowerCase();
        if (!id.matches("[a-z0-9][a-z0-9_]*"))
            throw new ApiException(422, "id '" + id
                    + "' must match [a-z0-9][a-z0-9_]* (lowercase letters, digits and underscores)");

        // The id must be free as a live pipeline AND on disk — either would collide at registration.
        WriteGates.conflictIf(api.service().pathFor(id).isPresent(),
                "pipeline id '" + id + "' is already registered");
        String fileName = WriteGates.safeName(id, "pipeline id") + "_pipeline.toon";
        Path target = WriteGates.jail(writeRoot, writeRoot.resolve(fileName), "resolved path");
        WriteGates.conflictIf(Files.exists(target), "file exists: " + fileName);

        String displayName = ApiContext.str(body, "name");
        List<String> notes = new ArrayList<>();
        Map<String, Object> tpl = neutralizeForTemplate(
                ConfigLoader.filesystem().decode(srcPath.toString()), id,
                (displayName == null || displayName.isBlank()) ? id : displayName.trim(),
                srcPath, writeRoot, notes);

        // The same gate POST /config/write runs — a template is still a real config and must be safe.
        List<Finding> findings = new ArrayList<>(ConfigLoader.filesystem().validate(ConfigSpecs.pipeline(), tpl));
        findings.addAll(ConfigSafetyValidator.check("pipeline", tpl, SafetyPolicy.defaultPolicy()));
        // W3: against the template's own directory — see saveGraph. `neutralizeForTemplate` copies the
        // schema next to the template and re-points `schema_file` at it, so it resolves exactly there.
        findings.addAll(ConfigRoutes.schemaFileFindings("pipeline", tpl, Severity.WARNING, target.getParent()));
        if (findings.stream().anyMatch(f -> f.severity() == Severity.ERROR))
            return ApiContext.respondJson(e, 422, Map.of("written", false,
                    "error", "config has ERROR-level findings; not written", "findings", findings));

        byte[] bytes = ConfigCodec.toToon(tpl).getBytes(StandardCharsets.UTF_8);
        AtomicFiles.write(target, bytes, ".tpl-");
        log.info("[PIPELINE-TEMPLATE] copied '{}' to template '{}' at {} ({} bytes)",
                source, id, fileName, bytes.length);

        // Register it so it shows up in GET /pipelines for editing/promotion. Safe: every run path
        // (trigger, reprocess, poll cycle) refuses a template, so being in the registry cannot run it.
        api.service().registerPipeline(target);

        Map<String, Object> r = new LinkedHashMap<>();
        r.put("written", true);
        r.put("path", writeRoot.relativize(target).toString().replace('\\', '/'));
        r.put("id", id);
        r.put("source", source);
        r.put("template", true);
        r.put("notes", notes);
        r.put("findings", findings);
        return r;
    }

    /**
     * Build the template config from {@code src}: a verbatim copy with its identity replaced and every
     * <b>environment binding</b> repointed into a {@code templates/<id>/} sandbox.
     *
     * <p>Repointed (each one is a way a naive copy would collide with the original): {@code dirs.*} —
     * chiefly {@code poll}, whose reuse would make two pipelines race for the same inbox, and
     * {@code database}, which also determines where the audit CSVs and the persistent
     * {@code <id>_commits.log} land · {@code stream}, which defaults to the pipeline id and would
     * otherwise silently join the source's Catalog Stream · the collector's {@code id}, which is the
     * acquisition ledger's {@code source_id} and therefore the dedup + watermark key · the DuckLake
     * {@code data_path} · and {@code processing.schema_file}, copied so editing the template's schema
     * cannot edit the source's.
     *
     * <p>Carried verbatim: everything else — parsing/CSV settings, threads, dedup policy, output format,
     * connector/discovery/post-action, {@code produces}/{@code reference}, and {@code trigger} (inert
     * while the config is a template, and the operator's intent worth preserving).
     */
    private static Map<String, Object> neutralizeForTemplate(Map<String, Object> src, String id,
                                                             String displayName, Path srcPath,
                                                             Path writeRoot, List<String> notes)
            throws IOException {
        Map<String, Object> t = new LinkedHashMap<>(src);
        String sandbox = "templates/" + id;

        t.put("name", displayName);
        t.put("id", id);            // explicit identity, so a later display-name edit is a relabel
        t.put("template", true);
        t.put("active", false);     // `template: true` + `active: true` is refused by the parser
        t.put("stream", id);        // never inherit the source's Catalog Stream

        // dirs: every path the engine reads or writes moves into the sandbox. The well-known keys get
        // their conventional leaf name; any other key present is mapped under the sandbox by its own name
        // so a key added later still lands inside it rather than being left pointing at the source.
        Map<String, String> leaf = Map.of("poll", "inbox", "status_dir", "status", "log_dir", "logs");
        Map<String, Object> dirs = new LinkedHashMap<>();
        if (src.get("dirs") instanceof Map<?, ?> sd) {
            for (Map.Entry<?, ?> en : sd.entrySet()) {
                String k = String.valueOf(en.getKey());
                // a literal status CSV path, not a directory — keep it a file path inside the sandbox
                dirs.put(k, "status_file".equals(k)
                        ? sandbox + "/status/" + id + "_status.csv"
                        : sandbox + "/" + leaf.getOrDefault(k, k));
            }
        }
        dirs.putIfAbsent("poll", sandbox + "/inbox");         // spec-required
        dirs.putIfAbsent("database", sandbox + "/database");  // spec-required
        t.put("dirs", dirs);

        // The collector's id is the acquisition ledger's source_id; `source:` is the legacy spelling.
        String colKey = src.containsKey("collector") || !src.containsKey("source") ? "collector" : "source";
        if (src.get(colKey) instanceof Map<?, ?>) {
            Map<String, Object> col = new LinkedHashMap<>(mapAt(src, colKey));
            col.put("id", id);
            t.put(colKey, col);
        } else {
            t.put(colKey, new LinkedHashMap<>(Map.of("id", id)));
        }

        if (src.get("output") instanceof Map<?, ?>) {
            Map<String, Object> out = new LinkedHashMap<>(mapAt(src, "output"));
            if (out.get("ducklake") instanceof Map<?, ?>) {
                Map<String, Object> lake = new LinkedHashMap<>(mapAt(out, "ducklake"));
                if (lake.containsKey("data_path")) lake.put("data_path", sandbox + "/ducklake");
                out.put("ducklake", lake);
            }
            t.put("output", out);
        }

        copySchemaFile(src, t, id, srcPath, writeRoot, notes);
        return t;
    }

    /**
     * Copy the source's {@code processing.schema_file} next to the template and repoint at the copy, so the
     * two configs never share a schema an operator might edit. A relative reference resolves against the
     * source config's own directory first and the working directory second — the same order
     * {@link PipelineConfig#load} uses. When it cannot be resolved or read the original value is left
     * alone (harmless: the parser reads it, never writes it) and a note explains why.
     */
    private static void copySchemaFile(Map<String, Object> src, Map<String, Object> t, String id,
                                       Path srcPath, Path writeRoot, List<String> notes) throws IOException {
        if (!(src.get("processing") instanceof Map<?, ?>)) return;
        Map<String, Object> processing = new LinkedHashMap<>(mapAt(src, "processing"));
        Object ref = processing.get("schema_file");
        if (ref == null || String.valueOf(ref).isBlank()) return;   // inline schemas / segments: nothing to copy

        Path from = Path.of(String.valueOf(ref));
        if (!from.isAbsolute()) {
            Path here = srcPath.toAbsolutePath().getParent();
            Path beside = here == null ? null : here.resolve(from);
            from = (beside != null && Files.isReadable(beside)) ? beside : from.toAbsolutePath();
        }
        if (!Files.isReadable(from)) {
            notes.add("schema_file '" + ref + "' could not be read, so it still points at the source's schema"
                    + " — repoint it before editing the schema");
            return;
        }
        Path schemaTarget = WriteGates.jail(writeRoot, writeRoot.resolve(id + "_schema.toon"), "schema path");
        if (Files.exists(schemaTarget)) {
            notes.add("schema_file left as '" + ref + "': " + schemaTarget.getFileName() + " already exists");
            return;
        }
        AtomicFiles.write(schemaTarget, Files.readAllBytes(from), ".sch-");
        processing.put("schema_file", writeRoot.relativize(schemaTarget).toString().replace('\\', '/'));
        t.put("processing", processing);
        notes.add("copied the schema to " + schemaTarget.getFileName());
    }
}
