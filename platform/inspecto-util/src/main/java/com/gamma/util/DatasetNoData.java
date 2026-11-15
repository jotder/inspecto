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

    /** The folder a Pipeline's sink writes its rows under: {@code <pipeline>/database}. */
    private static final String SINK_DIR = "database";

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
     * The Pipeline that fills a Dataset, read from its stored content: an import's own Pipeline
     * ({@code importPipeline}), else the Pipeline whose sink store its {@code physicalRef} names
     * ({@code <pipeline>/database[/...]}, the layout a Pipeline writes). {@code null} when the content does not say -
     * a bare store name, a {@code shared/} ref or a view is not proof of which Pipeline writes it.
     */
    public static String pipelineOf(Map<String, Object> dataset) {
        if (dataset == null) return null;
        String own = Values.trimToNull(dataset.get("importPipeline"));
        if (own != null) return own;
        String ref = Values.trimToNull(dataset.get("physicalRef"));
        if (ref == null || ref.startsWith("shared/")) return null;
        String[] parts = ref.replace('\\', '/').split("/");
        return parts.length >= 2 && SINK_DIR.equals(parts[1]) && !parts[0].isBlank() ? parts[0] : null;
    }
}
