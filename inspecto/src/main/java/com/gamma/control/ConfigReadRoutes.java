package com.gamma.control;

import com.gamma.config.io.ConfigLoader;
import com.gamma.config.spec.ConfigSpecs;
import com.gamma.service.PipelineDependents;
import com.gamma.util.MappingCsv;
import com.sun.net.httpserver.HttpExchange;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Declarative-config read/delete routes ({@code DELETE /config/&#123;type&#125;/&#123;name&#125;},
 * {@code GET /config/pipeline/&#123;name&#125;/impact}, {@code GET
 * /config/&#123;type&#125;/&#123;name&#125;}; v5.1.0): discard a draft (never an active pipeline),
 * report a pipeline's dependents, and read a config file back. Extracted verbatim from
 * {@code ConfigRoutes}: identical routes, statuses, gating order and on-disk behaviour. File
 * resolution lives on {@link ConfigFileSupport}.
 */
final class ConfigReadRoutes implements RouteModule {

    private static final Logger log = LoggerFactory.getLogger(ConfigReadRoutes.class);

    @Override
    public void register(ApiContext api) {
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
        Path target = ConfigFileSupport.resolveConfigFile(writeRoot, dir, type, fileName);
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
        Path target = ConfigFileSupport.resolveConfigFile(writeRoot, dir, "pipeline", fileName);
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
        Path target = ConfigFileSupport.resolveConfigFile(writeRoot, dir, type, fileName);
        String rel = writeRoot.relativize(target).toString().replace('\\', '/');
        if (!Files.isRegularFile(target)) throw new ApiException(404, "no such config: " + rel);

        Map<String, Object> config = ConfigLoader.filesystem().decode(target.toString());
        // Split storage (schema): serve the conflated view — sibling _mapping.csv rules merged in.
        if ("schema".equals(type)) ConfigFileSupport.mergeSiblingMapping(target, config);
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("type", type);
        r.put("name", fileName);
        r.put("path", rel);
        r.put("config", config);
        return r;
    }
}
