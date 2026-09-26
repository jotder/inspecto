package com.gamma.control;

import com.gamma.acquire.ConnectionProfile;
import com.gamma.config.io.ConfigCodec;
import com.gamma.util.ToonHelper;
import com.gamma.config.io.ConfigLoader;
import com.gamma.config.safety.ConfigSafetyValidator;
import com.gamma.config.safety.SafetyPolicy;
import com.gamma.config.spec.ConfigSpecs;
import com.gamma.config.spec.Finding;
import com.gamma.config.spec.FindingCodes;
import com.gamma.config.spec.Severity;
import com.gamma.event.EventLog;
import com.gamma.service.BundleExporter;
import com.gamma.service.BundleImporter;
import com.gamma.service.DataSourceBundle;
import com.gamma.service.DataSourceBundleResolver;
import com.gamma.service.ImportJournal;
import com.gamma.service.PipelineView;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * Per-space data-source bundle endpoints — config/metadata export (Stage 6). Reached through the
 * {@code /spaces/{id}/} request seam, so the handlers act on the bound space ({@code api.service()} +
 * {@code api.writeRoot()} = that space's {@code config/} dir):
 * <pre>
 *   GET  /spaces/{id}/datasources                 list the space's data-source ids (pipeline names)  [v4.8.0]
 *   GET  /spaces/{id}/datasources/{ds}/export     download one data source's bundle as a zip          [v4.8.0]
 *   GET  /spaces/{id}/export                       download the whole space (config tree + space.toon) [v4.8.0]
 *   POST /spaces/{id}/import[?on_conflict=overwrite]  unpack a bundle zip into this space's config/    [v4.8.0]
 *   POST /spaces/{id}/import/preview               dry-run: what a bundle contains + conflicts + findings [v4.8.0]
 * </pre>
 *
 * <p>A bundle zip is config + metadata only (no ingested data — that is roadmap): the relevant TOON files
 * under a config-relative path plus a {@code bundle.toon} manifest, built by {@link BundleExporter}. All
 * require filesystem access (a write root); without one they {@code 503}.
 *
 * <p><b>Import</b> unpacks into {@code config/} (jailed against zip-slip), then makes the new configs live
 * immediately by re-registering pipelines + connections; it {@code 409}s on data-source id clashes unless
 * {@code ?on_conflict=overwrite}. Edited (overwritten) configs reload on the next poll cycle, as with
 * {@code /config/write}; imported jobs/metadata take effect on the next restart.
 */
final class DataSourceRoutes implements RouteModule {

    @Override
    public void register(ApiContext api) {
        api.get("/datasources", (e, m) -> resolver(api).dataSourceIds());
        api.get("/datasources/([^/]+)/export", (e, m) -> exportDataSource(api, e, ApiContext.name(m)));
        api.get("/export", (e, m) -> exportSpace(api, e));
        // Import writes real config AND hot-registers what it unpacked (importBundle: writeConfig, then
        // registerConnection/registerPipeline) — the same act its two siblings already gate, /bundle/import
        // and /pipelines/import. It was the one of the three left open (ROUTE-UNGATED-DEFAULT-1, grounded
        // 2026-09-15: no comment defended its openness and no test exercised it at all).
        api.post("/import", ApiContext.withCapability("canAuthorWorkbench", (e, m) -> importBundle(api, e)));
        api.post("/import/preview", (e, m) -> previewImport(api, e));
    }

    /**
     * Dry-run an import: report what the bundle contains (kind, data sources, files), which data-source ids
     * would clash with this space, and the validation findings for each pipeline — writing nothing. Backs the
     * bulk-onboarding "preview before commit" step; pipelines are validated with the same spec + safety checks
     * as {@code /validate}, plus the <b>connection</b> half of the import gate (a connection is checked by id,
     * which needs no filesystem, so preview and commit agree about a missing one).
     *
     * <p>⚠ {@code valid: true} is <b>not</b> the full commit gate. Commit additionally checks that every
     * schema/grammar reference <b>exists</b>, and that cannot be answered here: a schema reference resolves
     * relative to the config file naming it, so it only becomes answerable once the files are written. So a
     * bundle can preview clean and still be rejected at commit for an unresolvable schema path. Narrowing
     * that gap means resolving references against the zip's own entry list, which is worth doing but is not
     * what this does today — do not "simplify" the two into one by dropping the commit-side check.
     *
     * <p>⚠ <b>Containment is NOT part of that gap</b> — it needs the allowed roots, not the filesystem, so
     * both sides run {@link ConfigSafetyValidator} and agree about a ref that escapes. Until 2026-08-14
     * neither side ran it: the sentence above about "the same spec + safety checks" described an intent, not
     * the code, and an escaping ref reached {@code registerPipeline} to be refused one file at a time.
     */
    private Object previewImport(ApiContext api, HttpExchange e) throws IOException {
        Path config = requireConfig(api);
        BundleImporter.Bundle bundle;
        try {
            bundle = BundleImporter.parse(e.getRequestBody().readAllBytes());
        } catch (IllegalArgumentException bad) {
            throw new ApiException(400, ErrorCodes.MALFORMED_REQUEST, bad.getMessage());
        }

        Set<String> existing = api.service().pipelines().stream()
                .map(PipelineView::name).collect(Collectors.toSet());
        BundleImporter.Narrowed narrowed = BundleImporter.keepExistingReferences(bundle, existing);
        bundle = narrowed.bundle();
        List<String> dataSources = BundleImporter.pipelineIds(bundle);
        List<String> conflicts = dataSources.stream().filter(existing::contains).sorted().toList();

        // The connection half, evaluated from the zip bytes exactly as commit evaluates it, so preview names
        // the same missing connections and the same configs the commit will land disabled. A WARNING, not an
        // ERROR: since 2026-09-25 a missing connection disables its dependents rather than refusing the import.
        List<Map<String, Object>> connectionWarnings = BundleImporter.disableForMissingConnections(
                bundle, api.service().connections().keySet()).warnings();
        Map<String, Set<String>> missingByFile = missingConnectionsByFile(connectionWarnings);

        Map<String, List<Finding>> findings = new LinkedHashMap<>();
        boolean valid = true;
        for (Map.Entry<String, byte[]> entry : bundle.configEntries().entrySet()) {
            if (!entry.getKey().endsWith("_pipeline.toon")) continue;
            // judged from where the entry would land, so its data paths resolve under this Space
            List<Finding> fs = new ArrayList<>(validatePipeline(entry.getValue(),
                    config.resolve(entry.getKey()).getParent()));
            for (String conn : missingByFile.getOrDefault(entry.getKey(), Set.of()))
                fs.add(new Finding(Severity.WARNING, "connection",
                        "unknown connection '" + conn + "' — it is neither in this space nor in the bundle;"
                                + " the pipeline imports disabled — connect " + conn + " to enable",
                        FindingCodes.WARN_UNRESOLVED_CONNECTION,
                        "register the connection, then activate the pipeline"));
            if (!fs.isEmpty()) findings.put(entry.getKey(), fs);
            if (fs.stream().anyMatch(f -> f.severity() == Severity.ERROR)) valid = false;
        }

        Map<String, Object> r = new LinkedHashMap<>();
        r.put("kind", bundle.kind());
        r.put("sourceSpace", bundle.manifest().get("source_space"));
        r.put("dataSources", dataSources);
        r.put("files", new TreeSet<>(bundle.configEntries().keySet()));
        r.put("hasSpaceToon", bundle.spaceToon() != null);
        r.put("conflicts", conflicts);
        r.put("referencesKept", narrowed.referencesKept());
        r.put("findings", findings);
        r.put("connectionWarnings", connectionWarnings);
        r.put("valid", valid);
        return r;
    }

    /**
     * Structural-spec findings for one pipeline TOON (a parse failure is itself an ERROR finding). Mirrors the
     * default {@code /validate}: spec validation only — the path-jail safety gate is a deploy-environment
     * concern (paths in a bundle belong to the source space) and is opt-in there, so it is not applied here.
     */
    private static List<Finding> validatePipeline(byte[] toon, Path configDir) {
        Map<String, Object> map;
        try {
            map = ConfigCodec.toMap(new String(toon, StandardCharsets.UTF_8));
        } catch (RuntimeException parseErr) {
            return List.of(new Finding(Severity.ERROR, "(parse)", "cannot parse pipeline: " + parseErr.getMessage()));
        }
        List<Finding> fs = new ArrayList<>(ConfigLoader.filesystem().validate(ConfigSpecs.pipeline(), map));
        // The safety half, which this route's own doc claimed it already had. Containment IS answerable
        // here — jailing a path value needs the roots, not the filesystem — so preview and commit can and
        // must agree about an escaping schema_file/grammar/mapping_file. Only *existence* is unanswerable
        // before the files land.
        fs.addAll(ConfigSafetyValidator.check("pipeline", map, SafetyPolicy.defaultPolicy(), configDir));
        return fs;
    }

    /**
     * ERROR findings per bundle file for references that would not resolve in this space — the import-time
     * referential-integrity gate. Empty means the bundle is self-consistent against the target.
     *
     * <p>Runs on the <b>written</b> files rather than the zip entries, deliberately: a schema reference
     * resolves relative to the config file that names it, so the only honest way to ask "does this resolve?"
     * is to ask it where the file actually lands. That also lets this reuse
     * {@link ConfigRoutes#schemaFileFindings} — which already mirrors {@code PipelineConfigParser
     * .resolveSchemaRef} — instead of re-deriving path resolution here. Re-deriving it is exactly the trap
     * W1b hit: a validator that predicts resolution differently from the reader rejects configs the engine
     * would run, or passes ones it would not.
     *
     * <p>⚠ That reuse answers <b>existence</b> only. Containment is a separate question and comes from
     * {@link ConfigSafetyValidator}, which is why both are called here — see the note at the call site.
     *
     * <p>⚠ A <b>connection</b> is deliberately NOT part of this gate any more (operator, 2026-09-25): a missing
     * one is handled before the write by {@link BundleImporter#disableForMissingConnections}, which lands every
     * config that needs it disabled and returns a warning, instead of refusing the whole bundle.
     */
    private static Map<String, List<Finding>> referentialFindings(Path config, List<String> written) {
        Map<String, List<Finding>> out = new LinkedHashMap<>();
        for (String rel : written) {
            if (!rel.endsWith("_pipeline.toon")) continue;
            Path file = config.resolve(rel);
            Map<String, Object> map;
            try {
                map = ToonHelper.load(file.toString());
            } catch (RuntimeException | IOException bad) {
                out.put(rel, List.of(new Finding(Severity.ERROR, "(parse)", "cannot parse pipeline: " + bad)));
                continue;
            }
            List<Finding> fs = new ArrayList<>(
                    ConfigRoutes.schemaFileFindings("pipeline", map, Severity.ERROR, file.getParent()));
            // ⚠ schemaFileFindings answers "does this ref EXIST", never "is it contained" — the two are
            // separate questions and only the validator asks the second. Every other pipeline gate pairs
            // them (ConfigRoutes:113/137/330, PipelineRoutes:380, RunRoutes:261); this one did not, so a
            // bundle whose schema_file/grammar/mapping_file escapes the allowed roots passed here and was
            // then refused one file at a time by registerPipeline below — the exact mid-walk partial
            // registration this whole gate exists to prevent.
            fs.addAll(ConfigSafetyValidator.check("pipeline", map, SafetyPolicy.defaultPolicy(), file.getParent()));
            if (!fs.isEmpty()) out.put(rel, fs);
        }
        return out;
    }

    /** The missing connection ids each disabled file needs, inverted from the {@code connectionWarnings}. */
    private static Map<String, Set<String>> missingConnectionsByFile(List<Map<String, Object>> warnings) {
        Map<String, Set<String>> out = new LinkedHashMap<>();
        for (Map<String, Object> w : warnings)
            for (Object d : (List<?>) w.get("disabled"))
                out.computeIfAbsent(String.valueOf(((Map<?, ?>) d).get("file")), k -> new TreeSet<>())
                        .add(String.valueOf(w.get("connection")));
        return out;
    }

    /** Unpack a bundle zip into the bound space's {@code config/} and make the new configs live. */
    private Object importBundle(ApiContext api, HttpExchange e) throws IOException {
        Path config = requireConfig(api);
        boolean overwrite = "overwrite".equalsIgnoreCase(ApiContext.query(e, "on_conflict"));

        BundleImporter.Bundle bundle;
        try {
            bundle = BundleImporter.parse(e.getRequestBody().readAllBytes());
        } catch (IllegalArgumentException bad) {
            throw new ApiException(400, ErrorCodes.MALFORMED_REQUEST, bad.getMessage());
        }
        // SEC-IMPORT-ROLES-ESCALATION-1: an import may never write the files a narrower gate owns — the role
        // table, the Demo User table, the access config, the assist agent's policy, the settings documents.
        // Before this, a zip carrying roles.toon rewrote the role table with only canAuthorWorkbench. Judged by
        // ImportPaths — segment rules + a shape ALLOWLIST + ReservedConfigPaths + the real path — every entry
        // before anything is written (Windows aliases like "roles.toon." / "PENDIN~1/…" walk around a string list).
        List<String> refused = com.gamma.service.ImportPaths.refusals(config, bundle.configEntries());
        if (!refused.isEmpty())
            throw new ApiException(403, ErrorCodes.PERMISSION_DENIED, "an import may not write " + refused);

        // Conflict = a bundle pipeline id that already exists in this space's registry.
        Set<String> existing = api.service().pipelines().stream()
                .map(PipelineView::name).collect(Collectors.toSet());
        // A carried Reference the target already hosts is the target's to keep, not a clash (W5 forward).
        List<String> referencesKept = List.of();
        if (!overwrite) {
            BundleImporter.Narrowed narrowed = BundleImporter.keepExistingReferences(bundle, existing);
            bundle = narrowed.bundle();
            referencesKept = narrowed.referencesKept();
        }
        List<String> conflicts = BundleImporter.pipelineIds(bundle).stream()
                .filter(existing::contains).sorted().toList();
        if (!conflicts.isEmpty() && !overwrite)
            return ApiContext.respondJson(e, 409, Map.of(
                    "error", "data-source id(s) already exist; re-send with ?on_conflict=overwrite to replace",
                    "conflicts", conflicts));

        // A connection neither this space nor the bundle has WARNS, it does not refuse (operator, 2026-09-25):
        // every config that needs it is written switched off by its own switch, and the response names each
        // missing connection with the configs it disabled — "connect X to enable".
        BundleImporter.MissingConnections missing = BundleImporter.disableForMissingConnections(
                bundle, api.service().connections().keySet());
        bundle = missing.bundle();

        // The registration pre-check that CAN run before a write: a bundle pipeline whose id is registered from a
        // DIFFERENT file would 409 in registerPipeline after the whole bundle had landed.
        for (Map.Entry<String, byte[]> en : bundle.configEntries().entrySet()) {
            if (!en.getKey().endsWith("_pipeline.toon")) continue;
            Object name;
            try {
                name = ConfigCodec.toMap(new String(en.getValue(), StandardCharsets.UTF_8)).get("name");
            } catch (RuntimeException unparseable) {
                continue;   // registration names what is wrong — and the journal undoes the write
            }
            if (name == null) continue;
            Path lands = config.resolve(en.getKey()).toAbsolutePath().normalize();
            api.service().pathFor(name.toString().toLowerCase()).map(p -> p.toAbsolutePath().normalize())
                    .filter(p -> !p.equals(lands)).ifPresent(p -> {
                        throw new ApiException(409, ErrorCodes.CONFLICT, "pipeline id '" + name
                                + "' is already registered from " + p + "; nothing was written");
                    });
        }

        // All-or-nothing: every write goes through the journal, and every refusal below — a 422 on a reference, a
        // registration that fails — puts the tree back byte-for-byte and forgets what this import registered.
        ImportJournal journal = new ImportJournal();
        List<Path> registeredHere = new ArrayList<>();
        Map<String, java.util.Optional<ConnectionProfile>> connectionsBefore = new LinkedHashMap<>();
        boolean done = false;
        try {
            BundleImporter.Unpacked unpacked;
            try {
                unpacked = BundleImporter.writeConfig(bundle, config, true, journal);
            } catch (IllegalArgumentException jail) {
                throw new ApiException(400, ErrorCodes.MALFORMED_REQUEST, jail.getMessage());
            }
            List<String> written = unpacked.paths();

            // Referential integrity BEFORE anything goes live: a bundle whose pipeline names a schema file nobody
            // has (or one escaping the allowed roots) is rejected as a whole, listing every problem at once. Without
            // this the registration loop below discovered the same breakage one file at a time and threw mid-walk,
            // leaving some pipelines live and the rest not — and it could only ever report the first fault.
            Map<String, List<Finding>> broken = referentialFindings(config, written);
            if (!broken.isEmpty()) {
                undo(api, journal, registeredHere, connectionsBefore);   // before the response: the tree is whole
                done = true;
                return ApiContext.respondJson(e, 422, Map.of(
                        "error", "bundle references things this space does not have; nothing was registered",
                        "findings", broken));
            }

            List<String> pipelines = new ArrayList<>();
            // Connections FIRST, in two passes. Registration used to follow `written` (i.e. manifest) order, so a
            // pipeline could register before the connection it binds — a 422 that says "unknown connection" about
            // a connection sitting in the very same bundle, decided by zip entry order.
            for (String rel : written) {
                if (!rel.endsWith("_connection.toon")) continue;
                ConnectionProfile profile = ConnectionProfile.load(config.resolve(rel));
                connectionsBefore.putIfAbsent(profile.id(), api.service().connection(profile.id()));
                api.service().registerConnection(profile);
            }
            Set<Path> registeredBefore = api.service().pipelines().stream()
                    .flatMap(v -> api.service().pathFor(v.name()).stream())
                    .map(p -> p.toAbsolutePath().normalize()).collect(Collectors.toSet());
            for (String rel : written) {
                if (!rel.endsWith("_pipeline.toon")) continue;
                Path file = config.resolve(rel).toAbsolutePath().normalize();
                try {
                    pipelines.add(api.service().registerPipeline(file));
                    if (!registeredBefore.contains(file)) registeredHere.add(file);
                } catch (IllegalArgumentException invalid) {
                    throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "invalid pipeline " + rel + ": " + invalid.getMessage());
                } catch (IllegalStateException clash) {
                    throw new ApiException(409, ErrorCodes.CONFLICT, clash.getMessage());
                }
            }
            done = true;
            return importedBody(bundle, written, pipelines, referencesKept, overwrite && !conflicts.isEmpty(), missing);
        } finally {
            if (!done) undo(api, journal, registeredHere, connectionsBefore);
        }
    }

    /**
     * Undo a refused import: unregister the pipelines it registered, put back the connections it replaced, restore
     * every file it wrote ({@link ImportJournal#rollback}) and re-read the registered configs from the restored tree.
     */
    private static void undo(ApiContext api, ImportJournal journal, List<Path> registeredHere,
                             Map<String, java.util.Optional<ConnectionProfile>> connectionsBefore) throws IOException {
        for (Path p : registeredHere) api.service().unregisterPipeline(p);
        connectionsBefore.forEach((id, before) -> {
            if (before.isPresent()) api.service().registerConnection(before.get());
            else api.service().unregisterConnection(id);
        });
        journal.rollback();
        api.service().refreshConfigs();
    }

    private static Map<String, Object> importedBody(BundleImporter.Bundle bundle, List<String> written,
                                                    List<String> pipelines, List<String> referencesKept,
                                                    boolean overwritten, BundleImporter.MissingConnections missing) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("kind", bundle.kind());
        body.put("imported", written);
        body.put("pipelines", pipelines);
        body.put("referencesKept", referencesKept);
        body.put("overwritten", overwritten);
        body.put("connectionWarnings", missing.warnings());
        return body;
    }

    private Object exportDataSource(ApiContext api, HttpExchange e, String ds) throws IOException {
        Path config = requireConfig(api);
        DataSourceBundle bundle;
        try {
            bundle = new DataSourceBundleResolver(api.service(), config).resolve(ds);
        } catch (NoSuchElementException notFound) {
            throw new ApiException(404, ErrorCodes.NOT_FOUND, notFound.getMessage());
        }
        return download(e, BundleExporter.exportDataSource(bundle, config, EventLog.currentSpaceId()),
                ds + ".bundle.zip");
    }

    private Object exportSpace(ApiContext api, HttpExchange e) throws IOException {
        Path config = requireConfig(api);
        Path spaceToon = config.getParent() == null ? null : config.getParent().resolve("space.toon");
        String space = EventLog.currentSpaceId();
        return download(e, BundleExporter.exportSpace(config, spaceToon, space), space + ".space.zip");
    }

    private static DataSourceBundleResolver resolver(ApiContext api) {
        return new DataSourceBundleResolver(api.service(), requireConfig(api));
    }

    /** The bound space's config dir, or {@code 503} when filesystem writes are disabled (no write root). */
    private static Path requireConfig(ApiContext api) {
        Path config = api.writeRoot();
        if (config == null) throw new ApiException(503, ErrorCodes.CONTROL_PLANE_READ_ONLY, "filesystem access is disabled (no write root configured)");
        return config;
    }

    /** Write {@code zip} as an {@code application/zip} attachment download; returns {@link ApiContext#HANDLED}. */
    private static Object download(HttpExchange e, byte[] zip, String filename) throws IOException {
        e.getResponseHeaders().set("Content-Type", "application/zip");
        e.getResponseHeaders().set("Content-Disposition", "attachment; filename=\"" + filename + "\"");
        e.sendResponseHeaders(200, zip.length);
        e.getResponseBody().write(zip);
        return ApiContext.HANDLED;
    }
}
