package com.gamma.control;

import com.gamma.config.spec.Finding;
import com.gamma.config.spec.FindingCodes;
import com.gamma.config.spec.Severity;
import com.gamma.entitystore.EntityTypes;
import com.gamma.entitystore.LinkAnalysisSettings;
import com.gamma.util.ColumnClassification;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * The closed vocabulary of column classifications (CLASSIFICATION-UNMASKED-CLASS-1, operator decision 2026-11-14).
 * A classification is free text to every reader, and masking keys on two sets of it:
 * {@link ColumnClassification#SENSITIVE} and the classes a masked Entity Type claims. A class in neither masks
 * nothing, and three shipped templates classed personal identifiers {@code CONFIDENTIAL} that way. So a save refuses a
 * class this Space does not know. Known, per Space:
 * <ul>
 *   <li>{@link ColumnClassification#SENSITIVE};</li>
 *   <li>a class an Entity Type of the Space claims, or the id of one (the rule {@link OnboardMatches#typeOf} reads
 *       a Dataset column by), masked or not;</li>
 *   <li>{@link ColumnClassification#NOT_MASKED}: the stated "needs no masking".</li>
 * </ul>
 * An empty classification stays allowed. Save-time only: a file already on disk still loads, and a component keeps
 * a class it already stores (so retiring an Entity Type does not make every later edit of its Datasets fail).
 */
final class ClassificationVocabulary {

    private ClassificationVocabulary() {}

    /** The config types whose documents carry column classifications and pass {@link SaveGate}. */
    private static final List<String> GATED_CONFIG_TYPES = List.of("schema", "pipeline");

    /** The Space's Entity Types; the built-in ones where there is no Space root to read. */
    static List<EntityTypes.EntityType> typesOf(Path writeRoot) {
        return writeRoot == null ? EntityTypes.DEFAULTS : LinkAnalysisSettings.forRoot(writeRoot).effectiveEntityTypes();
    }

    /** Whether {@code classification} is known to a Space with {@code types}; blank is "none stated" and allowed. */
    static boolean known(Object classification, List<EntityTypes.EntityType> types) {
        String cls = ColumnClassification.normalise(classification);
        return cls == null || ColumnClassification.SENSITIVE.contains(cls) || ColumnClassification.NOT_MASKED.contains(cls)
                || OnboardMatches.typeOf(cls, types) != null;
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
    static List<Finding> findings(String type, Map<String, Object> draft, Path writeRoot) {
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
    static void require(Path writeRoot, String type, String id, Map<String, Object> content) {
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
