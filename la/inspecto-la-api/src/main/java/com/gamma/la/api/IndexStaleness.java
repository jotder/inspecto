package com.gamma.la.api;

import com.gamma.la.core.InputFingerprint;
import com.gamma.la.storage.IndexManifest;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The ONE staleness computation of an index (D-3 design 2.4 / 5.3a), shared by {@code GET /inv/index} and any consumer that
 * must decide whether to serve an index.
 *
 * <p>Reason codes, in precedence order (the first is the primary {@link Result#reason()}; all that apply are listed):
 * {@code input_files_changed}, {@code relation_sql_changed}, {@code relation_unresolvable}, {@code bucket_function_changed},
 * {@code duckdb_version_changed}.
 *
 * <p><b>Honesty about what is unknown.</b> The input comparison happens only when BOTH the manifest's recorded fingerprint and
 * the current one are real file fingerprints ({@code files:...}). Otherwise (a pre-fingerprint manifest, a view-backed
 * Dataset, too many files, a provider that cannot say) {@link Result#fingerprintKnown()} is false and no currency is claimed
 * on the input files - but nothing is called stale for it either.
 *
 * <p><b>{@code removedInput}</b> (Decision 6a: serve stale flagged, refuse only when removed rows could be exposed): true when
 * the input files changed AND a file present at build time is gone or has a different size/mtime now. A pure addition leaves
 * it false. When the manifest did not record the file list (over the cap) a change cannot be classified, so it is true.
 */
public final class IndexStaleness {

    private IndexStaleness() { }

    public static final String INPUT_FILES_CHANGED = "input_files_changed";
    public static final String RELATION_SQL_CHANGED = "relation_sql_changed";
    public static final String RELATION_UNRESOLVABLE = "relation_unresolvable";
    public static final String BUCKET_FUNCTION_CHANGED = "bucket_function_changed";
    public static final String DUCKDB_VERSION_CHANGED = "duckdb_version_changed";

    /**
     * @param stale            any reason applies
     * @param reasons          codes, precedence order
     * @param details          human text, parallel to {@code reasons}
     * @param removedInput     see the class doc
     * @param fingerprintKnown whether the input files could be compared at all
     */
    public record Result(boolean stale, List<String> reasons, List<String> details, boolean removedInput, boolean fingerprintKnown) {
        /** The primary reason code, or null when current. */
        public String reason() {
            return reasons.isEmpty() ? null : reasons.get(0);
        }
    }

    /**
     * @param relationSqlHash  hash of the Dataset's relation SQL now; null = it cannot be resolved
     * @param input            the current input fingerprint; null = the provider cannot say
     * @param bucketFn         the server's bucket function name
     * @param duckdbVersion    the server's DuckDB version; null = could not be read (then no claim either way)
     */
    public static Result compute(IndexManifest m, String relationSqlHash, InputFingerprint input, String bucketFn, String duckdbVersion) {
        List<String> codes = new ArrayList<>();
        List<String> text = new ArrayList<>();
        boolean known = input != null && input.known() && InputFingerprint.isKnown(m.baseFingerprint());
        boolean removed = false;
        if (known && !input.value().equals(m.baseFingerprint())) {
            codes.add(INPUT_FILES_CHANGED);
            text.add("the Dataset's input files changed since the index was built (added, removed or replaced)");
            removed = removedSince(m.inputFiles(), input.files());
        }
        if (relationSqlHash == null) {
            codes.add(RELATION_UNRESOLVABLE);
            text.add("the Dataset's relation cannot be resolved now");
        } else if (!relationSqlHash.equals(m.relationSqlHash())) {
            codes.add(RELATION_SQL_CHANGED);
            text.add("the Dataset's relation SQL changed since the index was built");
        }
        if (!bucketFn.equals(m.bucketFn())) {
            codes.add(BUCKET_FUNCTION_CHANGED);
            text.add("the bucket function differs (index: " + m.bucketFn() + ", server: " + bucketFn + ")");
        }
        if (duckdbVersion != null && !duckdbVersion.equals(m.duckdbVersion())) {
            codes.add(DUCKDB_VERSION_CHANGED);
            text.add("built with DuckDB " + m.duckdbVersion() + ", this server runs " + duckdbVersion);
        }
        return new Result(!codes.isEmpty(), List.copyOf(codes), List.copyOf(text), removed, known);
    }

    /** True when a recorded file is absent or differs now; true too when no list was recorded (cannot classify). */
    private static boolean removedSince(List<IndexManifest.InputFile> recorded, List<InputFingerprint.FileStamp> now) {
        if (recorded == null) return true;
        Set<String> current = new HashSet<>();
        for (InputFingerprint.FileStamp f : now) current.add(f.path() + '\t' + f.size() + '\t' + f.mtimeMillis());
        for (IndexManifest.InputFile f : recorded)
            if (!current.contains(f.path() + '\t' + f.size() + '\t' + f.mtimeMillis())) return true;
        return false;
    }
}
