package com.gamma.service;

import com.gamma.config.io.ConfigCodec;
import com.gamma.config.spec.Finding;
import com.gamma.config.spec.FindingCodes;
import com.gamma.config.spec.Severity;
import com.gamma.entitystore.EntityTypes;
import com.gamma.entitystore.LinkAnalysisSettings;
import com.gamma.pipeline.ComponentRegistry;
import com.gamma.util.ColumnClassification;
import com.gamma.util.StructureCsv;
import com.gamma.util.Values;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

/**
 * The closed vocabulary of column classifications (CLASSIFICATION-UNMASKED-CLASS-1, operator decision 2026-11-14).
 * A classification is free text to every reader, and masking keys on two sets of it:
 * {@link ColumnClassification#SENSITIVE} and the classes a masked Entity Type claims. A class in neither masks
 * nothing, and three shipped templates classed personal identifiers {@code CONFIDENTIAL} that way. So a save refuses a
 * class this Space does not know. Known, per Space:
 * <ul>
 *   <li>{@link ColumnClassification#SENSITIVE};</li>
 *   <li>a class an Entity Type of the Space claims, or the id of one (the rule {@code OnboardMatches.typeOf} reads
 *       a Dataset column by), masked or not;</li>
 *   <li>{@link ColumnClassification#NOT_MASKED}: the stated "needs no masking".</li>
 * </ul>
 * An empty classification stays allowed. Save-time only: a file already on disk still loads, and a component keeps
 * a class it already stores (so retiring an Entity Type does not make every later edit of its Datasets fail).
 */
public final class ClassificationVocabulary {

    private ClassificationVocabulary() {}

    /** The config types whose documents carry column classifications and pass {@code SaveGate}. */
    private static final List<String> GATED_CONFIG_TYPES = List.of("schema", "pipeline");

    /** The registry directories of the component kinds that carry column classifications ({@link #require}). */
    private static final List<String> GATED_REGISTRY_DIRS = Stream.of("dataset", "schema")
            .map(kind -> "registry/" + ComponentRegistry.dirForType(kind).orElseThrow() + "/").toList();

    /** The Space's Entity Types; the built-in ones where there is no Space root to read. */
    public static List<EntityTypes.EntityType> typesOf(Path writeRoot) {
        return writeRoot == null ? EntityTypes.DEFAULTS : LinkAnalysisSettings.forRoot(writeRoot).effectiveEntityTypes();
    }

    /** Whether {@code classification} is known to a Space with {@code types}; blank is "none stated" and allowed. */
    public static boolean known(Object classification, List<EntityTypes.EntityType> types) {
        String cls = ColumnClassification.normalise(classification);
        if (cls == null || ColumnClassification.SENSITIVE.contains(cls) || ColumnClassification.NOT_MASKED.contains(cls))
            return true;
        // mirrors control's OnboardMatches.typeOf (a type claims the class, else a type's id is it): service never imports control
        for (EntityTypes.EntityType t : types) {
            if (t.id().equalsIgnoreCase(cls)) return true;
            for (String c : t.classifications()) if (c.trim().equalsIgnoreCase(cls)) return true;
        }
        return false;
    }

    /** Why {@code classification} on {@code column} is refused. */
    static String refusal(Object column, Object classification) {
        return "column '" + column + "' has classification '" + String.valueOf(classification).trim()
                + "', which this Space does not know, so it would mask nothing. Use a class that masks ("
                + String.join(", ", new TreeSet<>(ColumnClassification.SENSITIVE))
                + ", or one an Entity Type of this Space claims), "
                + String.join(" or ", new TreeSet<>(ColumnClassification.NOT_MASKED))
                + " for a column that needs no masking, or leave it empty";
    }

    /** One ERROR per unknown classification in a {@code schema} or {@code pipeline} draft; empty for any other type. */
    public static List<Finding> findings(String type, Map<String, Object> draft, Path writeRoot) {
        if (!GATED_CONFIG_TYPES.contains(type) || draft == null) return List.of();
        List<Finding> out = new ArrayList<>();
        walk(draft, "", typesOf(writeRoot), Set.of(), out);
        return out;
    }

    /**
     * Refuses a component ({@code dataset}, {@code schema}) that INTRODUCES an unknown classification: a class the
     * stored component {@code type}/{@code id} already carries is kept, because half the writers of a Dataset have no
     * author present (the inbox watch, compaction, a pending Alert Rule) and must not fail on a class they never set.
     */
    public static void require(Path writeRoot, String type, String id, Map<String, Object> content) {
        Set<String> stored = new HashSet<>();
        if (writeRoot != null && id != null)
            new com.gamma.pipeline.ComponentStore(writeRoot.resolve("registry")).get(type, id)
                    .ifPresent(c -> collect(c.content(), stored));
        List<Finding> out = new ArrayList<>();
        walk(content, "", typesOf(writeRoot), stored, out);
        if (!out.isEmpty())
            throw new IllegalArgumentException(FindingCodes.ERR_UNKNOWN_CLASSIFICATION + ": "
                    + String.join("; ", out.stream().map(Finding::message).toList()));
    }

