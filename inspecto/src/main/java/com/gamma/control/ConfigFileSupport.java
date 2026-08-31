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
     * As {@link #resolveRegisteredConfigFile}, but for a pipeline's SATELLITE config — a schema,
     * mapping or enrichment that lives beside the pipeline it belongs to. <b>Read paths only.</b>
     *
     * <p>🔴 The same defect {@link #resolveRegisteredConfigFile} fixed for pipelines was never fixed
     * for their satellites. A sample pipeline lives at {@code config/<name>/<name>_pipeline.toon} and
     * its schema sits beside it at {@code config/<name>/<name>_schema.toon}; resolving only against the
     * write root answered "no such config" for every one of them. The Pipelines editor reads a node's
     * saved output schema by bare name with no {@code subdir}, so opening a Parse step on any pipeline
     * laid out this way 404'd and the drawer silently proposed a NEW schema over the saved one
     * (BACKLOG MOCK-GONE-1(a), found by driving the real app 2026-08-31).
     *
     * <p>Pipelines can ask the registry where their file is; a satellite has no registry, so this scans
     * the write root instead. ⚠ It accepts a match only when it is <b>UNIQUE</b> — two pipelines may
     * legitimately own same-named satellites in different directories, and guessing between them would
     * serve one pipeline's schema while editing another's. Zero or several ⇒ the convention path is
     * returned unchanged, i.e. today's 404. (Same rule as {@code /catalog/resolve}: ambiguity is not a
     * match.)
     *
     * <p>⚠ Deliberately NOT used by delete or write: a read that finds the wrong file shows wrong data,
     * a delete that finds the wrong file destroys it. Those stay strict, and an explicit {@code subdir}
     * always wins here too — the caller saying WHERE is never second-guessed.
     */
    static Path resolveSatelliteForRead(Path writeRoot, Path byConvention, String type, String safeName,
                                        String subdir) {
        if ("pipeline".equals(type) || (subdir != null && !subdir.isBlank())) return byConvention;
        if (Files.isRegularFile(byConvention)) return byConvention;
        String suffixed = fileBase(type, safeName) + ".toon";
        String bare = safeName + ".toon";
        try (java.util.stream.Stream<Path> tree = Files.walk(writeRoot, SATELLITE_SCAN_DEPTH)) {
            List<Path> hits = tree.filter(Files::isRegularFile)
                    .filter(p -> {
                        String f = p.getFileName().toString();
                        return f.equals(suffixed) || f.equals(bare);
                    })
                    .limit(2)   // one more than we accept — enough to detect ambiguity, no more work
                    .toList();
            return hits.size() == 1 ? hits.get(0) : byConvention;
        } catch (IOException e) {
            return byConvention;   // an unreadable tree is a 404, not a 500
        }
    }

    /** Depth for {@link #resolveSatelliteForRead}: a satellite sits beside its pipeline, one directory
     *  under the write root. Bounded so the scan can never walk an operator's whole data tree. */
    private static final int SATELLITE_SCAN_DEPTH = 3;

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
