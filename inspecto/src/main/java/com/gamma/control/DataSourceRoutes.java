package com.gamma.control;

import com.gamma.acquire.ConnectionProfile;
import com.gamma.config.io.ConfigCodec;
import com.gamma.config.io.ConfigLoader;
import com.gamma.config.spec.ConfigSpecs;
import com.gamma.config.spec.Finding;
import com.gamma.config.spec.Severity;
import com.gamma.event.EventLog;
import com.gamma.service.BundleExporter;
import com.gamma.service.BundleImporter;
import com.gamma.service.DataSourceBundle;
import com.gamma.service.DataSourceBundleResolver;
import com.gamma.service.CollectorService;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
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
        api.post("/import", (e, m) -> importBundle(api, e));
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
     * schema/grammar reference resolves, and that cannot be answered here: a schema reference resolves
     * relative to the config file naming it, so it only becomes answerable once the files are written. So a
     * bundle can preview clean and still be rejected at commit for an unresolvable schema path. Narrowing
     * that gap means resolving references against the zip's own entry list, which is worth doing but is not
     * what this does today — do not "simplify" the two into one by dropping the commit-side check.
     */
    private Object previewImport(ApiContext api, HttpExchange e) throws IOException {
        Path config = requireConfig(api);
        BundleImporter.Bundle bundle;
        try {
            bundle = BundleImporter.parse(e.getRequestBody().readAllBytes());
        } catch (IllegalArgumentException bad) {
            throw new ApiException(400, bad.getMessage());
        }

        List<String> dataSources = BundleImporter.pipelineIds(bundle);
        Set<String> existing = api.service().pipelines().stream()
                .map(CollectorService.PipelineView::name).collect(Collectors.toSet());
        List<String> conflicts = dataSources.stream().filter(existing::contains).sorted().toList();

        // The connection half of the import gate, evaluated from the zip bytes so preview agrees with commit
        // about a missing connection rather than reporting `valid: true` on a bundle the commit then 422s.
        Set<String> knownConnections = new TreeSet<>(api.service().connections().keySet());
        bundle.configEntries().forEach((name, bytes) -> {
            if (!name.endsWith("_connection.toon")) return;
            String id = connectionIdOf(bytes);
            if (id != null) knownConnections.add(id);
        });

        Map<String, List<Finding>> findings = new LinkedHashMap<>();
        boolean valid = true;
        for (Map.Entry<String, byte[]> entry : bundle.configEntries().entrySet()) {
            if (!entry.getKey().endsWith("_pipeline.toon")) continue;
            List<Finding> fs = new ArrayList<>(validatePipeline(entry.getValue()));
            String conn = connectionRefOf(entry.getValue());
            if (conn != null && !knownConnections.contains(conn))
                fs.add(new Finding(Severity.ERROR, "collector.connection",
                        "unknown connection '" + conn + "' — it is neither in this space nor in the bundle"));
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
        // What the commit would rewrite, shown before the operator commits to it.
        r.put("rebased", BundleImporter.rebaseTargets(bundle, config));
        r.put("findings", findings);
        r.put("valid", valid);
        return r;
    }

    /**
     * Structural-spec findings for one pipeline TOON (a parse failure is itself an ERROR finding). Mirrors the
     * default {@code /validate}: spec validation only — the path-jail safety gate is a deploy-environment
     * concern (paths in a bundle belong to the source space) and is opt-in there, so it is not applied here.
     */
    private static List<Finding> validatePipeline(byte[] toon) {
        Map<String, Object> map;
        try {
            map = ConfigCodec.toMap(new String(toon, StandardCharsets.UTF_8));
        } catch (RuntimeException parseErr) {
            return List.of(new Finding(Severity.ERROR, "(parse)", "cannot parse pipeline: " + parseErr.getMessage()));
        }
        return ConfigLoader.filesystem().validate(ConfigSpecs.pipeline(), map);
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
     * <p>A connection is checked by <b>id</b>, against the union of this space's registry and the ids the
     * bundle itself carries — a bundle that brings its own connection is complete, even though that
     * connection is not registered yet at this point.
     */
    private static Map<String, List<Finding>> referentialFindings(ApiContext api, Path config, List<String> written) {
        Set<String> knownConnections = new TreeSet<>(api.service().connections().keySet());
        for (String rel : written) {
            if (!rel.endsWith("_connection.toon")) continue;
            try {
                knownConnections.add(ConnectionProfile.load(config.resolve(rel)).id());
            } catch (RuntimeException | IOException ignored) {
                // A connection file that will not load is reported against the pipeline that needs it
                // (below) rather than here: "pipeline X wants connection Y" is the actionable message.
            }
        }

        Map<String, List<Finding>> out = new LinkedHashMap<>();
        for (String rel : written) {
            if (!rel.endsWith("_pipeline.toon")) continue;
            Path file = config.resolve(rel);
            Map<String, Object> map;
            try {
                map = ConfigCodec.toMap(Files.readString(file));
            } catch (RuntimeException | IOException bad) {
                out.put(rel, List.of(new Finding(Severity.ERROR, "(parse)", "cannot parse pipeline: " + bad)));
                continue;
            }
            List<Finding> fs = new ArrayList<>(
                    ConfigRoutes.schemaFileFindings("pipeline", map, Severity.ERROR, file.getParent()));
            String conn = connectionRef(map);
            if (conn != null && !knownConnections.contains(conn))
                fs.add(new Finding(Severity.ERROR, "collector.connection",
                        "unknown connection '" + conn + "' — it is neither in this space nor in the bundle;"
                                + " import the connection first or add it to the bundle"));
            if (!fs.isEmpty()) out.put(rel, fs);
        }
        return out;
    }

    /** {@link #connectionRef} over raw TOON bytes (preview works from the zip, not from disk). */
    private static String connectionRefOf(byte[] toon) {
        try {
            return connectionRef(ConfigCodec.toMap(new String(toon, StandardCharsets.UTF_8)));
        } catch (RuntimeException bad) {
            return null;   // a parse failure is already reported as its own ERROR by validatePipeline
        }
    }

    /** The in-file id of a {@code *_connection.toon} from its bytes, or {@code null} if it will not parse. */
    private static String connectionIdOf(byte[] toon) {
        try {
            Map<String, Object> doc = ConfigCodec.toMap(new String(toon, StandardCharsets.UTF_8));
            Object block = doc.get("connection");
            Object id = (block instanceof Map<?, ?> m ? m : doc).get("id");
            return id == null || String.valueOf(id).isBlank() ? null : String.valueOf(id).trim();
        } catch (RuntimeException bad) {
            return null;
        }
    }

    /** A pipeline's bound connection id ({@code collector.connection}), or {@code null} for a local source. */
    private static String connectionRef(Map<String, Object> pipeline) {
        if (!(pipeline.get("collector") instanceof Map<?, ?> collector)) return null;
        Object id = collector.get("connection");
        if (id == null) return null;
        String s = String.valueOf(id).trim();
        return s.isEmpty() ? null : s;
    }

    /** Unpack a bundle zip into the bound space's {@code config/} and make the new configs live. */
    private Object importBundle(ApiContext api, HttpExchange e) throws IOException {
        Path config = requireConfig(api);
        boolean overwrite = "overwrite".equalsIgnoreCase(ApiContext.query(e, "on_conflict"));

        BundleImporter.Bundle bundle;
        try {
            bundle = BundleImporter.parse(e.getRequestBody().readAllBytes());
        } catch (IllegalArgumentException bad) {
            throw new ApiException(400, bad.getMessage());
        }

        // Conflict = a bundle pipeline id that already exists in this space's registry.
        Set<String> existing = api.service().pipelines().stream()
                .map(CollectorService.PipelineView::name).collect(Collectors.toSet());
        List<String> conflicts = BundleImporter.pipelineIds(bundle).stream()
                .filter(existing::contains).sorted().toList();
        if (!conflicts.isEmpty() && !overwrite)
            return ApiContext.respondJson(e, 409, Map.of(
                    "error", "data-source id(s) already exist; re-send with ?on_conflict=overwrite to replace",
                    "conflicts", conflicts));

        BundleImporter.Unpacked unpacked;
        try {
            unpacked = BundleImporter.writeConfig(bundle, config);
        } catch (IllegalArgumentException jail) {
            throw new ApiException(400, jail.getMessage());
        }
        List<String> written = unpacked.paths();

        // Referential integrity BEFORE anything goes live: a bundle whose pipeline names a connection or a
        // schema file nobody has is rejected as a whole, listing every problem at once. Without this the
        // registration loop below discovered the same breakage one file at a time and threw mid-walk,
        // leaving some pipelines live and the rest not — and it could only ever report the first fault.
        Map<String, List<Finding>> broken = referentialFindings(api, config, written);
        if (!broken.isEmpty())
            return ApiContext.respondJson(e, 422, Map.of(
                    "error", "bundle references things this space does not have; nothing was registered",
                    "findings", broken));

        List<String> pipelines = new ArrayList<>();
        // Connections FIRST, in two passes. Registration used to follow `written` (i.e. manifest) order, so a
        // pipeline could register before the connection it binds — a 422 that says "unknown connection" about
        // a connection sitting in the very same bundle, decided by zip entry order.
        for (String rel : written) {
            if (rel.endsWith("_connection.toon"))
                api.service().registerConnection(ConnectionProfile.load(config.resolve(rel)));
        }
        for (String rel : written) {
            if (!rel.endsWith("_pipeline.toon")) continue;
            try {
                pipelines.add(api.service().registerPipeline(config.resolve(rel)));
            } catch (IllegalArgumentException invalid) {
                throw new ApiException(422, "invalid pipeline " + rel + ": " + invalid.getMessage());
            } catch (IllegalStateException clash) {
                throw new ApiException(409, clash.getMessage());
            }
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("kind", bundle.kind());
        body.put("imported", written);
        // Named, not silent: these files had the source space's paths rewritten to this space's.
        body.put("rebased", unpacked.rebased());
        body.put("pipelines", pipelines);
        body.put("overwritten", overwrite && !conflicts.isEmpty());
        return body;
    }

    private Object exportDataSource(ApiContext api, HttpExchange e, String ds) throws IOException {
        Path config = requireConfig(api);
        DataSourceBundle bundle;
        try {
            bundle = new DataSourceBundleResolver(api.service(), config).resolve(ds);
        } catch (NoSuchElementException notFound) {
            throw new ApiException(404, notFound.getMessage());
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
        if (config == null) throw new ApiException(503, "filesystem access is disabled (no write root configured)");
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