    /**
     * One ERROR per unknown classification in the config files an import just WROTE under {@code root}
     * (CLASSIFICATION-BULK-IMPORT-UNGATED-1). A bulk importer lands files as bytes, past {@link #findings} and
     * {@link #require}, so its documents are judged where they landed: every {@code .toon} outside {@code registry/}
     * (a schema file is named by the Pipeline that reads it, not by a suffix; a Pipeline states classes on its Steps'
     * output columns) and the registry's Datasets and schemas. A schema file is read merged with its sibling structure
     * CSV, as every reader merges it, and a written structure CSV has its schema file judged. The Entity Types are read
     * from {@code root} now, after the writes, so a bundle that brings its own {@code link-analysis.toon} is judged
     * against it. No stored class is kept: an import is not an edit. A file that does not parse states no class a
     * reader could act on, and is left to the importer's own gates.
     *
     * @param written the config-relative paths written, {@code /}-separated; a finding's field path starts with its file
     */
    public static List<Finding> findingsWritten(Path root, Collection<String> written) throws IOException {
        Set<Path> files = new LinkedHashSet<>();
        for (String w : written) {
            Path file = root.resolve(w).normalize();
            String rel = w.toLowerCase(Locale.ROOT);
            if (rel.endsWith("_structure.csv")) {
                String base = file.getFileName().toString();
                base = base.substring(0, base.length() - "_structure.csv".length());
                for (String schema : List.of(base + "_schema.toon", base + ".toon"))   // StructureCsv.siblingFor, backwards
                    if (Files.isRegularFile(file.resolveSibling(schema))) files.add(file.resolveSibling(schema));
            } else if (rel.endsWith(".toon")
                    && (!rel.startsWith("registry/") || GATED_REGISTRY_DIRS.stream().anyMatch(rel::startsWith))) {
                files.add(file);
            }
        }
        List<EntityTypes.EntityType> types = typesOf(root);
        List<Finding> out = new ArrayList<>();
        for (Path file : files) {
            Map<String, Object> doc;
            try {
                doc = ConfigCodec.toMap(Files.readString(file, StandardCharsets.UTF_8));
            } catch (RuntimeException unparseable) {
                continue;   // no reader can act on it either
            }
            // A schema file's sibling CSV holds its fields; a registry entry has none (ComponentStore reads .toon only),
            // so a CSV planted beside one must not change what is judged. A CSV that does not parse leaves the TOON judged alone.
            Path csv = StructureCsv.siblingFor(file);
            if (!root.relativize(file).startsWith("registry") && Files.exists(csv)) {
                try {
                    List<Map<String, String>> fields =
                            StructureCsv.parse(Files.readString(csv, StandardCharsets.UTF_8), csv.toString());
                    Map<String, Object> raw = Values.mapAt(doc, "raw");
                    if (raw == null) doc.put("raw", raw = new LinkedHashMap<>());
                    raw.put("fields", fields);
                } catch (RuntimeException unparseable) {
                    // judged without it
                }
            }
            List<Finding> found = new ArrayList<>();
            walk(doc, "", types, Set.of(), found);
            String rel = root.relativize(file).toString().replace('\\', '/');
            for (Finding f : found) out.add(new Finding(f.severity(), rel + ": " + f.fieldPath(), f.message(), f.code(), null));
        }
        return out;
    }

    /** Every map of the document that states a {@code classification}: a schema field, a Dataset column, a Step's output column. */
    private static void walk(Object node, String path, List<EntityTypes.EntityType> types, Set<String> stored,
                             List<Finding> out) {
        if (node instanceof Map<?, ?> map) {
            Object cls = map.get("classification");
            if (cls instanceof String && !known(cls, types) && !stored.contains(ColumnClassification.normalise(cls)))
                out.add(new Finding(Severity.ERROR, path.isEmpty() ? "classification" : path + ".classification",
                        refusal(map.get("name"), cls), FindingCodes.ERR_UNKNOWN_CLASSIFICATION, null));
            for (Map.Entry<?, ?> e : map.entrySet())
                walk(e.getValue(), path.isEmpty() ? String.valueOf(e.getKey()) : path + "." + e.getKey(), types, stored, out);
        } else if (node instanceof List<?> list) {
            for (int i = 0; i < list.size(); i++) walk(list.get(i), path + "[" + i + "]", types, stored, out);
        }
    }

    /** Every classification a stored document states, normalised. */
    private static void collect(Object node, Set<String> into) {
        if (node instanceof Map<?, ?> map) {
            if (map.get("classification") instanceof String cls && ColumnClassification.normalise(cls) != null)
                into.add(ColumnClassification.normalise(cls));
            map.values().forEach(v -> collect(v, into));
        } else if (node instanceof List<?> list) {
            list.forEach(v -> collect(v, into));
        }
    }
}
