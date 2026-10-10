package com.gamma.anomaly;

import com.gamma.alert.ScoreOutputDirs;
import com.gamma.pipeline.ComponentRegistry;
import com.gamma.pipeline.ComponentStore;
import com.gamma.query.DatasetMeasureProbe;
import com.gamma.spi.http.ComponentKindValidator;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * The {@code anomaly-model} kind's save-time gates (design §6), run by every writer through the core's
 * component save gate: structure ({@link AnomalyModel#fromMap}), then the Space checks ({@link #requireStorable}),
 * and the reserved {@code anomaly_scores_} prefix on every {@code dataset} / {@code sink} write.
 */
public final class AnomalyKindValidator implements ComponentKindValidator {

    /** Content keys through which a Dataset or sink names its store (as for Risk Scores). */
    static final List<String> STORE_KEYS = List.of("physicalRef", "store", "output_store", "path", "sourceName");
    static final Set<String> TIME_TYPES = Set.of("DATE", "TIMESTAMP", "TIMESTAMP_NS", "TIMESTAMP_MS", "TIMESTAMP_S",
            "TIMESTAMP WITH TIME ZONE");

    @Override public String type() { return AnomalyModel.KIND; }

    @Override public void validate(String id, Map<String, Object> content) {
        AnomalyModel.fromMap(id, content);
    }

    @Override public void validateInSpace(Path writeRoot, Supplier<Path> dataRoot, String id, Map<String, Object> content) {
        requireStorable(writeRoot, dataRoot, AnomalyModel.fromMap(id, content));
        requireLists(com.gamma.entitylist.WatchListFeed.installed(), writeRoot, AnomalyLists.of(content));
    }

    /**
     * Design §9, fail closed at save: a {@code watchList} must be a live {@code watch} Entity List and an
     * {@code exclusionList} a live {@code exclusion} one, and the edition must carry Entity Lists at all.
     */
    static void requireLists(java.util.Optional<com.gamma.entitylist.WatchListFeed> provider, Path writeRoot,
                             AnomalyLists lists) {
        if (lists.watchList() == null && lists.exclusionList() == null) return;
        com.gamma.entitylist.WatchListFeed feed = provider.orElseThrow(() -> new IllegalArgumentException(
                "anomaly-model." + (lists.watchList() != null ? "watchList" : "exclusionList")
                        + " needs Entity Lists, which this edition does not carry"));
        try {
            if (lists.watchList() != null)
                feed.check(writeRoot, lists.watchList().list(), com.gamma.entitylist.WatchListFeed.WATCH);
            if (lists.exclusionList() != null)
                feed.check(writeRoot, lists.exclusionList(), com.gamma.entitylist.WatchListFeed.EXCLUSION);
        } catch (java.io.IOException e) {
            throw new IllegalArgumentException("anomaly-model lists cannot be checked: the Entity List log is unreadable", e);
        }
    }

    @Override public void requireNotReserved(Path writeRoot, String type, String id, Map<String, Object> content) {
        String prefix = AnomalyModel.SCORES_PREFIX;
        List<String> named = ScoreOutputDirs.namedStores(id, content, STORE_KEYS);
        if (!ScoreOutputDirs.anyReserved(named, prefix)) return;
        if ("dataset".equals(type) && id != null && id.equals(content.get("physicalRef")) && named.size() == 2
                && id.startsWith(prefix) && id.endsWith(AnomalyModel.LATEST_SUFFIX) && writeRoot != null) {
            String model = id.substring(prefix.length(), id.length() - AnomalyModel.LATEST_SUFFIX.length());
            if (new ComponentStore(writeRoot.resolve("registry")).exists(AnomalyModel.KIND, model))
                return;   // the documented Alert Rule Dataset over anomaly_scores_<model>_latest
        }
        throw new IllegalArgumentException(type + " '" + id + "' names a store under the reserved prefix '" + prefix
                + "' (Anomaly Score outputs); only a Dataset with id = physicalRef = " + prefix
                + "<model>" + AnomalyModel.LATEST_SUFFIX + " over a saved model is allowed");
    }

    /**
     * Every named column is in its Dataset's Schema, each feature's {@code time} is a DATE/TIMESTAMP column, and the
     * derived output names collide with nothing this model does not own. An unreadable Schema refuses the save.
     */
    static void requireStorable(Path writeRoot, Supplier<Path> dataRoots, AnomalyModel model) {
        if (writeRoot == null) throw new IllegalArgumentException("anomaly-model write needs a write root");
        DatasetMeasureProbe probe = new DatasetMeasureProbe(() -> writeRoot, dataRoots);
        for (Map.Entry<String, Set<String>> e : model.referencedColumns().entrySet()) {
            List<String> columns;
            try {
                columns = probe.columns(e.getKey());
            } catch (IllegalArgumentException ex) {
                throw new IllegalArgumentException("anomaly-model features cannot be checked against the Schema of "
                        + "dataset '" + e.getKey() + "': " + ex.getMessage(), ex);
            }
            List<String> missing = e.getValue().stream().filter(c -> !columns.contains(c)).toList();
            if (!missing.isEmpty())
                throw new IllegalArgumentException("anomaly-model column(s) " + missing
                        + " are not in the Schema of dataset '" + e.getKey() + "' (have: " + columns + ")");
        }
        for (AnomalyModel.Feature f : model.features()) {
            String t = probe.columnType(f.dataset(), f.time());
            if (!TIME_TYPES.contains(t))
                throw new IllegalArgumentException("anomaly-model feature '" + f.id() + "': time column '" + f.time()
                        + "' of dataset '" + f.dataset() + "' must be a DATE or TIMESTAMP, is " + t);
        }
        ComponentStore store = new ComponentStore(writeRoot.resolve("registry"));
        Path dataRoot = dataRoots.get();
        for (String out : List.of(model.scoresDataset(), model.latestDataset())) {
            boolean ours = dataRoot != null && AnomalyScoreEvaluator.ownedBy(dataRoot.resolve(out), model.id());
            if (dataRoot != null && Files.exists(dataRoot.resolve(out)) && !ours)
                throw new IllegalArgumentException("anomaly-model '" + model.id() + "' would write '" + out
                        + "', which already exists under the data root and is not this model's output");
            Object ref = store.get("dataset", out).map(ComponentRegistry.Component::content)
                    .map(c -> c.get("physicalRef")).orElse(null);
            if (store.exists("dataset", out) && !out.equals(String.valueOf(ref)))
                throw new IllegalArgumentException("anomaly-model '" + model.id() + "' would write '" + out
                        + "', which is the id of a Dataset over another store");
            if (!ours)
                for (ComponentRegistry.Component ds : store.list("dataset"))
                    if (out.equals(String.valueOf(ds.content().get("physicalRef")).trim()))
                        throw new IllegalArgumentException("anomaly-model '" + model.id() + "' would write '" + out
                                + "', which Dataset '" + ds.content().get("name") + "' already reads as its store");
        }
    }
}
