package com.gamma.control;

import com.gamma.util.MappingCsv;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import static com.gamma.util.Values.mapAt;

/**
 * On-disk resolution shared by the config write/read/delete routes ({@link ConfigWriteRoutes},
 * {@link ConfigReadRoutes}): the filename convention a config type persists under, the back-compat
 * bare-name fallback, and the schema split-storage read-side merge. Extracted verbatim from
 * {@code ConfigRoutes}.
 */
final class ConfigFileSupport {

    private ConfigFileSupport() {}

    /**
     * The on-disk base name for a config. The service bootstrap scan indexes pipelines by the
     * {@code *_pipeline.toon} suffix ({@code MultiCollectorProcessor.resolveConfigs}) and Stage-2
     * enrichments by {@code *_enrich.toon} ({@code ServiceBootstrap.resolveBySuffix}), so a
     * guided write MUST follow them — otherwise a draft silently drops out on the next service
     * restart (found by the P2 live walk for pipelines; same trap for enrichments). Other types
     * keep the bare name (their identity fields, e.g. {@code raw.name}, already carry the scan
     * suffix by convention).
     */
    static String fileBase(String type, String safeName) {
        if ("pipeline".equals(type) && !safeName.endsWith("_pipeline")) return safeName + "_pipeline";
        if ("enrichment".equals(type) && !safeName.endsWith("_enrich")) return safeName + "_enrich";
        return safeName;
    }

    /** Resolve a config's file: the suffixed convention first, then the bare name (back-compat). */
    static Path resolveConfigFile(Path writeRoot, Path dir, String type, String safeName) {
        Path suffixed = WriteGates.jail(writeRoot, dir.resolve(fileBase(type, safeName) + ".toon"), "resolved path");
        if (Files.isRegularFile(suffixed)) return suffixed;
        return WriteGates.jail(writeRoot, dir.resolve(safeName + ".toon"), "resolved path");
    }

    /**
     * As {@link #resolveConfigFile}, but a REGISTERED pipeline is found wherever its file actually is.
     *
     * <p>🔴 A pipeline is addressed by NAME, and its file need not sit at the write root: every sample
     * pipeline in this repo lives in {@code config/<name>/<name>_pipeline.toon}. Resolving only against
     * the write root answered "no such config" for all of them, so delete, patch and read-back were all
     * unusable on any pipeline the caller had not just created at the root — you could not even
     * deactivate one, which is the prerequisite for deleting it.
     *
     * <p>The registry already knows the path ({@code PipelineGraphRoutes.saveGraph} writes through the
     * same {@code pathFor}), so ask it and fall back to the write-root convention for an unregistered
     * draft. ⚠ Still jailed: a registered path outside the write root does not win, it is simply not
     * accepted — the registry is a lookup here, never an authority on what may be written.
     *
     * <p>⚠ Only consulted when the caller supplied no explicit {@code subdir}: an explicit subdir is
     * the caller saying WHERE, and silently looking elsewhere would ignore them.
     */
    static Path resolveRegisteredConfigFile(ApiContext api, Path writeRoot, Path dir, String type,
                                            String safeName, String subdir) {
        Path byConvention = resolveConfigFile(writeRoot, dir, type, safeName);
        if (!"pipeline".equals(type) || (subdir != null && !subdir.isBlank())) return byConvention;
        if (Files.isRegularFile(byConvention)) return byConvention;
        return api.service().pathFor(safeName).map(Path::normalize)
                .filter(p -> p.startsWith(writeRoot))
                .filter(Files::isRegularFile)
                .orElse(byConvention);
    }

    /**
     * The read-side of the split: merge a schema file's sibling {@code _mapping.csv} (if any) into
     * its decoded map, so clients always see the conflated shape they authored — the same dual-read
     * the engine's {@code PipelineConfigParser} performs.
     */
    static void mergeSiblingMapping(Path schemaFile, Map<String, Object> config) throws IOException {
        Path csv = MappingCsv.siblingFor(schemaFile);
        if (!Files.exists(csv)) return;
        List<Map<String, String>> rules =
                MappingCsv.parse(Files.readString(csv, StandardCharsets.UTF_8), csv.toString());
        Map<String, Object> mapping = mapAt(config, "mapping");
        if (mapping == null) {
            mapping = new LinkedHashMap<>();
            config.put("mapping", mapping);
        }
        mapping.put("rules", rules);
    }
}
