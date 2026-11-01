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
import java.util.function.Supplier;

/**
 * The {@code forecast-model} kind's save-time gates, run by every writer through the core's component save gate:
 * structure ({@link ForecastModel#fromMap}), then the Space checks ({@link #requireStorable}), and the reserved
 * {@code forecasts_} prefix on every {@code dataset} / {@code sink} write.
 */
public final class ForecastKindValidator implements ComponentKindValidator {

    @Override public String type() { return ForecastModel.KIND; }

    @Override public void validate(String id, Map<String, Object> content) {
        ForecastModel.fromMap(id, content);
    }

    @Override public void validateInSpace(Path writeRoot, Supplier<Path> dataRoot, String id, Map<String, Object> content) {
        requireStorable(writeRoot, dataRoot, ForecastModel.fromMap(id, content));
    }

    @Override public void requireNotReserved(Path writeRoot, String type, String id, Map<String, Object> content) {
        String prefix = ForecastModel.FORECASTS_PREFIX;
        List<String> named = ScoreOutputDirs.namedStores(id, content, SegmentationKindValidator.STORE_KEYS);
        if (!ScoreOutputDirs.anyReserved(named, prefix)) return;
        if ("dataset".equals(type) && id != null && id.equals(content.get("physicalRef")) && named.size() == 2
                && id.startsWith(prefix) && writeRoot != null) {
            String model = id.endsWith(ForecastModel.LATEST_SUFFIX)
                    ? id.substring(prefix.length(), id.length() - ForecastModel.LATEST_SUFFIX.length())
                    : id.substring(prefix.length());
            if (new ComponentStore(writeRoot.resolve("registry")).exists(ForecastModel.KIND, model))
                return;   // a read Dataset over a saved model's own history or _latest store
        }
        throw new IllegalArgumentException(type + " '" + id + "' names a store under the reserved prefix '" + prefix
                + "' (Forecast outputs); only a Dataset with id = physicalRef = " + prefix
                + "<model> or " + prefix + "<model>" + ForecastModel.LATEST_SUFFIX + " over a saved model is allowed");
    }

    /**
     * Every named column is in the Dataset's Schema, {@code time} is a DATE/TIMESTAMP column, a {@code sum} field is
     * numeric, and the derived output names collide with nothing this model does not own. An unreadable Schema
     * refuses the save.
     */
    static void requireStorable(Path writeRoot, Supplier<Path> dataRoots, ForecastModel model) {
        if (writeRoot == null) throw new IllegalArgumentException("forecast-model write needs a write root");
        DatasetMeasureProbe probe = new DatasetMeasureProbe(() -> writeRoot, dataRoots);
        List<String> columns;
        try {
            columns = probe.columns(model.dataset());
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("forecast-model columns cannot be checked against the Schema of "
                    + "dataset '" + model.dataset() + "': " + ex.getMessage(), ex);
        }
        List<String> missing = model.referencedColumns().stream().filter(c -> !columns.contains(c)).toList();
        if (!missing.isEmpty())
            throw new IllegalArgumentException("forecast-model column(s) " + missing + " are not in the Schema of "
                    + "dataset '" + model.dataset() + "' (have: " + columns + ")");
        String t = probe.columnType(model.dataset(), model.time());
        if (!SegmentationKindValidator.TIME_TYPES.contains(t))
            throw new IllegalArgumentException("forecast-model time column '" + model.time() + "' of dataset '"
                    + model.dataset() + "' must be a DATE or TIMESTAMP, is " + t);
        if ("sum".equals(model.agg())) {
            String ft = probe.columnType(model.dataset(), model.field());
            if (ft == null || !(ChurnKindValidator.NUMERIC_TYPES.contains(ft) || ft.startsWith("DECIMAL")))
                throw new IllegalArgumentException("forecast-model measure sum(" + model.field() + ") must read a "
                        + "numeric column of dataset '" + model.dataset() + "', is " + ft);
        }
        ComponentStore store = new ComponentStore(writeRoot.resolve("registry"));
        Path dataRoot = dataRoots.get();
        for (String out : List.of(model.forecastsDataset(), model.latestDataset())) {
            boolean ours = dataRoot != null && ForecastEvaluator.ownedBy(dataRoot.resolve(out), model.id());
            if (dataRoot != null && Files.exists(dataRoot.resolve(out)) && !ours)
                throw new IllegalArgumentException("forecast-model '" + model.id() + "' would write '" + out
                        + "', which already exists under the data root and is not this model's output");
            Object ref = store.get("dataset", out).map(ComponentRegistry.Component::content)
                    .map(c -> c.get("physicalRef")).orElse(null);
            if (store.exists("dataset", out) && !out.equals(String.valueOf(ref)))
                throw new IllegalArgumentException("forecast-model '" + model.id() + "' would write '" + out
                        + "', which is the id of a Dataset over another store");
        }
    }
}
