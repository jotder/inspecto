package com.gamma.alert;

import com.gamma.analytics.score.ScoreColumns;
import com.gamma.analytics.score.ScoreKind;
import com.gamma.mask.EvidenceMasker;
import com.gamma.pipeline.ComponentRegistry;
import com.gamma.pipeline.ComponentStore;
import com.gamma.pipeline.SharingEnvelope;
import com.gamma.pipeline.ViewStore;
import com.gamma.util.ColumnClassification;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * A model's outputs as Datasets (`BI-INSIGHTS-1` P0, I-14): after a run wrote them, the history {@code <prefix><model>}
 * and {@code <prefix><model>_latest} are registered as system Datasets so Studio and Alert Rules can read them without
 * hand-declaring them.
 *
 * <ul>
 *   <li><b>Every column declared</b>, with its role; {@code entity_key} as an {@code entity} column classified with the
 *       STRICTEST of the model's Entity Type and the class of every input's key column, so BI masking aliases it
 *       whenever the keys it holds are masked at their source. An input key whose class cannot be traced registers
 *       nothing (fail closed).</li>
 *   <li><b>Readable only by who may read the model and every Dataset it reads</b> (a Mapping input with its base;
 *       {@link SharingEnvelope}).</li>
 *   <li><b>Any refusal narrows.</b> When the outputs cannot be registered as they are now (sources shared differently,
 *       an untraceable key), the system Datasets an earlier run registered are narrowed to the model's owner, so new
 *       scores never land under a wider, stale envelope.</li>
 *   <li><b>System Datasets</b> ({@code origin: system}), registered regardless of a Dataset approval policy (operator,
 *       2026-11-24, as D-BI11). A bare entry with no sharing (only {@code physicalRef}, as {@code PendingAlertRules}
 *       registers a template's {@code _latest}) is adopted; any other Dataset of that id declared by hand is never
 *       touched.</li>
 *   <li><b>Never fails the run</b> (operator, 2026-11-24): the scores are already written and Alert Rules read them; a
 *       reason why a Dataset was not registered is returned for the run's message.</li>
 * </ul>
 */
public final class ScoreDatasets {

    private ScoreDatasets() {}

    static final String TAG = "model-output";

    /** An input the model reads: a Dataset and the column holding the entity key there. */
    public record Input(String dataset, String key) {}

    /**
     * @param model  the saved model's content (its {@code owner} / {@code shares})
     * @param inputs every Dataset the model reads, with its key column
     * @return {@code null} when both Datasets are registered (or were declared by hand), else why not
     */
    public static String register(ComponentStore store, Path dataDir, ScoreKind kind, String modelId, String entityType,
                                  List<ScoreColumns.Column> kindColumns, Map<String, Object> model, List<Input> inputs) {
        List<String> ids = List.of(kind.scores(modelId), kind.latest(modelId));
        try {
            String refusal = null;
            Map<String, Object> envelope = null;
            try {
                envelope = SharingEnvelope.of(store, sources(store, model, inputs));
            } catch (SecurityException e) {
                refusal = "the model and the Datasets it reads are shared differently — its output Datasets would show "
                        + "one's values to the other's readers; share them alike";
            }
            String keyClass = null;
            if (refusal == null) {
                Path configRoot = store.root().getParent();
                Predicate<String> masked = EvidenceMasker.maskedClasses(configRoot);
                List<String> classes = new ArrayList<>();
                classes.add(entityType);
                for (Input in : inputs) {
                    String c;
                    try {
                        c = keyClass(store, new ViewStore(configRoot.resolve("views")), in);
                    } catch (IllegalStateException untraceable) {
                        refusal = untraceable.getMessage();
                        break;
                    }
                    if (c != null) classes.add(c);
                }
                String strictest = ColumnClassification.strictest(classes, masked);
                keyClass = strictest != null && strictest.equals(ColumnClassification.normalise(entityType)) ? entityType : strictest;
            }
            if (refusal != null) {
                narrow(store, ids, model, sources(store, model, inputs));
                return refusal + " (any earlier output Datasets were narrowed to the model's owner)";
            }
            List<String> problems = new ArrayList<>();
            for (String id : ids) {
                String p = registerOne(store, dataDir, kind, modelId, id, keyClass, kindColumns, envelope);
                if (p != null) problems.add(p);
            }
            return problems.isEmpty() ? null : String.join("; ", problems);
        } catch (IOException | RuntimeException e) {
            try {
                narrow(store, ids, model, List.of(model, Map.of("shares", List.of())));   // sources unknown: no owner
            } catch (IOException | RuntimeException ignored) { /* the reason below still reaches the run */ }
            return "registering the output Datasets failed: " + e.getClass().getSimpleName();
        }
    }

    /** The model, every input and, for a Mapping input, its base: whose readers the outputs must stay within. */
    private static List<Map<String, Object>> sources(ComponentStore store, Map<String, Object> model, List<Input> inputs) {
        List<Map<String, Object>> out = new ArrayList<>();
        out.add(model);
        Set<String> seen = new LinkedHashSet<>();
        for (Input in : inputs) {
            if (!seen.add(in.dataset())) continue;
            Map<String, Object> ds = dataset(store, in.dataset());
            if (ds == null) continue;
            out.add(ds);
            if ("mapping".equals(String.valueOf(ds.get("origin"))) && ds.get("basedOn") instanceof String base) {
                Map<String, Object> b = dataset(store, base);
                if (b != null) out.add(b);
            }
        }
        return out;
    }

    /**
     * The class of {@code in}'s key column: its own declaration and its lineage, the stricter; {@code null} = traced and
     * unclassified.
     *
     * @throws IllegalStateException when the lineage cannot be traced (fail closed)
     */
    static String keyClass(ComponentStore store, ViewStore views, Input in) {
        Map<String, Object> ds = dataset(store, in.dataset());
        if (ds == null || in.key() == null)
            throw new IllegalStateException("the key of dataset '" + in.dataset() + "' cannot be traced");
        String key = in.key().trim().toLowerCase(Locale.ROOT);
        String declared = null;
        if (ds.get("columns") instanceof List<?> cols)
            for (Object o : cols)
                if (o instanceof Map<?, ?> c && c.get("name") != null
                        && key.equals(String.valueOf(c.get("name")).trim().toLowerCase(Locale.ROOT)))
                    declared = ColumnClassification.normalise(c.get("classification"));
        Map<String, String> lineage = EvidenceMasker.lineageClassification(in.dataset(), ds, store, views, c -> true);
        if (lineage.containsKey(EvidenceMasker.UNKNOWN_LINEAGE))
            throw new IllegalStateException("the class of key '" + in.key() + "' in dataset '" + in.dataset()
                    + "' cannot be traced");
        String traced = null;
        for (Map.Entry<String, String> e : lineage.entrySet())
            if (key.equals(e.getKey().trim().toLowerCase(Locale.ROOT))) traced = ColumnClassification.normalise(e.getValue());
        return ColumnClassification.stricter(declared, traced);
    }

    private static String registerOne(ComponentStore store, Path dataDir, ScoreKind kind, String modelId, String id,
                                      String keyClass, List<ScoreColumns.Column> kindColumns,
                                      Map<String, Object> envelope) throws IOException {
        if (dataDir == null || !ScoreOutputDirs.ownedBy(dataDir.resolve(id), kind.ownerMarker(), modelId))
            return "'" + id + "' was not written by " + kind.kind() + " '" + modelId + "'";
        Map<String, Object> existing = dataset(store, id);
        if (existing != null && !id.equals(existing.get("physicalRef")))
            return "dataset '" + id + "' exists and reads another store; it was left as it is";
        if (existing != null && !ours(existing))
            return null;   // declared by hand: never touched
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("physicalRef", id);
        content.put("description", (id.endsWith(ScoreKind.LATEST_SUFFIX) ? "The newest run" : "Every run")
                + " of " + kind.kind() + " '" + modelId + "', written by the model.");
        content.put("origin", "system");
        content.put("tags", List.of(TAG));
        content.put("columns", columns(keyClass, kindColumns));
        content.putAll(envelope);
        if (!content.equals(existing)) store.write("dataset", id, content);
        return null;
    }

    /**
     * Whether this code governs {@code ds}: one it registered ({@code origin: system}), or a bare entry with no sharing
     * — only {@code physicalRef} (and the store's {@code name} / {@code owner}), as {@code PendingAlertRules} registers
     * a template's {@code _latest}. Adopting one can only narrow who reads it.
     */
    static boolean ours(Map<String, Object> ds) {
        if ("system".equals(ds.get("origin"))) return true;
        return !ds.containsKey("shares") && Set.of("physicalRef", "name", "owner").containsAll(ds.keySet());
    }

    /**
     * The output Datasets this code governs, narrowed to {@code shares: []}: owned by the model's owner when that owner
     * may see every shared source (its owner or a user share names them), otherwise by no one — readable only by who
     * administers access.
     */
    private static void narrow(ComponentStore store, List<String> ids, Map<String, Object> model,
                               List<Map<String, Object>> sources) throws IOException {
        String modelOwner = model.get("owner") == null ? null : String.valueOf(model.get("owner"));
        String owner = modelOwner != null && sources.stream().allMatch(s -> seenBy(s, modelOwner)) ? modelOwner : null;
        for (String id : ids) {
            Map<String, Object> ds = dataset(store, id);
            if (ds == null || !id.equals(ds.get("physicalRef")) || !ours(ds)) continue;
            Map<String, Object> narrowed = new LinkedHashMap<>(ds);
            if (owner != null) narrowed.put("owner", owner);
            else narrowed.remove("owner");
            narrowed.put("shares", List.of());
            if (!narrowed.equals(ds)) store.write("dataset", id, narrowed);
        }
    }

    /** Whether user {@code who} sees {@code source} for certain: it is unshared, theirs, or shared to them by name. */
    private static boolean seenBy(Map<String, Object> source, String who) {
        if (!source.containsKey("shares")) return true;
        if (who.equals(String.valueOf(source.get("owner")))) return true;
        if (source.get("shares") instanceof List<?> shares)
            for (Object o : shares)
                if (o instanceof Map<?, ?> s && "user".equals(String.valueOf(s.get("subjectType")))
                        && who.equals(String.valueOf(s.get("subjectId")))) return true;
        return false;
    }

    /** Every score column with its role; {@code entity_key} classified with {@code keyClass}. */
    static List<Map<String, Object>> columns(String keyClass, List<ScoreColumns.Column> kindColumns) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (ScoreColumns.Column c : ScoreColumns.table(kindColumns)) {
            Map<String, Object> col = new LinkedHashMap<>();
            col.put("name", c.name());
            if ("entity_key".equals(c.name())) {
                col.put("role", "entity");
                col.put("classification", keyClass);
            } else {
                col.put("role", c.role().name().toLowerCase(Locale.ROOT));
            }
            out.add(col);
        }
        return out;
    }

    private static Map<String, Object> dataset(ComponentStore store, String id) {
        return store.get("dataset", id).map(ComponentRegistry.Component::content).orElse(null);
    }
}
