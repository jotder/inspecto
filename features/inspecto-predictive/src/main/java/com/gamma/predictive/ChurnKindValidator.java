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
 * The {@code churn-model} kind's save-time gates, run by every writer through the core's component save gate:
 * structure ({@link ChurnModel#fromMap}), then the Space checks ({@link #requireStorable}), and the reserved
 * {@code churn_scores_} prefix on every {@code dataset} / {@code sink} write.
 */
public final class ChurnKindValidator implements ComponentKindValidator {

    /** DuckDB numeric column types an input's {@code sum/avg/min/max} may read. */
    static final Set<String> NUMERIC_TYPES = Set.of("TINYINT", "SMALLINT", "INTEGER", "BIGINT", "HUGEINT", "UTINYINT",
            "USMALLINT", "UINTEGER", "UBIGINT", "UHUGEINT", "FLOAT", "DOUBLE", "REAL");

    @Override public String type() { return ChurnModel.KIND; }

    @Override public void validate(String id, Map<String, Object> content) {
        ChurnModel.fromMap(id, content);
    }

    @Override public void validateInSpace(Path writeRoot, Supplier<Path> dataRoot, String id, Map<String, Object> content) {
        requireStorable(writeRoot, dataRoot, ChurnModel.fromMap(id, content));
    }

    @Override public void requireNotReserved(Path writeRoot, String type, String id, Map<String, Object> content) {
        String prefix = ChurnModel.SCORES_PREFIX;
        List<String> named = ScoreOutputDirs.namedStores(id, content, SegmentationKindValidator.STORE_KEYS);
        if (!ScoreOutputDirs.anyReserved(named, prefix)) return;
        if ("dataset".equals(type) && id != null && id.equals(content.get("physicalRef")) && named.size() == 2
                && id.startsWith(prefix) && writeRoot != null) {
            String model = id.endsWith(ChurnModel.LATEST_SUFFIX)
                    ? id.substring(prefix.length(), id.length() - ChurnModel.LATEST_SUFFIX.length())
                    : id.substring(prefix.length());
            if (new ComponentStore(writeRoot.resolve("registry")).exists(ChurnModel.KIND, model))
                return;   // a read Dataset over a saved model's own history or _latest store
        }
        throw new IllegalArgumentException(type + " '" + id + "' names a store under the reserved prefix '" + prefix
                + "' (Churn Score outputs); only a Dataset with id = physicalRef = " + prefix
                + "<model> or " + prefix + "<model>" + ChurnModel.LATEST_SUFFIX + " over a saved model is allowed");
    }

    /**
     * Every named column is in the Dataset's Schema, {@code time} and every {@code recency} field is a DATE/TIMESTAMP
     * column, every {@code sum/avg/min/max} field is numeric, and the derived output names collide with nothing this
     * model does not own. An unreadable Schema refuses the save.
     */
    static void requireStorable(Path writeRoot, Supplier<Path> dataRoots, ChurnModel model) {
        if (writeRoot == null) throw new IllegalArgumentException("churn-model write needs a write root");
        DatasetMeasureProbe probe = new DatasetMeasureProbe(() -> writeRoot, dataRoots);
        List<String> columns;
        try {
            columns = probe.columns(model.dataset());
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("churn-model columns cannot be checked against the Schema of "
                    + "dataset '" + model.dataset() + "': " + ex.getMessage(), ex);
        }
        List<String> missing = model.referencedColumns().stream().filter(c -> !columns.contains(c)).toList();
        if (!missing.isEmpty())
            throw new IllegalArgumentException("churn-model column(s) " + missing + " are not in the Schema of "
                    + "dataset '" + model.dataset() + "' (have: " + columns + ")");
        requireTime(probe, model, "time", model.time());
        for (ChurnModel.Input in : model.inputs()) {
            if ("recency".equals(in.agg())) requireTime(probe, model, "input '" + in.name() + "'", in.field());
            if (ChurnModel.NUMERIC_AGGS.contains(in.agg())) {
                String t = probe.columnType(model.dataset(), in.field());
                if (t == null || !(NUMERIC_TYPES.contains(t) || t.startsWith("DECIMAL")))
                    throw new IllegalArgumentException("churn-model input '" + in.name() + "' reads " + in.agg()
                            + "(" + in.field() + "), which must be a numeric column of dataset '" + model.dataset()
                            + "', is " + t);
            }
        }
        ComponentStore store = new ComponentStore(writeRoot.resolve("registry"));
        Path dataRoot = dataRoots.get();
        for (String out : List.of(model.scoresDataset(), model.latestDataset())) {
            boolean ours = dataRoot != null && ChurnEvaluator.ownedBy(dataRoot.resolve(out), model.id());
            if (dataRoot != null && Files.exists(dataRoot.resolve(out)) && !ours)
                throw new IllegalArgumentException("churn-model '" + model.id() + "' would write '" + out
                        + "', which already exists under the data root and is not this model's output");
            Object ref = store.get("dataset", out).map(ComponentRegistry.Component::content)
                    .map(c -> c.get("physicalRef")).orElse(null);
            if (store.exists("dataset", out) && !out.equals(String.valueOf(ref)))
                throw new IllegalArgumentException("churn-model '" + model.id() + "' would write '" + out
                        + "', which is the id of a Dataset over another store");
        }
    }

    private static void requireTime(DatasetMeasureProbe probe, ChurnModel model, String what, String column) {
        String t = probe.columnType(model.dataset(), column);
        if (!SegmentationKindValidator.TIME_TYPES.contains(t))
            throw new IllegalArgumentException("churn-model " + what + " column '" + column + "' of dataset '"
                    + model.dataset() + "' must be a DATE or TIMESTAMP, is " + t);
    }
}
