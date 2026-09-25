package com.gamma.control;

import com.gamma.config.io.ConfigCodec;
import com.gamma.config.io.ConfigLoader;
import com.gamma.config.spec.Finding;
import com.gamma.etl.PipelineConfig;
import com.gamma.util.AtomicFiles;
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

/**
 * A Pipeline's config history ({@code PIPELINE-CONFIG-HISTORY-1}): list the kept versions, read one, diff two
 * (or one against the current config), and restore one. The write side is {@link PipelineHistory#record},
 * called by every save path once its write has succeeded — the restore included, so a restore is itself a
 * new version and never rewrites history.
 *
 * <p><b>Restore</b> ({@code POST …/history/{version}/restore}, {@code canAuthorWorkbench}) is a save of the
 * version's exact bytes over the Pipeline's REGISTERED file, through the same {@link SaveGate} every save
 * runs. ⚠ It is its own route rather than a client-side {@code POST /config/write} of the version on purpose:
 * {@code /config/write} files a Pipeline under {@code <id>_pipeline.toon} from the identity in the body, so
 * for any Pipeline registered from another filename (every committed sample, every legacy config) it forks a
 * SECOND file claiming the same id instead of restoring — measured, not assumed. Gate order: write root 503
 * → unknown Pipeline 404 / bad version 400 / version not kept 404 → content 422 → registered file outside
 * the write root 403 → a version declaring another id (saved before a rename) 409, stale {@code If-Match}
 * 409 → atomic write, record, and an immediate registry refresh so the editor's reload shows the restore.
 *
 * <p>⛔ The reads have no capability gate — the same posture as {@code GET /pipelines/{name}/graph/raw}, which
 * already serves the CURRENT config to any authenticated caller; a past version is no more confidential
 * than the present one. Confidentiality sits at the Space/ABAC layer ({@link CapabilityManifest}).
 *
 * <p>Gate order: unknown Pipeline 404 → bad version 400 → unknown version 404. The Pipeline is resolved
 * through the registry when it is registered; a Pipeline {@code /config/write} created but the registry
 * has not picked up yet is still addressable by its id, so its first versions are not invisible until the
 * next poll. The key is a bare safe name (no separators), and the resolved directory is jailed anyway.
 */
final class PipelineHistoryRoutes implements RouteModule {

    private static final Logger log = LoggerFactory.getLogger(PipelineHistoryRoutes.class);

    @Override
    public void register(ApiContext api) {
        api.post("/pipelines/([^/]+)/history/([^/]+)/restore", ApiContext.withCapability("canAuthorWorkbench",
                (e, m) -> restore(api, e, ApiContext.name(m), ApiContext.param(m, 2))));
        api.get("/pipelines/([^/]+)/history", (e, m) -> list(api, ApiContext.name(m)));
        // Before /history/{version}: first match wins, and "diff" would otherwise be read as a version.
        api.get("/pipelines/([^/]+)/history/diff", (e, m) -> diff(api, e, ApiContext.name(m)));
        api.get("/pipelines/([^/]+)/history/([^/]+)", (e, m) -> read(api, ApiContext.name(m), ApiContext.param(m, 2)));
    }

    /** {@code GET /pipelines/{name}/history} — the kept versions, newest first, metadata only. */
    private Object list(ApiContext api, String name) throws IOException {
        Resolved p = resolve(api, name);
        List<PipelineHistory.Version> versions = PipelineHistory.versions(p.dir());
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("pipeline", p.id());
        int keep = PipelineHistory.keep(api.writeRoot());
        r.put("keep", keep);
        r.put("total", versions.size());
        r.put("versions", versions.stream().limit(keep).map(PipelineHistoryRoutes::meta).toList());
        return r;
    }

    /**
     * {@code GET /pipelines/{name}/history/{version}} — one version's metadata, its TOON {@code text}, and the
     * Pipeline {@code id} that text declares. {@code id} differs from {@code pipeline} for a version saved
     * before a rename — the one kind of version {@link #restore} refuses, so the editor can say so up front.
     */
    private Object read(ApiContext api, String name, String version) throws IOException {
        Resolved p = resolve(api, name);
        PipelineHistory.Version v = version(p, version, "version");
        String text = Files.readString(v.file(), StandardCharsets.UTF_8);
        Map<String, Object> r = meta(v);
        r.put("pipeline", p.id());
        r.put("id", ConfigReadRoutes.pipelineIdOf(ConfigCodec.toMap(text), null));
        r.put("text", text);
        return r;
    }

