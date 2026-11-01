package com.gamma.predictive;

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
 * The {@code segmentation-model} kind's save-time gates, run by every writer through the core's component save gate:
 * structure ({@link SegmentationModel#fromMap}), then the Space checks ({@link #requireStorable}), and the reserved
 * {@code customer_segments_} prefix on every {@code dataset} / {@code sink} write.
 */
public final class SegmentationKindValidator implements ComponentKindValidator {

    /** Content keys through which a Dataset or sink names its store (as for Risk and Anomaly Scores). */
    static final List<String> STORE_KEYS = List.of("physicalRef", "store", "output_store", "path", "sourceName");
    static final Set<String> TIME_TYPES = Set.of("DATE", "TIMESTAMP", "TIMESTAMP_NS", "TIMESTAMP_MS", "TIMESTAMP_S",
            "TIMESTAMP WITH TIME ZONE");

    @Override public String type() { return SegmentationModel.KIND; }

    @Override public void validate(String id, Map<String, Object> content) {
        SegmentationModel.fromMap(id, content);
    }

    @Override public void validateInSpace(Path writeRoot, Supplier<Path> dataRoot, String id, Map<String, Object> content) {
        requireStorable(writeRoot, dataRoot, SegmentationModel.fromMap(id, content));
    }

    @Override public void requireNotReserved(Path writeRoot, String type, String id, Map<String, Object> content) {
        String prefix = SegmentationModel.SEGMENTS_PREFIX;
        List<String> named = ScoreOutputDirs.namedStores(id, content, STORE_KEYS);
        if (!ScoreOutputDirs.anyReserved(named, prefix)) return;
        if ("dataset".equals(type) && id != null && id.equals(content.get("physicalRef")) && named.size() == 2
                && id.startsWith(prefix) && writeRoot != null) {
            String model = id.endsWith(SegmentationModel.LATEST_SUFFIX)
                    ? id.substring(prefix.length(), id.length() - SegmentationModel.LATEST_SUFFIX.length())
                    : id.substring(prefix.length());
            if (new ComponentStore(writeRoot.resolve("registry")).exists(SegmentationModel.KIND, model))
                return;   // a read Dataset over a saved model's own history or _latest store
        }
        throw new IllegalArgumentException(type + " '" + id + "' names a store under the reserved prefix '" + prefix
                + "' (Customer Segment outputs); only a Dataset with id = physicalRef = " + prefix
                + "<model> or " + prefix + "<model>" + SegmentationModel.LATEST_SUFFIX + " over a saved model is allowed");
    }

    /**
     * Every named column is in the Dataset's Schema, {@code time} is a DATE/TIMESTAMP column, and the derived output
     * names collide with nothing this model does not own. An unreadable Schema refuses the save.
     */
    static void requireStorable(Path writeRoot, Supplier<Path> dataRoots, SegmentationModel model) {
        if (writeRoot == null) throw new IllegalArgumentException("segmentation-model write needs a write root");
        DatasetMeasureProbe probe = new DatasetMeasureProbe(() -> writeRoot, dataRoots);
        List<String> columns;
        try {
            columns = probe.columns(model.dataset());
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("segmentation-model columns cannot be checked against the Schema of "
                    + "dataset '" + model.dataset() + "': " + ex.getMessage(), ex);
        }
        List<String> missing = model.referencedColumns().stream().filter(c -> !columns.contains(c)).toList();
        if (!missing.isEmpty())
            throw new IllegalArgumentException("segmentation-model column(s) " + missing + " are not in the Schema of "
                    + "dataset '" + model.dataset() + "' (have: " + columns + ")");
        String t = probe.columnType(model.dataset(), model.time());
        if (!TIME_TYPES.contains(t))
            throw new IllegalArgumentException("segmentation-model.time column '" + model.time() + "' of dataset '"
                    + model.dataset() + "' must be a DATE or TIMESTAMP, is " + t);
        ComponentStore store = new ComponentStore(writeRoot.resolve("registry"));
        Path dataRoot = dataRoots.get();
        for (String out : List.of(model.segmentsDataset(), model.latestDataset())) {
            boolean ours = dataRoot != null && SegmentEvaluator.ownedBy(dataRoot.resolve(out), model.id());
            if (dataRoot != null && Files.exists(dataRoot.resolve(out)) && !ours)
                throw new IllegalArgumentException("segmentation-model '" + model.id() + "' would write '" + out
                        + "', which already exists under the data root and is not this model's output");
            Object ref = store.get("dataset", out).map(ComponentRegistry.Component::content)
                    .map(c -> c.get("physicalRef")).orElse(null);
            if (store.exists("dataset", out) && !out.equals(String.valueOf(ref)))
                throw new IllegalArgumentException("segmentation-model '" + model.id() + "' would write '" + out
                        + "', which is the id of a Dataset over another store");
        }
    }
}
