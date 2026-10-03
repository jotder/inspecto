package com.gamma.la.storage;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What a published index version would need to cover the Dataset as it is NOW (D-3 step 8, design 3.3 / 5.7): the ONE pure
 * classification that the staleness probe ({@code GET /inv/index}), the build service's submit gate and the builder's own
 * defence all share, so the three can never disagree about when an incremental append is sound.
 *
 * <p><b>The gate.</b> An APPEND is sound only when the index's input files are a SUBSET of the files now, every one of them with
 * the same size and mtime (nothing removed, superseded or rewritten), the relation SQL definition is unchanged, the bucket
 * function and DuckDB version are the ones that wrote it, and at least one file is new. Anything else needs a FULL rebuild,
 * because the index would otherwise keep rows the Dataset no longer has (or miss rows it now computes differently). When the
 * manifest or the caller cannot list the files, nothing is known and only FULL is offered - 'cannot know' is never 'only added'.
 *
 * <p>This class only advises. It never reads a file and never starts a build: a build happens on an explicit request (Decision 5).
 */
public final class IndexPlan {

    private IndexPlan() { }

    /** Deltas allowed on one version; at this many an append is refused and COMPACT is recommended (design 3.3's K, not measured). */
    public static final int MAX_DELTAS = 8;

    /** What the probe recommends. {@code NONE}: the index covers the Dataset (and carries few enough deltas). */
    public enum Action { NONE, APPEND, FULL, COMPACT }

    /**
     * @param recommended the advised next build
     * @param appendable  an APPEND would be sound right now
     * @param added       input files not covered by the index, sorted
     * @param removed     input files the index covers that are gone, sorted
     * @param changed     input files present in both with a different size or mtime, sorted
     * @param reasons     why FULL (or COMPACT) is needed, empty otherwise: codes {@code input_files_unknown},
     *                    {@code input_files_removed}, {@code input_files_changed}, {@code relation_sql_changed},
     *                    {@code relation_unresolvable}, {@code bucket_function_changed}, {@code duckdb_version_changed},
     *                    {@code delta_cap_reached}
     * @param deltas      deltas the index version already carries
     */
    public record Plan(Action recommended, boolean appendable, List<String> added, List<String> removed, List<String> changed,
                       List<String> reasons, int deltas) { }

    /**
     * @param current         the Dataset's input files now; null = they cannot be listed
     * @param relationSqlHash {@link IndexBuilder#relationSqlHash} of the relation now; null = it cannot be resolved
     * @param duckdbVersion   this server's DuckDB version; null = could not be read (then no claim either way)
     */
    public static Plan classify(IndexManifest m, List<IndexManifest.InputFile> current, String relationSqlHash, String bucketFn,
                                String duckdbVersion) {
        List<String> force = new ArrayList<>();
        if (relationSqlHash == null) force.add("relation_unresolvable");
        else if (!relationSqlHash.equals(m.relationSqlHash())) force.add("relation_sql_changed");
        if (!bucketFn.equals(m.bucketFn())) force.add("bucket_function_changed");
        if (duckdbVersion != null && !duckdbVersion.equals(m.duckdbVersion())) force.add("duckdb_version_changed");

        List<String> added = new ArrayList<>(), removed = new ArrayList<>(), changed = new ArrayList<>();
        if (current == null || m.inputFiles() == null) {
            force.add("input_files_unknown");
        } else {
            Map<String, IndexManifest.InputFile> now = new HashMap<>();
            for (IndexManifest.InputFile f : current) now.put(f.path(), f);
            Set<String> recorded = new HashSet<>();
            for (IndexManifest.InputFile f : m.inputFiles()) {
                recorded.add(f.path());
                IndexManifest.InputFile n = now.get(f.path());
                if (n == null) removed.add(f.path());
                else if (n.size() != f.size() || n.mtimeMillis() != f.mtimeMillis()) changed.add(f.path());
            }
            for (IndexManifest.InputFile f : current) if (!recorded.contains(f.path())) added.add(f.path());
            added.sort(null);
            removed.sort(null);
            changed.sort(null);
            if (!removed.isEmpty()) force.add("input_files_removed");
            if (!changed.isEmpty()) force.add("input_files_changed");
        }
        int deltas = m.deltas().size();
        if (!force.isEmpty()) return new Plan(Action.FULL, false, added, removed, changed, List.copyOf(force), deltas);
        boolean capped = deltas >= MAX_DELTAS;
        if (added.isEmpty()) {
            return new Plan(capped ? Action.COMPACT : Action.NONE, false, added, removed, changed,
                    capped ? List.of("delta_cap_reached") : List.of(), deltas);
        }
        if (capped) return new Plan(Action.COMPACT, false, added, removed, changed, List.of("delta_cap_reached"), deltas);
        return new Plan(Action.APPEND, true, added, removed, changed, List.of(), deltas);
    }
}