    /** {@code POST /pipelines/{name}/history/{version}/restore} — see the class doc for the gate order. */
    private Object restore(ApiContext api, HttpExchange ex, String name, String version) throws IOException {
        Path writeRoot = WriteGates.requireWriteRoot(api, "pipeline restore");
        Resolved p = resolve(api, name);
        PipelineHistory.Version v = version(p, version, "version");
        Path registered = api.service().pathFor(p.id()).map(Path::normalize)
                .orElseThrow(() -> new ApiException(404, ErrorCodes.NOT_FOUND, "pipeline '" + p.id() + "' is not registered; nothing to restore over"));
        byte[] bytes = Files.readAllBytes(v.file());
        Map<String, Object> config = ConfigCodec.toMap(new String(bytes, StandardCharsets.UTF_8));

        List<Finding> findings = SaveGate.check(api, "pipeline", config, writeRoot, registered.getParent(),
                SaveGate.Referents.MUST_EXIST);
        if (SaveGate.refuses(findings))
            return ApiContext.respondJson(ex, 422, Map.of("written", false,
                    "error", "version " + v.version() + " has ERROR-level findings today; not restored", "findings", findings));

        Path target = WriteGates.jail(writeRoot, registered, "pipeline config");
        String declared = ConfigReadRoutes.pipelineIdOf(config, null);
        WriteGates.conflictIf(!p.id().equals(declared == null ? null : declared.trim()),
                "version " + v.version() + " declares pipeline id '" + declared + "' (saved before a rename); "
                        + "restoring it would re-key '" + p.id() + "'");
        ETags.requireMatch(ex, ETags.of(ContentHash.of(ConfigLoader.filesystem().decode(target.toString()))));

        AtomicFiles.write(target, bytes, ".cfg-");
        PipelineHistory.record(writeRoot, target);
        api.service().refreshConfigs();   // the editor reloads GET …/graph/raw next, which lifts the REGISTERED config
        ETags.set(ex, ETags.of(ContentHash.of(ConfigLoader.filesystem().decode(target.toString()))));
        List<PipelineHistory.Version> now = PipelineHistory.versions(p.dir());
        log.info("[PIPELINE-HISTORY] restored '{}' v{} to {}", p.id(), v.version(), target.getFileName());

        Map<String, Object> r = new LinkedHashMap<>();
        r.put("pipeline", p.id());
        r.put("restored", v.version());
        r.put("version", now.isEmpty() ? null : now.getFirst().version());
        r.put("path", writeRoot.relativize(target).toString().replace('\\', '/'));
        r.put("findings", findings);
        return r;
    }

    /**
     * {@code GET /pipelines/{name}/history/diff?from={v}&to={v|current}} — a line diff of two versions'
     * TOON text; {@code to} defaults to {@code current}, the config file as it is on disk now.
     */
    private Object diff(ApiContext api, HttpExchange ex, String name) throws IOException {
        Resolved p = resolve(api, name);
        String fromArg = ApiContext.query(ex, "from");
        if (fromArg == null || fromArg.isBlank()) throw new ApiException(400, ErrorCodes.MALFORMED_REQUEST, "query parameter 'from' (a version) is required");
        String toArg = ApiContext.query(ex, "to");
        boolean toCurrent = toArg == null || toArg.isBlank() || "current".equals(toArg.trim());

        PipelineHistory.Version from = version(p, fromArg, "from");
        Path toFile;
        if (toCurrent) {
            toFile = api.service().pathFor(p.id()).filter(Files::isRegularFile)
                    .orElseThrow(() -> new ApiException(404, ErrorCodes.NOT_FOUND, "pipeline '" + p.id() + "' has no current config file"));
        } else {
            toFile = version(p, toArg, "to").file();
        }
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("pipeline", p.id());
        r.put("from", from.version());
        r.put("to", toCurrent ? "current" : Integer.parseInt(toArg.trim()));
        r.putAll(PipelineHistory.diff(lines(from.file()), lines(toFile)));
        return r;
    }

    // ── helpers ──────────────────────────────────────────────────────────────────

    private record Resolved(String id, Path dir) {}

    private static Resolved resolve(ApiContext api, String name) {
        String id = api.service().configFor(name).map(PipelineConfig::identity)
                .map(PipelineConfig.Identity::pipelineName).orElse(null);
        boolean registered = id != null;
        if (id == null) id = name;
        if (!WriteGates.isSafeName(id)) throw new ApiException(404, ErrorCodes.NOT_FOUND, "no pipeline named '" + name + "'");
        Path root = api.writeRoot();
        Path dir = root == null ? null : WriteGates.jail(root, PipelineHistory.dirFor(root, id.trim()), "history path");
        if (!registered && (dir == null || !Files.isDirectory(dir)))
            throw new ApiException(404, ErrorCodes.NOT_FOUND, "no pipeline named '" + name + "'");
        return new Resolved(id.trim(), dir);   // dir null ⇔ no write root ⇔ no history
    }

    private static PipelineHistory.Version version(Resolved p, String raw, String what) throws IOException {
        int n;
        try {
            n = Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            throw new ApiException(400, ErrorCodes.MALFORMED_REQUEST, what + " must be a version number, got '" + raw + "'");
        }
        return PipelineHistory.versions(p.dir()).stream().filter(v -> v.version() == n).findFirst()
                .orElseThrow(() -> new ApiException(404, ErrorCodes.NOT_FOUND, "pipeline '" + p.id() + "' has no kept version " + n));
    }

    private static Map<String, Object> meta(PipelineHistory.Version v) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("version", v.version());
        m.put("savedAt", v.savedAt().toString());
        m.put("bytes", v.bytes());
        return m;
    }

    private static List<String> lines(Path file) throws IOException {
        return Files.readString(file, StandardCharsets.UTF_8).lines().toList();
    }
}
