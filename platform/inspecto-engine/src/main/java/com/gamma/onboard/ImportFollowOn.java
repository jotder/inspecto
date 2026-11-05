package com.gamma.onboard;

import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

/**
 * One application's follow-on step of an import's default Pipeline ({@code BASIC-ONBOARD-1} P1): read → arrange →
 * write Parquet → <b>follow-on</b>. The platform's Add data runs every registered follow-on inside the user's run
 * (DT-16), after the Parquet is written and compacted and before the Expectations, and reports each outcome in the run
 * summary under {@link #key()}. An application without one ends at the Parquet.
 *
 * <p>A {@code ServiceLoader} SPI (a {@code META-INF/services} entry, found through {@code OptionalSpi.all}) because the
 * applications live in OPTIONAL modules the platform does not depend on. Link Analysis registers its link index.
 */
public interface ImportFollowOn {

    /** The run summary key its outcome goes under (Link Analysis: {@code index}). */
    String key();

    /** What it does, as the clause of the review sentence (Link Analysis: {@code builds its link index}). */
    String does();

    /** The Dataset entry keys it needs; without them it reports itself skipped (Link Analysis: {@code defaultLinkMapping}). */
    Set<String> needs();

    /** Its outcome when the Space's Jobs do this work instead of the user's run ({@code pipeline.automation} present). */
    Map<String, Object> deferred();

    /**
     * Do the work for one import. A refusal or failure is an outcome ({@code result}, {@code message}, plus its own
     * fields), never an exception.
     */
    Map<String, Object> run(Context context);

    /**
     * What a follow-on gets: the Space's roots, the Dataset id, its registry entry as it stands after the run, and who
     * started the run.
     */
    record Context(Path writeRoot, Path dataRoot, String dataset, Map<String, Object> entry, String actor) {}
}
