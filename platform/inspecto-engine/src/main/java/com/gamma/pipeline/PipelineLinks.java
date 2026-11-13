package com.gamma.pipeline;

import com.gamma.acquire.ConnectionProfile;
import com.gamma.config.safety.PathJail;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * How {@link LinkGraph} judges the links a Pipeline's own file holds (SE-1b, `SPACE-LINK-CENSUS-1`), each exactly as
 * the code that really resolves it, so a DANGLING verdict is a Pipeline that fails to load or to run:
 *
 * <ul>
 *   <li>{@code collector.connection}: resolved when the Collector is built, every cycle
 *       ({@code CollectorConnectors.forConfig} → {@code ConnectionRegistry.find}, trimmed, case-sensitive), against
 *       the profiles the server loaded from {@code *_connection.toon} anywhere under the config root except
 *       {@code registry/} ({@code ServiceBootstrap.resolveBySuffix} + {@code ConnectionProfile.load}; a file that
 *       does not load is skipped there too). {@code registry/connections/} is not loaded into that registry, so a
 *       profile only there does not resolve a Collector. Judged only for a remote connector that needs a profile:
 *       a {@code local} (or absent) connector never looks the id up, and {@code connector: dataset} ignores it.</li>
 *   <li>{@code collector.dataset}: resolved by {@code DatasetCollectorConnectorFactory} with
 *       {@code connector: dataset} only (the parser refuses the key with any other connector, so it is then not a
 *       link): the {@code datasets/} prefix dropped, the id trimmed and checked safe ({@code ComponentStore.get}),
 *       then the Dataset component of that exact name in the Space registry.</li>
 *   <li>{@code processing.schema_file}: resolved when the file loads ({@code PipelineConfigParser.resolveSchemaRef}):
 *       {@code schema/<id>} names {@code registry/schemas/<id>.toon}, anything else resolves beside the Pipeline
 *       file through {@code PathJail.resolveConfigRef}; a missing file fails the load. Judged only when the loader
 *       reads it: not with a non-empty {@code processing.schemas}, a plugin ingester, a Grammar reference (a Grammar
 *       may carry a plugin, which the census does not open) or {@code frontend: asn1} - those stay UNCHECKED. The
 *       allowed-roots jail applied after resolution is not judged (it needs the Space's Safety Policy), so a
 *       schema outside it reads RESOLVED though the load refuses it: a missed finding, never a false one.</li>
 * </ul>
 *
 * {@code **.use} stays UNCHECKED: no loader reads a {@code use} key in a {@code *_pipeline.toon}; a {@code use} exists
 * on graph nodes (lifted, or authored under {@code pipelines/}), which the census does not scan.
 */
final class PipelineLinks {

    private static final Logger log = LoggerFactory.getLogger(PipelineLinks.class);

    /** {@code ComponentStore.SAFE_ID}: an id the Dataset connector can look up at all. */
    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]*");

    private PipelineLinks() {}

    /**
     * The connection ids the server registers for this config root (empty when none load), or {@code null} when the
     * root cannot be listed (the connection links then stay UNCHECKED). Walks as
     * {@code ServiceBootstrap.resolveBySuffix} does: every depth, the {@code registry/} subtree excluded.
     */
    static Set<String> connectionIds(Path configRoot) {
        Set<String> ids = new HashSet<>();
        Path registry = configRoot.resolve("registry");
        try (Stream<Path> w = Files.walk(configRoot)) {
            for (Path f : w.filter(Files::isRegularFile).filter(f -> !f.startsWith(registry))
                    .filter(f -> f.getFileName().toString().endsWith("_connection.toon")).sorted().toList()) {
                try {
                    ConnectionProfile p = ConnectionProfile.load(f);
                    if (p.id() != null) ids.add(p.id());
                } catch (IOException | RuntimeException ex) {
                    log.debug("[LINKS] connection {} does not load: {}", f, ex.getMessage());
                }
            }
        } catch (IOException | RuntimeException ex) {
            log.warn("[LINKS] could not list connections under {}: {}", configRoot, ex.getMessage());
            return null;   // unknown: the connection links stay UNCHECKED
        }
        return ids;
    }

    /**
     * The status of one Pipeline link, or {@code null} when this class does not judge it (the caller then answers
     * UNCHECKED). {@code file} is null for an in-memory draft; {@code connectionIds} null when unknown.
     */
    static LinkGraph.Status status(LinkCensus.Link l, Path file, Map<?, ?> doc, String value,
                                   Predicate<String> datasetExists, Set<String> connectionIds) {
        switch (l.keyPath()) {
            case "collector.connection" -> {
                String connector = connector(doc);
                if (connectionIds == null || connector.equals("local") || connector.equals("dataset")) return null;
                return connectionIds.contains(value.trim()) ? LinkGraph.Status.RESOLVED : LinkGraph.Status.DANGLING;
            }
            case "collector.dataset" -> {
                if (!connector(doc).equals("dataset")) return null;
                String id = value.startsWith("datasets/") ? value.substring("datasets/".length()) : value;
                id = id.trim();
                return !id.contains("..") && SAFE_ID.matcher(id).matches() && datasetExists.test(id)
                        ? LinkGraph.Status.RESOLVED : LinkGraph.Status.DANGLING;
            }
            case "processing.schema_file" -> {
                if (file == null || !readsSchemaFile(doc)) return null;
                Path dir = file.toAbsolutePath().getParent();
                String ref = value.startsWith("schema/")
                        ? "registry/schemas/" + value.substring("schema/".length()) + ".toon" : value;
                try {
                    return Files.exists(PathJail.resolveConfigRef(dir, ref, "processing.schema_file"))
                            ? LinkGraph.Status.RESOLVED : LinkGraph.Status.DANGLING;
                } catch (RuntimeException refused) {
                    return LinkGraph.Status.DANGLING;   // blank, not a path, or only the working directory sees it
                }
            }
            default -> {
                return null;
            }
        }
    }

    /** {@code collector.connector} as the parser reads it: absent or blank is {@code local}, else lower-cased. */
    private static String connector(Map<?, ?> doc) {
        if (!(doc.get("collector") instanceof Map<?, ?> c)) return "local";
        Object v = c.get("connector");
        return v == null || v.toString().isBlank() ? "local" : v.toString().toLowerCase(Locale.ROOT);
    }

    /** Whether {@code PipelineConfigParser.parseSchemas} reads the single {@code processing.schema_file}. */
    private static boolean readsSchemaFile(Map<?, ?> doc) {
        if (!(doc.get("processing") instanceof Map<?, ?> proc)) return false;
        if (proc.get("schemas") instanceof List<?> l && !l.isEmpty()) return false;
        if (proc.get("ingester") != null || proc.get("grammar") != null) return false;
        if (doc.get("parsing") instanceof Map<?, ?> parsing
                && (parsing.get("plugin") != null || parsing.get("grammar") != null)) return false;
        return !mentionsAsn1(doc.get("parsing")) && !mentionsAsn1(proc);
    }

    private static boolean mentionsAsn1(Object node) {
        if (node instanceof Map<?, ?> m) {
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if ("frontend".equals(String.valueOf(e.getKey()))
                        && "asn1".equalsIgnoreCase(String.valueOf(e.getValue()).trim())) return true;
                if (mentionsAsn1(e.getValue())) return true;
            }
        } else if (node instanceof List<?> l) {
            for (Object o : l) if (mentionsAsn1(o)) return true;
        }
        return false;
    }
}
