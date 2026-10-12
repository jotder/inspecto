package com.gamma.la.api;

import com.gamma.la.core.DatasetProviders;
import com.gamma.spi.auth.ApiException;
import com.gamma.spi.auth.ErrorCodes;
import com.gamma.util.DuckDbUtil;

import java.nio.file.Path;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * The one place a Link Analysis read turns a Dataset read failure into an {@link ApiException}.
 *
 * <ul>
 *   <li>A file-backed Dataset whose store holds no files yet (DuckDB: "No files found that match the pattern")
 *       answers a closed 409 naming the Dataset and its store - never DuckDB's text, never a server path.</li>
 *   <li>Any other driver message keeps its useful text but loses the JDBC preamble and every absolute filesystem
 *       path (a Windows drive path or a POSIX path of two or more segments reads as {@code <path>}).</li>
 * </ul>
 */
public final class DatasetReadFault {

    private static final String NO_FILES = "No files found that match the pattern";
    private static final Pattern LEFTOVER_PATH = Pattern.compile(
            "(?<![\\w.])(?:[A-Za-z]:[\\\\/]|/(?=[^\\s'\"/]+/))[^\\s'\"]*");

    private DatasetReadFault() {}

    /**
     * @param what      the route's own phrase ("projection failed", "traversal failed"...)
     * @param datasetId the Dataset the read ran over
     * @param writeRoot the Space's config root, to name the Dataset's store; may be {@code null}
     */
    public static ApiException of(String what, String datasetId, Path writeRoot, Exception e) {
        String raw = DuckDbUtil.withoutPendingQueryPreamble(e.getMessage());
        if (raw != null && raw.contains(NO_FILES)) return noData(datasetId, writeRoot);
        return new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, what + ": " + scrub(raw));
    }

    /** The closed 409: the Dataset exists and is well-formed, its store simply holds nothing yet. */
    public static ApiException noData(String datasetId, Path writeRoot) {
        String store = storeOf(datasetId, writeRoot);
        return new ApiException(409, ErrorCodes.CONFLICT, "Dataset '" + datasetId
                + "' has no data yet: nothing has been ingested into its store"
                + (store == null ? "" : " (" + store + ")")
                + ". Run the Pipeline that fills it, or check the Dataset's physicalRef.");
    }

    /** {@code message} with every absolute path replaced; {@code null} stays {@code null}. */
    public static String scrub(String message) {
        return message == null ? null : LEFTOVER_PATH.matcher(message).replaceAll("<path>");
    }

    private static String storeOf(String datasetId, Path writeRoot) {
        if (writeRoot == null) return null;
        try {
            Map<String, Object> ds = DatasetProviders.require().dataset(writeRoot, datasetId).orElse(null);
            Object ref = ds == null ? null : ds.get("physicalRef");
            String s = ref == null ? null : String.valueOf(ref).trim();
            return s == null || s.isEmpty() ? null : s;
        } catch (RuntimeException ignore) {
            return null;
        }
    }
}
