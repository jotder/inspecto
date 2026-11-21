package com.gamma.control;

import com.gamma.config.spec.AcceptedConfigKeys;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The keys a <b>Dataset</b> or a <b>Reconciliation</b> body may carry (UNKNOWN-KEYS-SILENT-1): the census the
 * {@code widget}/{@code dashboard}/{@code expectation} kinds already have in {@code ComponentRoutes}, for the two kinds that
 * had none. A key outside the set is read by nothing, so the save used to answer 200 and the engine never looked at it
 * (a misspelled {@code toleranceType} ran as {@code exact}).
 *
 * <p>The sets are not invented: a Dataset's is the union of every key the 303 shipped Dataset files carry (each is read by
 * the engine or the console) and every key a writer in this module puts on a Dataset ({@code OnboardImport},
 * {@code DatasetEntryRules}, the arrival and feed writers); a Reconciliation's is exactly what {@code ReconConfigLoader}
 * reads. {@code UnknownComponentKeysShippedContentTest} holds every shipped file inside its set, so a shipped stray is a
 * test failure, not a later 422. A key named {@code x-…} is the author's "this is mine" marker and always passes.
 *
 * <p>Deliberately a KEY census, one level for a Dataset and two for a Reconciliation's {@code compareColumns} items (where the
 * tolerance lives); {@code columns}, {@code calculated}, {@code query}, {@code filters}, {@code columnMap}, {@code bands} and
 * {@code impact} are accepted whole, as {@code AcceptedConfigKeys} accepts a block whole.
 */
final class UnknownComponentKeys {

    private UnknownComponentKeys() {}

    /** Keys the store adds to every persisted component ({@code ComponentRoutes.COMPONENT_ENVELOPE_KEYS}). */
    private static final Set<String> ENVELOPE = Set.of("name", "owner", "shares");

    static final Set<String> DATASET = Set.of(
            // the shipped Dataset files
            "physicalRef", "columns", "description", "primaryEntityType", "eventDate", "defaultLinkMapping", "dateField",
            "calculated", "kind", "sourceName", "view", "sql", "materialized", "query", "measures", "viz",
            // written by Add data, Mappings, arrivals and feeds
            "importPipeline", "importFormat", "importSource", "origin", "lastImport", "basedOn", "mapping", "eventDateColumn",
            "dataAsOf", "identifyingColumn", "recordType", "feed", "storage", "retention", "compaction", "poll", "tags");

    static final Set<String> RECONCILIATION = Set.of(
            "datasets", "leftDataset", "rightDataset", "thirdDataset", "columnMap", "filters", "compareColumns",
            "includeRecordCount", "cardinality", "keyColumns", "impact", "bands");

    /** What one {@code compareColumns} entry reads ({@code ReconConfigLoader.buildSpec}). */
    static final Set<String> COMPARE_COLUMN = Set.of("column", "agg", "toleranceType", "tolerance");

    /** The kinds this census judges. */
    static boolean covers(String type) {
        return "dataset".equals(type) || "reconciliation".equals(type);
    }

    /**
     * The unknown keys of {@code content} for {@code type}, sorted, each as the path the author wrote
     * ({@code toleranceTyp}, {@code compareColumns[1].tolerence}); empty when none or when the kind is not covered.
     */
    static List<String> of(String type, Map<String, Object> content) {
        if (!covers(type) || content == null) return List.of();
        Set<String> known = "dataset".equals(type) ? DATASET : RECONCILIATION;
        List<String> out = new ArrayList<>();
        for (String k : content.keySet())
            if (!known.contains(k) && !ENVELOPE.contains(k) && !k.startsWith(AcceptedConfigKeys.EXTENSION_PREFIX)) out.add(k);
        if ("reconciliation".equals(type) && content.get("compareColumns") instanceof List<?> rows)
            for (int i = 0; i < rows.size(); i++)
                if (rows.get(i) instanceof Map<?, ?> row)
                    for (Object k : row.keySet())
                        if (!COMPARE_COLUMN.contains(String.valueOf(k)) && !String.valueOf(k).startsWith(AcceptedConfigKeys.EXTENSION_PREFIX))
                            out.add("compareColumns[" + i + "]." + k);
        out.sort(null);
        return out;
    }

    /** The sentence a refusal or a warning carries. */
    static String describe(String type, List<String> unknown) {
        return type + " has unknown key(s) " + unknown + " that nothing reads; remove them, or prefix with '"
                + AcceptedConfigKeys.EXTENSION_PREFIX + "' to mark them as deliberately extra";
    }
}
