package com.gamma.query;

import com.gamma.pipeline.ViewStore;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;

/** {@link DatasetAccess#over}: one Space's Datasets, pinned per call; {@link RunSnapshot} pins them per Run through it. */
final class SpaceDatasetAccess implements DatasetAccess {

    private final String ownSpaceId;
    private final Function<String, Optional<Map<String, Object>>> datasets;
    private final Supplier<Path> dataRoot;
    private final Supplier<ViewStore> views;

    SpaceDatasetAccess(String ownSpaceId, Function<String, Optional<Map<String, Object>>> datasets,
                       Supplier<Path> dataRoot, Supplier<ViewStore> views) {
        this.ownSpaceId = ownSpaceId;
        this.datasets = datasets;
        this.dataRoot = dataRoot;
        this.views = views;
    }

    @Override
    public Read read(String spaceId, String datasetId) {
        Map<String, Object> cfg = config(spaceId, datasetId);
        String sql;
        Optional<List<String>> files;
        try {
            sql = DatasetRelation.relationSql(cfg, dataRoot.get(), views.get());
            files = DatasetRelation.inputFiles(cfg, dataRoot.get(), Integer.MAX_VALUE - 1)
                    .map(f -> f.files().stream().map(DatasetRelation.FileStamp::path).toList());
        } catch (IllegalArgumentException bad) {
            throw unusable(datasetId, bad);
        }
        return new Read(datasetId, sql, files);
    }

    /** The Dataset's config; refuses another Space or an unknown Dataset, naming the id. */
    Map<String, Object> config(String spaceId, String datasetId) {
        if (spaceId == null || !spaceId.equals(ownSpaceId))
            throw new IllegalStateException("Dataset read refused: Space '" + spaceId
                    + "' is not the Space this service is bound to ('" + ownSpaceId + "'), dataset '" + datasetId + "'");
        Map<String, Object> cfg = datasetId == null ? null : datasets.apply(datasetId).orElse(null);
        if (cfg == null)
            throw new IllegalStateException("Dataset read refused: no dataset '" + datasetId
                    + "' in Space '" + ownSpaceId + "'");
        return cfg;
    }

    Path dataRoot() {
        return dataRoot.get();
    }

    static IllegalStateException unusable(String datasetId, IllegalArgumentException bad) {
        return new IllegalStateException("Dataset read refused: dataset '" + datasetId + "' is unusable: "
                + bad.getMessage(), bad);
    }
}
