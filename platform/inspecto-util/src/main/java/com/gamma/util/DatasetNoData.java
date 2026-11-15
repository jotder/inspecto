package com.gamma.util;

import java.util.Map;

/**
 * The one wording of "this Dataset's store holds no files yet", shared by every Dataset-reading route
 * (control plane and Link Analysis) so the answer is the same closed 409 everywhere: names the Dataset and,
 * when its content says which, the Pipeline that fills it - in an analyst's words (LA-EMPTY-DATASET-WORDING-1),
 * never DuckDB's text, a CREATE VIEW statement, a glob, the store or the {@code physicalRef} key.
 */
public final class DatasetNoData {

    private static final String NO_FILES = "No files found that match the pattern";

    private DatasetNoData() {}

    /** True when a driver message says a file-backed relation matched no files. */
    public static boolean matches(String driverMessage) {
        return driverMessage != null && driverMessage.contains(NO_FILES);
    }

    /** @param pipeline the Pipeline that fills the Dataset ({@link #pipelineOf}), or {@code null} when unknown */
    public static String message(String datasetId, String pipeline) {
        return "Dataset '" + datasetId + "' has no rows yet: its Pipeline "
                + (pipeline == null ? "" : "'" + pipeline + "' ") + "has not run.";
    }

    /**
     * The Pipeline that fills a Dataset, when its stored content records it: an import's own Pipeline
     * ({@code importPipeline}). {@code null} otherwise. The folder in {@code physicalRef} is not proof: a Pipeline's sink
     * folder ({@code dirs.database}) is free text, and two Pipelines may write one store, so naming it could name the
     * wrong Pipeline.
     */
    public static String pipelineOf(Map<String, Object> dataset) {
        if (dataset == null) return null;
        return Values.trimToNull(dataset.get("importPipeline"));
    }
}
