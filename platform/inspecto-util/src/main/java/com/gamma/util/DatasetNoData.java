package com.gamma.util;

/**
 * The one wording of "this Dataset's store holds no files yet", shared by every Dataset-reading route
 * (control plane and Link Analysis) so the answer is the same closed 409 everywhere: names the Dataset and
 * its store, never DuckDB's text, a CREATE VIEW statement or a glob.
 */
public final class DatasetNoData {

    private static final String NO_FILES = "No files found that match the pattern";

    private DatasetNoData() {}

    /** True when a driver message says a file-backed relation matched no files. */
    public static boolean matches(String driverMessage) {
        return driverMessage != null && driverMessage.contains(NO_FILES);
    }

    /** @param store the Dataset's physicalRef, or {@code null} when unknown */
    public static String message(String datasetId, String store) {
        return "Dataset '" + datasetId + "' has no data yet: nothing has been ingested into its store"
                + (store == null ? "" : " (" + store + ")")
                + ". Run the Pipeline that fills it, or check the Dataset's physicalRef.";
    }
}
