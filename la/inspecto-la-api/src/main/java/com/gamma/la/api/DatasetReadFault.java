package com.gamma.la.api;

import com.gamma.la.core.DatasetProviders;
import com.gamma.spi.auth.ApiException;
import com.gamma.spi.auth.ErrorCodes;
import com.gamma.util.DatasetNoData;
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

    private static final Pattern LEFTOVER_PATH = Pattern.compile(
            "(?<![\\w.])(?:[A-Za-z]:[\\\\/]|/(?=[^\\s'\"/]+/))[^\\s'\"]*");

    /** A path DuckDB quoted: a glob or a relative store path ("database/store/**" + "/*.parquet"). */
    private static final Pattern QUOTED_PATH = Pattern.compile("(['\"])[^'\"\\s]*[/\\\\][^'\"\\s]*\\1");
    /** DuckDB's trailing statement echo ("LINE 1: CREATE VIEW ...") carries the view's path and SQL. */
    private static final Pattern STATEMENT_ECHO = Pattern.compile("\\R+LINE \\d+:.*", Pattern.DOTALL);

    private DatasetReadFault() {}

    /**
     * @param what      the route's own phrase ("projection failed", "traversal failed"...)
     * @param datasetId the Dataset the read ran over
     * @param writeRoot the Space's config root, to name the Dataset's store; may be {@code null}
     */
    public static ApiException of(String what, String datasetId, Path writeRoot, Exception e) {
        String raw = DuckDbUtil.withoutPendingQueryPreamble(e.getMessage());
        if (DatasetNoData.matches(raw)) return noData(datasetId, writeRoot);
        return new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, what + ": " + scrub(raw));
    }

    /** The closed 409: the Dataset exists and is well-formed, its store simply holds nothing yet. */
    public static ApiException noData(String datasetId, Path writeRoot) {
        return new ApiException(409, ErrorCodes.CONFLICT, DatasetNoData.message(datasetId, storeOf(datasetId, writeRoot)));
    }

    /** {@code message} with every absolute path replaced; {@code null} stays {@code null}. */
    public static String scrub(String message) {
        if (message == null) return null;
        String m = STATEMENT_ECHO.matcher(message).replaceFirst("");
        m = LEFTOVER_PATH.matcher(m).replaceAll("<path>");
        return QUOTED_PATH.matcher(m).replaceAll("'<path>'");
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
