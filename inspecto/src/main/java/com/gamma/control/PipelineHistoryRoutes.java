package com.gamma.control;

import com.gamma.etl.PipelineConfig;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A Pipeline's config history, read side ({@code PIPELINE-CONFIG-HISTORY-1}): list the kept versions, read
 * one, diff two (or one against the current config). The write side is {@link PipelineHistory#record},
 * called by every save path once its write has succeeded.
 *
 * <p>⛔ Reads, so no capability gate — the same posture as {@code GET /pipelines/{name}/graph/raw}, which
 * already serves the CURRENT config to any authenticated caller; a past version is no more confidential
 * than the present one. Confidentiality sits at the Space/ABAC layer ({@link CapabilityManifest}).
 *
 * <p>Gate order: unknown Pipeline 404 → bad version 400 → unknown version 404. The Pipeline is resolved
 * through the registry when it is registered; a Pipeline {@code /config/write} created but the registry
 * has not picked up yet is still addressable by its id, so its first versions are not invisible until the
 * next poll. The key is a bare safe name (no separators), and the resolved directory is jailed anyway.
 */
final class PipelineHistoryRoutes implements RouteModule {

    @Override
    public void register(ApiContext api) {
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
        r.put("keep", PipelineHistory.KEEP);
        r.put("total", versions.size());
        r.put("versions", versions.stream().limit(PipelineHistory.KEEP).map(PipelineHistoryRoutes::meta).toList());
        return r;
    }

    /** {@code GET /pipelines/{name}/history/{version}} — one version's metadata plus its TOON text. */
    private Object read(ApiContext api, String name, String version) throws IOException {
        Resolved p = resolve(api, name);
        PipelineHistory.Version v = version(p, version, "version");
        Map<String, Object> r = meta(v);
        r.put("pipeline", p.id());
        r.put("text", Files.readString(v.file(), StandardCharsets.UTF_8));
        return r;
    }

    /**
     * {@code GET /pipelines/{name}/history/diff?from={v}&to={v|current}} — a line diff of two versions'
     * TOON text; {@code to} defaults to {@code current}, the config file as it is on disk now.
     */
    private Object diff(ApiContext api, HttpExchange ex, String name) throws IOException {
        Resolved p = resolve(api, name);
        String fromArg = ApiContext.query(ex, "from");
        if (fromArg == null || fromArg.isBlank()) throw new ApiException(400, "query parameter 'from' (a version) is required");
        String toArg = ApiContext.query(ex, "to");
        boolean toCurrent = toArg == null || toArg.isBlank() || "current".equals(toArg.trim());

        PipelineHistory.Version from = version(p, fromArg, "from");
        Path toFile;
        if (toCurrent) {
            toFile = api.service().pathFor(p.id()).filter(Files::isRegularFile)
                    .orElseThrow(() -> new ApiException(404, "pipeline '" + p.id() + "' has no current config file"));
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
        if (!WriteGates.isSafeName(id)) throw new ApiException(404, "no pipeline named '" + name + "'");
        Path root = api.writeRoot();
        Path dir = root == null ? null : WriteGates.jail(root, PipelineHistory.dirFor(root, id.trim()), "history path");
        if (!registered && (dir == null || !Files.isDirectory(dir)))
            throw new ApiException(404, "no pipeline named '" + name + "'");
        return new Resolved(id.trim(), dir);   // dir null ⇔ no write root ⇔ no history
    }

    private static PipelineHistory.Version version(Resolved p, String raw, String what) throws IOException {
        int n;
        try {
            n = Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            throw new ApiException(400, what + " must be a version number, got '" + raw + "'");
        }
        return PipelineHistory.versions(p.dir()).stream().filter(v -> v.version() == n).findFirst()
                .orElseThrow(() -> new ApiException(404, "pipeline '" + p.id() + "' has no kept version " + n));
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
